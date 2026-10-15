package com.secureauth.simulator.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.secureauth.simulator.data.AppSettings;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds out where a login happens, but only if the user allows it. Location is optional
 * everywhere: with no permission, with the setting off, or if no position can be found in time,
 * the answer is simply "no location" and the login carries on.
 *
 * Methods are not final so tests can replace this class with one that returns a fixed place.
 */
public class LocationHelper {

    /** Listener result. {@code null} means "no location available". */
    public interface Listener {
        void onLocation(@Nullable Location location);
    }

    public static final String[] PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };

    /** A login further than this from the previous one is flagged as suspicious. */
    public static final double SUSPICIOUS_DISTANCE_KM = 100.0;

    /** Give up after this long so a slow GPS never holds up a login. */
    private static final long TIMEOUT_MS = 8_000;

    // The "Record login location" setting itself lives in AppSettings; this file only remembers
    // that the explanation dialog has been shown
    private static final String PREF_ASKED = "location_asked";

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final Context context;

    public LocationHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The "Record login location" setting. On by default. */
    public boolean isRecordingEnabled() {
        return AppSettings.isRecordLocation(context);
    }

    /** True if the user has allowed approximate or precise location. */
    public boolean hasPermission() {
        for (String permission : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    /** True once the explanation dialog has been shown, so we never nag on later logins. */
    public boolean wasAsked() {
        return prefs().getBoolean(PREF_ASKED, false);
    }

    public void markAsked() {
        prefs().edit().putBoolean(PREF_ASKED, true).apply();
    }

    /**
     * Looks up the current position once. Calls the listener exactly once, on the main thread:
     * at once with null if location is off or not allowed, otherwise when the position (or a
     * timeout) arrives.
     */
    @SuppressLint("MissingPermission") // hasPermission() is checked right below
    public void requestCurrentLocation(Listener listener) {
        if (!isRecordingEnabled() || !hasPermission()) {
            listener.onLocation(null);
            return;
        }

        final AtomicBoolean delivered = new AtomicBoolean(false);
        final Handler main = new Handler(Looper.getMainLooper());
        final CancellationTokenSource cancellation = new CancellationTokenSource();

        // Whichever happens first (position, failure, timeout) delivers; the rest are ignored
        final Runnable[] timeout = new Runnable[1];
        final java.util.function.Consumer<Location> deliver = location -> {
            if (delivered.compareAndSet(false, true)) {
                main.removeCallbacks(timeout[0]);
                listener.onLocation(location);
            }
        };
        timeout[0] = () -> {
            cancellation.cancel();
            deliver.accept(null);
        };
        main.postDelayed(timeout[0], TIMEOUT_MS);

        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(context);
        // HIGH_ACCURACY lets the system use GPS when there is no wifi/cell position (the cheaper
        // BALANCED level returns nothing then). It is one lookup per login, not continuous tracking.
        // With only "approximate" permission the system still hands back a coarse position.
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.getToken())
                .addOnSuccessListener(deliver::accept)
                .addOnFailureListener(e -> deliver.accept(null));
    }

    /**
     * Distance in kilometres between two points on Earth (haversine formula). It treats the
     * Earth as a sphere, which is accurate to well under 1% and plenty for "more than 100 km".
     */
    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** True when the distance is strictly more than {@link #SUSPICIOUS_DISTANCE_KM}. */
    public static boolean isSuspicious(double distanceKm) {
        return distanceKm > SUSPICIOUS_DISTANCE_KM;
    }
}
