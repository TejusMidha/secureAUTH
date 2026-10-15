package com.secureauth.simulator.io;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Makes small test pictures as files in the app's cache folder (shared by the avatar tests). */
public final class TestImages {

    private TestImages() {
    }

    /** A picture split into vertical bands of the given colours, left to right. */
    public static Bitmap bands(int width, int height, int... colours) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        for (int i = 0; i < colours.length; i++) {
            paint.setColor(colours[i]);
            canvas.drawRect(width * i / (float) colours.length, 0,
                    width * (i + 1) / (float) colours.length, height, paint);
        }
        return bitmap;
    }

    public static File writeJpeg(Context context, String name, Bitmap bitmap) throws IOException {
        return write(context, name, bitmap, Bitmap.CompressFormat.JPEG);
    }

    public static File writePng(Context context, String name, Bitmap bitmap) throws IOException {
        return write(context, name, bitmap, Bitmap.CompressFormat.PNG);
    }

    private static File write(Context context, String name, Bitmap bitmap, Bitmap.CompressFormat format)
            throws IOException {
        File file = new File(context.getCacheDir(), name);
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(format, 95, out);
        }
        return file;
    }

    /** Writes the EXIF "orientation" tag into a JPEG, the way a phone camera does. */
    public static void setOrientation(File jpeg, int exifOrientation) throws IOException {
        ExifInterface exif = new ExifInterface(jpeg.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation));
        exif.saveAttributes();
    }

    public static File writeText(Context context, String name, String text) throws IOException {
        File file = new File(context.getCacheDir(), name);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
        return file;
    }

    public static Uri uriOf(File file) {
        return Uri.fromFile(file);
    }

    public static final int RED = Color.RED;
    public static final int GREEN = Color.GREEN;
    public static final int BLUE = Color.BLUE;
}
