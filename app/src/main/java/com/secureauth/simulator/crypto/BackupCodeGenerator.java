package com.secureauth.simulator.crypto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Makes one-time backup codes such as "K7QM-X2PD" for when the authenticator is lost.
 *
 * The alphabet leaves out 0, O, 1 and I because they are easy to confuse when written down.
 * That leaves exactly 32 symbols, i.e. 5 bits per character, so 8 characters = 40 bits of
 * randomness per code. Only salted hashes are kept; the plain code is shown to the user once.
 */
public final class BackupCodeGenerator {

    public static final int CODE_COUNT = 10;
    private static final int GROUP_LENGTH = 4;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCodeGenerator() {
    }

    /** One new code: show {@code plain} to the user once, store only {@code hashed}. */
    public static final class Generated {
        public final String plain;
        public final PasswordHasher.Result hashed;

        Generated(String plain, PasswordHasher.Result hashed) {
            this.plain = plain;
            this.hashed = hashed;
        }
    }

    /**
     * Creates 10 different codes. Each hash costs a full PBKDF2 run (deliberately slow,
     * 120,000 rounds), so call this from a background thread, not the UI thread.
     */
    public static List<Generated> generate() {
        Set<String> seen = new HashSet<>(); // two identical codes would be a (very unlikely) bug
        List<Generated> codes = new ArrayList<>(CODE_COUNT);
        while (codes.size() < CODE_COUNT) {
            String plain = randomCode();
            if (seen.add(plain)) {
                codes.add(new Generated(plain, PasswordHasher.hash(plain.toCharArray())));
            }
        }
        return codes;
    }

    /**
     * True if the text the user typed matches a stored hash. Case, spaces and the hyphen
     * are forgiven ("k7qm x2pd" works); a malformed code is just false.
     */
    public static boolean matches(String typed, String storedHash, String storedSalt) {
        String canonical = normalize(typed);
        if (canonical == null) {
            return false;
        }
        return PasswordHasher.verify(canonical.toCharArray(), storedHash, storedSalt);
    }

    /** True if the text could be a backup code (right length and alphabet), ignoring case, spaces and hyphen. */
    public static boolean isWellFormed(String typed) {
        return normalize(typed) != null;
    }

    /** Turns user input into the stored shape "XXXX-XXXX", or null if it cannot be a code. */
    static String normalize(String typed) {
        if (typed == null) {
            return null;
        }
        String letters = typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (letters.length() != 2 * GROUP_LENGTH) {
            return null;
        }
        for (int i = 0; i < letters.length(); i++) {
            if (ALPHABET.indexOf(letters.charAt(i)) < 0) {
                return null;
            }
        }
        return letters.substring(0, GROUP_LENGTH) + "-" + letters.substring(GROUP_LENGTH);
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(2 * GROUP_LENGTH + 1);
        for (int i = 0; i < 2 * GROUP_LENGTH; i++) {
            if (i == GROUP_LENGTH) {
                sb.append('-');
            }
            // nextInt(bound) is uniform, so no symbol is more likely than another
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
