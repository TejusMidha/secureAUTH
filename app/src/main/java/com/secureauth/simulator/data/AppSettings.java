package com.secureauth.simulator.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.secureauth.simulator.crypto.TotpEngine;

/**
 * The few settings the user can change, kept in SharedPreferences (a small key-value file that
 * stays on the phone). This class owns the key names and the allowed values, so the Settings
 * screen and the code that uses a setting (login, enrollment, location) cannot disagree.
 *
 * Every getter checks what it reads: a missing or damaged value falls back to the default
 * instead of reaching the TOTP engine or the session timer.
 */
public final class AppSettings {

    public static final String PREFS_NAME = "secureauth_prefs";

    public static final String KEY_DIGITS = "totp_digits";
    public static final String KEY_PERIOD = "totp_period";
    public static final String KEY_ALGO = "totp_algo";
    public static final String KEY_AUTO_LOCK_MS = "auto_lock_ms";
    public static final String KEY_RECORD_LOCATION = "record_location";

    // What Google Authenticator expects; anything else may not work there
    public static final int DEFAULT_DIGITS = 6;
    public static final int DEFAULT_PERIOD = 30;
    public static final String DEFAULT_ALGO = TotpEngine.ALGO_SHA1;
    public static final long DEFAULT_AUTO_LOCK_MS = 5 * 60 * 1000L;

    public static final long MIN_AUTO_LOCK_MS = 60 * 1000L;
    public static final long MAX_AUTO_LOCK_MS = 30 * 60 * 1000L;

    /** The choices shown in the spinners, in the order they are listed. */
    public static final int[] DIGIT_CHOICES = {6, 8};
    public static final int[] PERIOD_CHOICES = {30, 60};
    public static final String[] ALGO_CHOICES = {TotpEngine.ALGO_SHA1, TotpEngine.ALGO_SHA256};

    private AppSettings() {
    }

    // ---- rules (no Android classes, so they are unit tested on the PC) ----------------------

    public static boolean isValidDigits(int digits) {
        return contains(DIGIT_CHOICES, digits);
    }

    public static boolean isValidPeriod(int period) {
        return contains(PERIOD_CHOICES, period);
    }

    public static boolean isValidAlgo(String algo) {
        return TotpEngine.ALGO_SHA1.equals(algo) || TotpEngine.ALGO_SHA256.equals(algo);
    }

    /** Keeps an auto-lock time inside 1 to 30 minutes. */
    public static long clampAutoLockMs(long ms) {
        return Math.max(MIN_AUTO_LOCK_MS, Math.min(MAX_AUTO_LOCK_MS, ms));
    }

    /** True if any of the three differs from what Google Authenticator expects. */
    public static boolean differsFromDefaults(int digits, int period, String algo) {
        return digits != DEFAULT_DIGITS || period != DEFAULT_PERIOD || !DEFAULT_ALGO.equals(algo);
    }

    /**
     * The time-picker answer (hour, minute) as a number of milliseconds, or -1 if it is outside
     * 1 to 30 minutes. The picker has no seconds, so the whole range is made of minutes: 0:01 to 0:30.
     */
    public static long autoLockMsFromPicker(int hour, int minute) {
        long totalMinutes = hour * 60L + minute;
        if (totalMinutes < 1 || totalMinutes > 30) {
            return -1;
        }
        return totalMinutes * 60_000L;
    }

    private static boolean contains(int[] values, int wanted) {
        for (int v : values) {
            if (v == wanted) {
                return true;
            }
        }
        return false;
    }

    // ---- reading and writing ----------------------------------------------------------------

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getDigits(Context context) {
        int saved = prefs(context).getInt(KEY_DIGITS, DEFAULT_DIGITS);
        return isValidDigits(saved) ? saved : DEFAULT_DIGITS;
    }

    public static void setDigits(Context context, int digits) {
        if (isValidDigits(digits)) {
            prefs(context).edit().putInt(KEY_DIGITS, digits).apply();
        }
    }

    public static int getPeriod(Context context) {
        int saved = prefs(context).getInt(KEY_PERIOD, DEFAULT_PERIOD);
        return isValidPeriod(saved) ? saved : DEFAULT_PERIOD;
    }

    public static void setPeriod(Context context, int period) {
        if (isValidPeriod(period)) {
            prefs(context).edit().putInt(KEY_PERIOD, period).apply();
        }
    }

    public static String getAlgo(Context context) {
        String saved = prefs(context).getString(KEY_ALGO, DEFAULT_ALGO);
        return isValidAlgo(saved) ? saved : DEFAULT_ALGO;
    }

    public static void setAlgo(Context context, String algo) {
        if (isValidAlgo(algo)) {
            prefs(context).edit().putString(KEY_ALGO, algo).apply();
        }
    }

    /** How long a login lasts before the session must be started again. Default 5 minutes. */
    public static long getAutoLockMs(Context context) {
        return clampAutoLockMs(prefs(context).getLong(KEY_AUTO_LOCK_MS, DEFAULT_AUTO_LOCK_MS));
    }

    public static void setAutoLockMs(Context context, long ms) {
        prefs(context).edit().putLong(KEY_AUTO_LOCK_MS, clampAutoLockMs(ms)).apply();
    }

    /** "Record login location". On by default. */
    public static boolean isRecordLocation(Context context) {
        return prefs(context).getBoolean(KEY_RECORD_LOCATION, true);
    }

    public static void setRecordLocation(Context context, boolean record) {
        prefs(context).edit().putBoolean(KEY_RECORD_LOCATION, record).apply();
    }
}
