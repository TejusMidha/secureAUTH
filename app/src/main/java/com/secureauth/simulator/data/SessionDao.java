package com.secureauth.simulator.data;

import android.content.ContentValues;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/** Reads and writes the sessions table. Only token hashes are stored, never the raw token. */
public class SessionDao {

    private final DbHelper helper;

    public SessionDao(DbHelper helper) {
        this.helper = helper;
    }

    public long insert(Session session) {
        return helper.getWritableDatabase().insert(DbHelper.TABLE_SESSIONS, null, toValues(session));
    }

    public Session getById(long id) {
        try (Cursor c = helper.getReadableDatabase().query(DbHelper.TABLE_SESSIONS, null,
                "id = ?", new String[]{String.valueOf(id)}, null, null, null, "1")) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    public List<Session> listForUser(long userId) {
        List<Session> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query(DbHelper.TABLE_SESSIONS, null,
                "user_id = ?", new String[]{String.valueOf(userId)}, null, null, "id ASC")) {
            while (c.moveToNext()) {
                result.add(fromCursor(c));
            }
        }
        return result;
    }

    /** Returns the session only if the hash matches and it has not expired at time `now`. */
    public Session findValid(long userId, String tokenHash, long now) {
        try (Cursor c = helper.getReadableDatabase().query(DbHelper.TABLE_SESSIONS, null,
                "user_id = ? AND token_hash = ? AND expires_at > ?",
                new String[]{String.valueOf(userId), tokenHash, String.valueOf(now)},
                null, null, null, "1")) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    public int update(Session session) {
        return helper.getWritableDatabase().update(DbHelper.TABLE_SESSIONS, toValues(session),
                "id = ?", new String[]{String.valueOf(session.id)});
    }

    public int delete(long id) {
        return helper.getWritableDatabase().delete(DbHelper.TABLE_SESSIONS,
                "id = ?", new String[]{String.valueOf(id)});
    }

    /** Removes all of one user's sessions (a new login replaces any older one). */
    public int deleteForUser(long userId) {
        return helper.getWritableDatabase().delete(DbHelper.TABLE_SESSIONS,
                "user_id = ?", new String[]{String.valueOf(userId)});
    }

    /** Removes every session that ended at or before `now`. Returns how many were removed. */
    public int deleteExpired(long now) {
        return helper.getWritableDatabase().delete(DbHelper.TABLE_SESSIONS,
                "expires_at <= ?", new String[]{String.valueOf(now)});
    }

    private ContentValues toValues(Session s) {
        ContentValues v = new ContentValues();
        v.put("user_id", s.userId);
        v.put("token_hash", s.tokenHash);
        v.put("created_at", s.createdAt);
        v.put("expires_at", s.expiresAt);
        return v;
    }

    private Session fromCursor(Cursor c) {
        Session s = new Session();
        s.id = c.getLong(c.getColumnIndexOrThrow("id"));
        s.userId = c.getLong(c.getColumnIndexOrThrow("user_id"));
        s.tokenHash = c.getString(c.getColumnIndexOrThrow("token_hash"));
        s.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        s.expiresAt = c.getLong(c.getColumnIndexOrThrow("expires_at"));
        return s;
    }
}
