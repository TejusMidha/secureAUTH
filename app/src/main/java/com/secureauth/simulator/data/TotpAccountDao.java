package com.secureauth.simulator.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** Reads and writes the totp_accounts table. */
public class TotpAccountDao {

    private final DbHelper helper;

    public TotpAccountDao(DbHelper helper) {
        this.helper = helper;
    }

    public long insert(TotpAccount account) {
        return helper.getWritableDatabase().insert(DbHelper.TABLE_TOTP_ACCOUNTS, null, toValues(account));
    }

    /**
     * Makes {@code account} the user's only authenticator: removes any old one and inserts the
     * new one in a single transaction, so the user is never left with none (or two) if it fails.
     * Used when enrolling or re-enrolling. Returns the new id.
     */
    public long replaceForUser(TotpAccount account) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(DbHelper.TABLE_TOTP_ACCOUNTS, "user_id = ?",
                    new String[]{String.valueOf(account.userId)});
            long id = db.insert(DbHelper.TABLE_TOTP_ACCOUNTS, null, toValues(account));
            if (id < 0) {
                return -1; // transaction is rolled back below because we never mark it successful
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public TotpAccount getById(long id) {
        try (Cursor c = helper.getReadableDatabase().query(DbHelper.TABLE_TOTP_ACCOUNTS, null,
                "id = ?", new String[]{String.valueOf(id)}, null, null, null, "1")) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    public List<TotpAccount> listForUser(long userId) {
        List<TotpAccount> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query(DbHelper.TABLE_TOTP_ACCOUNTS, null,
                "user_id = ?", new String[]{String.valueOf(userId)}, null, null, "id ASC")) {
            while (c.moveToNext()) {
                result.add(fromCursor(c));
            }
        }
        return result;
    }

    public int update(TotpAccount account) {
        return helper.getWritableDatabase().update(DbHelper.TABLE_TOTP_ACCOUNTS, toValues(account),
                "id = ?", new String[]{String.valueOf(account.id)});
    }

    public int delete(long id) {
        return helper.getWritableDatabase().delete(DbHelper.TABLE_TOTP_ACCOUNTS,
                "id = ?", new String[]{String.valueOf(id)});
    }

    private ContentValues toValues(TotpAccount a) {
        ContentValues v = new ContentValues();
        v.put("user_id", a.userId);
        v.put("secret_enc", a.secretEnc);
        v.put("iv", a.iv);
        v.put("issuer", a.issuer);
        v.put("digits", a.digits);
        v.put("period", a.period);
        v.put("algo", a.algo);
        v.put("created_at", a.createdAt);
        return v;
    }

    private TotpAccount fromCursor(Cursor c) {
        TotpAccount a = new TotpAccount();
        a.id = c.getLong(c.getColumnIndexOrThrow("id"));
        a.userId = c.getLong(c.getColumnIndexOrThrow("user_id"));
        a.secretEnc = c.getString(c.getColumnIndexOrThrow("secret_enc"));
        a.iv = c.getString(c.getColumnIndexOrThrow("iv"));
        a.issuer = c.getString(c.getColumnIndexOrThrow("issuer"));
        a.digits = c.getInt(c.getColumnIndexOrThrow("digits"));
        a.period = c.getInt(c.getColumnIndexOrThrow("period"));
        a.algo = c.getString(c.getColumnIndexOrThrow("algo"));
        a.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return a;
    }
}
