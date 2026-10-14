package com.secureauth.simulator.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/** The pure token functions of SessionManager (no database or phone needed). */
public class SessionTokenTest {

    @Test
    public void tokenIs32RandomBytesAsUrlSafeText() {
        String token = SessionManager.generateToken();
        assertEquals(43, token.length()); // 32 bytes -> 43 Base64 characters without padding
        assertTrue(token, token.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(32, Base64.getUrlDecoder().decode(token).length);
    }

    @Test
    public void tokensDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(SessionManager.generateToken());
        }
        assertEquals(200, seen.size());
    }

    // Reference values computed independently with Python's hashlib.sha256
    @Test
    public void hashIsSha256InBase64() {
        assertEquals("ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=", SessionManager.hashToken("abc"));
        assertEquals("47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", SessionManager.hashToken(""));
    }

    @Test
    public void hashIsStableAndDiffersFromTheToken() {
        String token = SessionManager.generateToken();
        assertEquals(SessionManager.hashToken(token), SessionManager.hashToken(token));
        assertNotEquals(token, SessionManager.hashToken(token));
        assertNotEquals(SessionManager.hashToken(token),
                SessionManager.hashToken(SessionManager.generateToken()));
    }
}
