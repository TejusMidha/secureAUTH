package com.secureauth.simulator.ui;

/**
 * The only two places the Help screen may show. Kept free of Android classes so the rule can be
 * unit tested on the PC.
 */
final class HelpPages {

    /** The page that ships inside the app (app/src/main/assets/help.html). */
    static final String LOCAL_URL = "file:///android_asset/help.html";

    /** The standard itself. */
    static final String RFC_URL = "https://datatracker.ietf.org/doc/html/rfc6238";

    private static final String ASSET_PREFIX = "file:///android_asset/";
    private static final String RFC_HOST_PREFIX = "https://datatracker.ietf.org/";

    private HelpPages() {
    }

    /**
     * True for our own asset pages and for pages on datatracker.ietf.org over https. The trailing
     * slash in the prefix matters: "https://datatracker.ietf.org.evil.example/" must not pass.
     */
    static boolean isAllowed(String url) {
        return url != null && (url.startsWith(ASSET_PREFIX) || url.startsWith(RFC_HOST_PREFIX));
    }
}
