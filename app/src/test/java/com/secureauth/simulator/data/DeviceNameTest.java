package com.secureauth.simulator.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The pure naming rules of DeviceTrust (no phone needed). */
public class DeviceNameTest {

    private static String repeat(char c, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    public void nameIsManufacturerThenModel() {
        assertEquals("Google Pixel 8", DeviceTrust.deviceName("Google", "Pixel 8"));
    }

    @Test
    public void makerIsNotRepeatedWhenTheModelAlreadyStartsWithIt() {
        assertEquals("HTC One", DeviceTrust.deviceName("HTC", "HTC One"));
        assertEquals("samsung SM-G991B", DeviceTrust.deviceName("samsung", "samsung SM-G991B"));
    }

    @Test
    public void comparisonOfMakerIgnoresCase() {
        assertEquals("Xiaomi 2201", DeviceTrust.deviceName("XIAOMI", "Xiaomi 2201"));
    }

    @Test
    public void oneMissingPartLeavesTheOther() {
        assertEquals("Pixel 8", DeviceTrust.deviceName("", "Pixel 8"));
        assertEquals("Google", DeviceTrust.deviceName("Google", null));
    }

    @Test
    public void blankInputGivesAGenericName() {
        assertEquals("Android device", DeviceTrust.deviceName(" ", ""));
        assertEquals("Android device", DeviceTrust.deviceName(null, null));
    }

    @Test
    public void cleanNameTrimsAndCollapsesSpaces() {
        assertEquals("My old phone", DeviceTrust.cleanName("   My   old\tphone  "));
        assertEquals("", DeviceTrust.cleanName(null));
    }

    @Test
    public void validNamesAreOneToFortyCharacters() {
        assertFalse(DeviceTrust.isValidName(""));
        assertFalse(DeviceTrust.isValidName(null));
        assertTrue(DeviceTrust.isValidName("A"));
        assertTrue(DeviceTrust.isValidName(repeat('x', DeviceTrust.MAX_NAME_LENGTH)));
        assertFalse(DeviceTrust.isValidName(repeat('x', DeviceTrust.MAX_NAME_LENGTH + 1)));
    }
}
