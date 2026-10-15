package com.secureauth.simulator.ui;

import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.enroll;
import static com.secureauth.simulator.ui.TestUtils.startSession;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.WindowManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * FLAG_SECURE (no screenshots, no recent-apps preview) must be on for the screens that show a
 * code or a secret, and only for those.
 */
@RunWith(AndroidJUnit4.class)
public class SecureScreenTest {

    private static final long FIVE_MINUTES = 5 * 60 * 1000L;

    private Context context;
    private UserDao users;
    private String username;
    private long userId;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        username = "f" + Long.toString(System.currentTimeMillis(), 36);
        userId = createUser(users, username, 0, 0);
        enroll(new TotpAccountDao(db), userId, TotpEngine.generateSecret());
        startSession(context, userId, FIVE_MINUTES);
    }

    @After
    public void tearDown() {
        SessionManager.getInstance().clear();
        User u = users.findByUsername(username);
        if (u != null) {
            users.delete(u.id);
        }
    }

    private static boolean isSecure(Activity activity) {
        return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
    }

    private <T extends Activity> boolean secureFlagOf(Class<T> screen) {
        final boolean[] result = new boolean[1];
        try (ActivityScenario<T> scenario = ActivityScenario.launch(new Intent(context, screen)
                .putExtra(EnrollActivity.EXTRA_USER_ID, userId))) {
            scenario.onActivity(a -> result[0] = isSecure(a));
        }
        return result[0];
    }

    @Test
    public void theDashboardIsSecure() {
        assertTrue(secureFlagOf(DashboardActivity.class));
    }

    @Test
    public void theEnrollScreenIsSecure() {
        assertTrue(secureFlagOf(EnrollActivity.class));
    }

    @Test
    public void theBackupCodesScreenIsSecure() {
        assertTrue(secureFlagOf(BackupCodesActivity.class));
    }

    @Test
    public void screensThatShowNoCodeOrSecretAreNotSecure() {
        assertFalse(secureFlagOf(LoginActivity.class));
        assertFalse(secureFlagOf(RegisterActivity.class));
        assertFalse(secureFlagOf(SettingsActivity.class));
        assertFalse(secureFlagOf(LoginHistoryActivity.class));
        assertFalse(secureFlagOf(HelpActivity.class));
    }

    @Test
    public void theOneTimeBackupCodesDialogIsSecureToo() throws Exception {
        // The codes are shown in a dialog on top of BackupCodesActivity. A dialog is a separate
        // window, so its own flag is checked
        try (ActivityScenario<BackupCodesActivity> scenario = ActivityScenario.launch(
                new Intent(context, BackupCodesActivity.class))) {
            waitUntilDisplayed(withId(R.id.button_generate));
            scenario.onActivity(activity -> {
                BackupCodesViewModel model = new androidx.lifecycle.ViewModelProvider(activity)
                        .get(BackupCodesViewModel.class);
                // Same path as a real generation, without the slow hashing
                model.generate(() -> new BackupCodesViewModel.Generation(
                        java.util.Collections.singletonList("ABCD-EFGH"), "tester", 0L));
            });

            // The dialog opens once the (instant) work has been posted back to the UI thread
            long deadline = System.currentTimeMillis() + 10_000;
            final android.app.Dialog[] dialog = new android.app.Dialog[1];
            while (dialog[0] == null && System.currentTimeMillis() < deadline) {
                scenario.onActivity(activity -> {
                    try {
                        java.lang.reflect.Field f = BackupCodesActivity.class.getDeclaredField("codesDialog");
                        f.setAccessible(true);
                        dialog[0] = (android.app.Dialog) f.get(activity);
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                });
                Thread.sleep(100);
            }
            assertTrue("the codes dialog never opened", dialog[0] != null);
            final boolean[] secure = new boolean[1];
            scenario.onActivity(activity -> secure[0] =
                    (dialog[0].getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertTrue(secure[0]);
        }
    }
}
