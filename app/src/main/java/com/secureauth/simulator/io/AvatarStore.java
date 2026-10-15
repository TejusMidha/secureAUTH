package com.secureauth.simulator.io;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Keeps the user's profile photo inside the app's private storage.
 *
 * A picture chosen from the gallery or taken with the camera is copied here, turned upright (the
 * camera records "this way up" in a tag instead of rotating the pixels), cut to a centred square
 * and shrunk to at most {@link #MAX_SIDE_PX}, then saved as a JPEG. Phone photos are several
 * megabytes; a 512 px avatar is a few dozen kilobytes. The original is never kept.
 */
@SuppressLint("ExifInterface") // framework class on purpose: androidx.exifinterface is not an allowed dependency
public final class AvatarStore {

    public static final int MAX_SIDE_PX = 512;

    private static final String DIR_NAME = "avatars";
    private static final int JPEG_QUALITY = 85;

    private AvatarStore() {
    }

    // ---- size maths (no Android classes, so it is unit tested on the PC) --------------------

    /**
     * The biggest power of two (1, 2, 4...) by which a picture of this size can be shrunk while
     * the SHORT side stays at least {@code targetSide}. BitmapFactory then decodes only a fraction
     * of the pixels, which keeps a 12-megapixel photo from filling the memory.
     */
    public static int sampleSize(int width, int height, int targetSide) {
        int shortSide = Math.min(width, height);
        int sample = 1;
        while (targetSide > 0 && shortSide / (sample * 2) >= targetSide) {
            sample *= 2;
        }
        return sample;
    }

    /** Side of the square avatar: the short side of the picture, but never more than {@code maxSide}. */
    public static int squareSide(int width, int height, int maxSide) {
        return Math.max(1, Math.min(Math.min(width, height), maxSide));
    }

    /** True if {@code file} is inside {@code dir} (after resolving "..", so it cannot escape). */
    public static boolean isInside(File dir, File file) {
        try {
            String base = dir.getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(base);
        } catch (IOException e) {
            return false;
        }
    }

    // ---- files ------------------------------------------------------------------------------

    /** The private folder that holds every avatar. */
    public static File avatarDir(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    /**
     * Copies the picture behind {@code source} into private storage as this user's avatar and
     * returns the saved file's path, or null if it could not be read as a picture. A new file name
     * is used every time so no screen shows an old picture from a cache. Call from a background
     * thread. The caller deletes the previous avatar (see {@link #delete}).
     */
    @Nullable
    public static String saveFrom(Context context, long userId, Uri source) {
        ContentResolver resolver = context.getContentResolver();
        Bitmap decoded = decode(resolver, source);
        if (decoded == null) {
            return null;
        }

        Bitmap square = null;
        try {
            Bitmap upright = rotate(decoded, orientationOf(resolver, source));
            square = centreSquare(upright, MAX_SIDE_PX);

            File dir = avatarDir(context);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File target = new File(dir, "avatar_" + userId + "_" + System.currentTimeMillis() + ".jpg");
            try (OutputStream out = new FileOutputStream(target)) {
                if (!square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    target.delete();
                    return null;
                }
            }
            return target.getAbsolutePath();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            decoded.recycle();
            if (square != null) {
                square.recycle();
            }
        }
    }

    /**
     * Reads a saved avatar, at most {@code maxSide} pixels wide, or null if there is none. Only
     * files inside our own avatar folder are read, so a damaged or tampered path in the database
     * cannot make the app open some other file.
     */
    @Nullable
    public static Bitmap load(Context context, @Nullable String path, int maxSide) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        File file = new File(path);
        if (!file.isFile() || !isInside(avatarDir(context), file)) {
            return null;
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide);
        return BitmapFactory.decodeFile(path, options);
    }

    /** Deletes an avatar file (only if it is inside the avatar folder). Safe to call with null. */
    public static void delete(Context context, @Nullable String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        File file = new File(path);
        if (isInside(avatarDir(context), file)) {
            file.delete();
        }
    }

    // ---- steps of saveFrom ------------------------------------------------------------------

    /** Decodes a shrunk copy of the picture, or null if the bytes are not a picture. */
    @Nullable
    private static Bitmap decode(ContentResolver resolver, Uri source) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(source)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (IOException | RuntimeException e) {
            return null; // includes SecurityException and a file that is gone
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null; // not an image
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE_PX);
        try (InputStream in = resolver.openInputStream(source)) {
            return in == null ? null : BitmapFactory.decodeStream(in, null, options);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * The EXIF "which way up" tag, or "normal" if there is none or it cannot be read. The framework
     * class is used on purpose: the androidx one is a separate library, and this project only
     * allows the libraries listed in the plan. The InputStream constructor exists from API 24.
     */
    private static int orientationOf(ContentResolver resolver, Uri source) {
        try (InputStream in = resolver.openInputStream(source)) {
            if (in == null) {
                return ExifInterface.ORIENTATION_NORMAL;
            }
            return new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** Turns the pixels so the picture is upright, as the EXIF tag says. */
    private static Bitmap rotate(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(270);
                matrix.postScale(-1, 1);
                break;
            default:
                return source;
        }
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    /**
     * Cuts the biggest centred square out of the picture and shrinks it to at most
     * {@code maxSide}. A transparent PNG is placed on white, because JPEG has no transparency
     * and would turn it black.
     */
    private static Bitmap centreSquare(Bitmap source, int maxSide) {
        int side = squareSide(source.getWidth(), source.getHeight(), maxSide);
        int shortSide = Math.min(source.getWidth(), source.getHeight());
        int left = (source.getWidth() - shortSide) / 2;
        int top = (source.getHeight() - shortSide) / 2;

        Matrix scale = new Matrix();
        float factor = (float) side / shortSide;
        scale.postScale(factor, factor);
        Bitmap cropped = Bitmap.createBitmap(source, left, top, shortSide, shortSide, scale, true);
        if (!cropped.hasAlpha()) {
            return cropped;
        }
        Bitmap flat = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(flat);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(cropped, 0, 0, null);
        if (cropped != source) {
            cropped.recycle();
        }
        return flat;
    }
}
