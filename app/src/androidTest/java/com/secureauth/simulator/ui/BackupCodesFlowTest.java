package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.startSession;
import static com.secureauth.simulator.ui.TestUtils.textOf;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.BackupCodeGenerator;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.BackupCode;
import com.secureauth.simulator.data.BackupCodeDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The backup-codes screen against the real app database with a throw-away user and session. */
@RunWith(AndroidJUnit4.class)
public class BackupCodesFlowTest {

    /** Hashing 10 codes takes a while on an emulator. */
    private static final long GENERATE_TIMEOUT_MS = 90_000;

    private Context context;
    private UserDao users;
    private BackupCodeDao codes;
    private AuditLogDao audit;
    private String username;
    private long userId;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        codes = new BackupCodeDao(db);
        audit = new AuditLogDao(db);
        username = "k" + Long.toString(System.currentTimeMillis(), 36);
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

    // ---- helpers ----------------------------------------------------------------------------

    private ActivityScenario<BackupCodesActivity> launch() {
        return ActivityScenario.launch(new Intent(context, BackupCodesActivity.class));
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

    /** Stores a code row with a stand-in hash (these tests only look at counts and flags). */
    private long addRow(String hash, boolean used) {
        BackupCode c = new BackupCode();
        c.userId = userId;
        c.codeHash = hash;
        c.salt = "salt";
        long id = codes.insert(c);
        if (used) {
            codes.markUsed(id);
        }
        return id;
    }

    private void generateAndWaitForTheDialog() throws InterruptedException {
        onView(withId(R.id.button_generate)).perform(click());
        waitUntilDisplayed(withText(R.string.generate_confirm_title));
        onView(withText(R.string.button_generate)).perform(click());
        waitUntilDisplayed(withText(R.string.codes_dialog_title), GENERATE_TIMEOUT_MS);
    }

    private static List<String> shownCodes() {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+\\.\\s+([A-Z2-9]{4}-[A-Z2-9]{4})").matcher(textOf(R.id.text_codes));
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private void waitForRemainingText(String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!expected.equals(textOf(R.id.text_remaining))) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("expected '" + expected + "' but saw '" + textOf(R.id.text_remaining) + "'");
            }
            Thread.sleep(100);
        }
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    public void startsWithNoCodes() throws Exception {
        try (ActivityScenario<BackupCodesActivity> ignored = launch()) {
            waitForRemainingText(context.getString(R.string.backup_codes_none));
        }
    }

    @Test
    public void remainingCountShowsUnusedOutOfAll() throws Exception {
        for (int i = 0; i < 10; i++) {
            addRow("stand-in-" + i, i < 3); // three of ten already used
        }
        try (ActivityScenario<BackupCodesActivity> ignored = launch()) {
            waitForRemainingText("7 of 10 codes remaining");
        }
    }

    @Test
    public void cancellingTheWarningChangesNothing() throws Exception {
        addRow("existing", false);
        try (ActivityScenario<BackupCodesActivity> ignored = launch()) {
            onView(withId(R.id.button_generate)).perform(click());
            waitUntilDisplayed(withText(R.string.generate_confirm_title));
            onView(withText(R.string.generate_confirm_message)).check(matches(isDisplayed()));
            onView(withText(R.string.button_cancel)).perform(click());

            Thread.sleep(500);
            assertEquals(1, codes.listForUser(userId).size());
            assertEquals("existing", codes.listForUser(userId).get(0).codeHash);
            assertEquals(0, countEvents(AuditEvent.BACKUP_CODES_GENERATED));
        }
    }

    @Test
    public void generatingShowsTenCodesOnceAndStoresOnlyHashes() throws Exception {
        try (ActivityScenario<BackupCodesActivity> ignored = launch()) {
            generateAndWaitForTheDialog();

            List<String> shown = shownCodes();
            assertEquals(10, shown.size());
            Set<String> unique = new HashSet<>(shown);
            assertEquals(10, unique.size());
            onView(withId(R.id.button_save_txt)).check(matches(isDisplayed()));
            onView(withId(R.id.button_share_codes)).check(matches(isDisplayed()));

            // 10 rows, none used, and no plain code stored anywhere
            List<BackupCode> rows = codes.listForUser(userId);
            assertEquals(10, rows.size());
            for (BackupCode row : rows) {
                assertFalse(row.used);
                for (String plain : shown) {
                    assertNotEquals(plain, row.codeHash);
                }
            }
            // the first shown code really is one of the stored ones
            boolean matchesARow = false;
            for (BackupCode row : rows) {
                if (BackupCodeGenerator.matches(shown.get(0), row.codeHash, row.salt)) {
                    matchesARow = true;
                    break;
                }
            }
            assertTrue(matchesARow);
            assertEquals(1, countEvents(AuditEvent.BACKUP_CODES_GENERATED));

            // Done closes the dialog for good; the codes are not shown again
            onView(withText(R.string.button_done)).perform(click());
            onView(withText(R.string.codes_dialog_title)).check(doesNotExist());
            waitForRemainingText("10 of 10 codes remaining");
        }
    }

    @Test
    public void rotatingTheScreenDoesNotLoseTheCodes() throws Exception {
        try (ActivityScenario<BackupCodesActivity> scenario = launch()) {
            generateAndWaitForTheDialog();
            List<String> before = shownCodes();

            scenario.recreate(); // what a rotation does to the Activity
            waitUntilDisplayed(withText(R.string.codes_dialog_title), 20_000);

            assertEquals(before, shownCodes());
        }
    }

    @Test
    public void newCodesReplaceTheOldSetEntirely() throws Exception {
        addRow("old-hash-1", false);
        addRow("old-hash-2", true);
        addRow("old-hash-3", false);

        try (ActivityScenario<BackupCodesActivity> ignored = launch()) {
            generateAndWaitForTheDialog();

            List<BackupCode> rows = codes.listForUser(userId);
            assertEquals(10, rows.size()); // 10, not 13
            for (BackupCode row : rows) {
                assertFalse(row.codeHash.startsWith("old-hash"));
            }
            assertEquals(10, codes.listUnused(userId).size());
        }
    }

    @Test
    public void withoutASessionTheScreenSendsYouToLogin() throws Exception {
        SessionManager.getInstance().clear();
        try (ActivityScenario<BackupCodesActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.button_login));
        }
    }
}
