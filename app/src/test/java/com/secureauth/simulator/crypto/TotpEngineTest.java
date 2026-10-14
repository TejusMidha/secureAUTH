package com.secureauth.simulator.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class TotpEngineTest {

    // RFC 6238 Appendix B uses these ASCII strings as the shared secrets
    private static final byte[] SHA1_SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHA256_SECRET =
            "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);

    // Times in seconds from the RFC table, with the expected 8-digit codes
    private static final long[] TIMES = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
    private static final String[] SHA1_CODES = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
    private static final String[] SHA256_CODES = {"46119246", "68084774", "67062674", "91819424", "90698825", "77737706"};

    @Test
    public void rfc6238VectorsForSha1() {
        for (int i = 0; i < TIMES.length; i++) {
            String code = TotpEngine.generate(SHA1_SECRET, TIMES[i] * 1000L, 8, 30, TotpEngine.ALGO_SHA1);
            assertEquals("SHA1 at t=" + TIMES[i], SHA1_CODES[i], code);
        }
    }

    @Test
    public void rfc6238VectorsForSha256() {
        for (int i = 0; i < TIMES.length; i++) {
            String code = TotpEngine.generate(SHA256_SECRET, TIMES[i] * 1000L, 8, 30, TotpEngine.ALGO_SHA256);
            assertEquals("SHA256 at t=" + TIMES[i], SHA256_CODES[i], code);
        }
    }

    @Test
    public void sixDigitCodeIsTheLastSixOfTheEightDigitCode() {
        // RFC 4226 counter 1 gives 287082 (the same number as 94287082 without its first two digits)
        assertEquals("287082", TotpEngine.generate(SHA1_SECRET, 59_000L, 6, 30, TotpEngine.ALGO_SHA1));
    }

    @Test
    public void leadingZerosAreKept() {
        String code = TotpEngine.generate(SHA1_SECRET, 1111111109_000L, 8, 30, TotpEngine.ALGO_SHA1);
        assertEquals("07081804", code);
        assertEquals(8, code.length());
    }

    @Test
    public void codeStaysTheSameInsideOneStepAndChangesAtTheNext() {
        long stepStart = 1_000_000_020_000L; // multiple of 30 000 ms
        String first = TotpEngine.generate(SHA1_SECRET, stepStart, 6, 30, TotpEngine.ALGO_SHA1);
        String last = TotpEngine.generate(SHA1_SECRET, stepStart + 29_999L, 6, 30, TotpEngine.ALGO_SHA1);
        String next = TotpEngine.generate(SHA1_SECRET, stepStart + 30_000L, 6, 30, TotpEngine.ALGO_SHA1);
        assertEquals(first, last);
        assertNotEquals(first, next);
    }

    @Test
    public void verifyAcceptsCurrentPreviousAndNextWindow() {
        long now = 1_700_000_010_000L;
        long step = 30_000L;
        for (long offset : new long[]{-step, 0, step}) {
            String code = TotpEngine.generate(SHA1_SECRET, now + offset, 6, 30, TotpEngine.ALGO_SHA1);
            assertTrue("offset " + offset,
                    TotpEngine.verify(SHA1_SECRET, code, now, 6, 30, TotpEngine.ALGO_SHA1));
        }
    }

    @Test
    public void verifyRejectsTwoWindowsAway() {
        long now = 1_700_000_010_000L;
        long step = 30_000L;
        for (long offset : new long[]{-2 * step, 2 * step}) {
            String code = TotpEngine.generate(SHA1_SECRET, now + offset, 6, 30, TotpEngine.ALGO_SHA1);
            assertFalse("offset " + offset,
                    TotpEngine.verify(SHA1_SECRET, code, now, 6, 30, TotpEngine.ALGO_SHA1));
        }
    }

    @Test
    public void verifyRejectsWrongMalformedAndNullCodes() {
        long now = 1_700_000_010_000L;
        String good = TotpEngine.generate(SHA1_SECRET, now, 6, 30, TotpEngine.ALGO_SHA1);
        // Same code with the first digit changed (time is fixed, so this is deterministic)
        String wrong = String.valueOf((good.charAt(0) - '0' + 5) % 10) + good.substring(1);

        assertFalse(TotpEngine.verify(SHA1_SECRET, wrong, now, 6, 30, TotpEngine.ALGO_SHA1));
        assertFalse(TotpEngine.verify(SHA1_SECRET, good.substring(1), now, 6, 30, TotpEngine.ALGO_SHA1));
        assertFalse(TotpEngine.verify(SHA1_SECRET, good + "0", now, 6, 30, TotpEngine.ALGO_SHA1));
        assertFalse(TotpEngine.verify(SHA1_SECRET, "", now, 6, 30, TotpEngine.ALGO_SHA1));
        assertFalse(TotpEngine.verify(SHA1_SECRET, null, now, 6, 30, TotpEngine.ALGO_SHA1));
    }

    @Test
    public void verifyRejectsCodeFromADifferentSecret() {
        long now = 1_700_000_010_000L;
        String other = TotpEngine.generate(SHA256_SECRET, now, 6, 30, TotpEngine.ALGO_SHA1);
        assertFalse(TotpEngine.verify(SHA1_SECRET, other, now, 6, 30, TotpEngine.ALGO_SHA1));
    }

    @Test
    public void verifyWorksWithSha256AndSixtySecondPeriod() {
        long now = 1_700_000_010_000L;
        String code = TotpEngine.generate(SHA256_SECRET, now, 8, 60, TotpEngine.ALGO_SHA256);
        assertTrue(TotpEngine.verify(SHA256_SECRET, code, now + 60_000L, 8, 60, TotpEngine.ALGO_SHA256));
        assertFalse(TotpEngine.verify(SHA256_SECRET, code, now + 120_000L, 8, 60, TotpEngine.ALGO_SHA256));
    }

    @Test
    public void secondsRemainingCountsDownFromPeriodToOne() {
        assertEquals(30, TotpEngine.secondsRemaining(0L, 30));          // exactly on a boundary
        assertEquals(29, TotpEngine.secondsRemaining(1_000L, 30));
        assertEquals(1, TotpEngine.secondsRemaining(29_999L, 30));      // last millisecond of the step
        assertEquals(30, TotpEngine.secondsRemaining(30_000L, 30));     // next step begins
        assertEquals(45, TotpEngine.secondsRemaining(15_000L, 60));
    }

    @Test
    public void codesAreShownInTwoHalves() {
        assertEquals("287 082", TotpEngine.formatForDisplay("287082"));
        assertEquals("1234 5678", TotpEngine.formatForDisplay("12345678"));
        assertEquals("007 081", TotpEngine.formatForDisplay("007081")); // leading zeros survive
    }

    @Test
    public void generateSecretIsTwentyRandomBytes() {
        byte[] a = TotpEngine.generateSecret();
        byte[] b = TotpEngine.generateSecret();
        assertEquals(20, a.length);
        assertEquals(20, b.length);
        assertFalse(Arrays.equals(a, b));
    }

    @Test
    public void otpAuthUriHasAllParametersAndIsEncoded() {
        String uri = TotpEngine.buildOtpAuthUri("Secure Auth", "alice@example.com",
                "JBSWY3DPEHPK3PXP", 6, 30, TotpEngine.ALGO_SHA1);
        assertEquals("otpauth://totp/Secure%20Auth:alice%40example.com"
                + "?secret=JBSWY3DPEHPK3PXP&issuer=Secure%20Auth&algorithm=SHA1&digits=6&period=30", uri);
    }

    @Test
    public void otpAuthUriEncodesSpecialCharactersAndAlgorithm() {
        String uri = TotpEngine.buildOtpAuthUri("A&B:C", "x y", "MFRGG", 8, 60, TotpEngine.ALGO_SHA256);
        assertEquals("otpauth://totp/A%26B%3AC:x%20y"
                + "?secret=MFRGG&issuer=A%26B%3AC&algorithm=SHA256&digits=8&period=60", uri);
    }

    @Test
    public void secretRoundTripsThroughBase32InsideTheUri() {
        byte[] secret = TotpEngine.generateSecret();
        String encoded = Base32.encode(secret);
        String uri = TotpEngine.buildOtpAuthUri("SecureAuth", "bob", encoded, 6, 30, TotpEngine.ALGO_SHA1);
        assertTrue(uri.contains("secret=" + encoded));
        assertTrue(Arrays.equals(secret, Base32.decode(encoded)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedAlgorithmIsRejected() {
        TotpEngine.generate(SHA1_SECRET, 0L, 6, 30, "HmacMD5");
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidDigitsAreRejected() {
        TotpEngine.generate(SHA1_SECRET, 0L, 9, 30, TotpEngine.ALGO_SHA1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptySecretIsRejected() {
        TotpEngine.generate(new byte[0], 0L, 6, 30, TotpEngine.ALGO_SHA1);
    }
}
