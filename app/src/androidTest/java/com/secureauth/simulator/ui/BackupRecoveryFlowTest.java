package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.currentCode;
import static com.secureauth.simulator.ui.TestUtils.enroll;
import static com.secureauth.simulator.ui.TestUtils.textOf;
import static com.secureauth.simulator.ui.TestUtils.waitForScreen;
import static com.secureauth.simulator.ui.TestUtils.waitUntilClosed;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoActivityResumedException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.Base32;
import com.secureauth.simulator.crypto.KeystoreHelper;
import com.secureauth.simulator.crypto.PasswordHasher;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.BackupCode;
import com.secureauth.simulator.data.BackupCodeDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.TotpAccount;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** Recovery with a backup code, run with a user who owns three known codes. */
@RunWith(AndroidJUnit4.class)
public class BackupRecoveryFlowTest {

    private static final String CODE_1 = "ABCD-EFGH";
    private static final String CODE_2 = "JKLM-NPQR";
    private static final String CODE_3 = "STUV-WXYZ";

    private Context context;
    private UserDao users;
    private BackupCodeDao codes;
    private TotpAccountDao accounts;
    private AuditLogDao audit;
    private String username;
    private long userId;
    private byte[] oldSecret;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        codes = new BackupCodeDao(db);
        accounts = new TotpAccountDao(db);
        audit = new AuditLogDao(db);

