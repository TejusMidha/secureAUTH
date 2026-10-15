package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.enroll;
import static com.secureauth.simulator.ui.TestUtils.startSession;
import static com.secureauth.simulator.ui.TestUtils.waitForScreen;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionDao;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;
import com.secureauth.simulator.receiver.AirplaneModeReceiver;
import com.secureauth.simulator.receiver.ScreenOffReceiver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.BooleanSupplier;

/**
 * The two broadcast receivers and what the screens do when they fire. The real broadcasts can only
 * be sent by Android itself, so the receivers are called with the same Intents Android would send.
 */
@RunWith(AndroidJUnit4.class)
public class ReceiverFlowTest {

    private static final long FIVE_MINUTES = 5 * 60 * 1000L;

    private Context context;
    private UserDao users;
    private SessionDao sessions;
    private AuditLogDao audit;
    private String username;
    private long userId;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        sessions = new SessionDao(db);
        audit = new AuditLogDao(db);
        username = "b" + Long.toString(System.currentTimeMillis(), 36);
        userId = createUser(users, username, 0, 0);
        enroll(new TotpAccountDao(db), userId, TotpEngine.generateSecret());
    }

    @After
    public void tearDown() {
        SessionManager.getInstance().clear();
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    private int countEvents(String event) {
        int count = 0;
        for (AuditLog row : audit.listForUser(userId)) {
            if (event.equals(row.event)) {
                count++;
            }
        }
        return count;
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Condition not reached in time");
            }
            Thread.sleep(100);
        }
    }

    // ---- the receivers themselves -----------------------------------------------------------

    @Test
    public void screenOffReceiverReportsOnlyTheScreenOffBroadcast() {
        final int[] calls = {0};
        ScreenOffReceiver receiver = new ScreenOffReceiver(() -> calls[0]++);

        receiver.onReceive(context, new Intent(Intent.ACTION_SCREEN_ON));
        receiver.onReceive(context, new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED));
        receiver.onReceive(context, new Intent());
        assertEquals(0, calls[0]);

        receiver.onReceive(context, new Intent(Intent.ACTION_SCREEN_OFF));
        assertEquals(1, calls[0]);
    }

    @Test
    public void airplaneReceiverReportsOnlyWhenTheModeWasSwitchedOn() {
        final int[] calls = {0};
        AirplaneModeReceiver receiver = new AirplaneModeReceiver(() -> calls[0]++);

        receiver.onReceive(context, new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", false));
        receiver.onReceive(context, new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)); // no extra at all
        receiver.onReceive(context, new Intent(Intent.ACTION_SCREEN_OFF).putExtra("state", true));
        assertEquals(0, calls[0]);

        receiver.onReceive(context, new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", true));
        assertEquals(1, calls[0]);
    }

    // ---- airplane mode on a screen ----------------------------------------------------------

    @Test
    public void airplaneModeOnShowsTheOfflineMessageOnAnyScreen() throws Exception {
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            waitUntilDisplayed(withId(R.id.button_login));
            scenario.onActivity(LoginActivity::handleAirplaneModeOn);

            waitUntilDisplayed(withText(R.string.airplane_mode_message));
            onView(withText(R.string.airplane_mode_message)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void theOfflineMessageSaysTheCodeOnlyNeedsTheClock() {
        String message = context.getString(R.string.airplane_mode_message);
        assertTrue(message, message.contains("TOTP still works"));
        assertTrue(message, message.contains("only needs the clock"));
    }

    // ---- screen off -------------------------------------------------------------------------

    @Test
    public void screenOffOnAProtectedScreenEndsTheSessionWritesAutoLockAndOpensLogin() throws Exception {
        startSession(context, userId, FIVE_MINUTES);
        try (ActivityScenario<DashboardActivity> scenario =
                     ActivityScenario.launch(new Intent(context, DashboardActivity.class))) {
            waitUntilDisplayed(withId(R.id.text_account));
            assertEquals(userId, SessionManager.getInstance().getUserId());

            scenario.onActivity(DashboardActivity::handleScreenOff);

            assertEquals(-1L, SessionManager.getInstance().getUserId()); // forgotten in memory at once
            waitForScreen(LoginActivity.class);
            TestUtils.waitUntilClosed(scenario);
            waitUntil(() -> countEvents(AuditEvent.AUTO_LOCK) == 1);
            waitUntil(() -> sessions.listForUser(userId).isEmpty());   // the database row is gone too
        }
    }

    @Test
    public void screenOffOnTheLoginScreenDoesNothing() throws Exception {
        startSession(context, userId, FIVE_MINUTES);
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            waitUntilDisplayed(withId(R.id.button_login));
            scenario.onActivity(LoginActivity::handleScreenOff);

            Thread.sleep(500);
            assertEquals(userId, SessionManager.getInstance().getUserId());
            assertEquals(0, countEvents(AuditEvent.AUTO_LOCK));
        }
    }

    @Test
    public void screenOffWithNoLiveSessionWritesNothing() throws Exception {
        startSession(context, userId, FIVE_MINUTES);
        try (ActivityScenario<DashboardActivity> scenario =
                     ActivityScenario.launch(new Intent(context, DashboardActivity.class))) {
            waitUntilDisplayed(withId(R.id.text_account));
            SessionManager.getInstance().clear(); // the session ended some other way a moment earlier
            scenario.onActivity(DashboardActivity::handleScreenOff);

            Thread.sleep(500);
            assertEquals(0, countEvents(AuditEvent.AUTO_LOCK));
        }
    }

    @Test
    public void afterAScreenOffLockTheDashboardCannotBeReopenedWithoutLoggingInAgain() throws Exception {
        startSession(context, userId, FIVE_MINUTES);
        try (ActivityScenario<DashboardActivity> scenario =
                     ActivityScenario.launch(new Intent(context, DashboardActivity.class))) {
            waitUntilDisplayed(withId(R.id.text_account));
            scenario.onActivity(DashboardActivity::handleScreenOff);
            waitForScreen(LoginActivity.class);
            TestUtils.waitUntilClosed(scenario);
        }
        try (ActivityScenario<DashboardActivity> again =
                     ActivityScenario.launch(new Intent(context, DashboardActivity.class))) {
            waitForScreen(LoginActivity.class);
            TestUtils.waitUntilClosed(again);
            onView(withId(R.id.text_account)).check(doesNotExist());
            assertNull(sessions.findValid(userId, "x", System.currentTimeMillis()));
        }
    }
}
