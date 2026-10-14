package com.secureauth.simulator.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** Reads and writes the users table. All values go through ? placeholders. */
public class UserDao {

    private final DbHelper helper;

    public UserDao(DbHelper helper) {
        this.helper = helper;
    }

    /** Inserts the user and returns the new id, or -1 if the username is already taken. */
    public long insert(User user) {
        return helper.getWritableDatabase().insert(DbHelper.TABLE_USERS, null, toValues(user));
    }

    public User getById(long id) {
        return queryOne("id = ?", new String[]{String.valueOf(id)});
    }

    public User findByUsername(String username) {
        return queryOne("username = ?", new String[]{username});
    }

    public List<User> list() {
        List<User> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query(
                DbHelper.TABLE_USERS, null, null, null, null, null, "id ASC")) {
            while (c.moveToNext()) {
                result.add(fromCursor(c));
            }
        }
        return result;
    }

    /** Overwrites every column of the row with the matching id. Returns rows changed. */
    public int update(User user) {
        return helper.getWritableDatabase().update(
                DbHelper.TABLE_USERS, toValues(user), "id = ?", new String[]{String.valueOf(user.id)});
    }

    public int delete(long id) {
        return helper.getWritableDatabase().delete(
                DbHelper.TABLE_USERS, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Adds 1 inside SQL so two quick failures cannot overwrite each other. */
    public void incrementFailedAttempts(long id) {
        helper.getWritableDatabase().execSQL(
                "UPDATE users SET failed_attempts = failed_attempts + 1 WHERE id = ?",
                new Object[]{id});
    }

    public void resetFailedAttempts(long id) {
        updateLong(id, "failed_attempts", 0);
    }

    /** Pass 0 to clear the lock. */
    public void setLockedUntil(long id, long lockedUntil) {
        updateLong(id, "locked_until", lockedUntil);
    }

    private void updateLong(long id, String column, long value) {
        ContentValues values = new ContentValues();
        values.put(column, value);
        helper.getWritableDatabase().update(
                DbHelper.TABLE_USERS, values, "id = ?", new String[]{String.valueOf(id)});
    }

    private User queryOne(String where, String[] args) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(DbHelper.TABLE_USERS, null, where, args, null, null, null, "1")) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    private ContentValues toValues(User user) {
        ContentValues v = new ContentValues();
        v.put("username", user.username);
        v.put("password_hash", user.passwordHash);
        v.put("salt", user.salt);
        v.put("avatar_uri", user.avatarUri);
        v.put("failed_attempts", user.failedAttempts);
        v.put("locked_until", user.lockedUntil);
        v.put("created_at", user.createdAt);
        return v;
    }

    private User fromCursor(Cursor c) {
        User u = new User();
        u.id = c.getLong(c.getColumnIndexOrThrow("id"));
        u.username = c.getString(c.getColumnIndexOrThrow("username"));
        u.passwordHash = c.getString(c.getColumnIndexOrThrow("password_hash"));
        u.salt = c.getString(c.getColumnIndexOrThrow("salt"));
        u.avatarUri = c.getString(c.getColumnIndexOrThrow("avatar_uri"));
        u.failedAttempts = c.getInt(c.getColumnIndexOrThrow("failed_attempts"));
        u.lockedUntil = c.getLong(c.getColumnIndexOrThrow("locked_until"));
        u.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return u;
    }
}
