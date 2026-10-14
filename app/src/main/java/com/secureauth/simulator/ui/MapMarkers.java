package com.secureauth.simulator.ui;

import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLog;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Decides what goes on the login map: one marker per entry that has a place, red when it was a
 * failure or a suspicious login, green otherwise. Free of map classes so it can be unit tested.
 */
final class MapMarkers {

    /** One marker to draw. */
    static final class Spec {
        final double lat;
        final double lng;
        final String title;     // the event, shown in the info window
        final String snippet;   // the date and time, shown under the title
        final boolean warning;  // true = red marker

        Spec(double lat, double lng, String title, String snippet, boolean warning) {
            this.lat = lat;
            this.lng = lng;
            this.title = title;
            this.snippet = snippet;
            this.warning = warning;
        }
    }

    private MapMarkers() {
    }

    /** Entries without a place are left out (there is nowhere to put them). */
    static List<Spec> from(List<AuditLog> rows, DateFormat timeFormat) {
        List<Spec> markers = new ArrayList<>();
        for (AuditLog row : rows) {
            if (row.lat == null || row.lng == null) {
                continue;
            }
            markers.add(new Spec(row.lat, row.lng, row.event,
                    timeFormat.format(new Date(row.ts)), isWarning(row)));
        }
        return markers;
    }

    /** Failures (FAIL, ERROR, CANCELLED...) and the suspicious-location flag are drawn red. */
    static boolean isWarning(AuditLog row) {
        return HistoryFormat.isFailure(row.result)
                || AuditEvent.SUSPICIOUS_LOCATION.equals(row.event);
    }
}
