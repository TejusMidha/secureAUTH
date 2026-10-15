package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.secureauth.simulator.crypto.KeystoreHelper;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionDao;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TotpAccount;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.hamcrest.Matcher;

import java.security.GeneralSecurityException;
import java.util.Collection;

/** Small helpers shared by the Espresso tests. */
final class TestUtils {

    private TestUtils() {
    }

    /** Inserts a throw-away user (password hash is a dummy; these tests never log in by password). */
    static long createUser(UserDao users, String username, int failedAttempts, long lockedUntil) {
        User u = new User();
        u.username = username;
        u.passwordHash = "h";
        u.salt = "s";
        u.failedAttempts = failedAttempts;
        u.lockedUntil = lockedUntil;
        u.createdAt = System.currentTimeMillis();
        return users.insert(u);
    }

    /** Stores {@code secret} as the user's authenticator (SHA-1, 6 digits, 30 s), encrypted like the app does. */
    static void enroll(TotpAccountDao accounts, long userId, byte[] secret) throws GeneralSecurityException {
        KeystoreHelper.Result encrypted = KeystoreHelper.encrypt(secret);
        TotpAccount a = new TotpAccount();
        a.userId = userId;
        a.secretEnc = encrypted.ciphertext;
        a.iv = encrypted.iv;
        a.issuer = "SecureAuth";
        a.createdAt = System.currentTimeMillis();
        accounts.replaceForUser(a);
    }

    /** A 6-digit code that is NOT valid in the current, previous or next time step. */
    static String invalidCode(byte[] secret) {
        long now = System.currentTimeMillis();
        for (int candidate = 0; candidate < 1_000_000; candidate += 7919) {
            String code = String.format("%06d", candidate);
            if (!TotpEngine.verify(secret, code, now, 6, 30, TotpEngine.ALGO_SHA1)) {
                return code;
            }
        }
        throw new IllegalStateException("no invalid code found");
    }

    /** The code an authenticator app would show right now. */
    static String currentCode(byte[] secret) {
        return TotpEngine.generate(secret, System.currentTimeMillis(), 6, 30, TotpEngine.ALGO_SHA1);
    }

    /** As if the password step had just passed for this user (the code step and recovery refuse to open without it). */
    static void passPasswordStep(long userId) {
        com.secureauth.simulator.data.LoginHandshake.getInstance().passwordAccepted(userId, System.currentTimeMillis());
    }

    /** As if the password and the code step had both just passed (the fingerprint step refuses to open without it). */
    static void passCodeStep(long userId) {
        passPasswordStep(userId);
        com.secureauth.simulator.data.LoginHandshake.getInstance().codeAccepted(userId, System.currentTimeMillis());
    }

    /** As if this account had just been created on the Register screen (enrollment may open for it). */
    static void justRegistered(long userId) {
        com.secureauth.simulator.data.LoginHandshake.getInstance().registered(userId, System.currentTimeMillis());
    }

    /** A copy of everything in the settings file, so a test that changes settings can put it back. */
    static java.util.Map<String, Object> snapshotSettings(Context context) {
        return new java.util.HashMap<>(context.getSharedPreferences(
                com.secureauth.simulator.data.AppSettings.PREFS_NAME, Context.MODE_PRIVATE).getAll());
    }

    /** Puts the settings file back exactly as {@link #snapshotSettings} saw it. */
    static void restoreSettings(Context context, java.util.Map<String, Object> snapshot) {
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences(
                com.secureauth.simulator.data.AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear();
        for (java.util.Map.Entry<String, Object> entry : snapshot.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof Integer) {
                editor.putInt(entry.getKey(), (Integer) v);
            } else if (v instanceof Long) {
                editor.putLong(entry.getKey(), (Long) v);
            } else if (v instanceof Boolean) {
                editor.putBoolean(entry.getKey(), (Boolean) v);
            } else if (v instanceof String) {
                editor.putString(entry.getKey(), (String) v);
            }
        }
        editor.commit();
    }

    /** Starts a real session (database row + token in memory) as BiometricGateActivity would. */
    static void startSession(Context context, long userId, long durationMs) {
        SessionManager.getInstance().create(new SessionDao(DbHelper.getInstance(context)),
                userId, durationMs, System.currentTimeMillis());
    }

    /** Whatever app screen is currently visible (started or resumed), or null. */
    static Activity visibleActivity() {
        final Activity[] holder = new Activity[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ActivityLifecycleMonitor registry = ActivityLifecycleMonitorRegistry.getInstance();
            for (Stage stage : new Stage[]{Stage.RESUMED, Stage.STARTED}) {
                Collection<Activity> found = registry.getActivitiesInStage(stage);
                if (!found.isEmpty()) {
                    holder[0] = found.iterator().next();
                    return;
                }
            }
        });
        return holder[0];
    }

    /**
     * Waits until a screen of the given class is showing. Used instead of looking for text,
     * because the system fingerprint prompt may be drawn above our screen and hide its views.
     */
    static void waitForScreen(Class<? extends Activity> screen) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            Activity current = visibleActivity();
            if (current != null && screen.isInstance(current)) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Expected " + screen.getSimpleName() + " but saw "
                        + (current == null ? "nothing" : current.getClass().getSimpleName()));
            }
            Thread.sleep(100);
        }
    }

    /** Sets the date shown in a DatePicker (month is 0-11, like Calendar). */
    static ViewAction setDate(final int year, final int month, final int day) {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return isAssignableFrom(android.widget.DatePicker.class);
            }

            @Override
            public String getDescription() {
                return "set date " + year + "-" + (month + 1) + "-" + day;
            }

            @Override
            public void perform(UiController uiController, View view) {
                ((android.widget.DatePicker) view).updateDate(year, month, day);
            }
        };
    }

    /** Waits until the scenario's activity has finished. */
    static void waitUntilClosed(ActivityScenario<?> scenario) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000; // generous: closing may follow a slow hash check
        while (true) {
            try {
                if (scenario.getState() == Lifecycle.State.DESTROYED) {
                    return;
                }
            } catch (NullPointerException stillTransitioning) {
                // getState() throws while the screen is between lifecycle stages (for example
                // while another screen is opening on top of it); just look again
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Activity did not close");
            }
            Thread.sleep(100);
        }
    }

    /** Reads the text currently shown in a TextView. */
    static String textOf(int viewId) {
        final String[] holder = new String[1];
        onView(withId(viewId)).perform(new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return isAssignableFrom(TextView.class);
            }

            @Override
            public String getDescription() {
                return "read text";
            }

            @Override
            public void perform(UiController uiController, View view) {
                holder[0] = ((TextView) view).getText().toString();
            }
        });
        return holder[0];
    }

    /** Background work finishes after a click returns, so poll until the view appears. */
    static void waitUntilDisplayed(Matcher<View> matcher) throws InterruptedException {
        // Password hashing is slow on purpose; on a busy emulator a register or login can take
        // well over 10 seconds, so allow plenty of time (a passing test never waits this long)
        waitUntilDisplayed(matcher, 40_000);
    }

    /** Same, with a longer limit for slow work such as hashing 10 backup codes. */
    static void waitUntilDisplayed(Matcher<View> matcher, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                onView(matcher).check(matches(isDisplayed()));
                return;
            } catch (RuntimeException | AssertionError e) {
                // Covers "no matching view" and also Espresso's "root has no window focus", which
                // happens for a moment while a dialog opens or the screen changes; just look again
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }
}
