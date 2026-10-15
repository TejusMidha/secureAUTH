package com.secureauth.simulator.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.secureauth.simulator.crypto.TotpEngine;

import org.junit.Test;

/** The rules of AppSettings that need no phone. */
public class AppSettingsRulesTest {

    @Test
    public void onlyTheOfferedDigitCountsPeriodsAndAlgorithmsAreValid() {
        assertTrue(AppSettings.isValidDigits(6));
        assertTrue(AppSettings.isValidDigits(8));
        assertFalse(AppSettings.isValidDigits(7));
        assertFalse(AppSettings.isValidDigits(0));

        assertTrue(AppSettings.isValidPeriod(30));
        assertTrue(AppSettings.isValidPeriod(60));
        assertFalse(AppSettings.isValidPeriod(45));
        assertFalse(AppSettings.isValidPeriod(-30));

        assertTrue(AppSettings.isValidAlgo(TotpEngine.ALGO_SHA1));
        assertTrue(AppSettings.isValidAlgo(TotpEngine.ALGO_SHA256));
        assertFalse(AppSettings.isValidAlgo("HmacMD5"));
        assertFalse(AppSettings.isValidAlgo(null));
    }

    @Test
    public void theDefaultsAreWhatGoogleAuthenticatorExpects() {
        assertEquals(6, AppSettings.DEFAULT_DIGITS);
        assertEquals(30, AppSettings.DEFAULT_PERIOD);
        assertEquals(TotpEngine.ALGO_SHA1, AppSettings.DEFAULT_ALGO);
        assertFalse(AppSettings.differsFromDefaults(6, 30, TotpEngine.ALGO_SHA1));
    }

    @Test
    public void anyChangeFromTheDefaultsIsReported() {
        assertTrue(AppSettings.differsFromDefaults(8, 30, TotpEngine.ALGO_SHA1));
        assertTrue(AppSettings.differsFromDefaults(6, 60, TotpEngine.ALGO_SHA1));
        assertTrue(AppSettings.differsFromDefaults(6, 30, TotpEngine.ALGO_SHA256));
    }

    @Test
    public void autoLockIsKeptBetweenOneAndThirtyMinutes() {
        assertEquals(60_000L, AppSettings.clampAutoLockMs(0));
        assertEquals(60_000L, AppSettings.clampAutoLockMs(-5));
        assertEquals(5 * 60_000L, AppSettings.clampAutoLockMs(5 * 60_000L));
        assertEquals(30 * 60_000L, AppSettings.clampAutoLockMs(10 * 60 * 60_000L));
    }

    @Test
    public void thePickerAnswerIsAMinuteCountFromOneToThirty() {
        assertEquals(60_000L, AppSettings.autoLockMsFromPicker(0, 1));
        assertEquals(5 * 60_000L, AppSettings.autoLockMsFromPicker(0, 5));
        assertEquals(30 * 60_000L, AppSettings.autoLockMsFromPicker(0, 30));
        // 1:05 is 65 minutes: too long
        assertEquals(-1L, AppSettings.autoLockMsFromPicker(1, 5));
    }

    @Test
    public void thePickerRefusesZeroAndMoreThanThirtyMinutes() {
        assertEquals(-1L, AppSettings.autoLockMsFromPicker(0, 0));
        assertEquals(-1L, AppSettings.autoLockMsFromPicker(0, 31));
        assertEquals(-1L, AppSettings.autoLockMsFromPicker(23, 59));
    }
}
