package com.secureauth.simulator.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** Reads and writes the backup_codes table. */
public class BackupCodeDao {

    private final DbHelper helper;

    public BackupCodeDao(DbHelper helper) {
        this.helper = helper;
    }

    public long insert(BackupCode code) {
        return helper.getWritableDatabase().insert(DbHelper.TABLE_BACKUP_CODES, null, toValues(code));
    }

    public BackupCode getById(long id) {
        try (Cursor c = helper.getReadableDatabase().query(DbHelper.TABLE_BACKUP_CODES, null,
                "id = ?", new String[]{String.valueOf(id)}, null, null, null, "1")) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    /** All codes for the user, used or not. */
    public List<BackupCode> listForUser(long userId) {
        return query("user_id = ?", new String[]{String.valueOf(userId)});
    }

    /** Codes that can still be used; the recovery screen checks the entered code against these. */
    public List<BackupCode> listUnused(long userId) {
        return query("user_id = ? AND used = 0", new String[]{String.valueOf(userId)});
    }

    /**
     * Flags the code as spent so it can never be accepted twice. The "used = 0" condition makes
     * this atomic: if two attempts race, only one of them changes the row (returns 1), so the
     * other can tell the code was already gone (returns 0).
     */
    public int markUsed(long id) {
        ContentValues v = new ContentValues();
        v.put("used", 1);
        v.put("used_at", System.currentTimeMillis());
        return helper.getWritableDatabase().update(DbHelper.TABLE_BACKUP_CODES, v,
                "id = ? AND used = 0", new String[]{String.valueOf(id)});
    }

    /**
     * Throws away all of the user's backup codes (used or not) and stores {@code fresh} instead,
     * in one transaction: either the user ends up with exactly the new set, or nothing changes.
     * Returns true on success.
     */
    public boolean replaceForUser(long userId, List<BackupCode> fresh) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(DbHelper.TABLE_BACKUP_CODES, "user_id = ?", new String[]{String.valueOf(userId)});
            for (BackupCode code : fresh) {
                if (db.insert(DbHelper.TABLE_BACKUP_CODES, null, toValues(code)) < 0) {
                    return false; // never marked successful, so the delete is rolled back too
                }
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public int update(BackupCode code) {
        return helper.getWritableDatabase().update(DbHelper.TABLE_BACKUP_CODES, toValues(code),
                "id = ?", new String[]{String.valueOf(code.id)});
    }

    public int delete(long id) {
        return helper.getWritableDatabase().delete(DbHelper.TABLE_BACKUP_CODES,
                "id = ?", new String[]{String.valueOf(id)});
    }

    private List<BackupCode> query(String where, String[] args) {
        List<BackupCode> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query(
                DbHelper.TABLE_BACKUP_CODES, null, where, args, null, null, "id ASC")) {
            while (c.moveToNext()) {
                result.add(fromCursor(c));
            }
        }
        return result;
    }

    private ContentValues toValues(BackupCode b) {
        ContentValues v = new ContentValues();
        v.put("user_id", b.userId);
        v.put("code_hash", b.codeHash);
        v.put("salt", b.salt);
        v.put("used", b.used ? 1 : 0);
        v.put("used_at", b.usedAt);
        return v;
    }

    private BackupCode fromCursor(Cursor c) {
        BackupCode b = new BackupCode();
        b.id = c.getLong(c.getColumnIndexOrThrow("id"));
        b.userId = c.getLong(c.getColumnIndexOrThrow("user_id"));
        b.codeHash = c.getString(c.getColumnIndexOrThrow("code_hash"));
        b.salt = c.getString(c.getColumnIndexOrThrow("salt"));
        b.used = c.getInt(c.getColumnIndexOrThrow("used")) == 1;
        int usedAtCol = c.getColumnIndexOrThrow("used_at");
        b.usedAt = c.isNull(usedAtCol) ? null : c.getLong(usedAtCol);
        return b;
    }
}
