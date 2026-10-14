package com.secureauth.simulator.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Base64;

/** Runs on the emulator/device because the Android Keystore does not exist on the desktop JVM. */
public class KeystoreHelperTest {

    @Test
    public void encryptThenDecryptReturnsTheOriginalSecret() throws Exception {
        byte[] secret = TotpEngine.generateSecret();

        KeystoreHelper.Result stored = KeystoreHelper.encrypt(secret);
        byte[] back = KeystoreHelper.decrypt(stored.ciphertext, stored.iv);

        assertArrayEquals(secret, back);
    }

    @Test
    public void ciphertextDoesNotContainThePlainSecret() throws Exception {
        byte[] secret = TotpEngine.generateSecret();
        KeystoreHelper.Result stored = KeystoreHelper.encrypt(secret);

        assertNotEquals(Base64.getEncoder().encodeToString(secret), stored.ciphertext);
        // GCM output = plain bytes + 16-byte authentication tag
        assertEquals(secret.length + 16, Base64.getDecoder().decode(stored.ciphertext).length);
        assertEquals(12, Base64.getDecoder().decode(stored.iv).length);
    }

    @Test
    public void everyEncryptionUsesAFreshIv() throws Exception {
        byte[] secret = TotpEngine.generateSecret();
        KeystoreHelper.Result first = KeystoreHelper.encrypt(secret);
        KeystoreHelper.Result second = KeystoreHelper.encrypt(secret);

        assertNotEquals(first.iv, second.iv);
        assertNotEquals(first.ciphertext, second.ciphertext);
    }

    @Test
    public void tamperedCiphertextIsRejected() throws Exception {
        KeystoreHelper.Result stored = KeystoreHelper.encrypt(TotpEngine.generateSecret());

        byte[] raw = Base64.getDecoder().decode(stored.ciphertext);
        raw[0] ^= 0x01; // flip a single bit
        String tampered = Base64.getEncoder().encodeToString(raw);

        try {
            KeystoreHelper.decrypt(tampered, stored.iv);
            fail("Tampered data must not decrypt");
        } catch (GeneralSecurityException expected) {
            // GCM authentication tag check failed, as it should
        }
    }

    @Test
    public void wrongIvIsRejected() throws Exception {
        KeystoreHelper.Result a = KeystoreHelper.encrypt(TotpEngine.generateSecret());
        KeystoreHelper.Result b = KeystoreHelper.encrypt(TotpEngine.generateSecret());

        try {
            KeystoreHelper.decrypt(a.ciphertext, b.iv);
            fail("Wrong IV must not decrypt");
        } catch (GeneralSecurityException expected) {
            // ok
        }
    }

    @Test
    public void keyLivesInAndroidKeystoreUnderTheExpectedAlias() throws Exception {
        KeystoreHelper.encrypt(new byte[]{1, 2, 3}); // makes sure the key exists

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        assertTrue(keyStore.containsAlias("secureauth_totp_key"));
        assertEquals("secureauth_totp_key", KeystoreHelper.KEY_ALIAS);
    }
}
