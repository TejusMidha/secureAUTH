package com.secureauth.simulator.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLog;

import org.junit.Test;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class MapMarkersTest {

    private static final DateFormat FORMAT = utcFormat();

    private static DateFormat utcFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    private static AuditLog row(String event, String result, Double lat, Double lng, long ts) {
        AuditLog a = new AuditLog();
        a.event = event;
        a.result = result;
        a.lat = lat;
        a.lng = lng;
        a.ts = ts;
        return a;
    }

    @Test
    public void entriesWithoutAPlaceGetNoMarker() {
        List<MapMarkers.Spec> specs = MapMarkers.from(Arrays.asList(
                row("REGISTER", "SUCCESS", null, null, 0L),
                row("PASSWORD_OK", "SUCCESS", 19.0, null, 0L),
                row("PASSWORD_OK", "SUCCESS", null, 72.0, 0L),
                row("SESSION_START", "SUCCESS", 19.0, 72.0, 0L)), FORMAT);
        assertEquals(1, specs.size());
        assertEquals("SESSION_START", specs.get(0).title);
    }

    @Test
    public void aMarkerShowsTheEventAndTheDateTime() {
        MapMarkers.Spec spec = MapMarkers.from(Arrays.asList(
                row("PASSWORD_OK", "SUCCESS", 19.076, 72.8777, 1700000000000L)), FORMAT).get(0);
        assertEquals(19.076, spec.lat, 0.0);
        assertEquals(72.8777, spec.lng, 0.0);
        assertEquals("PASSWORD_OK", spec.title);
        assertEquals("2023-11-14 22:13", spec.snippet);
    }

    @Test
    public void successfulEntriesAreGreen() {
        assertFalse(MapMarkers.isWarning(row("SESSION_START", "SUCCESS", 1.0, 1.0, 0L)));
        assertFalse(MapMarkers.isWarning(row("IMPORTED", null, 1.0, 1.0, 0L))); // no result is not a failure
    }

    @Test
    public void failuresAreRed() {
        assertTrue(MapMarkers.isWarning(row("PASSWORD_FAIL", "FAIL", 1.0, 1.0, 0L)));
        assertTrue(MapMarkers.isWarning(row("BIOMETRIC_FAIL", "CANCELLED", 1.0, 1.0, 0L)));
        assertTrue(MapMarkers.isWarning(row("BIOMETRIC_FAIL", "ERROR", 1.0, 1.0, 0L)));
    }

    @Test
    public void suspiciousLoginsAreRedWhateverTheirResultSays() {
        assertTrue(MapMarkers.isWarning(row(AuditEvent.SUSPICIOUS_LOCATION, AuditEvent.RESULT_WARNING, 1.0, 1.0, 0L)));
        assertTrue(MapMarkers.isWarning(row(AuditEvent.SUSPICIOUS_LOCATION, "SUCCESS", 1.0, 1.0, 0L)));
    }

    @Test
    public void markerOrderFollowsTheInputOrder() {
        List<MapMarkers.Spec> specs = MapMarkers.from(Arrays.asList(
                row("A_ONE", "SUCCESS", 1.0, 1.0, 1L),
                row("B_TWO", "FAIL", 2.0, 2.0, 2L),
                row("C_THREE", "SUCCESS", 3.0, 3.0, 3L)), FORMAT);
        assertEquals("A_ONE", specs.get(0).title);
        assertEquals("B_TWO", specs.get(1).title);
        assertTrue(specs.get(1).warning);
        assertEquals("C_THREE", specs.get(2).title);
    }
}
