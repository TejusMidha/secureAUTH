package com.secureauth.simulator.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** The launcher icon Android really shows. */
@RunWith(AndroidJUnit4.class)
public class LauncherIconTest {

    @Test
    public void theLauncherIconIsAnAdaptiveIconWithAForegroundAndABackground() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Drawable icon = context.getPackageManager().getApplicationIcon(context.getPackageName());

        assertTrue("expected an adaptive icon but got " + icon.getClass(), icon instanceof AdaptiveIconDrawable);
        AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) icon;
        assertNotNull(adaptive.getForeground());
        assertNotNull(adaptive.getBackground());
    }

    @Test
    public void onAndroid13AndNewerThereIsAThemedMonochromeLayer() throws Exception {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable)
                context.getPackageManager().getApplicationIcon(context.getPackageName());
        assertNotNull(adaptive.getMonochrome());
    }
}
