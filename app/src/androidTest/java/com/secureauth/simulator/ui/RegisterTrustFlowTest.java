package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.DeviceTrust;
import com.secureauth.simulator.data.TrustedDevice;
import com.secureauth.simulator.data.TrustedDeviceDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** The "Trust this device" check box on the Register screen. */
@RunWith(AndroidJUnit4.class)
public class RegisterTrustFlowTest {

    private static final String PASSWORD = "Correct-Horse-9";

    private Context context;
    private UserDao users;
    private TrustedDeviceDao devices;
    private AuditLogDao audit;
    private String username;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        devices = new TrustedDeviceDao(db);
        audit = new AuditLogDao(db);
        username = "g" + Long.toString(System.currentTimeMillis(), 36);
    }

    @After
    public void tearDown() {
        User u = users.findByUsername(username);
        if (u != null) {
            context.getSharedPreferences("secureauth_prefs", Context.MODE_PRIVATE)
                    .edit().remove("trusted_device_id_" + u.id).commit();
            users.delete(u.id);
        }
    }

    private static void type(int viewId, String text) {
        onView(withId(viewId)).perform(scrollTo(), replaceText(text), closeSoftKeyboard());
    }

    /** Registers through the real screen and waits until the Enroll screen shows (the account exists by then). */
    private void register(boolean tickTrustBox) throws InterruptedException {
        try (ActivityScenario<RegisterActivity> ignored = ActivityScenario.launch(RegisterActivity.class)) {
            type(R.id.input_username, username);
            type(R.id.input_password, PASSWORD);
            type(R.id.input_confirm, PASSWORD);
            if (tickTrustBox) {
                onView(withId(R.id.checkbox_trust)).perform(scrollTo(), click());
            }
            onView(withId(R.id.button_register)).perform(scrollTo(), click());
            waitUntilDisplayed(withId(R.id.button_scanned));
        }
    }

    @Test
    public void tickedBoxAddsThisPhoneToTheTrustedDevices() throws Exception {
        register(true);

        User user = users.findByUsername(username);
        assertNotNull(user);
        List<TrustedDevice> list = devices.listForUser(user.id);
        assertEquals(1, list.size());
        assertEquals(DeviceTrust.currentDeviceName(), list.get(0).deviceName);
        assertFalse(list.get(0).revoked);
        assertEquals(list.get(0).id, DeviceTrust.currentDeviceId(context, user.id));

        int trusted = 0;
        for (AuditLog row : audit.listForUser(user.id)) {
            if (AuditEvent.DEVICE_TRUSTED.equals(row.event)) {
                trusted++;
            }
        }
        assertEquals(1, trusted);
    }

    @Test
    public void untickedBoxLeavesTheListEmpty() throws Exception {
        register(false);

        User user = users.findByUsername(username);
        assertNotNull(user);
        assertEquals(0, devices.listForUser(user.id).size());
        assertEquals(-1L, DeviceTrust.currentDeviceId(context, user.id));
    }
}
