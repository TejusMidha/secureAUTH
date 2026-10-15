package com.secureauth.simulator.io;

import static com.secureauth.simulator.io.TestImages.BLUE;
import static com.secureauth.simulator.io.TestImages.GREEN;
import static com.secureauth.simulator.io.TestImages.RED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** The real picture pipeline: decode, turn upright, cut to a square, shrink, save as JPEG. */
@RunWith(AndroidJUnit4.class)
public class AvatarStoreDeviceTest {

    private static final long USER = 987_654L; // no real user has this id; files carry it in their name

    private Context context;
    private final List<File> madeFiles = new ArrayList<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @After
    public void tearDown() {
        for (File f : madeFiles) {
            f.delete();
        }
        File[] saved = AvatarStore.avatarDir(context).listFiles();
        if (saved != null) {
            for (File f : saved) {
                if (f.getName().startsWith("avatar_" + USER + "_")) {
                    f.delete();
                }
            }
        }
    }

    private File jpeg(String name, Bitmap bitmap) throws Exception {
        File f = TestImages.writeJpeg(context, name, bitmap);
        madeFiles.add(f);
        return f;
    }

    private String save(File picture) {
        return AvatarStore.saveFrom(context, USER, TestImages.uriOf(picture));
    }

    private static Bitmap decode(String path) {
        return BitmapFactory.decodeFile(path);
    }

    /** True if the pixel is clearly the given primary colour (JPEG blurs colours a little). */
    private static boolean isMostly(int pixel, int primary) {
        int r = Color.red(pixel);
        int g = Color.green(pixel);
        int b = Color.blue(pixel);
        if (primary == RED) {
            return r > 180 && g < 90 && b < 90;
        }
        if (primary == GREEN) {
            return g > 180 && r < 90 && b < 90;
        }
        return b > 180 && r < 90 && g < 90;
    }

    @Test
    public void aBigPictureBecomesA512PixelSquareJpegInsideTheAvatarFolder() throws Exception {
        String path = save(jpeg("big.jpg", TestImages.bands(2000, 1000, RED, GREEN)));

        assertNotNull(path);
        assertTrue(path.endsWith(".jpg"));
        assertTrue(AvatarStore.isInside(AvatarStore.avatarDir(context), new File(path)));
        Bitmap saved = decode(path);
        assertEquals(512, saved.getWidth());
        assertEquals(512, saved.getHeight());
        assertTrue("file should be small, was " + new File(path).length(), new File(path).length() < 100_000);
    }

    @Test
    public void aSmallPictureIsCutToASquareButNotEnlarged() throws Exception {
        String path = save(jpeg("small.jpg", TestImages.bands(200, 300, RED, BLUE)));

        Bitmap saved = decode(path);
        assertEquals(200, saved.getWidth());
        assertEquals(200, saved.getHeight());
    }

    @Test
    public void theMiddleOfThePictureIsWhatIsKept() throws Exception {
        // 300 x 100 in three bands: the centred 100 x 100 square is all green
        String path = save(jpeg("wide.jpg", TestImages.bands(300, 100, RED, GREEN, BLUE)));

        Bitmap saved = decode(path);
        assertEquals(100, saved.getWidth());
        assertTrue(isMostly(saved.getPixel(50, 50), GREEN));
        assertTrue(isMostly(saved.getPixel(10, 50), GREEN));
        assertTrue(isMostly(saved.getPixel(90, 50), GREEN));
    }

    @Test
    public void aCameraPictureThatIsTaggedAsRotatedComesOutUpright() throws Exception {
        // Stored as 400 x 200 with the left half red and the right half blue, tagged "turn 90
        // degrees clockwise": upright it is 200 x 400 with red on TOP and blue at the BOTTOM
        File picture = jpeg("rotated.jpg", TestImages.bands(400, 200, RED, BLUE));
        TestImages.setOrientation(picture, ExifInterface.ORIENTATION_ROTATE_90);

        Bitmap saved = decode(save(picture));

        assertEquals(200, saved.getWidth());
        assertEquals(200, saved.getHeight());
        assertTrue(isMostly(saved.getPixel(100, 20), RED));
        assertTrue(isMostly(saved.getPixel(100, 180), BLUE));
    }

    @Test
    public void aTransparentPngIsPlacedOnWhiteNotBlack() throws Exception {
        Bitmap clear = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888); // all transparent
        File png = TestImages.writePng(context, "clear.png", clear);
        madeFiles.add(png);

        Bitmap saved = decode(save(png));

        int pixel = saved.getPixel(50, 50);
        assertTrue(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240);
    }

    @Test
    public void somethingThatIsNotAPictureIsRefusedAndLeavesNothingBehind() throws Exception {
        File text = TestImages.writeText(context, "notes.txt", "this is not a picture");
        madeFiles.add(text);
        int before = countOfAvatars();

        assertNull(save(text));
        assertEquals(before, countOfAvatars());
    }

    @Test
    public void aMissingFileIsRefused() {
        File gone = new File(context.getCacheDir(), "does_not_exist.jpg");
        assertNull(save(gone));
    }

    @Test
    public void everySaveUsesANewFileName() throws Exception {
        File picture = jpeg("again.jpg", TestImages.bands(100, 100, RED, BLUE));
        String first = save(picture);
        Thread.sleep(5); // the name carries the time in milliseconds
        String second = save(picture);

        assertNotEquals(first, second);
    }

    @Test
    public void aSavedAvatarCanBeLoadedAndDeleted() throws Exception {
        String path = save(jpeg("load.jpg", TestImages.bands(600, 600, RED, GREEN)));

        Bitmap loaded = AvatarStore.load(context, path, 128);
        assertNotNull(loaded);
        assertTrue(loaded.getWidth() <= 256 && loaded.getWidth() >= 128); // a power-of-two shrink, never below the size asked

        AvatarStore.delete(context, path);
        assertFalse(new File(path).exists());
        assertNull(AvatarStore.load(context, path, 128));
    }

    @Test
    public void loadAndDeleteIgnoreFilesOutsideTheAvatarFolder() throws Exception {
        File outside = jpeg("outside.jpg", TestImages.bands(100, 100, RED, BLUE));

        assertNull(AvatarStore.load(context, outside.getAbsolutePath(), 128));
        AvatarStore.delete(context, outside.getAbsolutePath());
        assertTrue(outside.exists()); // not ours to remove
    }

    @Test
    public void loadAndDeleteAcceptNullAndEmptyPaths() {
        assertNull(AvatarStore.load(context, null, 128));
        assertNull(AvatarStore.load(context, "", 128));
        AvatarStore.delete(context, null);
        AvatarStore.delete(context, "");
    }

    private int countOfAvatars() {
        File[] files = AvatarStore.avatarDir(context).listFiles();
        return files == null ? 0 : files.length;
    }
}
