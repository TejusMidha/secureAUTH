package com.secureauth.simulator.crypto;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Re-runs two RFC 6238 vectors on the device, because Android uses its own
 * javax.crypto provider (the unit tests run on the desktop JVM's provider).
 */
public class TotpEngineDeviceTest {

    @Test
    public void rfcVectorsMatchOnAndroidCryptoProvider() {
        byte[] sha1Key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        byte[] sha256Key = "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);

        assertEquals("94287082", TotpEngine.generate(sha1Key, 59_000L, 8, 30, TotpEngine.ALGO_SHA1));
        assertEquals("65353130", TotpEngine.generate(sha1Key, 20000000000L * 1000L, 8, 30, TotpEngine.ALGO_SHA1));
        assertEquals("46119246", TotpEngine.generate(sha256Key, 59_000L, 8, 30, TotpEngine.ALGO_SHA256));
        assertEquals("77737706", TotpEngine.generate(sha256Key, 20000000000L * 1000L, 8, 30, TotpEngine.ALGO_SHA256));
    }
}
