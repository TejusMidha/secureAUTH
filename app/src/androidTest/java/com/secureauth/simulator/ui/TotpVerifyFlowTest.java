package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.currentCode;
import static com.secureauth.simulator.ui.TestUtils.enroll;
import static com.secureauth.simulator.ui.TestUtils.invalidCode;
import static com.secureauth.simulator.ui.TestUtils.waitUntilClosed;
import static com.secureauth.simulator.ui.TestUtils.waitForScreen;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
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
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Step 2 of the login handshake, run against the real app database with a throw-away user. */
@RunWith(AndroidJUnit4.class)
public class TotpVerifyFlowTest {

    private Context context;
    private UserDao users;
    private TotpAccountDao accounts;
    private AuditLogDao audit;
    private String username;
    private byte[] secret;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        accounts = new TotpAccountDao(db);
        audit = new AuditLogDao(db);
        username = "v" + Long.toString(System.currentTimeMillis(), 36);
        secret = TotpEngine.generateSecret();
    }

    @After
    public void tearDown() {
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private ActivityScenario<TotpVerifyActivity> launch(long userId) {
        return ActivityScenario.launch(new Intent(context, TotpVerifyActivity.class)
                .putExtra(TotpVerifyActivity.EXTRA_USER_ID, userId));
    }

    private long enrolledUser(int failedAttempts, long lockedUntil) throws Exception {
        long id = createUser(users, username, failedAttempts, lockedUntil);
        enroll(accounts, id, secret);
        return id;
    }

    private static void typeCode(String code) throws InterruptedException {
        // The box starts disabled until the account has loaded
        waitUntilDisplayed(allOf(withId(R.id.input_code), isEnabled()));
        onView(withId(R.id.input_code)).perform(replaceText(code), closeSoftKeyboard());
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

    // ---- tests ------------------------------------------------------------------------------

    @Test
    public void correctCodeSubmitsByItselfAndResetsTheFailureCounter() throws Exception {
        long userId = enrolledUser(2, 0); // two earlier password mistakes
        try (ActivityScenario<TotpVerifyActivity> ignored = launch(userId)) {
            typeCode(currentCode(secret)); // no button: the 6th digit triggers the check

            waitForScreen(BiometricGateActivity.class); // step 3 opens; no session exists yet
            assertEquals(1, countEvents(userId, AuditEvent.TOTP_OK));
            assertEquals(-1L, SessionManager.getInstance().getUserId());
            assertEquals(0, users.getById(userId).failedAttempts);

            // close step 3 so its fingerprint prompt does not linger into the next test
            android.app.Activity gate = TestUtils.visibleActivity();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(gate::finish);
        }
    }

    @Test
    public void wrongCodesCountDownThenLockTheAccount() throws Exception {
        long userId = enrolledUser(0, 0);
        try (ActivityScenario<TotpVerifyActivity> scenario = launch(userId)) {
            typeCode(invalidCode(secret));
            waitUntilDisplayed(withText(containsString("2 attempts left")));

            typeCode(invalidCode(secret));
            waitUntilDisplayed(withText(R.string.totp_wrong_code_last));

            typeCode(invalidCode(secret));
            waitUntilDisplayed(withText(R.string.lock_title));
            onView(withText(containsString("5:00"))).check(matches(isDisplayed()));
            onView(withText(R.string.button_ok)).perform(click());
            waitUntilClosed(scenario); // sent back to Login

            assertEquals(3, countEvents(userId, AuditEvent.TOTP_FAIL));
            assertEquals(1, countEvents(userId, AuditEvent.LOCKED_OUT));
            assertEquals(0, countEvents(userId, AuditEvent.TOTP_OK));
            assertTrue(users.getById(userId).lockedUntil > System.currentTimeMillis());
        }
    }

    @Test
    public void passwordMistakesAndCodeMistakesShareOneCounter() throws Exception {
        long userId = enrolledUser(2, 0); // 2 wrong passwords already
        try (ActivityScenario<TotpVerifyActivity> ignored = launch(userId)) {
            typeCode(invalidCode(secret)); // the 3rd failure overall
            waitUntilDisplayed(withText(R.string.lock_title));
            assertEquals(1, countEvents(userId, AuditEvent.LOCKED_OUT));
        }
    }

    @Test
    public void lockedUserIsStoppedBeforeTypingAnything() throws Exception {
        long userId = enrolledUser(3, System.currentTimeMillis() + 120_000L);
        try (ActivityScenario<TotpVerifyActivity> scenario = launch(userId)) {
            waitUntilDisplayed(withText(R.string.lock_title));
            onView(withText(R.string.button_ok)).perform(click());
            waitUntilClosed(scenario);
            assertEquals(0, countEvents(userId, AuditEvent.TOTP_OK));
        }
    }

    @Test
    public void incompleteCodeIsNotSubmitted() throws Exception {
        long userId = enrolledUser(0, 0);
        try (ActivityScenario<TotpVerifyActivity> ignored = launch(userId)) {
            typeCode("12345");
            Thread.sleep(800); // give a wrongly triggered check time to write its audit row
            assertEquals(0, countEvents(userId, AuditEvent.TOTP_FAIL));
            assertEquals(0, users.getById(userId).failedAttempts);
        }
    }

    @Test
    public void userWithoutAuthenticatorIsSentToEnrollment() throws Exception {
        long userId = createUser(users, username, 0, 0); // never enrolled
        try (ActivityScenario<TotpVerifyActivity> ignored = launch(userId)) {
            waitUntilDisplayed(withId(R.id.button_scanned)); // the Enroll screen
        }
    }

    @Test
    public void backupCodeLinkIsAvailable() throws Exception {
        long userId = enrolledUser(0, 0);
        try (ActivityScenario<TotpVerifyActivity> ignored = launch(userId)) {
            onView(withText(R.string.link_backup_code)).check(matches(isDisplayed()));
        }
    }
}
