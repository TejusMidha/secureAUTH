package com.secureauth.simulator.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class BackupCodeGeneratorTest {

    // 4 + 4 characters from A-Z without I and O, and digits 2-9 (no 0 or 1)
    private static final Pattern FORMAT = Pattern.compile("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");

    @Test
    public void generatesTenUniqueCodesInTheRightFormat() {
        List<BackupCodeGenerator.Generated> codes = BackupCodeGenerator.generate();
        assertEquals(10, codes.size());

        Set<String> unique = new HashSet<>();
        for (BackupCodeGenerator.Generated g : codes) {
            assertTrue(g.plain, FORMAT.matcher(g.plain).matches());
            unique.add(g.plain);
        }
        assertEquals(10, unique.size());
    }

    @Test
    public void codesNeverContainAmbiguousCharacters() {
        for (int round = 0; round < 3; round++) {
            for (BackupCodeGenerator.Generated g : BackupCodeGenerator.generate()) {
                assertFalse(g.plain, g.plain.matches(".*[01OI].*"));
            }
        }
    }

    @Test
    public void storedValueIsAHashNotThePlainCode() {
        for (BackupCodeGenerator.Generated g : BackupCodeGenerator.generate()) {
            assertNotEquals(g.plain, g.hashed.hash);
            assertFalse(g.hashed.hash.contains(g.plain));
            assertFalse(g.hashed.salt.isEmpty());
        }
    }

    @Test
    public void everyCodeHasItsOwnSalt() {
        Set<String> salts = new HashSet<>();
        for (BackupCodeGenerator.Generated g : BackupCodeGenerator.generate()) {
            salts.add(g.hashed.salt);
        }
        assertEquals(10, salts.size());
    }

    @Test
    public void plainCodeMatchesItsOwnHashOnly() {
        List<BackupCodeGenerator.Generated> codes = BackupCodeGenerator.generate();
        BackupCodeGenerator.Generated a = codes.get(0);
        BackupCodeGenerator.Generated b = codes.get(1);

        assertTrue(BackupCodeGenerator.matches(a.plain, a.hashed.hash, a.hashed.salt));
        assertFalse(BackupCodeGenerator.matches(b.plain, a.hashed.hash, a.hashed.salt));
    }

    @Test
    public void matchingForgivesCaseSpacesAndMissingHyphen() {
        BackupCodeGenerator.Generated g = BackupCodeGenerator.generate().get(0);
        String lower = g.plain.toLowerCase();
        String spaced = g.plain.replace("-", " ");
        String noHyphen = g.plain.replace("-", "");

        assertTrue(BackupCodeGenerator.matches(lower, g.hashed.hash, g.hashed.salt));
        assertTrue(BackupCodeGenerator.matches(" " + spaced + " ", g.hashed.hash, g.hashed.salt));
        assertTrue(BackupCodeGenerator.matches(noHyphen, g.hashed.hash, g.hashed.salt));
    }

    @Test
    public void malformedInputNeverMatches() {
        BackupCodeGenerator.Generated g = BackupCodeGenerator.generate().get(0);
        assertFalse(BackupCodeGenerator.matches(null, g.hashed.hash, g.hashed.salt));
        assertFalse(BackupCodeGenerator.matches("", g.hashed.hash, g.hashed.salt));
        assertFalse(BackupCodeGenerator.matches("ABCD", g.hashed.hash, g.hashed.salt));
        assertFalse(BackupCodeGenerator.matches("0000-0000", g.hashed.hash, g.hashed.salt));
    }

    @Test
    public void wellFormedMeansRightLengthAndAlphabetOnly() {
        assertTrue(BackupCodeGenerator.isWellFormed("K7QM-X2PD"));
        assertTrue(BackupCodeGenerator.isWellFormed("  k7qm x2pd "));
        assertTrue(BackupCodeGenerator.isWellFormed("K7QMX2PD"));
        assertFalse(BackupCodeGenerator.isWellFormed(null));
        assertFalse(BackupCodeGenerator.isWellFormed(""));
        assertFalse(BackupCodeGenerator.isWellFormed("K7QM-X2P"));   // too short
        assertFalse(BackupCodeGenerator.isWellFormed("K7QM-X2PDD")); // too long
        assertFalse(BackupCodeGenerator.isWellFormed("K7QM-X2P0"));  // 0 is not in the alphabet
        assertFalse(BackupCodeGenerator.isWellFormed("K7QM-X2PI"));  // nor is I
    }

    @Test
    public void normalizeProducesCanonicalShape() {
        assertEquals("K7QM-X2PD", BackupCodeGenerator.normalize("k7qm x2pd"));
        assertEquals("K7QM-X2PD", BackupCodeGenerator.normalize("K7QMX2PD"));
        assertNull(BackupCodeGenerator.normalize("K7QM-X2P"));
        assertNull(BackupCodeGenerator.normalize("K7QM-X2P1")); // '1' is not in the alphabet
    }
}
