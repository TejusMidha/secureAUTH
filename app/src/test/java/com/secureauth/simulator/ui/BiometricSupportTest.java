package com.secureauth.simulator.ui;

import static org.junit.Assert.assertEquals;

import androidx.biometric.BiometricManager;

import org.junit.Test;

public class BiometricSupportTest {

    private static final int STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG;
    private static final int WEAK = BiometricManager.Authenticators.BIOMETRIC_WEAK;
    private static final int CREDENTIAL = BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    @Test
    public void asksForStrongBiometricWithScreenLockFallback() {
        assertEquals(STRONG | CREDENTIAL, BiometricSupport.authenticators(26));
        assertEquals(STRONG | CREDENTIAL, BiometricSupport.authenticators(30));
        assertEquals(STRONG | CREDENTIAL, BiometricSupport.authenticators(34));
    }

    @Test
    public void onAndroid9And10TheUnsupportedStrongCombinationIsAvoided() {
        assertEquals(WEAK | CREDENTIAL, BiometricSupport.authenticators(28));
        assertEquals(WEAK | CREDENTIAL, BiometricSupport.authenticators(29));
    }

    @Test
    public void successMeansReady() {
        assertEquals(BiometricSupport.Availability.READY,
                BiometricSupport.fromCanAuthenticate(BiometricManager.BIOMETRIC_SUCCESS));
    }

    @Test
    public void noEnrolledFingerprintOrLockMeansNotEnrolled() {
        assertEquals(BiometricSupport.Availability.NOT_ENROLLED,
                BiometricSupport.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED));
    }

    @Test
    public void everyOtherResultMeansUnavailable() {
        int[] others = {
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
                BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
                BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
        };
        for (int code : others) {
            assertEquals("code " + code, BiometricSupport.Availability.UNAVAILABLE,
                    BiometricSupport.fromCanAuthenticate(code));
        }
    }
}
