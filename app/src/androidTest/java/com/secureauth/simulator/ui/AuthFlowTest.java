package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.textOf;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.Base32;
import com.secureauth.simulator.crypto.PasswordHasher;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * End-to-end tests of Register and Login on the emulator. They use the real app database,
 * so every test uses a fresh username and removes it afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class AuthFlowTest {

    private static final String PASSWORD = "Correct-Horse-9";

    private Context context;
    private java.util.Map<String, Object> savedSettings;
    private UserDao users;
    private AuditLogDao audit;
    private String username;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // These tests are about the password and code steps, not about location. Saying "the
        // explanation was already shown" keeps the first-login location dialog (which depends on
        // what the phone has granted) from covering the screens under test.
        savedSettings = TestUtils.snapshotSettings(context);
        new com.secureauth.simulator.location.LocationHelper(context).markAsked();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        audit = new AuditLogDao(db);
        username = "t" + Long.toString(System.currentTimeMillis(), 36); // unique, 8-10 chars
    }

    @After
    public void tearDown() {
        TestUtils.restoreSettings(context, savedSettings);
        SessionManager.getInstance().clear(); // a test may have logged in all the way
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id); // ON DELETE CASCADE also removes the audit rows
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private long createUserInDb() {
        PasswordHasher.Result hashed = PasswordHasher.hash(PASSWORD.toCharArray());
        User u = new User();
        u.username = username;
        u.passwordHash = hashed.hash;
        u.salt = hashed.salt;
        u.createdAt = System.currentTimeMillis();
        return users.insert(u);
    }

    /**
     * On a phone with a screen lock the real system prompt opens over the gate and keeps the window
     * focus. Success was reported by hand above, so the prompt is dismissed (reflection: the field
     * is private; the screen has already marked itself done, so it ignores the cancel).
     */
    private static void closeSystemPrompt(BiometricGateActivity gate) {
        try {
            java.lang.reflect.Field field = BiometricGateActivity.class.getDeclaredField("prompt");
            field.setAccessible(true);
            ((androidx.biometric.BiometricPrompt) field.get(gate)).cancelAuthentication();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void type(int viewId, String text) {
        onView(withId(viewId)).perform(scrollTo(), replaceText(text), closeSoftKeyboard());
    }

    private static void tap(int viewId) {
        onView(withId(viewId)).perform(scrollTo(), click());
    }

    private int countEvents(long userId, String event) {
        int count = 0;
        for (AuditLog row : audit.listForUser(userId)) {
            if (event.equals(row.event)) {
                count++;
            }
        }
        return count;
    }

    private void failLoginOnce(String wrongPassword) throws InterruptedException {
        type(R.id.input_password, wrongPassword);
        tap(R.id.button_login);
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    public void registerThenLoginReachesTheTotpScreen() throws Exception {
        try (ActivityScenario<LoginActivity> ignored = ActivityScenario.launch(LoginActivity.class)) {
            tap(R.id.button_create_account);

            type(R.id.input_username, username);
            type(R.id.input_password, PASSWORD);
            type(R.id.input_confirm, PASSWORD);
            tap(R.id.button_register);

            // Registration leads straight to enrollment: read the secret on screen, play the
            // part of the authenticator app, and type the code it would show
            waitUntilDisplayed(withId(R.id.button_scanned));
            byte[] secret = Base32.decode(textOf(R.id.text_secret));
            tap(R.id.button_scanned);
            type(R.id.input_code, TotpEngine.generate(secret, System.currentTimeMillis(), 6, 30,
                    TotpEngine.ALGO_SHA1));
            tap(R.id.button_confirm);

            // Back on Login with the new username filled in
            waitUntilDisplayed(withId(R.id.button_login));
            onView(withId(R.id.input_username)).check(matches(withText(username)));

            User saved = users.findByUsername(username);
            assertNotNull(saved);
            assertNotEquals(PASSWORD, saved.passwordHash);
            assertEquals(1, countEvents(saved.id, AuditEvent.REGISTER));
            assertEquals(1, countEvents(saved.id, AuditEvent.TOTP_ENROLLED));

            type(R.id.input_password, PASSWORD);
            tap(R.id.button_login);
            waitUntilDisplayed(withText(R.string.totp_verify_title));
            assertEquals(1, countEvents(saved.id, AuditEvent.PASSWORD_OK));

            // Step 2: the code the authenticator app shows right now (the box submits by itself)
            waitUntilDisplayed(allOf(withId(R.id.input_code), isEnabled()));
            type(R.id.input_code, TotpEngine.generate(secret, System.currentTimeMillis(), 6, 30,
                    TotpEngine.ALGO_SHA1));
            // Step 3 opens. The system fingerprint prompt cannot be driven from here, so we
            // stand in for "the prompt reported success" by calling the screen's own handler
            TestUtils.waitForScreen(BiometricGateActivity.class);
            assertEquals(1, countEvents(saved.id, AuditEvent.TOTP_OK));
            assertEquals(-1L, SessionManager.getInstance().getUserId()); // no session before step 3

            BiometricGateActivity gate = (BiometricGateActivity) TestUtils.visibleActivity();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                gate.handleSuccess();
                closeSystemPrompt(gate);
            });

            waitUntilDisplayed(withId(R.id.text_issuer)); // the Dashboard
            assertEquals(saved.id, SessionManager.getInstance().getUserId());
            assertEquals(1, countEvents(saved.id, AuditEvent.BIOMETRIC_OK));
            assertEquals(1, countEvents(saved.id, AuditEvent.SESSION_START));
        }
    }

    @Test
    public void correctPasswordDoesNotResetTheFailureCounter() throws Exception {
        long userId = createUserInDb();
        TestUtils.enroll(new TotpAccountDao(DbHelper.getInstance(
                InstrumentationRegistry.getInstrumentation().getTargetContext())),
                userId, TotpEngine.generateSecret());
        users.incrementFailedAttempts(userId);
        users.incrementFailedAttempts(userId);

        try (ActivityScenario<LoginActivity> ignored = ActivityScenario.launch(LoginActivity.class)) {
            type(R.id.input_username, username);
            type(R.id.input_password, PASSWORD);
            tap(R.id.button_login);
            waitUntilDisplayed(withText(R.string.totp_verify_title));

            // Only a correct TOTP code may clear the counter, otherwise a password thief
            // could restart the 3 guesses at the 6-digit code with every fresh login
            assertEquals(2, users.getById(userId).failedAttempts);
        }
    }

    @Test
    public void threeWrongPasswordsLockTheAccountEvenForTheRightPassword() throws Exception {
        long userId = createUserInDb();

        try (ActivityScenario<LoginActivity> ignored = ActivityScenario.launch(LoginActivity.class)) {
            type(R.id.input_username, username);

            failLoginOnce("wrong-password-1");
            waitUntilDisplayed(withText(R.string.login_failed));
            failLoginOnce("wrong-password-2");
            waitUntilDisplayed(withText(R.string.login_failed));
            failLoginOnce("wrong-password-3");

            // 3rd strike: lock dialog with the 5 minute wait
            waitUntilDisplayed(withText(R.string.lock_title));
            onView(withText(containsString("5:00"))).check(matches(isDisplayed()));
            onView(withText(R.string.button_ok)).perform(click());

            assertEquals(3, countEvents(userId, AuditEvent.PASSWORD_FAIL));
            assertEquals(1, countEvents(userId, AuditEvent.LOCKED_OUT));
            assertTrue(users.getById(userId).lockedUntil > System.currentTimeMillis());

            // Even the correct password is refused while locked
            type(R.id.input_password, PASSWORD);
            tap(R.id.button_login);
            waitUntilDisplayed(withText(R.string.lock_title));
            onView(withText(R.string.button_ok)).perform(click());
            assertEquals(0, countEvents(userId, AuditEvent.PASSWORD_OK));
        }
    }

    @Test
    public void unknownUsernameGetsTheSameGenericError() throws Exception {
        try (ActivityScenario<LoginActivity> ignored = ActivityScenario.launch(LoginActivity.class)) {
            type(R.id.input_username, username); // never registered
            failLoginOnce("whatever-password");
            waitUntilDisplayed(withText(R.string.login_failed));
        }
    }

    @Test
    public void emptyLoginFieldsShowInlineErrors() throws Exception {
        try (ActivityScenario<LoginActivity> ignored = ActivityScenario.launch(LoginActivity.class)) {
            tap(R.id.button_login);
            onView(withText(R.string.error_username_required)).check(matches(isDisplayed()));
            onView(withText(R.string.error_password_required)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void registerShowsValidationErrors() throws Exception {
        try (ActivityScenario<RegisterActivity> ignored = ActivityScenario.launch(RegisterActivity.class)) {
            type(R.id.input_username, "ab");
            type(R.id.input_password, "short");
            tap(R.id.button_register);
            onView(withText(R.string.error_username_length)).check(matches(isDisplayed()));
            onView(withText(R.string.error_password_short)).check(matches(isDisplayed()));

            type(R.id.input_username, username);
            type(R.id.input_password, PASSWORD);
            type(R.id.input_confirm, PASSWORD + "x");
            tap(R.id.button_register);
            onView(withText(R.string.error_confirm_mismatch)).check(matches(isDisplayed()));
            assertFalse(users.findByUsername(username) != null);
        }
    }

    @Test
    public void registerRejectsAUsernameThatAlreadyExists() throws Exception {
        createUserInDb();
        try (ActivityScenario<RegisterActivity> ignored = ActivityScenario.launch(RegisterActivity.class)) {
            type(R.id.input_username, username.toUpperCase()); // case must not matter
            type(R.id.input_password, PASSWORD);
            type(R.id.input_confirm, PASSWORD);
            tap(R.id.button_register);
            waitUntilDisplayed(withText(R.string.error_username_taken));

            List<User> all = users.list();
            int matching = 0;
            for (User u : all) {
                if (u.username.equals(username)) {
                    matching++;
                }
            }
            assertEquals(1, matching);
        }
    }

    @Test
    public void strengthHintAppearsWhileTyping() throws Exception {
        try (ActivityScenario<RegisterActivity> ignored = ActivityScenario.launch(RegisterActivity.class)) {
            type(R.id.input_password, "abc");
            onView(withText(R.string.strength_weak)).check(matches(isDisplayed()));
            type(R.id.input_password, "Abcdefg1");
            onView(withText(R.string.strength_strong)).check(matches(isDisplayed()));
        }
    }
}
