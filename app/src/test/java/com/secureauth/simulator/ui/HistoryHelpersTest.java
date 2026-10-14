package com.secureauth.simulator.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

public class HistoryHelpersTest {

    @Test
    public void locationIsTwoNumbersWithFourDecimals() {
        assertEquals("19.0760, 72.8777", HistoryFormat.location(19.076, 72.8777, "none"));
        assertEquals("-33.8688, -151.2093", HistoryFormat.location(-33.8688, -151.2093, "none"));
        assertEquals("0.0000, 0.0000", HistoryFormat.location(0.0, 0.0, "none")); // 0,0 is a real place, not "missing"
    }

    @Test
    public void aMissingCoordinateMeansNoLocation() {
        assertEquals("No location", HistoryFormat.location(null, null, "No location"));
        assertEquals("No location", HistoryFormat.location(19.0, null, "No location"));
        assertEquals("No location", HistoryFormat.location(null, 72.0, "No location"));
    }

    @Test
    public void onlyRealFailuresGetTheFailureIcon() {
        assertFalse(HistoryFormat.isFailure("SUCCESS"));
        assertFalse(HistoryFormat.isFailure(null));
        assertFalse(HistoryFormat.isFailure(""));
        assertTrue(HistoryFormat.isFailure("FAIL"));
        assertTrue(HistoryFormat.isFailure("CANCELLED"));
        assertTrue(HistoryFormat.isFailure("ERROR"));
    }

    @Test
    public void aDayRunsFromMidnightToTheLastMillisecond() {
        long start = DateRange.startOfDay(2026, Calendar.OCTOBER, 14);
        long end = DateRange.endOfDay(2026, Calendar.OCTOBER, 14);

        Calendar s = Calendar.getInstance();
        s.setTimeInMillis(start);
        assertEquals(2026, s.get(Calendar.YEAR));
        assertEquals(Calendar.OCTOBER, s.get(Calendar.MONTH));
        assertEquals(14, s.get(Calendar.DAY_OF_MONTH));
        assertEquals(0, s.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, s.get(Calendar.MINUTE));
        assertEquals(0, s.get(Calendar.MILLISECOND));

        Calendar e = Calendar.getInstance();
        e.setTimeInMillis(end);
        assertEquals(14, e.get(Calendar.DAY_OF_MONTH));
        assertEquals(23, e.get(Calendar.HOUR_OF_DAY));
        assertEquals(59, e.get(Calendar.MINUTE));
        assertEquals(59, e.get(Calendar.SECOND));
        assertEquals(999, e.get(Calendar.MILLISECOND));
    }

    @Test
    public void theNextDayStartsRightAfterTheLastMillisecond() {
        long end = DateRange.endOfDay(2026, Calendar.OCTOBER, 14);
        long nextStart = DateRange.startOfDay(2026, Calendar.OCTOBER, 15);
        assertEquals(1, nextStart - end);
    }

    @Test
    public void monthEndsAreHandled() {
        long end = DateRange.endOfDay(2026, Calendar.FEBRUARY, 28);
        long next = DateRange.startOfDay(2026, Calendar.MARCH, 1);
        assertEquals(1, next - end);
    }

    @Test
    public void todayEndsAfterNow() {
        assertTrue(DateRange.endOfToday() >= System.currentTimeMillis());
        assertTrue(DateRange.endOfToday() - System.currentTimeMillis() < 25L * 60 * 60 * 1000);
    }
}
