package com.secureauth.simulator.ui;

import java.util.Calendar;

/** Turns the days picked in the date dialogs into the exact millisecond range to search. */
final class DateRange {

    private DateRange() {
    }

    /** 00:00:00.000 at the start of the given day (month is 0-11, as DatePickerDialog gives it). */
    static long startOfDay(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, 0, 0, 0);
        return c.getTimeInMillis();
    }

    /** 23:59:59.999 on the given day, so the whole day is included in a "between" search. */
    static long endOfDay(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, 23, 59, 59);
        c.set(Calendar.MILLISECOND, 999);
        return c.getTimeInMillis();
    }

    /** The last millisecond of today, the latest date the pickers should allow. */
    static long endOfToday() {
        Calendar now = Calendar.getInstance();
        return endOfDay(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
    }
}
