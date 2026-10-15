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
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.currentCode;
import static com.secureauth.simulator.ui.TestUtils.enroll;
import static com.secureauth.simulator.ui.TestUtils.waitForScreen;
import static com.secureauth.simulator.ui.TestUtils.waitUntilClosed;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.PasswordHasher;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.BackupCode;
import com.secureauth.simulator.data.BackupCodeDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.DeviceTrust;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.TrustedDevice;
import com.secureauth.simulator.data.TrustedDeviceDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Step 2 of the login when this phone is on the trusted list and has been revoked: the 6-digit
 * code is no longer enough, one unused backup code is asked for as well.
 */
@RunWith(AndroidJUnit4.class)
public class RevokedDeviceLoginTest {

    private static final String CODE_1 = "ABCD-EFGH";
    private static final String CODE_2 = "JKLM-NPQR";
    private static final long SLOW = 60_000; // hashing the typed code against stored codes

    private Context context;
    private UserDao users;
    private TrustedDeviceDao devices;
    private BackupCodeDao codes;
    private AuditLogDao audit;
    private String username;
    private long userId;
    private long phoneId;
    private byte[] secret;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        devices = new TrustedDeviceDao(db);
        codes = new BackupCodeDao(db);
        audit = new AuditLogDao(db);

        username = "x" + Long.toString(System.currentTimeMillis(), 36);
        userId = createUser(users, username, 0, 0);
        secret = TotpEngine.generateSecret();
        enroll(new TotpAccountDao(db), userId, secret);
        for (String plain : new String[]{CODE_1, CODE_2}) {
            PasswordHasher.Result hashed = PasswordHasher.hash(plain.toCharArray());
            BackupCode row = new BackupCode();
            row.userId = userId;
            row.codeHash = hashed.hash;
            row.salt = hashed.salt;
            codes.insert(row);
        }

        phoneId = DeviceTrust.trustCurrentDevice(context, devices, audit, userId);
        setRevoked(true);
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("secureauth_prefs", Context.MODE_PRIVATE)
                .edit().remove("trusted_device_id_" + userId).commit();
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void setRevoked(boolean revoked) {
        TrustedDevice row = devices.getById(phoneId);
        row.revoked = revoked;
        devices.update(row);
    }

    private ActivityScenario<TotpVerifyActivity> launchTotp() {
        TestUtils.passPasswordStep(userId); // step 1 comes first in the real flow
        return ActivityScenario.launch(new Intent(context, TotpVerifyActivity.class)
                .putExtra(TotpVerifyActivity.EXTRA_USER_ID, userId));
    }

    private void typeTotpCode() throws InterruptedException {
        waitUntilDisplayed(allOf(withId(R.id.input_code), isEnabled()));
        onView(withId(R.id.input_code)).perform(replaceText(currentCode(secret)), closeSoftKeyboard());
    }

    private void typeBackupCode(String code) throws InterruptedException {
        waitUntilDisplayed(allOf(withId(R.id.input_backup_code), isEnabled()), SLOW);
        onView(withId(R.id.input_backup_code)).perform(scrollTo(), replaceText(code), closeSoftKeyboard());
        onView(withId(R.id.button_continue)).perform(scrollTo(), click());
    }

