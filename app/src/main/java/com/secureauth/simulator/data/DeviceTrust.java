package com.secureauth.simulator.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Locale;

/**
 * The rules about "this phone" in the trusted devices list.
 *
 * The trusted_devices table only has a name column, so there is nothing in it that says which
 * row is THIS phone. We remember that row's id in SharedPreferences, per user. That way the
 * user can rename the device without losing the link, and two accounts on one phone each know
 * their own row.
 */
public final class DeviceTrust {

    public static final int MAX_NAME_LENGTH = 40;

    private static final String FALLBACK_NAME = "Android device";

    /** Same preferences file as the session and location settings. */
    private static final String PREFS_NAME = "secureauth_prefs";
    private static final String PREF_DEVICE_ID_PREFIX = "trusted_device_id_";

    private DeviceTrust() {
    }

    // ---- names ------------------------------------------------------------------------------

    /** What this phone is called by default, for example "Google Pixel 8". */
    public static String currentDeviceName() {
        return deviceName(Build.MANUFACTURER, Build.MODEL);
    }

    /**
     * Manufacturer and model joined with a space. Some phones already put the maker at the start
     * of the model ("HTC One"), so it is not repeated. Blank input gives a generic name.
     */
    public static String deviceName(String manufacturer, String model) {
        String maker = manufacturer == null ? "" : manufacturer.trim();
        String type = model == null ? "" : model.trim();
        String name;
        if (maker.isEmpty()) {
            name = type;
        } else if (type.isEmpty()) {
            name = maker;
        } else if (type.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT))) {
            name = type;
        } else {
            name = maker + " " + type;
        }
        return name.isEmpty() ? FALLBACK_NAME : name;
    }

    /** What the user typed, tidied: no spaces at the ends, and runs of spaces become one. */
    public static String cleanName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    /** True if a cleaned name is acceptable: not empty and not longer than {@link #MAX_NAME_LENGTH}. */
    public static boolean isValidName(String cleaned) {
        return cleaned != null && !cleaned.isEmpty() && cleaned.length() <= MAX_NAME_LENGTH;
    }

    // ---- this phone's row -------------------------------------------------------------------

    /**
     * Adds this phone to the user's trusted devices (once) and writes DEVICE_TRUSTED. Returns the
     * id of the row, or -1 if it could not be stored. Call from a background thread.
     */
    public static long trustCurrentDevice(Context context, TrustedDeviceDao devices,
                                          AuditLogDao audit, long userId) {
        long existing = currentDeviceId(context, userId);
        if (existing >= 0 && devices.getById(existing) != null) {
            return existing; // already on the list: do not add the phone twice
        }

        TrustedDevice device = new TrustedDevice();
        device.userId = userId;
        device.deviceName = currentDeviceName();
        device.addedAt = System.currentTimeMillis();
        long id = devices.insert(device);
        if (id < 0) {
            return -1;
        }
        prefs(context).edit().putLong(PREF_DEVICE_ID_PREFIX + userId, id).apply();
        audit.log(userId, AuditEvent.DEVICE_TRUSTED, AuditEvent.RESULT_SUCCESS);
        return id;
    }

    /** Id of this phone's row for the user, or -1 if this phone was never trusted by them. */
    public static long currentDeviceId(Context context, long userId) {
        return prefs(context).getLong(PREF_DEVICE_ID_PREFIX + userId, -1L);
    }

    /**
     * True if this phone is on the user's list AND has been revoked. A phone that was never
     * trusted is not "revoked": it simply logs in the normal way. Call from a background thread.
     */
    public static boolean isCurrentDeviceRevoked(Context context, TrustedDeviceDao devices, long userId) {
        long id = currentDeviceId(context, userId);
        if (id < 0) {
            return false;
        }
        TrustedDevice device = devices.getById(id);
        // The row must belong to this user, in case the stored id points at someone else's row
        return device != null && device.userId == userId && device.revoked;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
