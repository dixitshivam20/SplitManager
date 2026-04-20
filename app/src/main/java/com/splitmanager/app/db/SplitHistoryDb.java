package com.splitmanager.app.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight SQLite helper for split history.
 * No Room — no extra dependencies.
 */
public class SplitHistoryDb extends SQLiteOpenHelper {

    private static final String DB_NAME    = "split_history.db";
    private static final int    DB_VERSION = 1;
    private static final String TABLE      = "splits";

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

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "amount REAL NOT NULL," +
            "merchant TEXT," +
            "group_name TEXT," +
            "member_count INTEGER," +
            "split_type TEXT," +   // EQUAL or CUSTOM
            "timestamp INTEGER NOT NULL," +
            "expense_id TEXT" +    // Splitwise expense ID
        ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public void insert(double amount, String merchant, String groupName,
                       int memberCount, String splitType, String expenseId) {
        ContentValues cv = new ContentValues();
        cv.put("amount",       amount);
        cv.put("merchant",     merchant);
        cv.put("group_name",   groupName);
        cv.put("member_count", memberCount);
        cv.put("split_type",   splitType);
        cv.put("timestamp",    System.currentTimeMillis());
        cv.put("expense_id",   expenseId != null ? expenseId : "");
        getWritableDatabase().insert(TABLE, null, cv);
    }

    public List<SplitRecord> getAll() {
        List<SplitRecord> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(
            TABLE, null, null, null, null, null, "timestamp DESC", "100");
        while (c.moveToNext()) {
            SplitRecord r = new SplitRecord();
            r.id          = c.getLong(c.getColumnIndexOrThrow("id"));
            r.amount      = c.getDouble(c.getColumnIndexOrThrow("amount"));
            r.merchant    = c.getString(c.getColumnIndexOrThrow("merchant"));
            r.groupName   = c.getString(c.getColumnIndexOrThrow("group_name"));
            r.memberCount = c.getInt(c.getColumnIndexOrThrow("member_count"));
            r.splitType   = c.getString(c.getColumnIndexOrThrow("split_type"));
            r.timestamp   = c.getLong(c.getColumnIndexOrThrow("timestamp"));
            r.expenseId   = c.getString(c.getColumnIndexOrThrow("expense_id"));
            list.add(r);
        }
        c.close();
        return list;
    }

    public void deleteAll() {
        getWritableDatabase().delete(TABLE, null, null);
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
