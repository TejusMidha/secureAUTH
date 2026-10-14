package com.secureauth.simulator.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class InputRulesTest {

    private static String repeat(char c, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    public void usernameIsTrimmedAndLowerCased() {
        assertEquals("alice", InputRules.normalizeUsername("  Alice "));
        assertEquals("", InputRules.normalizeUsername(null));
    }

    @Test
    public void usernameLengthBoundaries() {
        assertNotEquals(0, InputRules.usernameError(""));
        assertNotEquals(0, InputRules.usernameError("ab"));
        assertEquals(0, InputRules.usernameError("abc"));
        assertEquals(0, InputRules.usernameError(repeat('a', 20)));
        assertNotEquals(0, InputRules.usernameError(repeat('a', 21)));
    }

    @Test
    public void passwordNeedsAtLeastEightCharacters() {
        assertNotEquals(0, InputRules.passwordError("1234567".toCharArray()));
        assertEquals(0, InputRules.passwordError("12345678".toCharArray()));
    }

    @Test
    public void shortPasswordsAreAlwaysWeak() {
        assertEquals(InputRules.Strength.WEAK, InputRules.strength("Ab1!".toCharArray()));
    }

    @Test
    public void strengthGrowsWithVarietyAndLength() {
        // one character type, 8 chars
        assertEquals(InputRules.Strength.WEAK, InputRules.strength("abcdefgh".toCharArray()));
        // two types
        assertEquals(InputRules.Strength.FAIR, InputRules.strength("abcdefg1".toCharArray()));
        // three types
        assertEquals(InputRules.Strength.STRONG, InputRules.strength("Abcdefg1".toCharArray()));
        // long + two types = 3 points
        assertEquals(InputRules.Strength.STRONG, InputRules.strength("abcdefghijk1".toCharArray()));
        // long but one type = 2 points
        assertEquals(InputRules.Strength.FAIR, InputRules.strength("abcdefghijklmn".toCharArray()));
    }
}
