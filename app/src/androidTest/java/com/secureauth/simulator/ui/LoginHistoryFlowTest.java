package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withClassName;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.setDate;
import static com.secureauth.simulator.ui.TestUtils.startSession;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.DatePicker;
import android.widget.ListView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;
import com.secureauth.simulator.io.FileExporter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** The history screen against the real database with four known entries. */
@RunWith(AndroidJUnit4.class)
public class LoginHistoryFlowTest {

    private Context context;
    private UserDao users;
    private AuditLogDao audit;
    private String username;
    private long userId;

    // Four entries: now, 3 days ago (with a location), and two 10 days ago (one an hour after the other)
    private long tNow;
    private long t3;
    private long t10a;
    private long t10b;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        audit = new AuditLogDao(db);
        username = "h" + Long.toString(System.currentTimeMillis(), 36);
        userId = createUser(users, username, 0, 0);
        startSession(context, userId, 5 * 60 * 1000L);

        tNow = System.currentTimeMillis();
        t3 = noonDaysAgo(3);
        t10a = noonDaysAgo(10);
        t10b = t10a + 60 * 60 * 1000L;
        add(tNow, "TOTP_OK", "SUCCESS", null, null);
        add(t3, "PASSWORD_OK", "SUCCESS", 19.076, 72.8777);
        add(t10a, "PASSWORD_FAIL", "FAIL", null, null);
        add(t10b, "LOCKED_OUT", "FAIL", null, null);
    }

    @After
    public void tearDown() {
        SessionManager.getInstance().clear();
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void add(long ts, String event, String result, Double lat, Double lng) {
        AuditLog a = new AuditLog();
        a.userId = userId;
        a.ts = ts;
        a.event = event;
        a.result = result;
        a.lat = lat;
        a.lng = lng;
        audit.insert(a);
    }

    private static long noonDaysAgo(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -days);
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private ActivityScenario<LoginHistoryActivity> launch() {
        return ActivityScenario.launch(new Intent(context, LoginHistoryActivity.class));
    }

    /** The "event" of every row currently in the list, top to bottom. */
    private static String eventsShown(ActivityScenario<LoginHistoryActivity> scenario) {
        final StringBuilder events = new StringBuilder();
        scenario.onActivity(activity -> {
            ListView list = activity.findViewById(R.id.list_history);
            for (int i = 0; i < list.getAdapter().getCount(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) list.getAdapter().getItem(i);
                events.append(i == 0 ? "" : ",").append(item.get("event"));
            }
        });
        return events.toString();
    }

    private static void waitForEvents(ActivityScenario<LoginHistoryActivity> scenario, String expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!expected.equals(eventsShown(scenario))) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("expected [" + expected + "] but list shows [" + eventsShown(scenario) + "]");
            }
            Thread.sleep(100);
        }
    }

    /** Picks a day in the date dialog that is open right now, and confirms it. */
    private static void pickDay(int daysAgo) throws InterruptedException {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -daysAgo);
        waitUntilDisplayed(withClassName(equalTo(DatePicker.class.getName())));
        onView(withClassName(equalTo(DatePicker.class.getName())))
                .perform(setDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)));
        onView(withId(android.R.id.button1)).perform(click()); // OK
        Thread.sleep(600); // let the next dialog (or the list) appear
    }

    private static void filterDays(int fromDaysAgo, int toDaysAgo) throws InterruptedException {
        onView(withId(R.id.button_filter)).perform(click());
        pickDay(fromDaysAgo);
        pickDay(toDaysAgo);
    }

    private File cacheFile(String name) {
        return new File(context.getCacheDir(), name);
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    public void listsEveryEntryNewestFirst() throws Exception {
        try (ActivityScenario<LoginHistoryActivity> scenario = launch()) {
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");
        }
    }

    @Test
    public void eachRowShowsTheEventTheTimeAndTheLocationOrNoLocation() throws Exception {
        try (ActivityScenario<LoginHistoryActivity> scenario = launch()) {
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");

            onData(hasEntry(equalTo("event"), equalTo("PASSWORD_OK")))
                    .onChildView(withId(R.id.text_event)).check(matches(withText("PASSWORD_OK")));
            onData(hasEntry(equalTo("event"), equalTo("PASSWORD_OK")))
                    .onChildView(withId(R.id.text_location)).check(matches(withText("19.0760, 72.8777")));
            onData(hasEntry(equalTo("event"), equalTo("LOCKED_OUT")))
                    .onChildView(withId(R.id.text_location)).check(matches(withText("No location")));
            onData(hasEntry(equalTo("event"), equalTo("LOCKED_OUT")))
                    .onChildView(withId(R.id.text_time)).check(matches(
                            withText(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
                                    java.text.DateFormat.SHORT).format(new java.util.Date(t10b)))));
        }
    }

    @Test
    public void iconsTellSuccessFromFailure() throws Exception {
        try (ActivityScenario<LoginHistoryActivity> scenario = launch()) {
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");

            onData(hasEntry(equalTo("event"), equalTo("PASSWORD_OK")))
                    .onChildView(withId(R.id.image_status)).check(matches(withContentDescription(R.string.cd_status_ok)));
            onData(hasEntry(equalTo("event"), equalTo("PASSWORD_FAIL")))
                    .onChildView(withId(R.id.image_status)).check(matches(withContentDescription(R.string.cd_status_fail)));
        }
    }

    @Test
    public void dateFilterKeepsOnlyTheChosenDaysAndTheChipRemovesIt() throws Exception {
        try (ActivityScenario<LoginHistoryActivity> scenario = launch()) {
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");
            onView(withId(R.id.chip_clear_filter)).check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));

            filterDays(10, 9); // from 10 days ago to 9 days ago: the two old entries only
            waitForEvents(scenario, "LOCKED_OUT,PASSWORD_FAIL");
            onView(withId(R.id.chip_clear_filter)).check(matches(isDisplayed()));
            onView(withId(R.id.text_filter_range)).check(matches(withText(containsString(" to "))));

            onView(withId(R.id.chip_clear_filter)).perform(click());
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");
            onView(withId(R.id.chip_clear_filter)).check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
        }
    }

    @Test
    public void aRangeEndingTodayIncludesTodaysEntries() throws Exception {
        try (ActivityScenario<LoginHistoryActivity> scenario = launch()) {
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");
            filterDays(4, 0); // 4 days ago until the end of today
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK");
        }
    }

    @Test
    public void aRangeWithNoEntriesShowsTheEmptyMessage() throws Exception {
        try (ActivityScenario<LoginHistoryActivity> scenario = launch()) {
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");
            filterDays(100, 90);
            waitForEvents(scenario, "");
            onView(withId(R.id.text_empty)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void cancellingTheDateDialogLeavesTheListAlone() throws Exception {
        try (ActivityScenario<LoginHistoryActivity> scenario = launch()) {
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");
            onView(withId(R.id.button_filter)).perform(click());
            waitUntilDisplayed(withClassName(equalTo(DatePicker.class.getName())));
            onView(withId(android.R.id.button2)).perform(click()); // Cancel
            Thread.sleep(500);
            onView(withId(R.id.chip_clear_filter)).check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
            assertEquals("TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL", eventsShown(scenario));
        }
    }

    @Test
    public void theFilterSurvivesRotation() throws Exception {
        try (ActivityScenario<LoginHistoryActivity> scenario = launch()) {
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");
            filterDays(10, 9);
            waitForEvents(scenario, "LOCKED_OUT,PASSWORD_FAIL");

            scenario.recreate(); // what a rotation does
            waitForEvents(scenario, "LOCKED_OUT,PASSWORD_FAIL");
            onView(withId(R.id.chip_clear_filter)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void optionsMenuOffersMapExportAndImport() throws Exception {
        try (ActivityScenario<LoginHistoryActivity> ignored = launch()) {
            openActionBarOverflowOrOptionsMenu(context);
            onView(withText(R.string.menu_login_map)).check(matches(isDisplayed()));
            onView(withText(R.string.menu_export_csv)).check(matches(isDisplayed()));
            onView(withText(R.string.menu_import_csv)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void theMapMenuEntryOpensTheLoginMap() throws Exception {
        try (ActivityScenario<LoginHistoryActivity> ignored = launch()) {
            openActionBarOverflowOrOptionsMenu(context);
            onView(withText(R.string.menu_login_map)).perform(click());
            TestUtils.waitForScreen(LoginMapActivity.class);
        }
    }

    @Test
    public void exportWritesExactlyTheRowsOnScreen() throws Exception {
        File out = cacheFile("history_export.csv");
        out.delete();
        try (ActivityScenario<LoginHistoryActivity> scenario = launch()) {
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");
            filterDays(10, 9);
            waitForEvents(scenario, "LOCKED_OUT,PASSWORD_FAIL");

            scenario.onActivity(activity -> activity.exportTo(Uri.fromFile(out)));
            long deadline = System.currentTimeMillis() + 10_000;
            while (!out.exists() || out.length() == 0) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("export file never appeared");
                }
                Thread.sleep(100);
            }
            Thread.sleep(200);

            List<AuditLog> expected = audit.listForUserBetween(userId, t10a, t10b + 1);
            String csv = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
            assertEquals(FileExporter.formatAuditCsv(expected, TimeZone.getDefault()), csv);
            assertTrue(csv.startsWith("ts,datetime,event,result,lat,lng\r\n"));
            assertEquals(3, csv.split("\r\n").length); // header + the 2 rows of the filter
        } finally {
            out.delete();
        }
    }

    @Test
    public void importAddsNewRowsAndSkipsDuplicatesAndGarbage() throws Exception {
        File in = cacheFile("history_import.csv");
        try (FileOutputStream stream = new FileOutputStream(in)) {
            String csv = "ts,event,result,lat,lng\r\n"
                    + t3 + ",PASSWORD_OK,SUCCESS,19.076,72.8777\r\n"  // already in the history
                    + "1000,IMPORTED_ONE,SUCCESS,,\r\n"                // new
                    + "2000,IMPORTED_TWO,FAIL,10.5,20.5\r\n"           // new, with a place
                    + "oops,IMPORTED_BAD,SUCCESS,,\r\n";               // invalid time
            stream.write(csv.getBytes(StandardCharsets.UTF_8));
        }
        try (ActivityScenario<LoginHistoryActivity> scenario = launch()) {
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");

            scenario.onActivity(activity -> activity.importFrom(Uri.fromFile(in)));

            waitForEvents(scenario,
                    "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL,IMPORTED_TWO,IMPORTED_ONE");
            assertEquals(6, audit.listForUser(userId).size());
        } finally {
            in.delete();
        }
    }

    @Test
    public void importingAFileThatIsNotAnAuditLogChangesNothing() throws Exception {
        File in = cacheFile("not_a_log.csv");
        try (FileOutputStream stream = new FileOutputStream(in)) {
            stream.write("name,price\r\napple,3\r\n".getBytes(StandardCharsets.UTF_8));
        }
        try (ActivityScenario<LoginHistoryActivity> scenario = launch()) {
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");
            scenario.onActivity(activity -> activity.importFrom(Uri.fromFile(in)));
            Thread.sleep(1000);
            assertEquals(4, audit.listForUser(userId).size());
        } finally {
            in.delete();
        }
    }

    @Test
    public void withoutASessionTheScreenSendsYouToLogin() throws Exception {
        SessionManager.getInstance().clear();
        try (ActivityScenario<LoginHistoryActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.button_login));
        }
    }

    @Test
    public void aWholeHistoryOfOtherUsersIsNeverShown() throws Exception {
        // a second user's entries must not appear in this user's list
        long other = createUser(users, username + "x", 0, 0);
        AuditLog theirs = new AuditLog();
        theirs.userId = other;
        theirs.ts = tNow;
        theirs.event = "THEIR_EVENT";
        theirs.result = "SUCCESS";
        audit.insert(theirs);
        try (ActivityScenario<LoginHistoryActivity> scenario = launch()) {
            waitForEvents(scenario, "TOTP_OK,PASSWORD_OK,LOCKED_OUT,PASSWORD_FAIL");
        } finally {
            users.delete(other);
        }
    }
}
