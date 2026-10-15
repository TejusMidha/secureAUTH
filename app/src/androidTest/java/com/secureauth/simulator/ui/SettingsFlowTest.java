package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isNotChecked;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withSpinnerText;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.startSession;
import static com.secureauth.simulator.ui.TestUtils.textOf;
import static com.secureauth.simulator.ui.TestUtils.waitForScreen;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.AppSettings;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

/** The Settings screen, with a throw-away user and a real session. */
@RunWith(AndroidJUnit4.class)
public class SettingsFlowTest {

    private static final long FIVE_MINUTES = 5 * 60 * 1000L;

    private Context context;
    private UserDao users;
    private String username;
    private long userId;
    private Map<String, Object> savedSettings;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        users = new UserDao(DbHelper.getInstance(context));
        savedSettings = TestUtils.snapshotSettings(context);
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(AppSettings.KEY_DIGITS).remove(AppSettings.KEY_PERIOD)
                .remove(AppSettings.KEY_ALGO).remove(AppSettings.KEY_AUTO_LOCK_MS)
                .remove(AppSettings.KEY_RECORD_LOCATION).commit();

        username = "s" + Long.toString(System.currentTimeMillis(), 36);
        userId = createUser(users, username, 0, 0);
        startSession(context, userId, FIVE_MINUTES);
    }

    @After
    public void tearDown() {
        SessionManager.getInstance().clear();
        TestUtils.restoreSettings(context, savedSettings);
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    private ActivityScenario<SettingsActivity> launch() {
        return ActivityScenario.launch(new Intent(context, SettingsActivity.class));
    }

    private static void choose(int spinnerId, String label) {
        onView(withId(spinnerId)).perform(scrollTo(), click());
        onData(allOf(is(instanceOf(String.class)), is(label))).perform(click());
    }

    // ---- showing ----------------------------------------------------------------------------

    @Test
    public void startsWithTheDefaultsAndNoWarning() throws Exception {
        try (ActivityScenario<SettingsActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.spinner_digits));
            onView(withId(R.id.spinner_digits)).check(matches(withSpinnerText("6 digits")));
            onView(withId(R.id.spinner_period)).check(matches(withSpinnerText("30 seconds")));
            onView(withId(R.id.spinner_algorithm)).check(matches(withSpinnerText("SHA-1 (default)")));
            onView(withId(R.id.text_warning)).check(matches(not(isDisplayed())));
            onView(withId(R.id.text_autolock)).check(matches(withText(containsString("5 min"))));
            onView(withId(R.id.checkbox_record_location)).perform(scrollTo()).check(matches(isChecked()));
        }
    }

    @Test
    public void savedChoicesAreShownWhenTheScreenOpens() throws Exception {
        AppSettings.setDigits(context, 8);
        AppSettings.setPeriod(context, 60);
        AppSettings.setAlgo(context, TotpEngine.ALGO_SHA256);
        AppSettings.setAutoLockMs(context, 12 * 60_000L);
        AppSettings.setRecordLocation(context, false);

        try (ActivityScenario<SettingsActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.spinner_digits));
            onView(withId(R.id.spinner_digits)).check(matches(withSpinnerText("8 digits")));
            onView(withId(R.id.spinner_period)).check(matches(withSpinnerText("60 seconds")));
            onView(withId(R.id.spinner_algorithm)).check(matches(withSpinnerText("SHA-256")));
            onView(withId(R.id.text_warning)).check(matches(isDisplayed()));
            onView(withId(R.id.text_autolock)).check(matches(withText(containsString("12 min"))));
            onView(withId(R.id.checkbox_record_location)).perform(scrollTo()).check(matches(isNotChecked()));
        }
    }

    // ---- the spinners -----------------------------------------------------------------------

    @Test
    public void choosingEightDigitsSavesItAndShowsTheWarning() throws Exception {
        try (ActivityScenario<SettingsActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.spinner_digits));
            choose(R.id.spinner_digits, "8 digits");

            assertEquals(8, AppSettings.getDigits(context));
            onView(withId(R.id.text_warning)).check(matches(isDisplayed()));
            onView(withId(R.id.text_warning)).check(matches(withText(containsString("set up your authenticator again"))));
        }
    }

    @Test
    public void choosingThePeriodAndAlgorithmSavesThem() throws Exception {
        try (ActivityScenario<SettingsActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.spinner_period));
            choose(R.id.spinner_period, "60 seconds");
            choose(R.id.spinner_algorithm, "SHA-256");

            assertEquals(60, AppSettings.getPeriod(context));
            assertEquals(TotpEngine.ALGO_SHA256, AppSettings.getAlgo(context));
        }
    }

    @Test
    public void goingBackToTheDefaultsHidesTheWarning() throws Exception {
        try (ActivityScenario<SettingsActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.spinner_digits));
            choose(R.id.spinner_digits, "8 digits");
            onView(withId(R.id.text_warning)).check(matches(isDisplayed()));

            choose(R.id.spinner_digits, "6 digits");
            onView(withId(R.id.text_warning)).check(matches(not(isDisplayed())));
            assertEquals(6, AppSettings.getDigits(context));
        }
    }

    @Test
    public void changingSettingsDoesNotTouchTheExistingAuthenticator() throws Exception {
        TestUtils.enroll(new com.secureauth.simulator.data.TotpAccountDao(DbHelper.getInstance(context)),
                userId, TotpEngine.generateSecret());
        try (ActivityScenario<SettingsActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.spinner_digits));
            choose(R.id.spinner_digits, "8 digits");
        }
        com.secureauth.simulator.data.TotpAccount account = new com.secureauth.simulator.data.TotpAccountDao(
                DbHelper.getInstance(context)).listForUser(userId).get(0);
        assertEquals(6, account.digits); // only the NEXT enrollment uses 8
    }

    // ---- auto-lock --------------------------------------------------------------------------

    @Test
    public void theTimePickerButtonOpensADialogWithATimePicker() throws Exception {
        try (ActivityScenario<SettingsActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.button_autolock));
            onView(withId(R.id.button_autolock)).perform(scrollTo(), click());
            waitUntilDisplayed(withText(R.string.autolock_picker_title));
        }
    }

    @Test
    public void aPickedTimeInsideTheRangeIsSavedAndShown() throws Exception {
        try (ActivityScenario<SettingsActivity> scenario = launch()) {
            waitUntilDisplayed(withId(R.id.button_autolock));
            scenario.onActivity(a -> a.onAutoLockPicked(0, 10));

            assertEquals(10 * 60_000L, AppSettings.getAutoLockMs(context));
            assertEquals("Log out after 10 min", textOf(R.id.text_autolock));
        }
    }

    @Test
    public void aPickedTimeOutsideTheRangeIsRefusedAndNothingChanges() throws Exception {
        try (ActivityScenario<SettingsActivity> scenario = launch()) {
            waitUntilDisplayed(withId(R.id.button_autolock));
            scenario.onActivity(a -> a.onAutoLockPicked(0, 45));

            assertEquals(5 * 60_000L, AppSettings.getAutoLockMs(context));
            waitUntilDisplayed(withText(R.string.autolock_picker_title)); // the picker opened again
        }
    }

    @Test
    public void theNewAutoLockTimeIsUsedForTheNextSession() throws Exception {
        AppSettings.setAutoLockMs(context, 3 * 60_000L);
        long before = System.currentTimeMillis();
        SessionManager.getInstance().create(new com.secureauth.simulator.data.SessionDao(DbHelper.getInstance(context)),
                userId, SessionManager.getAutoLockMs(context), before);

        long length = SessionManager.getInstance().getExpiresAt() - before;
        assertEquals(3 * 60_000L, length);
    }

    // ---- location ---------------------------------------------------------------------------

    @Test
    public void theLocationCheckBoxIsSavedWhenToggled() throws Exception {
        try (ActivityScenario<SettingsActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.checkbox_record_location));
            onView(withId(R.id.checkbox_record_location)).perform(scrollTo(), click());
            assertFalse(AppSettings.isRecordLocation(context));

            onView(withId(R.id.checkbox_record_location)).perform(click());
            assertTrue(AppSettings.isRecordLocation(context));
        }
    }

    // ---- setting up again -------------------------------------------------------------------

    @Test
    public void setUpAgainAsksFirstThenOpensEnrollmentForThisUser() throws Exception {
        try (ActivityScenario<SettingsActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.button_setup_again));
            onView(withId(R.id.button_setup_again)).perform(scrollTo(), click());
            waitUntilDisplayed(withText(R.string.setup_again_title));
            onView(withText(R.string.button_continue)).perform(click());

            waitForScreen(EnrollActivity.class);
            long shownFor = ((EnrollActivity) TestUtils.visibleActivity()).getIntent()
                    .getLongExtra(EnrollActivity.EXTRA_USER_ID, -1L);
            assertEquals(userId, shownFor);
            android.app.Activity enroll = TestUtils.visibleActivity();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(enroll::finish);
        }
    }

    @Test
    public void withoutASessionTheScreenSendsYouToLogin() throws Exception {
        SessionManager.getInstance().clear();
        try (ActivityScenario<SettingsActivity> ignored = launch()) {
            waitForScreen(LoginActivity.class);
        }
    }
}
