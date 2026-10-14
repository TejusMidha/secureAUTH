package com.secureauth.simulator.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

/**
 * A ring that shrinks as time runs out: full = a new code just started, empty = it is about
 * to change. In the last 5 seconds it turns red as a warning. Drawn by hand with Canvas.
 */
public class CountdownRingView extends View {

    /** At or below this many seconds left the ring changes colour. */
    public static final int WARNING_SECONDS = 5;

    private static final float STROKE_DP = 12f;
    private static final float DEFAULT_SIZE_DP = 200f;
    private static final int TRACK_ALPHA = 0x33; // the faint full circle behind the arc

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final int normalColor;
    private final int warningColor;

    private float progress = 1f;
    private int periodSeconds = 30;

    public CountdownRingView(Context context) {
        this(context, null);
    }

    public CountdownRingView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CountdownRingView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float strokePx = STROKE_DP * getResources().getDisplayMetrics().density;

        // Theme colours, so the ring follows light/dark mode automatically
        normalColor = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSecondary, 0xFF00796B);
        warningColor = MaterialColors.getColor(context,
                androidx.appcompat.R.attr.colorError, 0xFFBA1A1A);
        int trackColor = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurface, 0xFF000000);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(strokePx);
        trackPaint.setColor(trackColor);
        trackPaint.setAlpha(TRACK_ALPHA);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(strokePx);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Fraction of the period still left: 1.0 = full ring, 0.0 = empty. Values are clamped. */
    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    /** Needed to know when "5 seconds left" is reached (30 s and 60 s periods differ). */
    public void setPeriodSeconds(int periodSeconds) {
        this.periodSeconds = Math.max(1, periodSeconds);
        invalidate();
    }

    /** True while the ring is in its red "about to expire" colour. */
    public boolean isWarning() {
        return progress * periodSeconds <= WARNING_SECONDS;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desired = (int) (DEFAULT_SIZE_DP * getResources().getDisplayMetrics().density);
        int width = resolveSize(desired, widthMeasureSpec);
        int height = resolveSize(desired, heightMeasureSpec);
        int size = Math.min(width, height); // always a circle, never an oval
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Inset by half the stroke so the thick line stays inside the view's bounds
        float inset = arcPaint.getStrokeWidth() / 2f;
        oval.set(inset, inset, getWidth() - inset, getHeight() - inset);

        canvas.drawOval(oval, trackPaint);

        arcPaint.setColor(isWarning() ? warningColor : normalColor);
        // -90 degrees = start at 12 o'clock; the sweep shrinks as progress falls
        canvas.drawArc(oval, -90f, 360f * progress, false, arcPaint);
    }
}
