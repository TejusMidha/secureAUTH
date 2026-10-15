package com.secureauth.simulator.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/** The size maths and the folder check of AvatarStore (no phone needed). */
public class AvatarSizeTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aSmallPictureIsNotShrunk() {
        assertEquals(1, AvatarStore.sampleSize(300, 200, 512));
        assertEquals(1, AvatarStore.sampleSize(512, 512, 512));
    }

    @Test
    public void aBigPictureIsShrunkByPowersOfTwoButStaysAtLeastTheTarget() {
        // 4000 x 3000: the short side 3000 can be halved to 1500, 750, but not to 375 (< 512)
        assertEquals(4, AvatarStore.sampleSize(4000, 3000, 512));
        // exactly twice the target: one halving keeps the short side at the target
        assertEquals(2, AvatarStore.sampleSize(1024, 2000, 512));
        // just under twice the target: no halving possible
        assertEquals(1, AvatarStore.sampleSize(1023, 2000, 512));
    }

    @Test
    public void theShortSideDecidesNotTheLongSide() {
        assertEquals(1, AvatarStore.sampleSize(10_000, 400, 512));
        assertEquals(1, AvatarStore.sampleSize(400, 10_000, 512));
    }

    @Test
    public void aZeroTargetNeverLoopsForever() {
        assertEquals(1, AvatarStore.sampleSize(4000, 3000, 0));
    }

    @Test
    public void theSquareIsTheShortSideButNeverMoreThanTheMaximum() {
        assertEquals(200, AvatarStore.squareSide(300, 200, 512));
        assertEquals(200, AvatarStore.squareSide(200, 300, 512));
        assertEquals(512, AvatarStore.squareSide(4000, 3000, 512));
        assertEquals(1, AvatarStore.squareSide(0, 0, 512));
    }

    @Test
    public void aFileInsideTheFolderIsInside() throws Exception {
        File dir = folder.newFolder("avatars");
        File inside = new File(dir, "avatar_1.jpg");
        assertTrue(AvatarStore.isInside(dir, inside));
    }

    @Test
    public void aFileNextToTheFolderIsNotInside() throws Exception {
        File dir = folder.newFolder("avatars");
        File sibling = new File(folder.getRoot(), "avatars_other/avatar_1.jpg");
        assertFalse(AvatarStore.isInside(dir, sibling));
        assertFalse(AvatarStore.isInside(dir, new File(folder.getRoot(), "secret.db")));
    }

    @Test
    public void dotDotCannotEscapeTheFolder() throws Exception {
        File dir = folder.newFolder("avatars");
        File escaping = new File(dir, "../secret.db");
        assertFalse(AvatarStore.isInside(dir, escaping));
    }

    @Test
    public void theFolderItselfIsNotAFileInsideIt() throws Exception {
        File dir = folder.newFolder("avatars");
        assertFalse(AvatarStore.isInside(dir, dir));
    }
}
