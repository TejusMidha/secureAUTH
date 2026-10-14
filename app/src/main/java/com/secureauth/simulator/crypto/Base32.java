package com.secureauth.simulator.crypto;

import java.io.ByteArrayOutputStream;

/**
 * Hand-written Base32 (RFC 4648 section 6). Authenticator apps share secrets in this
 * format because it only uses letters and digits that are easy to read and type.
 *
 * Every 5 bytes (40 bits) become 8 characters of 5 bits each.
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    /** Encodes bytes as uppercase Base32 without '=' padding. */
    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;     // bits waiting to be written
        int bitsInBuffer = 0;

        for (byte b : data) {
            // Push 8 new bits in at the right-hand end
            buffer = (buffer << 8) | (b & 0xFF);
            bitsInBuffer += 8;
            // Take 5 bits at a time from the left-hand end
            while (bitsInBuffer >= 5) {
                int index = (buffer >> (bitsInBuffer - 5)) & 0x1F;
                out.append(ALPHABET.charAt(index));
                bitsInBuffer -= 5;
            }
            // Keep only the bits not yet written so the int cannot overflow
            buffer &= (1 << bitsInBuffer) - 1;
        }

        if (bitsInBuffer > 0) {
            // Last group is short: pad with zero bits on the right to make 5
            int index = (buffer << (5 - bitsInBuffer)) & 0x1F;
            out.append(ALPHABET.charAt(index));
        }
        return out.toString();
    }

    /**
     * Decodes Base32 text. Lower case, spaces and trailing '=' padding are accepted
     * (people copy secrets that way); any other character throws IllegalArgumentException.
     */
    public static byte[] decode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Base32 text is null");
        }
        String clean = stripSpacesAndPadding(text).toUpperCase(java.util.Locale.ROOT);

        // Lengths 1, 3 and 6 (mod 8) can never come from whole bytes
        int rem = clean.length() % 8;
        if (rem == 1 || rem == 3 || rem == 6) {
            throw new IllegalArgumentException("Invalid Base32 length");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(clean.length() * 5 / 8);
        int buffer = 0;
        int bitsInBuffer = 0;

        for (int i = 0; i < clean.length(); i++) {
            int value = ALPHABET.indexOf(clean.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Invalid Base32 character: '" + clean.charAt(i) + "'");
            }
            // Push 5 new bits in; whenever 8 are ready, write one byte
            buffer = (buffer << 5) | value;
            bitsInBuffer += 5;
            if (bitsInBuffer >= 8) {
                out.write((buffer >> (bitsInBuffer - 8)) & 0xFF);
                bitsInBuffer -= 8;
                buffer &= (1 << bitsInBuffer) - 1;
            }
        }
        // Any leftover bits (fewer than 8) are the zero padding added by encode, so drop them
        return out.toByteArray();
    }

    /** Removes whitespace everywhere and '=' only at the end; '=' in the middle stays and is rejected. */
    private static String stripSpacesAndPadding(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '=') {
            end--;
        }
        return sb.substring(0, end);
    }
}
