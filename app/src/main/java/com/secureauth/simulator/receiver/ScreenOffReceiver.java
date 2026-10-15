package com.secureauth.simulator.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Hears Android's "the screen turned off" broadcast, so the app can lock itself the moment the
 * phone is put away instead of waiting for the auto-lock time to run out.
 *
 * ACTION_SCREEN_OFF can only be received by a receiver that is registered in code while the app
 * is running; a receiver declared in the manifest never gets it. That is why BaseActivity
 * registers this one in onStart and removes it in onStop.
 */
public class ScreenOffReceiver extends BroadcastReceiver {

    /** Called when the screen has just turned off. */
    public interface Listener {
        void onScreenOff();
    }

    private final Listener listener;

    public ScreenOffReceiver(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
            listener.onScreenOff();
        }
    }
}
