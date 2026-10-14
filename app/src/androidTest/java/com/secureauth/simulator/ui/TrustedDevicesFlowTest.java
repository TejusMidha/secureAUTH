package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.startSession;
import static com.secureauth.simulator.ui.TestUtils.waitForScreen;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.BackupCode;
import com.secureauth.simulator.data.BackupCodeDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.DeviceTrust;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TrustedDevice;
import com.secureauth.simulator.data.TrustedDeviceDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.BooleanSupplier;

/**
 * The trusted devices screen against the real database, with a throw-away user who has two
 * devices: this phone (row 0) and "Old tablet" (row 1).
 */
@RunWith(AndroidJUnit4.class)
public class TrustedDevicesFlowTest {

    private static final long FIVE_MINUTES = 5 * 60 * 1000L;
    private static final String OLD_TABLET = "Old tablet";

    private Context context;
    private UserDao users;
    private TrustedDeviceDao devices;
    private AuditLogDao audit;
    private BackupCodeDao codes;
    private String username;
    private long userId;
    private long thisPhoneId;
    private long tabletId;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        devices = new TrustedDeviceDao(db);
        audit = new AuditLogDao(db);
        codes = new BackupCodeDao(db);

        username = "d" + Long.toString(System.currentTimeMillis(), 36);
        userId = createUser(users, username, 0, 0);
        thisPhoneId = DeviceTrust.trustCurrentDevice(context, devices, audit, userId);

        TrustedDevice tablet = new TrustedDevice();
        tablet.userId = userId;
        tablet.deviceName = OLD_TABLET;
        tablet.addedAt = System.currentTimeMillis();
        tabletId = devices.insert(tablet);

