package com.secureauth.simulator.ui;

import android.os.Build;

import androidx.biometric.BiometricManager;

/**
 * Decides which authenticators to ask for and what the result of
 * {@code BiometricManager.canAuthenticate} means for the screen. Kept separate so it can be
 * unit tested without a phone.
 */
final class BiometricSupport {

    enum Availability {
        /** A fingerprint/face or the screen lock is ready to use. */
        READY,
        /** The phone can do it, but the user has not set up a fingerprint or screen lock. */
        NOT_ENROLLED,
        /** No hardware, hardware busy, or a security update is needed. */
        UNAVAILABLE
    }

    private BiometricSupport() {
    }

    /**
     * Strong biometrics with the screen lock (PIN/pattern) as fallback. On Android 9 and 10
     * (API 28-29) that exact combination is not supported by the system, so the slightly
     * more permissive BIOMETRIC_WEAK is used there.
     */
    static int authenticators(int sdkInt) {
        int biometric = (sdkInt == Build.VERSION_CODES.P || sdkInt == Build.VERSION_CODES.Q)
                ? BiometricManager.Authenticators.BIOMETRIC_WEAK
                : BiometricManager.Authenticators.BIOMETRIC_STRONG;
        return biometric | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    }

    static Availability fromCanAuthenticate(int result) {
        switch (result) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                return Availability.READY;
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return Availability.NOT_ENROLLED;
            default:
                return Availability.UNAVAILABLE;
        }
    }
}
