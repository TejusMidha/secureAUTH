package com.secureauth.simulator.crypto;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based One-Time Password (RFC 6238) built on HOTP (RFC 4226), written by hand.
 * Only the HMAC primitive comes from javax.crypto; everything else is in this file.
 *
 * Idea: both sides know a secret and the clock. Time is cut into steps (30 s by default),
 * the step number is run through HMAC with the secret, and the result is squeezed
 * down to a short decimal code.
 */
public final class TotpEngine {

    public static final String ALGO_SHA1 = "HmacSHA1";
    public static final String ALGO_SHA256 = "HmacSHA256";

    /** 20 random bytes = 160 bits, the size RFC 4226 recommends for HMAC-SHA1. */
    private static final int SECRET_BYTES = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpEngine() {
    }

    /** New random secret from the system's cryptographically secure generator. */
    public static byte[] generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /**
     * Returns the code for the time step containing unixTimeMillis, e.g. "287082".
     * The result is left-padded with zeros to exactly {@code digits} characters.
     */
    public static String generate(byte[] secret, long unixTimeMillis, int digits,
                                  int periodSeconds, String algo) {
        checkParameters(secret, digits, periodSeconds, algo);

        // Step 1: which time step are we in? (whole seconds since 1970, divided by the period)
        long counter = Math.floorDiv(unixTimeMillis, 1000L * periodSeconds);

        // Step 2: write the counter as 8 bytes, most significant byte first (big-endian)
        byte[] message = new byte[8];
        for (int i = 7; i >= 0; i--) {
            message[i] = (byte) (counter & 0xFF);
            counter >>>= 8;
        }

        // Step 3: HMAC the counter with the secret (20 bytes for SHA-1, 32 for SHA-256)
        byte[] hash = hmac(secret, message, algo);

        // Step 4 (RFC 4226 section 5.3, dynamic truncation): the low 4 bits of the
        // LAST byte choose where to read from, so the 4 bytes taken vary from code to code
        int offset = hash[hash.length - 1] & 0x0F;

        // Step 5: read 4 bytes starting at offset and join them into one 32-bit number
        int fourBytes = ((hash[offset] & 0xFF) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        // Step 6: clear the top bit so the number is never negative in Java's signed int
        int binary = fourBytes & 0x7FFFFFFF;

        // Step 7: keep only the last `digits` decimal digits
        int modulus = 1;
        for (int i = 0; i < digits; i++) {
            modulus *= 10;
        }
        int otp = binary % modulus;

        // Step 8: add leading zeros back, because "007081" must stay 6 characters long
        StringBuilder code = new StringBuilder(Integer.toString(otp));
        while (code.length() < digits) {
            code.insert(0, '0');
        }
        return code.toString();
    }

    /**
     * True if {@code code} is correct for the current step or one step before or after.
     * The +-1 window forgives small clock differences and typing delay.
     */
    public static boolean verify(byte[] secret, String code, long nowMillis, int digits,
                                 int periodSeconds, String algo) {
        checkParameters(secret, digits, periodSeconds, algo);
        if (code == null || code.length() != digits) {
            return false;
        }
        byte[] given = code.getBytes(StandardCharsets.UTF_8);

        boolean match = false;
        for (int step = -1; step <= 1; step++) {
            long time = nowMillis + (long) step * periodSeconds * 1000L;
            if (time < 0) {
                continue; // before 1970: no such time step
            }
            byte[] expected = generate(secret, time, digits, periodSeconds, algo)
                    .getBytes(StandardCharsets.UTF_8);
            // Constant-time compare, and no early exit, so timing does not reveal
            // how many digits were right or which window matched
            match |= MessageDigest.isEqual(expected, given);
        }
        return match;
    }

    /** "123456" becomes "123 456" (and "12345678" becomes "1234 5678"): halves are easier to read. */
    public static String formatForDisplay(String code) {
        int middle = code.length() / 2;
        return code.substring(0, middle) + " " + code.substring(middle);
    }

    /** Seconds until the current code changes: between 1 and periodSeconds. */
    public static int secondsRemaining(long nowMillis, int periodSeconds) {
        long secondsIntoStep = Math.floorMod(Math.floorDiv(nowMillis, 1000L), (long) periodSeconds);
        return (int) (periodSeconds - secondsIntoStep);
    }

    /**
     * Builds the otpauth:// link that authenticator apps read from a QR code:
     * otpauth://totp/Issuer:account?secret=...&issuer=Issuer&algorithm=SHA1&digits=6&period=30
     */
    public static String buildOtpAuthUri(String issuer, String accountName, String base32Secret,
                                         int digits, int periodSeconds, String algo) {
        // The label and the issuer parameter must be URL-encoded: names may contain
        // spaces, '@', '&' or ':' which would otherwise break the link
        String encodedIssuer = urlEncode(issuer);
        String label = encodedIssuer + ":" + urlEncode(accountName);

        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + encodedIssuer
                + "&algorithm=" + algoName(algo)
                + "&digits=" + digits
                + "&period=" + periodSeconds;
    }

    /** "HmacSHA256" -> "SHA256", the spelling used inside otpauth links. */
    private static String algoName(String algo) {
        if (ALGO_SHA1.equals(algo)) {
            return "SHA1";
        }
        if (ALGO_SHA256.equals(algo)) {
            return "SHA256";
        }
        throw new IllegalArgumentException("Unsupported algorithm: " + algo);
    }

    private static byte[] hmac(byte[] secret, byte[] message, String algo) {
        try {
            Mac mac = Mac.getInstance(algo);
            mac.init(new SecretKeySpec(secret, algo));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            // Cannot happen for the two algorithms we allow; fail loudly rather than return a wrong code
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private static void checkParameters(byte[] secret, int digits, int periodSeconds, String algo) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("Secret must not be empty");
        }
        // 9+ digits would overflow the int arithmetic and RFC 6238 only defines 6-8
        if (digits < 6 || digits > 8) {
            throw new IllegalArgumentException("Digits must be 6, 7 or 8");
        }
        if (periodSeconds <= 0) {
            throw new IllegalArgumentException("Period must be positive");
        }
        algoName(algo); // throws if the algorithm is not one of the two supported
    }

    /** URL-encodes text so spaces become %20 (URLEncoder alone would produce '+'). */
    private static String urlEncode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is always supported", e);
        }
    }
}
