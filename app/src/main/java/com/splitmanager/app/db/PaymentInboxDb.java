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
 *
 * notif_id is the Android notification ID posted by PaymentService.
 * Storing it here allows InboxActivity / SplitReviewActivity to cancel
 * the exact system notification when the user acts on the inbox entry,
 * keeping the device notification drawer and the in-app inbox in sync.
 */
public class PaymentInboxDb extends SQLiteOpenHelper {

    private static final String TAG     = "PaymentInboxDb";
    private static final String DB_NAME = "payment_inbox.db";
    // Version bumped to 2 — adds notif_id column for notification sync
    private static final int    DB_VER  = 2;
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
            "read INTEGER NOT NULL DEFAULT 0," +  // 0=unread, 1=read
            "notif_id INTEGER NOT NULL DEFAULT -1" + // Android notification ID — -1 if not applicable
        ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            // Add notif_id column to existing installations
            try {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN notif_id INTEGER NOT NULL DEFAULT -1");
            } catch (Exception e) {
                // If ALTER fails (e.g. table doesn't exist yet), recreate cleanly
                db.execSQL("DROP TABLE IF EXISTS " + TABLE);
                onCreate(db);
            }
        }
    }

    /**
     * Insert a newly detected payment.
     * @param notifId the Android notification ID posted for this payment (-1 if none)
     * @return the new row id, or -1 on error
     */
    public long insert(double amount, String merchant, String method, String source, int notifId) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("amount",    amount);
            cv.put("merchant",  merchant != null ? merchant : "");
            cv.put("method",    method   != null ? method   : "");
            cv.put("source",    source   != null ? source   : "");
            cv.put("timestamp", System.currentTimeMillis());
            cv.put("read",      0);
            cv.put("notif_id",  notifId);
            return getWritableDatabase().insert(TABLE, null, cv);
        } catch (Exception e) {
            Log.w(TAG, "Could not insert payment into inbox");
            return -1;
        }
    }

    /**
     * Mark the inbox entry matching a given Android notification ID as read.
     * Called when the user taps "Ignore" on the system notification, so the
     * in-app bell badge reflects the dismissal immediately.
     */
    public void markReadByNotifId(int notifId) {
        if (notifId == -1) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put("read", 1);
            getWritableDatabase().update(TABLE, cv, "notif_id=?",
                new String[]{String.valueOf(notifId)});
        } catch (Exception e) {
            Log.w(TAG, "Could not mark payment read by notifId");
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

    /**
     * Delete a single entry and return its notif_id so the caller can cancel
     * the matching system notification.
     * @return the notif_id that was stored, or -1 if not found / error
     */
    public int deleteAndGetNotifId(long id) {
        int notifId = -1;
        Cursor c = null;
        try {
            // Read notif_id before deleting
            c = getReadableDatabase().query(
                TABLE, new String[]{"notif_id"}, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
            if (c.moveToFirst()) notifId = c.getInt(0);
        } catch (Exception e) {
            Log.w(TAG, "Could not read notif_id before delete");
        } finally {
            if (c != null) c.close();
        }
        // Now delete
        try {
            getWritableDatabase().delete(TABLE, "id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            Log.w(TAG, "Could not delete inbox entry");
        }
        return notifId;
    }

    /** Delete a single entry (legacy — use deleteAndGetNotifId when notification sync is needed). */
    public void delete(long id) {
        try {
            getWritableDatabase().delete(TABLE, "id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            Log.w(TAG, "Could not delete inbox entry");
        }
    }

    /** Delete all entries and return all notif_ids so the caller can cancel all system notifications. */
    public List<Integer> deleteAllAndGetNotifIds() {
        List<Integer> ids = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(
                TABLE, new String[]{"notif_id"}, "notif_id != -1",
                null, null, null, null);
            while (c.moveToNext()) ids.add(c.getInt(0));
        } catch (Exception e) {
            Log.w(TAG, "Could not read notif_ids before clear");
        } finally {
            if (c != null) c.close();
        }
        try { getWritableDatabase().delete(TABLE, null, null); }
        catch (Exception e) { Log.w(TAG, "Could not clear inbox"); }
        return ids;
    }

    /** Delete all entries. */
    public void deleteAll() {
        try { getWritableDatabase().delete(TABLE, null, null); }
        catch (Exception e) { Log.w(TAG, "Could not clear inbox"); }
    }

    /** Count of unread entries — used for badge on bell icon. */
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

    /** Total count — used for app icon badge number. */
    public int totalCount() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE, null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /** Get the notif_id for a specific inbox entry (used for notification sync). */
    public int getNotifId(long id) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query(
                TABLE, new String[]{"notif_id"}, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
            return c.moveToFirst() ? c.getInt(0) : -1;
        } catch (Exception e) {
            return -1;
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
                e.notifId   = c.getInt(c.getColumnIndexOrThrow("notif_id"));
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
        public int     notifId; // Android notification ID — cancel this when entry is acted on
    }
}
