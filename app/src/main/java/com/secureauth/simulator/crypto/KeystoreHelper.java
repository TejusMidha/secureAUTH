package com.secureauth.simulator.crypto;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts small secrets (the TOTP secret) with an AES-256-GCM key that lives inside the
 * Android Keystore. The key bytes never enter our app: we only ask the Keystore to
 * encrypt or decrypt, so a copy of the database file alone is useless to a thief.
 *
 * GCM both encrypts and authenticates: if the stored ciphertext is changed even by one bit,
 * decrypt() throws instead of returning garbage.
 */
public final class KeystoreHelper {

    public static final String KEY_ALIAS = "secureauth_totp_key";

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128; // length of the GCM authentication tag

    private KeystoreHelper() {
    }

    /** Ciphertext and the IV used to make it, both Base64 text ready for the database. */
    public static final class Result {
        public final String ciphertext;
        public final String iv;

        public Result(String ciphertext, String iv) {
            this.ciphertext = ciphertext;
            this.iv = iv;
        }
    }

    /** Encrypts with the Keystore key; the Keystore picks a fresh random IV every time. */
    public static Result encrypt(byte[] plain) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        // Reusing an IV with the same GCM key breaks its security, which is why we do not choose it
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain);
        return new Result(toBase64(encrypted), toBase64(iv));
    }

    /**
     * Decrypts what encrypt() produced. Throws (e.g. AEADBadTagException) if the data or IV
     * was changed or the key is gone, so callers must handle the failure.
     */
    public static byte[] decrypt(String ciphertext, String iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(iv));
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec);
        return cipher.doFinal(Base64.getDecoder().decode(ciphertext));
    }

    /** Loads the key from the Keystore, creating it on first use. */
    private static synchronized SecretKey getOrCreateKey() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            }
        } catch (java.io.IOException e) {
            throw new java.security.KeyStoreException("Cannot open Android Keystore", e);
        }
        return createKey();
    }

    private static SecretKey createKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String toBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
