package com.secureauth.simulator.data;

import android.content.ContentValues;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/** Reads and writes the trusted_devices table. */
public class TrustedDeviceDao {

    private final DbHelper helper;

    public TrustedDeviceDao(DbHelper helper) {
        this.helper = helper;
    }

    public long insert(TrustedDevice device) {
        return helper.getWritableDatabase().insert(DbHelper.TABLE_TRUSTED_DEVICES, null, toValues(device));
    }

    public TrustedDevice getById(long id) {
        try (Cursor c = helper.getReadableDatabase().query(DbHelper.TABLE_TRUSTED_DEVICES, null,
                "id = ?", new String[]{String.valueOf(id)}, null, null, null, "1")) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    public List<TrustedDevice> listForUser(long userId) {
        List<TrustedDevice> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query(DbHelper.TABLE_TRUSTED_DEVICES, null,
                "user_id = ?", new String[]{String.valueOf(userId)}, null, null, "id ASC")) {
            while (c.moveToNext()) {
                result.add(fromCursor(c));
            }
        }
        return result;
    }

    /** Used for both rename (change deviceName) and revoke (set revoked = true). */
    public int update(TrustedDevice device) {
        return helper.getWritableDatabase().update(DbHelper.TABLE_TRUSTED_DEVICES, toValues(device),
                "id = ?", new String[]{String.valueOf(device.id)});
    }

    public int delete(long id) {
        return helper.getWritableDatabase().delete(DbHelper.TABLE_TRUSTED_DEVICES,
                "id = ?", new String[]{String.valueOf(id)});
    }

    private ContentValues toValues(TrustedDevice d) {
        ContentValues v = new ContentValues();
        v.put("user_id", d.userId);
        v.put("device_name", d.deviceName);
        v.put("added_at", d.addedAt);
        v.put("revoked", d.revoked ? 1 : 0);
        return v;
    }

    private TrustedDevice fromCursor(Cursor c) {
        TrustedDevice d = new TrustedDevice();
        d.id = c.getLong(c.getColumnIndexOrThrow("id"));
        d.userId = c.getLong(c.getColumnIndexOrThrow("user_id"));
        d.deviceName = c.getString(c.getColumnIndexOrThrow("device_name"));
        d.addedAt = c.getLong(c.getColumnIndexOrThrow("added_at"));
        d.revoked = c.getInt(c.getColumnIndexOrThrow("revoked")) == 1;
        return d;
    }
}
