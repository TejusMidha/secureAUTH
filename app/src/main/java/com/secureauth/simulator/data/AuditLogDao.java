package com.secureauth.simulator.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** Reads and writes the audit_log table. Lists are newest first. */
public class AuditLogDao {

    private final DbHelper helper;

    public AuditLogDao(DbHelper helper) {
        this.helper = helper;
    }

    public long insert(AuditLog log) {
        return helper.getWritableDatabase().insert(DbHelper.TABLE_AUDIT_LOG, null, toValues(log));
    }

    /**
     * Inserts many rows in one transaction (much faster than one by one, and all-or-nothing).
     * Returns how many were stored, or -1 if something failed and nothing was kept.
     */
    public int insertAll(List<AuditLog> rows) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (AuditLog row : rows) {
                if (db.insert(DbHelper.TABLE_AUDIT_LOG, null, toValues(row)) < 0) {
                    return -1;
                }
            }
            db.setTransactionSuccessful();
            return rows.size();
        } finally {
            db.endTransaction();
        }
    }

    /** Shortcut for the common case: "now", no location. */
    public long log(long userId, String event, String result) {
        return log(userId, event, result, null, null);
    }

    /** "Now", with the place where it happened (both null when there is no location). */
    public long log(long userId, String event, String result, Double lat, Double lng) {
        AuditLog row = new AuditLog();
        row.userId = userId;
        row.event = event;
        row.result = result;
        row.lat = lat;
        row.lng = lng;
        row.ts = System.currentTimeMillis();
        return insert(row);
    }

    /**
     * The newest entry of this kind that has a location, or null. Used to find where the user
     * last logged in (event SESSION_START) when judging whether a new login is far away.
     */
    public AuditLog findLastWithLocation(long userId, String event) {
        try (Cursor c = helper.getReadableDatabase().query(DbHelper.TABLE_AUDIT_LOG, null,
                "user_id = ? AND event = ? AND lat IS NOT NULL AND lng IS NOT NULL",
                new String[]{String.valueOf(userId), event}, null, null, "ts DESC, id DESC", "1")) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    public AuditLog getById(long id) {
        try (Cursor c = helper.getReadableDatabase().query(DbHelper.TABLE_AUDIT_LOG, null,
                "id = ?", new String[]{String.valueOf(id)}, null, null, null, "1")) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    public List<AuditLog> listForUser(long userId) {
        return query("user_id = ?", new String[]{String.valueOf(userId)});
    }

    /** Rows with fromTs <= ts <= toTs (both epoch milliseconds, inclusive). */
    public List<AuditLog> listForUserBetween(long userId, long fromTs, long toTs) {
        return query("user_id = ? AND ts >= ? AND ts <= ?",
                new String[]{String.valueOf(userId), String.valueOf(fromTs), String.valueOf(toTs)});
    }

    public int update(AuditLog log) {
        return helper.getWritableDatabase().update(DbHelper.TABLE_AUDIT_LOG, toValues(log),
                "id = ?", new String[]{String.valueOf(log.id)});
    }

    public int delete(long id) {
        return helper.getWritableDatabase().delete(DbHelper.TABLE_AUDIT_LOG,
                "id = ?", new String[]{String.valueOf(id)});
    }

    private List<AuditLog> query(String where, String[] args) {
        List<AuditLog> result = new ArrayList<>();
        // ORDER BY ts DESC uses the (user_id, ts) index
        try (Cursor c = helper.getReadableDatabase().query(
                DbHelper.TABLE_AUDIT_LOG, null, where, args, null, null, "ts DESC, id DESC")) {
            while (c.moveToNext()) {
                result.add(fromCursor(c));
            }
        }
        return result;
    }

    private ContentValues toValues(AuditLog a) {
        ContentValues v = new ContentValues();
        v.put("user_id", a.userId);
        v.put("event", a.event);
        v.put("result", a.result);
        v.put("lat", a.lat);   // a null Double is stored as SQL NULL
        v.put("lng", a.lng);
        v.put("ts", a.ts);
        return v;
    }

    private AuditLog fromCursor(Cursor c) {
        AuditLog a = new AuditLog();
        a.id = c.getLong(c.getColumnIndexOrThrow("id"));
        a.userId = c.getLong(c.getColumnIndexOrThrow("user_id"));
        a.event = c.getString(c.getColumnIndexOrThrow("event"));
        a.result = c.getString(c.getColumnIndexOrThrow("result"));
        int latCol = c.getColumnIndexOrThrow("lat");
        int lngCol = c.getColumnIndexOrThrow("lng");
        // getDouble returns 0.0 for NULL, so check for NULL first to keep "no location" distinct
        a.lat = c.isNull(latCol) ? null : c.getDouble(latCol);
        a.lng = c.isNull(lngCol) ? null : c.getDouble(lngCol);
        a.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
        return a;
    }
}
