package com.secureauth.simulator.ui;

import androidx.lifecycle.ViewModel;

import com.secureauth.simulator.crypto.Base32;
import com.secureauth.simulator.crypto.TotpEngine;

import java.util.Arrays;

/**
 * Holds the new secret while the enroll screen is open. A ViewModel survives screen rotation,
 * so the QR code does not change under the user's feet, and it is thrown away when the
 * screen is left. The secret is NOT written to the database until the user proves it works.
 */
public class EnrollViewModel extends ViewModel {

    private final byte[] secret = TotpEngine.generateSecret();
    private final String base32Secret = Base32.encode(secret);

    /** True once the user has tapped "I've scanned it" (so rotation keeps the code box open). */
    boolean confirming;

    String getBase32Secret() {
        return base32Secret;
    }

    /** A copy, so callers can wipe theirs without damaging ours. */
    byte[] copySecret() {
        return secret.clone();
    }

    @Override
    protected void onCleared() {
        Arrays.fill(secret, (byte) 0); // do not leave the secret in memory after the screen is gone
    }
}
