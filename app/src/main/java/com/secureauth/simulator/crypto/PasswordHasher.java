package com.secureauth.simulator.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Salted password hashing with PBKDF2 (HMAC-SHA256).
 *
 * Why not plain SHA-256? It is built to be fast, so an attacker who steals the database
 * can try billions of guesses per second. PBKDF2 repeats the HMAC 120,000 times, which makes
 * every guess slow. The random salt makes two users with the same password get different hashes
 * and defeats pre-computed tables.
 *
 * The hash() and verify() methods overwrite the char[] you pass in, so the plain
 * password does not stay in memory. Copy it first if you still need it.
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /** A hash and the salt that produced it, both Base64 text ready for the database. */
    public static final class Result {
        public final String hash;
        public final String salt;

        public Result(String hash, String salt) {
            this.hash = hash;
            this.salt = salt;
        }
    }

    /** Hashes the password with a new random salt, then wipes the char[]. */
    public static Result hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        try {
            byte[] hash = derive(password, salt);
            return new Result(toBase64(hash), toBase64(salt));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * True if the password produces the stored hash with the stored salt.
     * Wipes the char[]. Bad Base64 in the stored values simply gives false.
     */
    public static boolean verify(char[] password, String storedHash, String storedSalt) {
        try {
            byte[] salt = Base64.getDecoder().decode(storedSalt);
            byte[] expected = Base64.getDecoder().decode(storedHash);
            byte[] actual = derive(password, salt);
            // Constant-time comparison: takes the same time wherever the first difference is
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Runs PBKDF2: the password and salt go in, a 256-bit key comes out. */
    private static byte[] derive(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            // PBKDF2WithHmacSHA256 exists on every Android version from API 26 up
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword(); // PBEKeySpec keeps its own copy of the password
        }
    }

    private static String toBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
