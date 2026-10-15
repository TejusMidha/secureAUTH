package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.currentCode;
import static com.secureauth.simulator.ui.TestUtils.enroll;
import static com.secureauth.simulator.ui.TestUtils.startSession;
import static com.secureauth.simulator.ui.TestUtils.waitForScreen;
import static com.secureauth.simulator.ui.TestUtils.waitUntilClosed;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
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
import com.secureauth.simulator.data.LoginHandshake;
import com.secureauth.simulator.data.SessionDao;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * "Any failure stops the flow": the screens in the middle of the login must refuse to open unless
 * the earlier steps really passed, even if someone starts them with the right user id in the
 * Intent. Every test here tries to skip a step and checks that Login is what shows up instead.
 */
@RunWith(AndroidJUnit4.class)
public class HandshakeGuardTest {

    private static final long FIVE_MINUTES = 5 * 60 * 1000L;

    private Context context;
    private UserDao users;
    private AuditLogDao audit;
    private SessionDao sessions;
    private String username;
    private long userId;
    private byte[] secret;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        audit = new AuditLogDao(db);
        sessions = new SessionDao(db);
        username = "h" + Long.toString(System.currentTimeMillis(), 36);
        userId = createUser(users, username, 0, 0);
        secret = TotpEngine.generateSecret();
        enroll(new TotpAccountDao(db), userId, secret);
        LoginHandshake.getInstance().clear();
        SessionManager.getInstance().clear();
    }

    @After
    public void tearDown() {
        LoginHandshake.getInstance().clear();
        SessionManager.getInstance().clear();
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private <T extends Activity> ActivityScenario<T> start(Class<T> screen, String extraName) {
        return ActivityScenario.launch(new Intent(context, screen).putExtra(extraName, userId));
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

    private void assertNothingWasGranted() {
        assertEquals("no session may exist", -1L, SessionManager.getInstance().getUserId());
        assertTrue(sessions.listForUser(userId).isEmpty());
        assertEquals(0, countEvents(AuditEvent.SESSION_START));
        assertEquals(0, countEvents(AuditEvent.TOTP_OK));
    }

    // ---- skipping step 1 (the password) -----------------------------------------------------

    @Test
    public void theCodeStepRefusesToOpenWithoutAPassword() throws Exception {
        try (ActivityScenario<TotpVerifyActivity> scenario = start(TotpVerifyActivity.class, TotpVerifyActivity.EXTRA_USER_ID)) {
            waitForScreen(LoginActivity.class);
            waitUntilClosed(scenario);
        }
        assertNothingWasGranted();
    }

    @Test
    public void theBackupCodeScreenRefusesToOpenWithoutAPassword() throws Exception {
        try (ActivityScenario<BackupRecoveryActivity> scenario =
                     start(BackupRecoveryActivity.class, BackupRecoveryActivity.EXTRA_USER_ID)) {
            waitForScreen(LoginActivity.class);
            waitUntilClosed(scenario);
        }
        assertNothingWasGranted();
    }

    @Test
    public void enrollmentRefusesToOpenWithoutRegisteringOrAPasswordOrASession() throws Exception {
        try (ActivityScenario<EnrollActivity> scenario = start(EnrollActivity.class, EnrollActivity.EXTRA_USER_ID)) {
            waitForScreen(LoginActivity.class);
            waitUntilClosed(scenario);
        }
        assertEquals(0, countEvents(AuditEvent.TOTP_ENROLLED));
    }

    // ---- skipping step 2 (the code) ---------------------------------------------------------

    @Test
    public void theFingerprintStepRefusesToOpenWithoutTheCodeStep() throws Exception {
        // Step 1 passed, step 2 skipped: this is the bypass that matters
        LoginHandshake.getInstance().passwordAccepted(userId, System.currentTimeMillis());
        try (ActivityScenario<BiometricGateActivity> scenario =
                     start(BiometricGateActivity.class, BiometricGateActivity.EXTRA_USER_ID)) {
            waitForScreen(LoginActivity.class);
            waitUntilClosed(scenario);
        }
        assertNothingWasGranted();
    }

    @Test
    public void theFingerprintStepRefusesToOpenWithNothingAtAll() throws Exception {
        try (ActivityScenario<BiometricGateActivity> scenario =
                     start(BiometricGateActivity.class, BiometricGateActivity.EXTRA_USER_ID)) {
            waitForScreen(LoginActivity.class);
            waitUntilClosed(scenario);
        }
        assertNothingWasGranted();
    }

    // ---- someone else's steps, old steps ----------------------------------------------------

    @Test
    public void anotherUsersPasswordStepDoesNotOpenThisUsersCodeStep() throws Exception {
        long other = createUser(users, username + "x", 0, 0);
        try {
            LoginHandshake.getInstance().passwordAccepted(other, System.currentTimeMillis());
            try (ActivityScenario<TotpVerifyActivity> scenario =
                         start(TotpVerifyActivity.class, TotpVerifyActivity.EXTRA_USER_ID)) {
                waitForScreen(LoginActivity.class);
                waitUntilClosed(scenario);
            }
            assertNothingWasGranted();
        } finally {
            users.delete(other);
        }
    }

    @Test
    public void aPasswordStepOlderThanTenMinutesIsNoLongerEnough() throws Exception {
        long stale = System.currentTimeMillis() - LoginHandshake.MAX_AGE_MS - 60_000L;
        LoginHandshake.getInstance().passwordAccepted(userId, stale);
        try (ActivityScenario<TotpVerifyActivity> scenario =
                     start(TotpVerifyActivity.class, TotpVerifyActivity.EXTRA_USER_ID)) {
            waitForScreen(LoginActivity.class);
            waitUntilClosed(scenario);
        }
        assertNothingWasGranted();
    }

    // ---- the real order works ---------------------------------------------------------------

    @Test
    public void afterAPasswordTheCodeStepOpensAndAGoodCodeUnlocksTheFingerprintStep() throws Exception {
        LoginHandshake.getInstance().passwordAccepted(userId, System.currentTimeMillis());
        try (ActivityScenario<TotpVerifyActivity> scenario =
                     start(TotpVerifyActivity.class, TotpVerifyActivity.EXTRA_USER_ID)) {
            waitUntilDisplayed(allOf(withId(R.id.input_code), isEnabled()));
            onView(withId(R.id.input_code)).perform(replaceText(currentCode(secret)), closeSoftKeyboard());

            waitForScreen(BiometricGateActivity.class);
            assertTrue(LoginHandshake.getInstance().allowsBiometricStep(userId, System.currentTimeMillis()));
            assertEquals("still no session before the fingerprint", -1L, SessionManager.getInstance().getUserId());

            Activity gate = TestUtils.visibleActivity();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(gate::finish);
            waitUntilClosed(scenario);
        }
    }

    @Test
    public void aWrongCodeLeavesTheFingerprintStepClosed() throws Exception {
        LoginHandshake.getInstance().passwordAccepted(userId, System.currentTimeMillis());
        try (ActivityScenario<TotpVerifyActivity> ignored =
                     start(TotpVerifyActivity.class, TotpVerifyActivity.EXTRA_USER_ID)) {
            waitUntilDisplayed(allOf(withId(R.id.input_code), isEnabled()));
            onView(withId(R.id.input_code)).perform(replaceText(TestUtils.invalidCode(secret)), closeSoftKeyboard());

            waitUntilDisplayed(allOf(withId(R.id.input_code), isEnabled()));
            assertFalse(LoginHandshake.getInstance().allowsBiometricStep(userId, System.currentTimeMillis()));
        }
    }

    // ---- enrollment with a session ----------------------------------------------------------

    @Test
    public void aLoggedInUserMayEnrollAgainForThemselvesButNotForSomeoneElse() throws Exception {
        long other = createUser(users, username + "y", 0, 0);
        try {
            startSession(context, userId, FIVE_MINUTES);

            try (ActivityScenario<EnrollActivity> mine = start(EnrollActivity.class, EnrollActivity.EXTRA_USER_ID)) {
                waitUntilDisplayed(withId(R.id.button_scanned));
            }
            try (ActivityScenario<EnrollActivity> theirs = ActivityScenario.launch(
                    new Intent(context, EnrollActivity.class).putExtra(EnrollActivity.EXTRA_USER_ID, other))) {
                waitForScreen(LoginActivity.class);
                waitUntilClosed(theirs);
            }
        } finally {
            SessionManager.getInstance().clear();
            users.delete(other);
        }
    }

    // ---- forgetting the steps ---------------------------------------------------------------

    @Test
    public void returningToTheLoginScreenForgetsTheStepsAlreadyPassed() throws Exception {
        LoginHandshake handshake = LoginHandshake.getInstance();
        handshake.passwordAccepted(userId, System.currentTimeMillis());
        handshake.codeAccepted(userId, System.currentTimeMillis());

        try (ActivityScenario<LoginActivity> ignored = ActivityScenario.launch(LoginActivity.class)) {
            waitUntilDisplayed(withId(R.id.button_login));
            assertFalse(handshake.allowsBiometricStep(userId, System.currentTimeMillis()));
            assertFalse(handshake.allowsCodeStep(userId, System.currentTimeMillis()));
        }
    }

    @Test
    public void loggingOutForgetsTheStepsToo() throws Exception {
        startSession(context, userId, FIVE_MINUTES);
        LoginHandshake.getInstance().passwordAccepted(userId, System.currentTimeMillis());
        try (ActivityScenario<DashboardActivity> scenario = ActivityScenario.launch(
                new Intent(context, DashboardActivity.class))) {
            waitUntilDisplayed(withId(R.id.text_account));
            androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu(context);
            onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.menu_logout)).perform(click());
            waitForScreen(LoginActivity.class);
            assertFalse(LoginHandshake.getInstance().allowsCodeStep(userId, System.currentTimeMillis()));
            waitUntilClosed(scenario);
        }
    }

    @Test
    public void everyProtectedScreenSendsYouToLoginWhenThereIsNoSession() throws Exception {
        Class<?>[] protectedScreens = {DashboardActivity.class, BackupCodesActivity.class,
                LoginHistoryActivity.class, LoginMapActivity.class, TrustedDevicesActivity.class,
                ProfileActivity.class, SettingsActivity.class};
        for (Class<?> screen : protectedScreens) {
            try (ActivityScenario<?> scenario = ActivityScenario.launch(new Intent(context, screen))) {
                waitForScreen(LoginActivity.class);
                waitUntilClosed(scenario);
            }
        }
        assertNothingWasGranted();
    }
}
