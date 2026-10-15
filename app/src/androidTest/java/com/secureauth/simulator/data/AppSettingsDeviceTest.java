package com.secureauth.simulator.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.location.LocationHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

/** Saving and reading the settings through the real SharedPreferences file. */
@RunWith(AndroidJUnit4.class)
public class AppSettingsDeviceTest {

    private Context context;
    private Map<String, Object> saved;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        saved = new HashMap<>(prefs().getAll());
        // Start every test from "nothing chosen yet"
        prefs().edit().remove(AppSettings.KEY_DIGITS).remove(AppSettings.KEY_PERIOD)
                .remove(AppSettings.KEY_ALGO).remove(AppSettings.KEY_AUTO_LOCK_MS)
                .remove(AppSettings.KEY_RECORD_LOCATION).commit();
    }

    @After
    public void tearDown() {
        android.content.SharedPreferences.Editor editor = prefs().edit().clear();
        for (Map.Entry<String, Object> e : saved.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Integer) {
                editor.putInt(e.getKey(), (Integer) v);
            } else if (v instanceof Long) {
                editor.putLong(e.getKey(), (Long) v);
            } else if (v instanceof Boolean) {
                editor.putBoolean(e.getKey(), (Boolean) v);
            } else if (v instanceof String) {
                editor.putString(e.getKey(), (String) v);
            }
        }
        editor.commit();
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Test
    public void withNothingSavedEverySettingHasItsDefault() {
        assertEquals(6, AppSettings.getDigits(context));
        assertEquals(30, AppSettings.getPeriod(context));
        assertEquals(TotpEngine.ALGO_SHA1, AppSettings.getAlgo(context));
        assertEquals(5 * 60_000L, AppSettings.getAutoLockMs(context));
        assertTrue(AppSettings.isRecordLocation(context));
    }

    @Test
    public void savedChoicesAreReadBack() {
        AppSettings.setDigits(context, 8);
        AppSettings.setPeriod(context, 60);
        AppSettings.setAlgo(context, TotpEngine.ALGO_SHA256);
        AppSettings.setAutoLockMs(context, 12 * 60_000L);
        AppSettings.setRecordLocation(context, false);

        assertEquals(8, AppSettings.getDigits(context));
        assertEquals(60, AppSettings.getPeriod(context));
        assertEquals(TotpEngine.ALGO_SHA256, AppSettings.getAlgo(context));
        assertEquals(12 * 60_000L, AppSettings.getAutoLockMs(context));
        assertFalse(AppSettings.isRecordLocation(context));
    }

    @Test
    public void aChoiceThatIsNotOfferedIsNotSaved() {
        AppSettings.setDigits(context, 7);
        AppSettings.setPeriod(context, 45);
        AppSettings.setAlgo(context, "HmacMD5");

        assertEquals(6, AppSettings.getDigits(context));
        assertEquals(30, AppSettings.getPeriod(context));
        assertEquals(TotpEngine.ALGO_SHA1, AppSettings.getAlgo(context));
        assertFalse(prefs().contains(AppSettings.KEY_DIGITS));
    }

    @Test
    public void damagedValuesInTheFileFallBackToTheDefaults() {
        prefs().edit().putInt(AppSettings.KEY_DIGITS, 99).putInt(AppSettings.KEY_PERIOD, 0)
                .putString(AppSettings.KEY_ALGO, "nonsense").commit();

        assertEquals(6, AppSettings.getDigits(context));
        assertEquals(30, AppSettings.getPeriod(context));
        assertEquals(TotpEngine.ALGO_SHA1, AppSettings.getAlgo(context));
    }

    @Test
    public void anAutoLockOutsideTheRangeIsPulledIntoIt() {
        AppSettings.setAutoLockMs(context, 10 * 60 * 60_000L);
        assertEquals(30 * 60_000L, AppSettings.getAutoLockMs(context));

        prefs().edit().putLong(AppSettings.KEY_AUTO_LOCK_MS, 5L).commit(); // damaged by hand
        assertEquals(60_000L, AppSettings.getAutoLockMs(context));
    }

    @Test
    public void theSessionTimerAndTheLocationHelperFollowTheSettings() {
        AppSettings.setAutoLockMs(context, 7 * 60_000L);
        AppSettings.setRecordLocation(context, false);

        assertEquals(7 * 60_000L, SessionManager.getAutoLockMs(context));
        assertFalse(new LocationHelper(context).isRecordingEnabled());

        AppSettings.setRecordLocation(context, true);
        assertTrue(new LocationHelper(context).isRecordingEnabled());
    }
}
