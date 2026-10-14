package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.swipeDown;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static com.secureauth.simulator.ui.TestUtils.enroll;
import static com.secureauth.simulator.ui.TestUtils.startSession;
import static com.secureauth.simulator.ui.TestUtils.textOf;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;

import androidx.lifecycle.Lifecycle;
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
import com.secureauth.simulator.views.CountdownRingView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class DashboardFlowTest {

    private static final long FIVE_MINUTES = 5 * 60 * 1000L;

    private Context context;
    private UserDao users;
    private AuditLogDao audit;
    private String username;
    private long userId;
    private byte[] secret;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        username = "d" + Long.toString(System.currentTimeMillis(), 36);
        secret = TotpEngine.generateSecret();
        audit = new AuditLogDao(db);
        userId = createUser(users, username, 0, 0);
        enroll(new TotpAccountDao(db), userId, secret);
        startSession(context, userId, FIVE_MINUTES); // the Dashboard is only reachable with a session
    }

    @After
    public void tearDown() {
        SessionManager.getInstance().clear();
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    private ActivityScenario<DashboardActivity> launch() {
        return ActivityScenario.launch(new Intent(context, DashboardActivity.class));
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

    /** Audit rows are written on a background thread, so wait briefly for the one we expect. */
    private void waitForEvent(String event) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (countEvents(event) == 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("no " + event + " audit row");
            }
            Thread.sleep(100);
        }
    }

    /** Waits until the screen shows a real code instead of the "------" placeholder. */
    private String waitForCode() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            String shown = textOf(R.id.text_code);
            if (shown.matches("\\d{3} \\d{3}")) {
                return shown;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("code never appeared: " + shown);
            }
            Thread.sleep(100);
        }
    }

    /** True if {@code shownRaw} is the code for now, or one second either side (the tick may be mid-flight). */
    private boolean isCurrentCode(String shownRaw) {
        long now = System.currentTimeMillis();
        for (long t : new long[]{now - 1500, now, now + 1500}) {
            if (TotpEngine.generate(secret, t, 6, 30, TotpEngine.ALGO_SHA1).equals(shownRaw)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void showsTheLiveCodeIssuerAndAccount() throws Exception {
        try (ActivityScenario<DashboardActivity> ignored = launch()) {
            String shown = waitForCode();
            assertTrue(shown, isCurrentCode(shown.replace(" ", "")));
            assertEquals("SecureAuth", textOf(R.id.text_issuer));
            assertEquals(username, textOf(R.id.text_account));
        }
    }

    @Test
    public void countdownTextAndRingAgree() throws Exception {
        try (ActivityScenario<DashboardActivity> scenario = launch()) {
            waitForCode();

            Matcher m = Pattern.compile("New code in (\\d+) s").matcher(textOf(R.id.text_seconds));
            assertTrue(m.matches());
            int seconds = Integer.parseInt(m.group(1));
            assertTrue("seconds=" + seconds, seconds >= 1 && seconds <= 30);

            scenario.onActivity(activity -> {
                CountdownRingView ring = activity.findViewById(R.id.ring);
                float expected = seconds / 30f;
                // the ring is drawn from milliseconds, the text from whole seconds
                assertTrue("progress=" + ring.getProgress(), Math.abs(ring.getProgress() - expected) <= 1.1f / 30f);
                assertEquals(ring.getProgress() * 30 <= 5, ring.isWarning());
            });
        }
    }

    @Test
    public void tappingTheCodeCopiesItWithoutTheSpace() throws Exception {
        try (ActivityScenario<DashboardActivity> scenario = launch()) {
            waitForCode();
            onView(withId(R.id.text_code)).perform(click());

            final String[] clip = new String[1];
            scenario.onActivity(activity -> {
                ClipboardManager cm = activity.getSystemService(ClipboardManager.class);
                clip[0] = String.valueOf(cm.getPrimaryClip().getItemAt(0).getText());
            });
            assertTrue(clip[0], clip[0].matches("\\d{6}"));
            assertTrue(isCurrentCode(clip[0]));
        }
    }

    @Test
    public void optionsMenuListsEveryEntry() throws Exception {
        try (ActivityScenario<DashboardActivity> ignored = launch()) {
            waitForCode();
            openActionBarOverflowOrOptionsMenu(context);
            int[] titles = {R.string.menu_login_history, R.string.menu_backup_codes,
                    R.string.menu_trusted_devices, R.string.menu_profile, R.string.menu_settings,
                    R.string.menu_help, R.string.menu_logout};
            for (int title : titles) {
                onView(withText(title)).check(matches(isDisplayed()));
            }
        }
    }

    @Test
    public void aFarAwayLoginShowsTheWarningOnceWithADistance() throws Exception {
        Intent withWarning = new Intent(context, DashboardActivity.class)
                .putExtra(DashboardActivity.EXTRA_SUSPICIOUS_KM, 321.4);
        try (ActivityScenario<DashboardActivity> scenario = ActivityScenario.launch(withWarning)) {
            waitUntilDisplayed(withText(R.string.suspicious_title));
            onView(withText(org.hamcrest.Matchers.containsString("321 km"))).check(matches(isDisplayed()));
            onView(withText(R.string.button_ok)).perform(click());

            scenario.recreate(); // a rotation must not bring the warning back
            waitForCode();
            onView(withText(R.string.suspicious_title)).check(
                    androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
        }
    }

    @Test
    public void theWarningOffersAShortcutToTheLoginMap() throws Exception {
        Intent withWarning = new Intent(context, DashboardActivity.class)
                .putExtra(DashboardActivity.EXTRA_SUSPICIOUS_KM, 150.0);
        try (ActivityScenario<DashboardActivity> ignored = ActivityScenario.launch(withWarning)) {
            waitUntilDisplayed(withText(R.string.suspicious_title));
            onView(withText(R.string.button_view_map)).perform(click());
            TestUtils.waitForScreen(LoginMapActivity.class);
        }
    }

    @Test
    public void anOrdinaryLoginShowsNoWarning() throws Exception {
        try (ActivityScenario<DashboardActivity> ignored = launch()) {
            waitForCode();
            onView(withText(R.string.suspicious_title)).check(
                    androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
        }
    }

    @Test
    public void loginHistoryMenuEntryOpensTheHistoryScreen() throws Exception {
        try (ActivityScenario<DashboardActivity> ignored = launch()) {
            waitForCode();
            openActionBarOverflowOrOptionsMenu(context);
            onView(withText(R.string.menu_login_history)).perform(click());
            TestUtils.waitForScreen(LoginHistoryActivity.class);
        }
    }

    @Test
    public void backupCodesMenuEntryOpensTheBackupCodesScreen() throws Exception {
        try (ActivityScenario<DashboardActivity> ignored = launch()) {
            waitForCode();
            openActionBarOverflowOrOptionsMenu(context);
            onView(withText(R.string.menu_backup_codes)).perform(click());
            TestUtils.waitForScreen(BackupCodesActivity.class);
        }
    }

    @Test
    public void trustedDevicesMenuEntryOpensTheTrustedDevicesScreen() throws Exception {
        try (ActivityScenario<DashboardActivity> ignored = launch()) {
            waitForCode();
            openActionBarOverflowOrOptionsMenu(context);
            onView(withText(R.string.menu_trusted_devices)).perform(click());
            TestUtils.waitForScreen(TrustedDevicesActivity.class);
        }
    }

    @Test
    public void logoutEndsTheSessionAndReturnsToLogin() throws Exception {
        try (ActivityScenario<DashboardActivity> ignored = launch()) {
            waitForCode();
            openActionBarOverflowOrOptionsMenu(context);
            onView(withText(R.string.menu_logout)).perform(click());
            waitUntilDisplayed(withId(R.id.button_login));

            assertEquals(-1L, SessionManager.getInstance().getUserId()); // forgotten in memory
            waitForEvent(AuditEvent.LOGOUT);
            assertTrue(new SessionDao(DbHelper.getInstance(context)).listForUser(userId).isEmpty());
        }
    }

    @Test
    public void withoutASessionTheDashboardRedirectsToLogin() throws Exception {
        SessionManager.getInstance().clear(); // nobody is logged in
        try (ActivityScenario<DashboardActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.button_login));
            // and no code was ever shown
            onView(withId(R.id.text_code)).check(doesNotExist());
        }
    }

    @Test
    public void anExpiredSessionIsEndedAndAudited() throws Exception {
        SessionManager.getInstance().clear();
        startSession(context, userId, 1L); // expires 1 ms after it starts
        Thread.sleep(50);
        try (ActivityScenario<DashboardActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.button_login));
            waitForEvent(AuditEvent.SESSION_EXPIRED);
            assertEquals(-1L, SessionManager.getInstance().getUserId());
            assertTrue(new SessionDao(DbHelper.getInstance(context)).listForUser(userId).isEmpty());
        }
    }

    @Test
    public void aSessionThatRunsOutWhileTheScreenIsOpenLogsTheUserOut() throws Exception {
        SessionManager.getInstance().clear();
        startSession(context, userId, 3_000L);
        try (ActivityScenario<DashboardActivity> ignored = launch()) {
            waitForCode(); // fine at first...
            waitUntilDisplayed(withId(R.id.button_login)); // ...then the once-a-second check logs out
            waitForEvent(AuditEvent.SESSION_EXPIRED);
        }
    }

    @Test
    public void aDeletedSessionRowStopsTheDashboardEvenIfMemoryStillHasTheToken() throws Exception {
        // e.g. the session was revoked in the database: the token in memory is no longer trusted
        new SessionDao(DbHelper.getInstance(context)).deleteForUser(userId);
        try (ActivityScenario<DashboardActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.button_login));
        }
    }

    @Test
    public void pullToRefreshKeepsShowingTheCode() throws Exception {
        try (ActivityScenario<DashboardActivity> ignored = launch()) {
            String before = waitForCode();
            onView(withId(R.id.swipe_refresh)).perform(swipeDown());
            String after = waitForCode();
            assertTrue(after, isCurrentCode(after.replace(" ", "")));
            assertTrue(before.matches("\\d{3} \\d{3}"));
        }
    }

    @Test
    public void secretIsWipedFromMemoryWhenTheScreenIsHidden() throws Exception {
        Field secretField = DashboardActivity.class.getDeclaredField("secret");
        secretField.setAccessible(true);

        try (ActivityScenario<DashboardActivity> scenario = launch()) {
            waitForCode();
            final Object[] visibleState = new Object[1];
            scenario.onActivity(activity -> {
                try {
                    visibleState[0] = secretField.get(activity);
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            });
            assertNotNull(visibleState[0]); // loaded while visible

            scenario.moveToState(Lifecycle.State.CREATED); // onStop: screen hidden
            final Object[] hiddenState = new Object[1];
            scenario.onActivity(activity -> {
                try {
                    hiddenState[0] = secretField.get(activity);
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            });
            assertNull(hiddenState[0]); // wiped

            scenario.moveToState(Lifecycle.State.RESUMED); // comes back: decrypts again
            String again = waitForCode();
            assertTrue(isCurrentCode(again.replace(" ", "")));
        }
    }
}