    /**
     * Closes step 3 so its fingerprint prompt does not linger into the next test, then waits for
     * the screen under it (which has already finished itself) to be gone as well.
     */
    private static void closeBiometricStep(ActivityScenario<?> scenario) throws InterruptedException {
        android.app.Activity gate = TestUtils.visibleActivity();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(gate::finish);
        waitUntilClosed(scenario);
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

    private int unusedCodes() {
        return codes.listUnused(userId).size();
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    public void aRevokedPhoneAsksForABackupCodeAfterTheCorrectTotpCode() throws Exception {
        try (ActivityScenario<TotpVerifyActivity> scenario = launchTotp()) {
            typeTotpCode();

            waitUntilDisplayed(withText(R.string.recovery_subtitle_revoked), SLOW);
            onView(withId(R.id.radio_group)).check(matches(not(isDisplayed())));
            assertEquals(1, countEvents(AuditEvent.TOTP_OK));
            assertEquals(2, unusedCodes()); // nothing spent yet

            typeBackupCode(CODE_1);
            waitForScreen(BiometricGateActivity.class);
            assertEquals(1, countEvents(AuditEvent.BACKUP_CODE_USED));
            assertEquals(1, unusedCodes());
            closeBiometricStep(scenario);
        }
    }

    @Test
    public void aWrongBackupCodeIsCountedAsAFailureAndDoesNotContinue() throws Exception {
        try (ActivityScenario<TotpVerifyActivity> scenario = launchTotp()) {
            typeTotpCode();
            waitUntilDisplayed(withText(R.string.recovery_subtitle_revoked), SLOW);

            typeBackupCode("AAAA-AAAA");
            waitUntilDisplayed(withText(context.getString(R.string.error_backup_code_wrong, 2)), SLOW);

            assertEquals(1, countEvents(AuditEvent.BACKUP_CODE_FAIL));
            assertEquals(1, users.getById(userId).failedAttempts);
            assertEquals(2, unusedCodes());
        }
    }

    @Test
    public void backingOutOfTheBackupCodeStepEndsTheLogin() throws Exception {
        try (ActivityScenario<TotpVerifyActivity> scenario = launchTotp()) {
            typeTotpCode();
            waitUntilDisplayed(withText(R.string.recovery_subtitle_revoked), SLOW);

            // Back on this screen; finished by hand because a real Back press would also close the
            // whole test task and kill the app under test
            android.app.Activity recovery = TestUtils.visibleActivity();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(recovery::finish);
            waitUntilClosed(scenario);
            assertEquals(0, countEvents(AuditEvent.BACKUP_CODE_USED));
        }
    }

    @Test
    public void usingABackupCodeInsteadOfTotpStillNeedsASecondCode() throws Exception {
        try (ActivityScenario<TotpVerifyActivity> scenario = launchTotp()) {
            waitUntilDisplayed(allOf(withId(R.id.input_code), isEnabled()));
            onView(withId(R.id.button_backup_code)).perform(scrollTo(), click());

            // First code: stands in for the 6-digit code
            typeBackupCode(CODE_1);
            // Second code: the extra one a revoked phone needs
            waitUntilDisplayed(withText(R.string.recovery_subtitle_revoked), SLOW);
            typeBackupCode(CODE_2);

            waitForScreen(BiometricGateActivity.class);
            assertEquals(2, countEvents(AuditEvent.BACKUP_CODE_USED));
            assertEquals(0, unusedCodes());
            closeBiometricStep(scenario);
        }
    }

    @Test
    public void aTrustedPhoneThatIsNotRevokedGoesStraightToTheBiometricStep() throws Exception {
        setRevoked(false);
        try (ActivityScenario<TotpVerifyActivity> scenario = launchTotp()) {
            typeTotpCode();

            waitForScreen(BiometricGateActivity.class);
            assertEquals(2, unusedCodes());
            closeBiometricStep(scenario);
        }
    }

    @Test
    public void aPhoneThatWasNeverTrustedGoesStraightToTheBiometricStep() throws Exception {
        context.getSharedPreferences("secureauth_prefs", Context.MODE_PRIVATE)
                .edit().remove("trusted_device_id_" + userId).commit();
        assertFalse(DeviceTrust.isCurrentDeviceRevoked(context, devices, userId));
        try (ActivityScenario<TotpVerifyActivity> scenario = launchTotp()) {
            typeTotpCode();

            waitForScreen(BiometricGateActivity.class);
            assertEquals(1, countEvents(AuditEvent.TOTP_OK));
            closeBiometricStep(scenario);
        }
    }
}
