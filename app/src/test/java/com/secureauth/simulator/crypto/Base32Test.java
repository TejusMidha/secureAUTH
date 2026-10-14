package com.secureauth.simulator.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

public class Base32Test {

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    // RFC 4648 section 10 test vectors (encoded without the '=' padding)
    @Test
    public void encodeMatchesRfc4648Vectors() {
        assertEquals("", Base32.encode(ascii("")));
        assertEquals("MY", Base32.encode(ascii("f")));
        assertEquals("MZXQ", Base32.encode(ascii("fo")));
        assertEquals("MZXW6", Base32.encode(ascii("foo")));
        assertEquals("MZXW6YQ", Base32.encode(ascii("foob")));
        assertEquals("MZXW6YTB", Base32.encode(ascii("fooba")));
        assertEquals("MZXW6YTBOI", Base32.encode(ascii("foobar")));
    }

    @Test
    public void decodeMatchesRfc4648VectorsWithPadding() {
        assertArrayEquals(ascii(""), Base32.decode(""));
        assertArrayEquals(ascii("f"), Base32.decode("MY======"));
        assertArrayEquals(ascii("fo"), Base32.decode("MZXQ===="));
        assertArrayEquals(ascii("foo"), Base32.decode("MZXW6==="));
        assertArrayEquals(ascii("foob"), Base32.decode("MZXW6YQ="));
        assertArrayEquals(ascii("fooba"), Base32.decode("MZXW6YTB"));
        assertArrayEquals(ascii("foobar"), Base32.decode("MZXW6YTBOI======"));
    }

    @Test
    public void decodeAcceptsLowerCaseSpacesAndNoPadding() {
        assertArrayEquals(ascii("foobar"), Base32.decode("mzxw 6ytb oi"));
        assertArrayEquals(ascii("foobar"), Base32.decode("  MZXW6YTBOI\n"));
    }

    @Test
    public void randomBytesRoundTripAtEveryLength() {
        Random random = new Random(42);
        for (int length = 0; length <= 64; length++) {
            byte[] data = new byte[length];
            random.nextBytes(data);
            assertArrayEquals("length " + length, data, Base32.decode(Base32.encode(data)));
        }
    }

    @Test
    public void twentyByteSecretEncodesToThirtyTwoCharacters() {
        // 160 bits / 5 = 32 characters, no padding needed
        assertEquals(32, Base32.encode(new byte[20]).length());
    }

    @Test
    public void decodeRejectsCharactersOutsideTheAlphabet() {
        // 0, 1, 8, 9 are not in the Base32 alphabet; also punctuation
        for (String bad : new String[]{"MZXW0", "MZXW1", "MZXW8", "MZXW9", "MZ-W6YTB", "MZXW6!TB", "MZ=W6YTB"}) {
            try {
                Base32.decode(bad);
                fail("Expected rejection of " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeRejectsImpossibleLength() {
        Base32.decode("M"); // 5 bits cannot form a whole byte
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeRejectsNull() {
        Base32.decode(null);
    }
}
