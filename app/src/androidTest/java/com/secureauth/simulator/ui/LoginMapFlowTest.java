package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.startSession;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The map screen. The map must be ready (Google Play services) for markers to be added. */
@RunWith(AndroidJUnit4.class)
public class LoginMapFlowTest {

    private static final long MAP_TIMEOUT_MS = 90_000;

    private Context context;
    private UserDao users;
    private AuditLogDao audit;
    private String username;
    private long userId;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        audit = new AuditLogDao(db);
        username = "m" + Long.toString(System.currentTimeMillis(), 36);
        userId = createUser(users, username, 0, 0);
        startSession(context, userId, 5 * 60 * 1000L);
    }

    @After
    public void tearDown() {
        SessionManager.getInstance().clear();
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    private void add(String event, String result, Double lat, Double lng, long ts) {
        AuditLog a = new AuditLog();
        a.userId = userId;
        a.event = event;
        a.result = result;
        a.lat = lat;
        a.lng = lng;
        a.ts = ts;
        audit.insert(a);
    }

    private ActivityScenario<LoginMapActivity> launch() {
        return ActivityScenario.launch(new Intent(context, LoginMapActivity.class));
    }

    private static int markerCount(ActivityScenario<LoginMapActivity> scenario) {
        final int[] count = new int[1];
        scenario.onActivity(activity -> count[0] = activity.getMarkerCount());
        return count[0];
    }

    private static void waitForMarkers(ActivityScenario<LoginMapActivity> scenario, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + MAP_TIMEOUT_MS;
        while (markerCount(scenario) != expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("expected " + expected + " markers, have " + markerCount(scenario));
            }
            Thread.sleep(250);
        }
    }

    @Test
    public void everyEntryWithAPlaceGetsOneMarker() throws Exception {
        long now = System.currentTimeMillis();
        add("PASSWORD_OK", "SUCCESS", 19.076, 72.8777, now - 3000);        // green
        add("PASSWORD_FAIL", "FAIL", 18.5204, 73.8567, now - 2000);        // red
        add("SUSPICIOUS_LOCATION", "WARNING", 28.7041, 77.1025, now - 1000); // red
        add("REGISTER", "SUCCESS", null, null, now - 4000);                // no place: no marker

        try (ActivityScenario<LoginMapActivity> scenario = launch()) {
            waitForMarkers(scenario, 3);
            onView(withId(R.id.text_empty)).check(matches(androidx.test.espresso.matcher.ViewMatchers
                    .withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE)));
        }
    }

    @Test
    public void aSingleMarkerIsEnoughToShowTheMap() throws Exception {
        add("SESSION_START", "SUCCESS", 19.076, 72.8777, System.currentTimeMillis());
        try (ActivityScenario<LoginMapActivity> scenario = launch()) {
            waitForMarkers(scenario, 1);
        }
    }

    @Test
    public void withNoLocatedEntriesAMessageIsShown() throws Exception {
        add("REGISTER", "SUCCESS", null, null, System.currentTimeMillis());
        try (ActivityScenario<LoginMapActivity> scenario = launch()) {
            waitUntilDisplayed(withId(R.id.text_empty), MAP_TIMEOUT_MS);
            onView(withText(R.string.map_empty)).check(matches(isDisplayed()));
            assertEquals(0, markerCount(scenario));
        }
    }

    @Test
    public void theLegendExplainsTheColours() throws Exception {
        try (ActivityScenario<LoginMapActivity> ignored = launch()) {
            waitUntilDisplayed(withText(R.string.map_legend));
        }
    }

    @Test
    public void onlyTheLoggedInUsersEntriesAreMarked() throws Exception {
        long other = createUser(users, username + "x", 0, 0);
        AuditLog theirs = new AuditLog();
        theirs.userId = other;
        theirs.event = "SESSION_START";
        theirs.result = "SUCCESS";
        theirs.lat = 51.5;
        theirs.lng = -0.12;
        theirs.ts = System.currentTimeMillis();
        audit.insert(theirs);
        add("SESSION_START", "SUCCESS", 19.076, 72.8777, System.currentTimeMillis());
        try (ActivityScenario<LoginMapActivity> scenario = launch()) {
            waitForMarkers(scenario, 1);
        } finally {
            users.delete(other);
        }
    }

    @Test
    public void withoutASessionTheScreenSendsYouToLogin() throws Exception {
        SessionManager.getInstance().clear();
        try (ActivityScenario<LoginMapActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.button_login));
        }
    }
}
