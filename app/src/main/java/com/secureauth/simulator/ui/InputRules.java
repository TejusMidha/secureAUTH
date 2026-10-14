package com.secureauth.simulator.ui;

import java.util.Locale;

import com.secureauth.simulator.R;

/**
 * Validation rules for the register and login forms. Kept free of views so they are easy to
 * unit test. The error methods return a string resource id, or 0 when the input is fine.
 */
public final class InputRules {

    public static final int MIN_USERNAME = 3;
    public static final int MAX_USERNAME = 20;
    public static final int MIN_PASSWORD = 8;

    public enum Strength { WEAK, FAIR, STRONG }

    private InputRules() {
    }

    /**
     * Trims spaces and lower-cases, so "Alice " and "alice" are the same account.
     * SQLite's UNIQUE is case-sensitive, so we normalise before saving and before looking up.
     */
    public static String normalizeUsername(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Expects an already normalised username. */
    public static int usernameError(String username) {
        if (username.length() < MIN_USERNAME || username.length() > MAX_USERNAME) {
            return R.string.error_username_length;
        }
        return 0;
    }

    public static int passwordError(char[] password) {
        return password.length < MIN_PASSWORD ? R.string.error_password_short : 0;
    }

    /**
     * Rough hint, not a guarantee: counts character types (a-z, A-Z, 0-9, symbols) and
     * adds one point for 12+ characters. Only the 8-character minimum is enforced.
     */
    public static Strength strength(char[] password) {
        if (password.length < MIN_PASSWORD) {
            return Strength.WEAK;
        }
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean other = false;
        for (char c : password) {
            if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                other = true;
            }
        }
        int score = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0)
                + (password.length >= 12 ? 1 : 0);
        if (score <= 1) {
            return Strength.WEAK;
        }
        return score == 2 ? Strength.FAIR : Strength.STRONG;
    }
}
