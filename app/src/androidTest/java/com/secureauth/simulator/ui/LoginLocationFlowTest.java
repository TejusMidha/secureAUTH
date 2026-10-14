package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.enroll;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The password step of Login with a scripted location source instead of the real GPS. */
@RunWith(AndroidJUnit4.class)
public class LoginLocationFlowTest {

    private static final String PASSWORD = "Correct-Horse-9";

    private Context context;
    private UserDao users;
    private AuditLogDao audit;
    private String username;
    private long userId;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        audit = new AuditLogDao(db);
        username = "g" + Long.toString(System.currentTimeMillis(), 36);

        PasswordHasher.Result hashed = PasswordHasher.hash(PASSWORD.toCharArray());
        User u = new User();
        u.username = username;
        u.passwordHash = hashed.hash;
        u.salt = hashed.salt;
        u.createdAt = System.currentTimeMillis();
        userId = users.insert(u);
        enroll(new TotpAccountDao(db), userId, TotpEngine.generateSecret());
    }

    @After
    public void tearDown() {
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void type(int viewId, String text) {
        onView(withId(viewId)).perform(scrollTo(), replaceText(text), closeSoftKeyboard());
    }

    private AuditLog waitForRow(String event) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            for (AuditLog row : audit.listForUser(userId)) {
                if (event.equals(row.event)) {
                    return row;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("no " + event + " row");
            }
            Thread.sleep(100);
        }
    }

    private void logIn(String password) {
        type(R.id.input_username, username);
        type(R.id.input_password, password);
        onView(withId(R.id.button_login)).perform(scrollTo(), click());
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    public void bothPasswordRowsCarryThePlaceWhenOneIsAvailable() throws Exception {
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            scenario.onActivity(a -> a.locationHelper = FakeLocationHelper.at(context, 19.076, 72.8777));

            logIn("wrong-password");
            AuditLog failed = waitForRow(AuditEvent.PASSWORD_FAIL);
            assertEquals(19.076, failed.lat, 0.0);
            assertEquals(72.8777, failed.lng, 0.0);

            waitUntilDisplayed(withText(R.string.login_failed));
            logIn(PASSWORD);
            AuditLog ok = waitForRow(AuditEvent.PASSWORD_OK);
            assertEquals(19.076, ok.lat, 0.0);
            assertEquals(72.8777, ok.lng, 0.0);
        }
    }

    @Test
    public void withoutALocationTheLoginStillWorksAndStoresNoPlace() throws Exception {
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            scenario.onActivity(a -> a.locationHelper = FakeLocationHelper.nowhere(context));

            logIn(PASSWORD);
            AuditLog ok = waitForRow(AuditEvent.PASSWORD_OK);
            assertNull(ok.lat); // null, not 0.0: "no location" must stay distinct from a real place
            assertNull(ok.lng);
        }
    }

    @Test
    public void theFirstTimeAnExplanationAppearsAndDecliningStillLogsIn() throws Exception {
        FakeLocationHelper fake = new FakeLocationHelper(context, false, true, false, null, null);
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            scenario.onActivity(a -> a.locationHelper = fake);

            logIn(PASSWORD);
            waitUntilDisplayed(withText(R.string.location_rationale_title));
            onView(withText(containsString("100 km"))).check(matches(isDisplayed()));
            onView(withText(R.string.button_allow)).check(matches(isDisplayed()));

            onView(withText(R.string.button_not_now)).perform(click());

            AuditLog ok = waitForRow(AuditEvent.PASSWORD_OK); // the login went ahead without a place
            assertNull(ok.lat);
            assertTrue(fake.markAskedCalled); // and we will not ask again
        }
    }

    @Test
    public void someoneAlreadyAskedIsNotAskedAgain() throws Exception {
        FakeLocationHelper fake = new FakeLocationHelper(context, false, true, true, null, null);
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            scenario.onActivity(a -> a.locationHelper = fake);

            logIn(PASSWORD);
            waitForRow(AuditEvent.PASSWORD_OK);
            onView(withText(R.string.location_rationale_title)).check(doesNotExist());
            assertFalse(fake.markAskedCalled);
        }
    }

    @Test
    public void withTheSettingOffNothingIsAskedOrRecorded() throws Exception {
        FakeLocationHelper fake = new FakeLocationHelper(context, false, false, false, 19.0, 72.0);
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            scenario.onActivity(a -> a.locationHelper = fake);

            logIn(PASSWORD);
            AuditLog ok = waitForRow(AuditEvent.PASSWORD_OK);
            assertNull(ok.lat);
            onView(withText(R.string.location_rationale_title)).check(doesNotExist());
            assertNotNull(ok);
        }
    }
}
