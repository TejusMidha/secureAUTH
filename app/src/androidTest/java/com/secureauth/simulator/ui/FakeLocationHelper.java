package com.secureauth.simulator.ui;

import android.content.Context;
import android.location.Location;

import com.secureauth.simulator.location.LocationHelper;

/**
 * A LocationHelper that answers from a script instead of the phone's GPS, so tests can choose
 * "the user is in Mumbai" or "the user said no" without any real permission or provider.
 */
class FakeLocationHelper extends LocationHelper {

    private final boolean permission;
    private final boolean recording;
    private final Double lat;
    private final Double lng;

    boolean asked;
    boolean markAskedCalled;
    int lookups;

    FakeLocationHelper(Context context, boolean permission, boolean recording, boolean asked,
                       Double lat, Double lng) {
        super(context);
        this.permission = permission;
        this.recording = recording;
        this.asked = asked;
        this.lat = lat;
        this.lng = lng;
    }

    /** Permission granted and the phone is at (lat, lng). */
    static FakeLocationHelper at(Context context, double lat, double lng) {
        return new FakeLocationHelper(context, true, true, true, lat, lng);
    }

    /** No location at all (permission not given and the user was already asked). */
    static FakeLocationHelper nowhere(Context context) {
        return new FakeLocationHelper(context, false, true, true, null, null);
    }

    @Override
    public boolean isRecordingEnabled() {
        return recording;
    }

    @Override
    public boolean hasPermission() {
        return permission;
    }

    @Override
    public boolean wasAsked() {
        return asked;
    }

    @Override
    public void markAsked() {
        asked = true;
        markAskedCalled = true;
    }

    @Override
    public void requestCurrentLocation(Listener listener) {
        lookups++;
        if (!recording || !permission || lat == null || lng == null) {
            listener.onLocation(null);
            return;
        }
        Location place = new Location("fake");
        place.setLatitude(lat);
        place.setLongitude(lng);
        listener.onLocation(place);
    }
}
