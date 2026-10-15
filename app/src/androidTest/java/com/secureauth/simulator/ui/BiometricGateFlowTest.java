package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.enroll;
import static com.secureauth.simulator.ui.TestUtils.waitUntilClosed;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.biometric.BiometricPrompt;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.Session;
import com.secureauth.simulator.data.SessionDao;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Step 3 of the login handshake. The system fingerprint prompt cannot be pressed from a test,
 * so these tests call the screen's handlers exactly as the prompt's callbacks would, and check
 * what they do: sessions, audit rows, lockout, navigation.
 */
@RunWith(AndroidJUnit4.class)
public class BiometricGateFlowTest {

    private Context context;
    private UserDao users;
    private AuditLogDao audit;
    private SessionDao sessions;
    private String username;
    private long userId;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        audit = new AuditLogDao(db);
        sessions = new SessionDao(db);
        SessionManager.getInstance().clear();

        username = "b" + Long.toString(System.currentTimeMillis(), 36);
        userId = createUser(users, username, 0, 0);
        enroll(new TotpAccountDao(db), userId, TotpEngine.generateSecret()); // so the Dashboard can open
    }

    @After
    public void tearDown() {
        SessionManager.getInstance().clear();
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private ActivityScenario<BiometricGateActivity> launchGate() {
        TestUtils.passCodeStep(userId); // steps 1 and 2 come first in the real flow
        return ActivityScenario.launch(new Intent(context, BiometricGateActivity.class)
                .putExtra(BiometricGateActivity.EXTRA_USER_ID, userId)
                .putExtra(BiometricGateActivity.EXTRA_SHOW_PROMPT, false)); // keep the system prompt out of the way
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

    private static void waitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(100);
        }
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    public void openingTheGateAloneDoesNotCreateASession() throws Exception {
        try (ActivityScenario<BiometricGateActivity> ignored = launchGate()) {
            Thread.sleep(500);
            assertEquals(-1L, SessionManager.getInstance().getUserId());
            assertTrue(sessions.listForUser(userId).isEmpty());
        }
    }

    @Test
    public void successCreatesASessionAndOpensTheDashboard() throws Exception {
        users.incrementFailedAttempts(userId); // an earlier slip must be forgiven now
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(BiometricGateActivity::handleSuccess);

            waitUntilDisplayed(withId(R.id.text_issuer)); // the Dashboard is showing
            assertEquals(userId, SessionManager.getInstance().getUserId());
            assertEquals(1, countEvents(AuditEvent.BIOMETRIC_OK));
            assertEquals(1, countEvents(AuditEvent.SESSION_START));
            assertEquals(0, users.getById(userId).failedAttempts);

            List<Session> rows = sessions.listForUser(userId);
            assertEquals(1, rows.size());
            Session row = rows.get(0);
            assertEquals(SessionManager.getAutoLockMs(context), row.expiresAt - row.createdAt);
            assertEquals(44, row.tokenHash.length()); // only a SHA-256 hash is stored
        }
    }

    @Test
    public void aSecondSuccessCallbackDoesNotCreateAnotherSession() throws Exception {
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(activity -> {
                activity.handleSuccess();
                activity.handleSuccess(); // e.g. a duplicate callback
            });
            waitUntilDisplayed(withId(R.id.text_issuer));
            assertEquals(1, countEvents(AuditEvent.SESSION_START));
        }
    }

    @Test
    public void twoWrongAttemptsAreLoggedButDoNotLock() throws Exception {
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(activity -> {
                activity.handleFailedAttempt();
                activity.handleFailedAttempt();
            });
            waitUntil("two BIOMETRIC_FAIL rows", () -> countEvents(AuditEvent.BIOMETRIC_FAIL) == 2);

            assertEquals(2, users.getById(userId).failedAttempts);
            assertEquals(0L, users.getById(userId).lockedUntil);
            assertEquals(0, countEvents(AuditEvent.LOCKED_OUT));
        }
    }

    @Test
    public void threeWrongAttemptsLockTheAccountAndALateSuccessIsIgnored() throws Exception {
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            for (int i = 0; i < 3; i++) {
                scenario.onActivity(BiometricGateActivity::handleFailedAttempt);
            }
            waitUntil("lockout", () -> countEvents(AuditEvent.LOCKED_OUT) == 1);

            assertEquals(3, countEvents(AuditEvent.BIOMETRIC_FAIL));
            assertTrue(users.getById(userId).lockedUntil > System.currentTimeMillis());

            // a success arriving after the lock must not open the app
            scenario.onActivity(BiometricGateActivity::handleSuccess);
            Thread.sleep(800);
            assertEquals(-1L, SessionManager.getInstance().getUserId());
            assertTrue(sessions.listForUser(userId).isEmpty());
            assertEquals(0, countEvents(AuditEvent.BIOMETRIC_OK));
        }
    }

    @Test
    public void cancellingReturnsToLoginWithoutASession() throws Exception {
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(activity ->
                    activity.handleError(BiometricPrompt.ERROR_USER_CANCELED, "cancelled"));

            waitUntilClosed(scenario);
            waitUntil("cancel audit row", () -> countEvents(AuditEvent.BIOMETRIC_FAIL) == 1);
            assertEquals(AuditEvent.RESULT_CANCELLED, audit.listForUser(userId).get(0).result);
            assertEquals(-1L, SessionManager.getInstance().getUserId());
        }
    }

    @Test
    public void aHardwareErrorShowsTheMessageAndOffersARetry() throws Exception {
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(activity ->
                    activity.handleError(BiometricPrompt.ERROR_HW_UNAVAILABLE, "Sensor busy"));
            waitUntil("error audit row", () -> countEvents(AuditEvent.BIOMETRIC_FAIL) == 1);

            scenario.onActivity(activity -> {
                TextView status = activity.findViewById(R.id.text_status);
                assertTrue(status.getText().toString(), status.getText().toString().contains("Sensor busy"));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.button_try_again).getVisibility());
            });
            assertEquals(AuditEvent.RESULT_ERROR, audit.listForUser(userId).get(0).result);
            assertEquals(-1L, SessionManager.getInstance().getUserId());
        }
    }

    @Test
    public void deviceLockoutErrorExplainsToWait() throws Exception {
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(activity ->
                    activity.handleError(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts"));
            scenario.onActivity(activity -> {
                TextView status = activity.findViewById(R.id.text_status);
                assertEquals(activity.getString(R.string.biometric_status_lockout), status.getText().toString());
            });
        }
    }

    // ---- where the login happened -----------------------------------------------------------

    // Mumbai, Pune (about 120 km from Mumbai) and Thane (about 19 km from Mumbai)
    private static final double MUMBAI_LAT = 19.0760, MUMBAI_LNG = 72.8777;
    private static final double PUNE_LAT = 18.5204, PUNE_LNG = 73.8567;
    private static final double THANE_LAT = 19.2183, THANE_LNG = 72.9781;

    /** An earlier successful login at the given place, an hour ago. */
    private void addPreviousLogin(double lat, double lng) {
        AuditLog previous = new AuditLog();
        previous.userId = userId;
        previous.event = AuditEvent.SESSION_START;
        previous.result = AuditEvent.RESULT_SUCCESS;
        previous.lat = lat;
        previous.lng = lng;
        previous.ts = System.currentTimeMillis() - 60 * 60 * 1000L;
        audit.insert(previous);
    }

    private AuditLog newestOf(String event) {
        for (AuditLog row : audit.listForUser(userId)) {
            if (event.equals(row.event)) {
                return row;
            }
        }
        return null;
    }

    @Test
    public void aLoginStoresItsPlaceOnTheSessionStartRow() throws Exception {
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(activity -> activity.completeLogin(MUMBAI_LAT, MUMBAI_LNG));
            waitUntilDisplayed(withId(R.id.text_issuer)); // the Dashboard

            AuditLog start = newestOf(AuditEvent.SESSION_START);
            assertNotNull(start);
            assertEquals(MUMBAI_LAT, start.lat, 0.0);
            assertEquals(MUMBAI_LNG, start.lng, 0.0);
            assertNull(newestOf(AuditEvent.SUSPICIOUS_LOCATION)); // nothing earlier to compare with
            onView(withText(R.string.suspicious_title)).check(doesNotExist());
        }
    }

    @Test
    public void aLoginFarFromThePreviousOneIsFlaggedAndTheUserIsWarned() throws Exception {
        addPreviousLogin(MUMBAI_LAT, MUMBAI_LNG);
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(activity -> activity.completeLogin(PUNE_LAT, PUNE_LNG));

            // the warning appears on the Dashboard, with the distance
            waitUntilDisplayed(withText(R.string.suspicious_title));
            onView(withText(containsString("120 km"))).check(matches(isDisplayed()));

            AuditLog flag = newestOf(AuditEvent.SUSPICIOUS_LOCATION);
            assertNotNull(flag);
            assertEquals(AuditEvent.RESULT_WARNING, flag.result);
            assertEquals(PUNE_LAT, flag.lat, 0.0); // the NEW place, not the old one
            assertEquals(PUNE_LNG, flag.lng, 0.0);
            assertEquals(1, countEvents(AuditEvent.SUSPICIOUS_LOCATION));

            onView(withText(R.string.button_ok)).perform(click());
            onView(withText(R.string.suspicious_title)).check(doesNotExist());
        }
    }

    @Test
    public void theWarningCanOpenTheLoginMap() throws Exception {
        addPreviousLogin(MUMBAI_LAT, MUMBAI_LNG);
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(activity -> activity.completeLogin(PUNE_LAT, PUNE_LNG));
            waitUntilDisplayed(withText(R.string.suspicious_title));

            onView(withText(R.string.button_view_map)).perform(click());
            TestUtils.waitForScreen(LoginMapActivity.class);
        }
    }

    @Test
    public void aLoginNearThePreviousOneIsNotFlagged() throws Exception {
        addPreviousLogin(MUMBAI_LAT, MUMBAI_LNG);
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(activity -> activity.completeLogin(THANE_LAT, THANE_LNG));
            waitUntilDisplayed(withId(R.id.text_issuer));

            assertNull(newestOf(AuditEvent.SUSPICIOUS_LOCATION));
            onView(withText(R.string.suspicious_title)).check(doesNotExist());
            assertNotNull(newestOf(AuditEvent.SESSION_START).lat);
        }
    }

    @Test
    public void withoutAPlaceNowThereIsNothingToCompare() throws Exception {
        addPreviousLogin(MUMBAI_LAT, MUMBAI_LNG);
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(activity -> activity.completeLogin(null, null)); // location denied
            waitUntilDisplayed(withId(R.id.text_issuer));

            assertNull(newestOf(AuditEvent.SUSPICIOUS_LOCATION));
            assertNull(newestOf(AuditEvent.SESSION_START).lat); // stored as "no location", not 0
            assertNull(newestOf(AuditEvent.SESSION_START).lng);
        }
    }

    @Test
    public void aSuccessfulBiometricCheckLooksUpThePlaceThroughTheHelper() throws Exception {
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(activity -> {
                activity.locationHelper = FakeLocationHelper.at(context, MUMBAI_LAT, MUMBAI_LNG);
                activity.handleSuccess();
            });
            waitUntilDisplayed(withId(R.id.text_issuer));

            AuditLog start = newestOf(AuditEvent.SESSION_START);
            assertEquals(MUMBAI_LAT, start.lat, 0.0);
            assertEquals(MUMBAI_LNG, start.lng, 0.0);
        }
    }

    @Test
    public void whenLocationIsOffTheLoginStillSucceedsWithoutAPlace() throws Exception {
        try (ActivityScenario<BiometricGateActivity> scenario = launchGate()) {
            scenario.onActivity(activity -> {
                activity.locationHelper = FakeLocationHelper.nowhere(context);
                activity.handleSuccess();
            });
            waitUntilDisplayed(withId(R.id.text_issuer));

            assertEquals(userId, SessionManager.getInstance().getUserId());
            assertNull(newestOf(AuditEvent.SESSION_START).lat);
        }
    }

    @Test
    public void withoutAUserIdTheGateClosesImmediately() throws Exception {
        try (ActivityScenario<BiometricGateActivity> scenario =
                     ActivityScenario.launch(new Intent(context, BiometricGateActivity.class))) {
            waitUntilClosed(scenario);
            assertFalse(SessionManager.getInstance().getUserId() >= 0);
            assertNotEquals(userId, SessionManager.getInstance().getUserId());
        }
    }
}
