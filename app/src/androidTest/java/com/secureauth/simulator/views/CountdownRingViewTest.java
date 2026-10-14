package com.secureauth.simulator.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CountdownRingViewTest {

    private static final int SIZE = 400;

    private CountdownRingView ring;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ring = new CountdownRingView(context);
        ring.measure(View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY));
        ring.layout(0, 0, SIZE, SIZE);
    }

    private Bitmap draw() {
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        ring.draw(new Canvas(bitmap));
        return bitmap;
    }

    /** Alpha of the pixel in the middle of the ring's line at 12, 3, 6 or 9 o'clock. */
    private int alphaAt(Bitmap bitmap, int hour) {
        float strokeCenter = 6f * ring.getResources().getDisplayMetrics().density; // half of 12dp
        int mid = SIZE / 2;
        int near = Math.round(strokeCenter);
        int far = SIZE - 1 - near;
        switch (hour) {
            case 12: return Color.alpha(bitmap.getPixel(mid, near));
            case 3: return Color.alpha(bitmap.getPixel(far, mid));
            case 6: return Color.alpha(bitmap.getPixel(mid, far));
            default: return Color.alpha(bitmap.getPixel(near, mid)); // 9
        }
    }

    @Test
    public void progressIsClampedBetweenZeroAndOne() {
        ring.setProgress(1.7f);
        assertEquals(1f, ring.getProgress(), 0f);
        ring.setProgress(-0.3f);
        assertEquals(0f, ring.getProgress(), 0f);
        ring.setProgress(0.4f);
        assertEquals(0.4f, ring.getProgress(), 0f);
    }

    @Test
    public void warningStartsAtFiveSecondsLeft() {
        ring.setPeriodSeconds(30);
        ring.setProgress(6f / 30f);
        assertFalse(ring.isWarning());
        ring.setProgress(5f / 30f);
        assertTrue(ring.isWarning());
        ring.setProgress(0f);
        assertTrue(ring.isWarning());

        ring.setPeriodSeconds(60); // the same fraction now means twice as many seconds
        ring.setProgress(5f / 30f);
        assertFalse(ring.isWarning());
        ring.setProgress(5f / 60f);
        assertTrue(ring.isWarning());
    }

    @Test
    public void fullRingPaintsTheWholeCircle() {
        ring.setProgress(1f);
        Bitmap bitmap = draw();
        for (int hour : new int[]{12, 3, 6, 9}) {
            assertEquals("hour " + hour, 255, alphaAt(bitmap, hour)); // solid arc, not the faint track
        }
    }

    @Test
    public void arcShrinksClockwiseFromTwelveOClock() {
        ring.setProgress(0.5f); // arc covers 12 -> 6 o'clock through 3
        Bitmap bitmap = draw();
        assertEquals(255, alphaAt(bitmap, 12));
        assertEquals(255, alphaAt(bitmap, 3));
        assertTrue("9 o'clock should only show the faint track", alphaAt(bitmap, 9) < 255);

        ring.setProgress(0.2f); // only a little past 12 o'clock
        bitmap = draw();
        assertEquals(255, alphaAt(bitmap, 12));
        assertTrue("3 o'clock should be empty", alphaAt(bitmap, 3) < 255);
    }

    @Test
    public void measuredSizeIsAlwaysSquare() {
        CountdownRingView wide = new CountdownRingView(ring.getContext());
        wide.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
        assertEquals(wide.getMeasuredWidth(), wide.getMeasuredHeight());
        assertEquals(300, wide.getMeasuredWidth());
    }
}
