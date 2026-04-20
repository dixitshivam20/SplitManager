package com.splitmanager.app.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores every detected payment so the user can split them later from the inbox,
 * even if they missed or dismissed the original notification.
 *
 * read = 0 → unread (bold, accent colour)
 * read = 1 → read (normal weight, faded)
 */
public class PaymentInboxDb extends SQLiteOpenHelper {

    private static final String TAG     = "PaymentInboxDb";
    private static final String DB_NAME = "payment_inbox.db";
    private static final int    DB_VER  = 1;
    private static final String TABLE   = "inbox";

    private static volatile PaymentInboxDb INSTANCE;

    public static PaymentInboxDb getInstance(Context ctx) {
        if (INSTANCE == null) {
            synchronized (PaymentInboxDb.class) {
                if (INSTANCE == null)
                    INSTANCE = new PaymentInboxDb(ctx.getApplicationContext());
            }
        }
        return INSTANCE;
    }

    private PaymentInboxDb(Context ctx) { super(ctx, DB_NAME, null, DB_VER); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "amount REAL NOT NULL," +
            "merchant TEXT," +
            "method TEXT," +
            "source TEXT," +
            "timestamp INTEGER NOT NULL," +
            "read INTEGER NOT NULL DEFAULT 0" +   // 0=unread, 1=read
        ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    /** Insert a newly detected payment. Returns the new row id, or -1 on error. */
    public long insert(double amount, String merchant, String method, String source) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("amount",    amount);
            cv.put("merchant",  merchant != null ? merchant : "");
            cv.put("method",    method   != null ? method   : "");
            cv.put("source",    source   != null ? source   : "");
            cv.put("timestamp", System.currentTimeMillis());
            cv.put("read",      0);
            return getWritableDatabase().insert(TABLE, null, cv);
        } catch (Exception e) {
            Log.w(TAG, "Could not insert payment into inbox");
            return -1;
        }
    }

    /** Mark a single entry as read. */
    public void markRead(long id) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("read", 1);
            getWritableDatabase().update(TABLE, cv, "id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            Log.w(TAG, "Could not mark payment as read");
        }
    }

    /** Mark all entries as read. */
    public void markAllRead() {
        try {
            ContentValues cv = new ContentValues();
            cv.put("read", 1);
            getWritableDatabase().update(TABLE, cv, null, null);
        } catch (Exception e) {
            Log.w(TAG, "Could not mark all as read");
        }
    }

    /** Delete a single entry (after user splits it). */
    public void delete(long id) {
        try {
            getWritableDatabase().delete(TABLE, "id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            Log.w(TAG, "Could not delete inbox entry");
        }
    }

    /** Delete all entries. */
    public void deleteAll() {
        try { getWritableDatabase().delete(TABLE, null, null); }
        catch (Exception e) { Log.w(TAG, "Could not clear inbox"); }
    }

    /** Count of unread entries — used for badge. */
    public int unreadCount() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE read=0", null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    public List<InboxEntry> getAll() {
        List<InboxEntry> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(
                TABLE, null, null, null, null, null, "timestamp DESC", "200");
            while (c.moveToNext()) {
                InboxEntry e = new InboxEntry();
                e.id        = c.getLong(c.getColumnIndexOrThrow("id"));
                e.amount    = c.getDouble(c.getColumnIndexOrThrow("amount"));
                e.merchant  = c.getString(c.getColumnIndexOrThrow("merchant"));
                e.method    = c.getString(c.getColumnIndexOrThrow("method"));
                e.source    = c.getString(c.getColumnIndexOrThrow("source"));
                e.timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"));
                e.read      = c.getInt(c.getColumnIndexOrThrow("read")) == 1;
                list.add(e);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read inbox");
        } finally {
            if (c != null) c.close();
        }
        return list;
    }

    public static class InboxEntry {
        public long    id;
        public double  amount;
        public String  merchant;
        public String  method;
        public String  source;
        public long    timestamp;
        public boolean read;
    }
}
