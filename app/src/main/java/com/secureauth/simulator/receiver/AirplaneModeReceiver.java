package com.secureauth.simulator.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Hears Android's "airplane mode was switched" broadcast. When it was switched ON, the screen is
 * told, so it can remind the user that codes still work with no network: a TOTP code only needs
 * the secret and the clock.
 *
 * It does not decide what to show; the listener does. That keeps this class tiny and lets the
 * screen that registered it (see BaseActivity) choose how to tell the user.
 */
public class AirplaneModeReceiver extends BroadcastReceiver {

    /** Called when airplane mode has just been turned on. */
    public interface Listener {
        void onAirplaneModeOn();
    }

    private final Listener listener;

    public AirplaneModeReceiver(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction())) {
            return;
        }
        // The broadcast carries the new state as an extra named "state" (true = now on)
        if (intent.getBooleanExtra("state", false)) {
            listener.onAirplaneModeOn();
        }
    }
}
