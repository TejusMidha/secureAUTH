package com.secureauth.simulator.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Base64;

public class PasswordHasherTest {

    @Test
    public void correctPasswordVerifies() {
        PasswordHasher.Result r = PasswordHasher.hash("S3cret-pass".toCharArray());
        assertTrue(PasswordHasher.verify("S3cret-pass".toCharArray(), r.hash, r.salt));
    }

    @Test
    public void wrongPasswordIsRejected() {
        PasswordHasher.Result r = PasswordHasher.hash("S3cret-pass".toCharArray());
        assertFalse(PasswordHasher.verify("s3cret-pass".toCharArray(), r.hash, r.salt));
        assertFalse(PasswordHasher.verify("".toCharArray(), r.hash, r.salt));
    }

    @Test
    public void samePasswordGetsDifferentSaltAndHash() {
        PasswordHasher.Result a = PasswordHasher.hash("same".toCharArray());
        PasswordHasher.Result b = PasswordHasher.hash("same".toCharArray());
        assertNotEquals(a.salt, b.salt);
        assertNotEquals(a.hash, b.hash);
    }

    @Test
    public void saltIs16BytesAndHashIs32Bytes() {
        PasswordHasher.Result r = PasswordHasher.hash("x".toCharArray());
        assertEquals(16, Base64.getDecoder().decode(r.salt).length);
        assertEquals(32, Base64.getDecoder().decode(r.hash).length);
    }

    // Reference value computed independently with Python:
    // hashlib.pbkdf2_hmac('sha256', b'correct horse battery staple', bytes(range(16)), 120000, 32)
    @Test
    public void matchesIndependentPbkdf2Result() {
        String salt = "AAECAwQFBgcICQoLDA0ODw==";
        String hash = "6WLr2CZ7yDk4bUYIu8PIrDa/shX6qFROOk4svM7ISAY=";
        assertTrue(PasswordHasher.verify("correct horse battery staple".toCharArray(), hash, salt));
    }

    @Test
    public void passwordArrayIsWipedAfterUse() {
        char[] password = "wipe-me".toCharArray();
        PasswordHasher.Result r = PasswordHasher.hash(password);
        assertAllZero(password);

        char[] again = "wipe-me".toCharArray();
        assertTrue(PasswordHasher.verify(again, r.hash, r.salt));
        assertAllZero(again);
    }

    @Test
    public void brokenStoredValuesGiveFalseNotACrash() {
        assertFalse(PasswordHasher.verify("x".toCharArray(), "not base64!!", "also bad"));
        assertFalse(PasswordHasher.verify("x".toCharArray(), null, null));
    }

    private static void assertAllZero(char[] chars) {
        for (char c : chars) {
            assertEquals('\0', c);
        }
    }
}
