package com.secureauth.simulator.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The settings side of LocationHelper (the real GPS lookup is tried by hand on the emulator). */
@RunWith(AndroidJUnit4.class)
public class LocationHelperDeviceTest {

    private Context context;
    private LocationHelper helper;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new LocationHelper(context);
        clearPrefs();
    }

    @After
    public void tearDown() {
        clearPrefs();
    }

    private void clearPrefs() {
        context.getSharedPreferences("secureauth_prefs", Context.MODE_PRIVATE).edit()
                .remove("record_location").remove("location_asked").commit();
    }

    @Test
    public void recordingIsOnByDefaultAndFollowsTheSetting() {
        assertTrue(helper.isRecordingEnabled());

        context.getSharedPreferences("secureauth_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("record_location", false).commit();
        assertFalse(helper.isRecordingEnabled());
    }

    @Test
    public void theExplanationIsRememberedAfterTheFirstTime() {
        assertFalse(helper.wasAsked());
        helper.markAsked();
        assertTrue(helper.wasAsked());
        assertTrue(new LocationHelper(context).wasAsked()); // survives a new helper object
    }

    @Test
    public void permissionStateMatchesAndroidsAnswer() {
        boolean fine = ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        assertEquals(fine || coarse, helper.hasPermission());
    }

    @Test
    public void withTheSettingOffNoLocationIsReturnedAtOnce() {
        context.getSharedPreferences("secureauth_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("record_location", false).commit();

        final Location[] answer = {new Location("sentinel")};
        final int[] calls = {0};
        helper.requestCurrentLocation(location -> {
            answer[0] = location;
            calls[0]++;
        });

        // answered on the calling thread, before requestCurrentLocation even returned
        assertEquals(1, calls[0]);
        assertNull(answer[0]);
    }
}