        startSession(context, userId, FIVE_MINUTES);
    }

    @After
    public void tearDown() {
        SessionManager.getInstance().clear();
        context.getSharedPreferences("secureauth_prefs", Context.MODE_PRIVATE)
                .edit().remove("trusted_device_id_" + userId).commit();
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private ActivityScenario<TrustedDevicesActivity> launch() {
        return ActivityScenario.launch(new Intent(context, TrustedDevicesActivity.class));
    }

    private static void longPressRow(int position) throws InterruptedException {
        waitUntilDisplayed(withId(R.id.list_devices));
        onData(anything()).inAdapterView(withId(R.id.list_devices)).atPosition(position)
                .perform(longClick());
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

    /** Database changes are made on a background thread, so wait until the condition holds. */
    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("The change never reached the database");
            }
            Thread.sleep(100);
        }
    }

    private void addUnusedBackupCode() {
        BackupCode code = new BackupCode();
        code.userId = userId;
        code.codeHash = "h";
        code.salt = "s";
        codes.insert(code);
    }

    // ---- showing the list -------------------------------------------------------------------

    @Test
    public void listShowsBothDevicesAndMarksThisPhone() throws Exception {
        try (ActivityScenario<TrustedDevicesActivity> ignored = launch()) {
            waitUntilDisplayed(withText(DeviceTrust.currentDeviceName()));
            onView(withText(OLD_TABLET)).check(matches(isDisplayed()));
            onView(allOf(withId(R.id.text_state), withText(containsString(
                    context.getString(R.string.device_this_device))))).check(matches(isDisplayed()));
        }
    }

    @Test
    public void emptyListShowsTheHint() throws Exception {
        devices.delete(thisPhoneId);
        devices.delete(tabletId);
        try (ActivityScenario<TrustedDevicesActivity> ignored = launch()) {
            waitUntilDisplayed(withText(R.string.trusted_devices_empty));
        }
    }

    @Test
    public void withoutASessionTheScreenSendsYouToLogin() throws Exception {
        SessionManager.getInstance().clear();
        try (ActivityScenario<TrustedDevicesActivity> ignored = launch()) {
            waitForScreen(LoginActivity.class);
        }
    }

    // ---- rename -----------------------------------------------------------------------------

    @Test
    public void renameChangesTheNameAndWritesAnAuditRow() throws Exception {
        try (ActivityScenario<TrustedDevicesActivity> ignored = launch()) {
            longPressRow(1);
            onView(withText(R.string.menu_rename_device)).perform(click());
            waitUntilDisplayed(withId(R.id.input_device_name));
            onView(withId(R.id.input_device_name)).perform(replaceText("  Living   room tablet "));
            onView(withText(R.string.button_save)).perform(click());

            waitUntil(() -> "Living room tablet".equals(devices.getById(tabletId).deviceName));
            assertEquals(1, countEvents(AuditEvent.DEVICE_RENAMED));
            waitUntilDisplayed(withText("Living room tablet"));
        }
    }

    @Test
    public void renamingThisPhoneKeepsItMarkedAsThisPhone() throws Exception {
        try (ActivityScenario<TrustedDevicesActivity> ignored = launch()) {
            longPressRow(0);
            onView(withText(R.string.menu_rename_device)).perform(click());
            waitUntilDisplayed(withId(R.id.input_device_name));
            onView(withId(R.id.input_device_name)).perform(replaceText("My phone"));
            onView(withText(R.string.button_save)).perform(click());

            waitUntilDisplayed(withText("My phone"));
            onView(allOf(withId(R.id.text_state), withText(containsString(
                    context.getString(R.string.device_this_device))))).check(matches(isDisplayed()));
            assertEquals(thisPhoneId, DeviceTrust.currentDeviceId(context, userId));
        }
    }

    @Test
    public void blankNameIsRefusedAndTheDialogStaysOpen() throws Exception {
        try (ActivityScenario<TrustedDevicesActivity> ignored = launch()) {
            longPressRow(1);
            onView(withText(R.string.menu_rename_device)).perform(click());
            waitUntilDisplayed(withId(R.id.input_device_name));
            onView(withId(R.id.input_device_name)).perform(replaceText("   "));
            onView(withText(R.string.button_save)).perform(click());

            onView(withText(R.string.error_device_name)).check(matches(isDisplayed()));
            assertEquals(OLD_TABLET, devices.getById(tabletId).deviceName);
            assertEquals(0, countEvents(AuditEvent.DEVICE_RENAMED));
        }
    }

    // ---- revoke -----------------------------------------------------------------------------

    @Test
    public void revokeAnotherDeviceAsksFirstThenRevokesIt() throws Exception {
        try (ActivityScenario<TrustedDevicesActivity> ignored = launch()) {
            longPressRow(1);
            onView(withText(R.string.menu_revoke_device)).perform(click());
            waitUntilDisplayed(withText(R.string.revoke_title));
            assertFalse(devices.getById(tabletId).revoked); // nothing happens before the answer
            onView(withText(R.string.button_revoke)).perform(click());

            waitUntil(() -> devices.getById(tabletId).revoked);
            assertEquals(1, countEvents(AuditEvent.DEVICE_REVOKED));
            waitUntilDisplayed(withText(R.string.device_status_revoked));
            assertFalse(devices.getById(thisPhoneId).revoked);
        }
    }

    @Test
    public void cancelInTheRevokeDialogChangesNothing() throws Exception {
        try (ActivityScenario<TrustedDevicesActivity> ignored = launch()) {
            longPressRow(1);
            onView(withText(R.string.menu_revoke_device)).perform(click());
            waitUntilDisplayed(withText(R.string.revoke_title));
            onView(withText(R.string.button_cancel)).perform(click());

            Thread.sleep(500);
            assertFalse(devices.getById(tabletId).revoked);
            assertEquals(0, countEvents(AuditEvent.DEVICE_REVOKED));
        }
    }

    @Test
    public void aRevokedRowOffersRenameButNotRevoke() throws Exception {
        TrustedDevice tablet = devices.getById(tabletId);
        tablet.revoked = true;
        devices.update(tablet);

        try (ActivityScenario<TrustedDevicesActivity> ignored = launch()) {
            longPressRow(1);
            onView(withText(R.string.menu_rename_device)).check(matches(isDisplayed()));
            onView(withText(R.string.menu_revoke_device)).check(doesNotExist());
        }
    }

    @Test
    public void revokingThisPhoneIsRefusedWhileThereAreNoBackupCodes() throws Exception {
        try (ActivityScenario<TrustedDevicesActivity> ignored = launch()) {
            longPressRow(0);
            onView(withText(R.string.menu_revoke_device)).perform(click());

            Thread.sleep(1500); // the check runs in the background
            onView(withText(R.string.revoke_title)).check(doesNotExist());
            assertFalse(devices.getById(thisPhoneId).revoked);
            assertFalse(DeviceTrust.isCurrentDeviceRevoked(context, devices, userId));
        }
    }

    @Test
    public void revokingThisPhoneWarnsAboutBackupCodesAndThenWorks() throws Exception {
        addUnusedBackupCode();
        try (ActivityScenario<TrustedDevicesActivity> ignored = launch()) {
            longPressRow(0);
            onView(withText(R.string.menu_revoke_device)).perform(click());

            waitUntilDisplayed(withText(R.string.revoke_title));
            onView(withText(containsString("needs a backup code"))).check(matches(isDisplayed()));
            onView(withText(R.string.button_revoke)).perform(click());

            waitUntil(() -> DeviceTrust.isCurrentDeviceRevoked(context, devices, userId));
            assertTrue(devices.getById(thisPhoneId).revoked);
            assertEquals(1, countEvents(AuditEvent.DEVICE_REVOKED));
        }
    }
}
