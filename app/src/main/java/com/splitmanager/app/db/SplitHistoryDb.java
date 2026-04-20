package com.splitmanager.app.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * SQLite split history with AES-256-GCM field-level encryption for PII fields.
 *
 * Encrypted fields: merchant, group_name (contain names/merchant data)
 * Plain fields:     amount, member_count, split_type, timestamp, expense_id
 *                   (numbers — not personally identifiable on their own)
 *
 * Key stored in Android Keystore (hardware-backed on supported devices).
 * A rooted device reading the raw DB file will see only ciphertext for names.
 */
public class SplitHistoryDb extends SQLiteOpenHelper {

    private static final String TAG        = "SplitHistoryDb";
    private static final String DB_NAME    = "split_history.db";
    private static final int    DB_VERSION = 1;
    private static final String TABLE      = "splits";
    private static final String KEY_ALIAS  = "split_manager_history_key";
    private static final String TRANSFORM  = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN = 12;
    private static final int    GCM_TAG    = 128;

    private static volatile SplitHistoryDb INSTANCE;

    public static SplitHistoryDb getInstance(Context ctx) {
        if (INSTANCE == null) {
            synchronized (SplitHistoryDb.class) {
                if (INSTANCE == null)
                    INSTANCE = new SplitHistoryDb(ctx.getApplicationContext());
            }
        }
        return INSTANCE;
    }

    private SplitHistoryDb(Context ctx) { super(ctx, DB_NAME, null, DB_VERSION); }

    /**
     * Enable Write-Ahead Logging (WAL) mode.
     * See PaymentInboxDb.onConfigure() for full explanation.
     * SplitHistoryDb is accessed from SplitReviewActivity's executor thread during
     * split confirmation while HistoryActivity may be reading it — WAL prevents locking.
     */
    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "amount REAL NOT NULL," +
            "merchant TEXT," +         // AES-GCM encrypted, Base64 stored
            "group_name TEXT," +       // AES-GCM encrypted, Base64 stored
            "member_count INTEGER," +
            "split_type TEXT," +
            "timestamp INTEGER NOT NULL," +
            "expense_id TEXT" +
        ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    // ── Keystore helpers ──────────────────────────────────────────────────────

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true) // Android generates fresh IV each call
            .build());
        return kg.generateKey();
    }

    /**
     * Encrypt plaintext using AES-256-GCM.
     * Returns Base64(IV || ciphertext) or the original string prefixed with "raw:" on failure
     * (graceful degradation — history still works, just unencrypted for that record).
     */
    private String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv         = cipher.getIV(); // 12 bytes, random
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // Prepend IV so we can decrypt later: IV(12) || ciphertext
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return "enc:" + Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "Encryption failed — storing as plaintext");
            return "raw:" + plaintext;
        }
    }

    /**
     * Decrypt a value produced by encrypt().
     * Handles both "enc:..." (encrypted) and "raw:..." (fallback plaintext) prefixes,
     * plus legacy records with no prefix.
     */
    private String decrypt(String stored) {
        if (stored == null) return "";
        if (stored.startsWith("raw:")) return stored.substring(4);
        if (!stored.startsWith("enc:")) return stored; // legacy unencrypted record
        try {
            byte[] combined   = Base64.decode(stored.substring(4), Base64.NO_WRAP);
            byte[] iv         = new byte[GCM_IV_LEN];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LEN];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LEN);
            System.arraycopy(combined, GCM_IV_LEN, ciphertext, 0, ciphertext.length);

            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Decryption failed — returning empty string");
            return "";
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void insert(double amount, String merchant, String groupName,
                       int memberCount, String splitType, String expenseId) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("amount",       amount);
            cv.put("merchant",     encrypt(merchant != null ? merchant : ""));
            cv.put("group_name",   encrypt(groupName != null ? groupName : ""));
            cv.put("member_count", memberCount);
            cv.put("split_type",   splitType != null ? splitType : "");
            cv.put("timestamp",    System.currentTimeMillis());
            cv.put("expense_id",   expenseId != null ? expenseId : "");
            getWritableDatabase().insert(TABLE, null, cv);
        } catch (Exception e) {
            Log.w(TAG, "Could not save split record");
        }
    }

    public List<SplitRecord> getAll() {
        List<SplitRecord> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(
                TABLE, null, null, null, null, null, "timestamp DESC", "100");
            while (c.moveToNext()) {
                SplitRecord r = new SplitRecord();
                r.id          = c.getLong(c.getColumnIndexOrThrow("id"));
                r.amount      = c.getDouble(c.getColumnIndexOrThrow("amount"));
                r.merchant    = decrypt(c.getString(c.getColumnIndexOrThrow("merchant")));
                r.groupName   = decrypt(c.getString(c.getColumnIndexOrThrow("group_name")));
                r.memberCount = c.getInt(c.getColumnIndexOrThrow("member_count"));
                r.splitType   = c.getString(c.getColumnIndexOrThrow("split_type"));
                r.timestamp   = c.getLong(c.getColumnIndexOrThrow("timestamp"));
                r.expenseId   = c.getString(c.getColumnIndexOrThrow("expense_id"));
                list.add(r);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read split history");
        } finally {
            if (c != null) c.close();
        }
        return list;
    }

    public void deleteAll() {
        try {
            getWritableDatabase().delete(TABLE, null, null);
        } catch (Exception e) {
            Log.w(TAG, "Could not clear split history");
        }
    }

    public static class SplitRecord {
        public long   id;
        public double amount;
        public String merchant;
        public String groupName;
        public int    memberCount;
        public String splitType;
        public long   timestamp;
        public String expenseId;
    }
}