        username = "r" + Long.toString(System.currentTimeMillis(), 36);
        userId = createUser(users, username, 0, 0);
        oldSecret = TotpEngine.generateSecret();
        enroll(accounts, userId, oldSecret);
        for (String plain : new String[]{CODE_1, CODE_2, CODE_3}) {
            PasswordHasher.Result hashed = PasswordHasher.hash(plain.toCharArray());
            BackupCode row = new BackupCode();
            row.userId = userId;
            row.codeHash = hashed.hash;
            row.salt = hashed.salt;
            codes.insert(row);
        }
    }

    @After
    public void tearDown() {
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Launched "for result", the way TotpVerifyActivity opens it, so the result code can be read. */
    private ActivityScenario<BackupRecoveryActivity> launchRecovery() {
        return ActivityScenario.launchActivityForResult(new Intent(context, BackupRecoveryActivity.class)
                .putExtra(BackupRecoveryActivity.EXTRA_USER_ID, userId));
    }

    /** Checking a code hashes it against every unused code, which is slow on a busy emulator. */
    private static void waitSlow(org.hamcrest.Matcher<android.view.View> matcher) throws InterruptedException {
        TestUtils.waitUntilDisplayed(matcher, 60_000);
    }

    private static void waitForInput() throws InterruptedException {
        waitSlow(allOf(withId(R.id.input_backup_code), isEnabled()));
    }

    private static void typeCodeAndContinue(String code) {
        onView(withId(R.id.input_backup_code)).perform(scrollTo(), replaceText(code), closeSoftKeyboard());
        onView(withId(R.id.button_continue)).perform(scrollTo(), click());
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

    private void waitForEvent(String event, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (countEvents(event) != expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(event + " count is " + countEvents(event) + ", expected " + expected);
            }
            Thread.sleep(100);
        }
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    public void aValidCodeWorksOnceAndTheScreenReportsSuccess() throws Exception {
        users.incrementFailedAttempts(userId);
        users.incrementFailedAttempts(userId); // earlier slips are forgiven by a correct step
        try (ActivityScenario<BackupRecoveryActivity> scenario = launchRecovery()) {
            waitForInput();
            typeCodeAndContinue("abcd efgh"); // lower case and a space are fine

            waitUntilClosed(scenario);
            assertEquals(Activity.RESULT_OK, scenario.getResult().getResultCode());
            assertEquals(2, codes.listUnused(userId).size());
            assertEquals(1, countEvents(AuditEvent.BACKUP_CODE_USED));
            assertEquals(0, users.getById(userId).failedAttempts);
        }
    }

    @Test
    public void aSpentCodeIsRejectedTheSecondTime() throws Exception {
        try (ActivityScenario<BackupRecoveryActivity> first = launchRecovery()) {
            waitForInput();
            typeCodeAndContinue(CODE_1);
            waitUntilClosed(first);
        }

        try (ActivityScenario<BackupRecoveryActivity> second = launchRecovery()) {
            waitForInput();
            typeCodeAndContinue(CODE_1); // same code again
            waitSlow(withText(containsString("2 attempts left")));
            assertEquals(1, countEvents(AuditEvent.BACKUP_CODE_FAIL));
            assertEquals(1, users.getById(userId).failedAttempts);
            assertEquals(2, codes.listUnused(userId).size()); // nothing was spent by the retry
        }
    }

    @Test
    public void threeWrongCodesLockTheAccount() throws Exception {
        try (ActivityScenario<BackupRecoveryActivity> scenario = launchRecovery()) {
            waitForInput();
            typeCodeAndContinue("ZZZZ-ZZZZ");
            waitSlow(withText(containsString("2 attempts left")));
            typeCodeAndContinue("YYYY-YYYY");
            waitSlow(withText(R.string.error_backup_code_wrong_last));
            typeCodeAndContinue("XXXX-XXXX");

            waitSlow(withText(R.string.lock_title));
            onView(withText(containsString("5:00"))).check(matches(isDisplayed()));
            onView(withText(R.string.button_ok)).perform(click());

            waitUntilClosed(scenario);
            assertEquals(BackupRecoveryActivity.RESULT_LOCKED, scenario.getResult().getResultCode());
            assertEquals(3, countEvents(AuditEvent.BACKUP_CODE_FAIL));
            assertEquals(1, countEvents(AuditEvent.LOCKED_OUT));
            assertEquals(3, codes.listUnused(userId).size());
        }
    }

    @Test
    public void wrongCodesShareTheCounterWithPasswordMistakes() throws Exception {
        users.incrementFailedAttempts(userId);
        users.incrementFailedAttempts(userId); // 2 earlier failures
        try (ActivityScenario<BackupRecoveryActivity> ignored = launchRecovery()) {
            waitForInput();
            typeCodeAndContinue("ZZZZ-ZZZZ"); // the 3rd failure overall
            waitSlow(withText(R.string.lock_title));
            assertEquals(1, countEvents(AuditEvent.LOCKED_OUT));
        }
    }

    @Test
    public void anObviouslyMalformedCodeIsNotCountedAsAFailure() throws Exception {
        try (ActivityScenario<BackupRecoveryActivity> ignored = launchRecovery()) {
            waitForInput();
            typeCodeAndContinue("12");
            onView(withText(R.string.error_backup_code_format)).check(matches(isDisplayed()));
            Thread.sleep(500);
            assertEquals(0, countEvents(AuditEvent.BACKUP_CODE_FAIL));
            assertEquals(0, users.getById(userId).failedAttempts);
        }
    }

    @Test
    public void anAccountWithoutUnusedCodesCannotUseThisPath() throws Exception {
        for (BackupCode c : codes.listForUser(userId)) {
            codes.markUsed(c.id);
        }
        try (ActivityScenario<BackupRecoveryActivity> ignored = launchRecovery()) {
            waitSlow(withText(R.string.recovery_no_codes));
            onView(withId(R.id.input_backup_code)).check(matches(not(isEnabled())));
            onView(withId(R.id.button_continue)).check(matches(not(isEnabled())));
        }
    }

    @Test
    public void aLockedAccountIsStoppedImmediately() throws Exception {
        users.setLockedUntil(userId, System.currentTimeMillis() + 120_000L);
        try (ActivityScenario<BackupRecoveryActivity> scenario = launchRecovery()) {
            waitSlow(withText(R.string.lock_title));
            onView(withText(R.string.button_ok)).perform(click());
            waitUntilClosed(scenario);
            assertEquals(BackupRecoveryActivity.RESULT_LOCKED, scenario.getResult().getResultCode());
            assertEquals(2 + 1, codes.listUnused(userId).size()); // no code was touched
        }
    }

    @Test
    public void reEnrollNeedsACodeThenReplacesTheAuthenticator() throws Exception {
        try (ActivityScenario<BackupRecoveryActivity> scenario = launchRecovery()) {
            waitForInput();
            onView(withId(R.id.radio_reenroll)).perform(scrollTo(), click());
            typeCodeAndContinue(CODE_2);

            // the code was accepted and spent; now the Enroll screen opens
            waitSlow(withId(R.id.button_scanned));
            assertEquals(2, codes.listUnused(userId).size());
            assertEquals(1, countEvents(AuditEvent.BACKUP_CODE_USED));
            TotpAccount stillOld = accounts.listForUser(userId).get(0);
            assertArrayEquals(oldSecret, KeystoreHelper.decrypt(stillOld.secretEnc, stillOld.iv)); // not replaced yet

            // set up the new authenticator exactly like the Enroll tests do
            byte[] newSecret = Base32.decode(textOf(R.id.text_secret));
            onView(withId(R.id.button_scanned)).perform(scrollTo(), click());
            onView(withId(R.id.input_code)).perform(scrollTo(), replaceText(currentCode(newSecret)), closeSoftKeyboard());
            onView(withId(R.id.button_confirm)).perform(scrollTo(), click());

            waitUntilClosed(scenario);
            assertEquals(Activity.RESULT_OK, scenario.getResult().getResultCode());
            List<TotpAccount> now = accounts.listForUser(userId);
            assertEquals(1, now.size());
            assertArrayEquals(newSecret, KeystoreHelper.decrypt(now.get(0).secretEnc, now.get(0).iv));
        }
    }

    @Test
    public void backingOutOfEnrollmentLeavesTheOldAuthenticatorAndCancels() throws Exception {
        try (ActivityScenario<BackupRecoveryActivity> scenario = launchRecovery()) {
            waitForInput();
            onView(withId(R.id.radio_reenroll)).perform(scrollTo(), click());
            typeCodeAndContinue(CODE_3);
            waitSlow(withId(R.id.button_scanned));

            try {
                pressBack(); // leave Enroll without confirming a code
            } catch (NoActivityResumedException expected) {
                // Recovery closes right behind Enroll, leaving no screen at all: Espresso reports
                // that as an exception, but it is exactly what we expect here
            }

            waitUntilClosed(scenario);
            assertEquals(Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
            TotpAccount old = accounts.listForUser(userId).get(0);
            assertArrayEquals(oldSecret, KeystoreHelper.decrypt(old.secretEnc, old.iv));
        }
    }

    @Test
    public void theLinkOnTheCodeScreenLeadsThroughRecoveryToTheBiometricStep() throws Exception {
        ActivityScenario<TotpVerifyActivity> scenario = ActivityScenario.launch(
                new Intent(context, TotpVerifyActivity.class)
                        .putExtra(TotpVerifyActivity.EXTRA_USER_ID, userId));
        waitSlow(allOf(withId(R.id.input_code), isEnabled()));
        onView(withText(R.string.link_backup_code)).perform(scrollTo(), click());

        waitSlow(withId(R.id.radio_group));
        waitForInput();
        typeCodeAndContinue(CODE_1);

        waitForScreen(BiometricGateActivity.class); // the code stood in for the 6-digit step
        waitForEvent(AuditEvent.BACKUP_CODE_USED, 1);
        assertEquals(0, countEvents(AuditEvent.TOTP_OK)); // and it was not recorded as a TOTP success

        Activity gate = TestUtils.visibleActivity();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(gate::finish);
        // TotpVerify finished itself when it moved on, so there is nothing left to close
        waitUntilClosed(scenario);
    }

    @Test
    public void bothOptionsAreOfferedAndLoginIsTheDefault() throws Exception {
        try (ActivityScenario<BackupRecoveryActivity> ignored = launchRecovery()) {
            onView(withText(R.string.recovery_option_login)).check(matches(isDisplayed()));
            onView(withText(R.string.recovery_option_reenroll)).check(matches(isDisplayed()));
            onView(withId(R.id.radio_backup_login)).check(matches(
                    androidx.test.espresso.matcher.ViewMatchers.isChecked()));
            assertTrue(true);
        }
    }
}
