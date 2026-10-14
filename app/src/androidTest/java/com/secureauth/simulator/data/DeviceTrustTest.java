package com.secureauth.simulator.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** Trusting this phone and noticing a revoke, on an in-memory database. */
@RunWith(AndroidJUnit4.class)
public class DeviceTrustTest {

    private Context context;
    private DbHelper helper;
    private TrustedDeviceDao devices;
    private AuditLogDao audit;
    private long userId;
    private long otherUserId;

    // The in-memory users get small ids that a real user of the installed app may also have,
    // so whatever the real preferences held for those ids is put back afterwards
    private Long savedUserEntry;
    private Long savedOtherEntry;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null);
        devices = new TrustedDeviceDao(helper);
        audit = new AuditLogDao(helper);

        UserDao users = new UserDao(helper);
        userId = users.insert(user("alice"));
        otherUserId = users.insert(user("bob"));

        savedUserEntry = saved(userId);
        savedOtherEntry = saved(otherUserId);
        prefs().edit().remove(key(userId)).remove(key(otherUserId)).commit();
    }

    @After
    public void tearDown() {
        SharedPreferences.Editor editor = prefs().edit();
        restore(editor, userId, savedUserEntry);
        restore(editor, otherUserId, savedOtherEntry);
        editor.commit();
        helper.close();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("secureauth_prefs", Context.MODE_PRIVATE);
    }

    private static String key(long id) {
        return "trusted_device_id_" + id;
    }

    private Long saved(long id) {
        return prefs().contains(key(id)) ? prefs().getLong(key(id), -1L) : null;
    }

    private static void restore(SharedPreferences.Editor editor, long id, Long value) {
        if (value == null) {
            editor.remove(key(id));
        } else {
            editor.putLong(key(id), value);
        }
    }

    private User user(String name) {
        User u = new User();
        u.username = name;
        u.passwordHash = "h";
        u.salt = "s";
        u.createdAt = 1L;
        return u;
    }

    @Test
    public void trustingAddsThisPhoneUnderItsOwnName() {
        long id = DeviceTrust.trustCurrentDevice(context, devices, audit, userId);

        assertTrue(id > 0);
        List<TrustedDevice> list = devices.listForUser(userId);
        assertEquals(1, list.size());
        assertEquals(DeviceTrust.currentDeviceName(), list.get(0).deviceName);
        assertFalse(list.get(0).revoked);
        assertEquals(id, DeviceTrust.currentDeviceId(context, userId));
    }

    @Test
    public void trustingWritesOneAuditRow() {
        DeviceTrust.trustCurrentDevice(context, devices, audit, userId);

        List<AuditLog> rows = audit.listForUser(userId);
        assertEquals(1, rows.size());
        assertEquals(AuditEvent.DEVICE_TRUSTED, rows.get(0).event);
        assertEquals(AuditEvent.RESULT_SUCCESS, rows.get(0).result);
    }

    @Test
    public void trustingTwiceDoesNotAddThePhoneTwice() {
        long first = DeviceTrust.trustCurrentDevice(context, devices, audit, userId);
        long second = DeviceTrust.trustCurrentDevice(context, devices, audit, userId);

        assertEquals(first, second);
        assertEquals(1, devices.listForUser(userId).size());
        assertEquals(1, audit.listForUser(userId).size());
    }

    @Test
    public void aPhoneThatWasNeverTrustedIsNotRevoked() {
        assertEquals(-1L, DeviceTrust.currentDeviceId(context, userId));
        assertFalse(DeviceTrust.isCurrentDeviceRevoked(context, devices, userId));
    }

    @Test
    public void aTrustedPhoneIsNotRevokedUntilItIsRevoked() {
        long id = DeviceTrust.trustCurrentDevice(context, devices, audit, userId);
        assertFalse(DeviceTrust.isCurrentDeviceRevoked(context, devices, userId));

        TrustedDevice row = devices.getById(id);
        row.revoked = true;
        devices.update(row);

        assertTrue(DeviceTrust.isCurrentDeviceRevoked(context, devices, userId));
    }

    @Test
    public void renamingKeepsTheLinkToThisPhone() {
        long id = DeviceTrust.trustCurrentDevice(context, devices, audit, userId);

        TrustedDevice row = devices.getById(id);
        row.deviceName = "Kitchen phone";
        row.revoked = true;
        devices.update(row);

        assertEquals(id, DeviceTrust.currentDeviceId(context, userId));
        assertTrue(DeviceTrust.isCurrentDeviceRevoked(context, devices, userId));
    }

    @Test
    public void anotherUsersTrustDoesNotCountForThisUser() {
        DeviceTrust.trustCurrentDevice(context, devices, audit, otherUserId);
        TrustedDevice theirs = devices.listForUser(otherUserId).get(0);
        theirs.revoked = true;
        devices.update(theirs);

        assertFalse(DeviceTrust.isCurrentDeviceRevoked(context, devices, userId));
        assertTrue(DeviceTrust.isCurrentDeviceRevoked(context, devices, otherUserId));
    }

    @Test
    public void aStoredIdThatPointsAtSomeoneElsesRowIsNotRevoked() {
        DeviceTrust.trustCurrentDevice(context, devices, audit, otherUserId);
        TrustedDevice theirs = devices.listForUser(otherUserId).get(0);
        theirs.revoked = true;
        devices.update(theirs);

        // Damaged preferences: this user's entry names the other user's (revoked) row
        prefs().edit().putLong(key(userId), theirs.id).commit();

        assertFalse(DeviceTrust.isCurrentDeviceRevoked(context, devices, userId));
    }
}
