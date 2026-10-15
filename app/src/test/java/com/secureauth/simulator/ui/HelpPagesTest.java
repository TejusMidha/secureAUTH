package com.secureauth.simulator.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which addresses the Help screen may open. */
public class HelpPagesTest {

    @Test
    public void theTwoHelpPagesAreAllowed() {
        assertTrue(HelpPages.isAllowed(HelpPages.LOCAL_URL));
        assertTrue(HelpPages.isAllowed(HelpPages.RFC_URL));
    }

    @Test
    public void otherPagesOnTheSameSitesAreAllowedToo() {
        assertTrue(HelpPages.isAllowed("https://datatracker.ietf.org/doc/html/rfc4226"));
        assertTrue(HelpPages.isAllowed("https://datatracker.ietf.org/doc/html/rfc6238#section-4"));
        assertTrue(HelpPages.isAllowed("file:///android_asset/help.html#top"));
    }

    @Test
    public void otherSitesAreRefused() {
        assertFalse(HelpPages.isAllowed("https://example.com/"));
        assertFalse(HelpPages.isAllowed("https://www.rfc-editor.org/rfc/rfc6238"));
    }

    @Test
    public void plainHttpIsRefused() {
        assertFalse(HelpPages.isAllowed("http://datatracker.ietf.org/doc/html/rfc6238"));
    }

    @Test
    public void lookAlikeHostsAreRefused() {
        assertFalse(HelpPages.isAllowed("https://datatracker.ietf.org.evil.example/"));
        assertFalse(HelpPages.isAllowed("https://datatracker.ietf.org@evil.example/"));
        assertFalse(HelpPages.isAllowed("https://evil.example/https://datatracker.ietf.org/"));
    }

    @Test
    public void otherLocalFilesAndSchemesAreRefused() {
        assertFalse(HelpPages.isAllowed("file:///data/data/com.secureauth.simulator/databases/secureauth.db"));
        assertFalse(HelpPages.isAllowed("file:///sdcard/Download/page.html"));
        assertFalse(HelpPages.isAllowed("content://com.secureauth.simulator.fileprovider/qr/enroll_qr.png"));
        assertFalse(HelpPages.isAllowed("javascript:alert(1)"));
        assertFalse(HelpPages.isAllowed("intent://scan/#Intent;scheme=zxing;end"));
    }

    @Test
    public void emptyAndMissingAddressesAreRefused() {
        assertFalse(HelpPages.isAllowed(""));
        assertFalse(HelpPages.isAllowed(null));
    }
}
