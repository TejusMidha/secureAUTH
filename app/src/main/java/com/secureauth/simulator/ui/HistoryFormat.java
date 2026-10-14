package com.secureauth.simulator.ui;

import java.util.Locale;

/** How a login-history row is worded. Kept free of Android classes so it is easy to unit test. */
final class HistoryFormat {

    private HistoryFormat() {
    }

    /** "19.0760, 72.8777", or the given fallback text when the entry has no location. */
    static String location(Double lat, Double lng, String noLocationText) {
        if (lat == null || lng == null) {
            return noLocationText;
        }
        // Locale.ROOT keeps the decimal point a dot, whatever language the phone uses
        return String.format(Locale.ROOT, "%.4f, %.4f", lat, lng);
    }

    /**
     * True for entries that went wrong (FAIL, CANCELLED, ERROR). An entry with no result at all,
     * such as an imported row without that column, is not shown as a failure.
     */
    static boolean isFailure(String result) {
        return result != null && !result.isEmpty() && !"SUCCESS".equals(result);
    }
}
