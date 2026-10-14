package com.secureauth.simulator.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/** Session rules on an in-memory database. */
public class SessionManagerTest {

    private static final long NOW = 1_000_000L;
    private static final long FIVE_MINUTES = 5 * 60 * 1000L;

    private Context context;
    private DbHelper helper;
    private SessionDao sessions;
    private SessionManager manager;
    private long userId;
    private long otherUserId;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null);
        sessions = new SessionDao(helper);
        manager = SessionManager.getInstance();
        manager.clear();

        UserDao users = new UserDao(helper);
        userId = users.insert(user("alice"));
        otherUserId = users.insert(user("bob"));
    }

    @After
    public void tearDown() {
        manager.clear();
        helper.close();
        context.getSharedPreferences("secureauth_prefs", Context.MODE_PRIVATE).edit().clear().commit();
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
    public void withoutLoginThereIsNoSession() {
        assertEquals(SessionManager.State.NONE, manager.check(sessions, NOW));
        assertEquals(-1L, manager.getUserId());
        assertFalse(manager.isActive(NOW));
    }

    @Test
    public void createdSessionIsActiveUntilItExpires() {
        manager.create(sessions, userId, FIVE_MINUTES, NOW);

        assertEquals(SessionManager.State.ACTIVE, manager.check(sessions, NOW + 1));
        assertEquals(SessionManager.State.ACTIVE, manager.check(sessions, NOW + FIVE_MINUTES - 1));
        assertEquals(SessionManager.State.EXPIRED, manager.check(sessions, NOW + FIVE_MINUTES));
        assertEquals(userId, manager.getUserId());
        assertEquals(NOW + FIVE_MINUTES, manager.getExpiresAt());
        assertTrue(manager.isActive(NOW + FIVE_MINUTES - 1));
        assertFalse(manager.isActive(NOW + FIVE_MINUTES));
    }

    @Test
    public void onlyTheHashOfTheTokenIsStored() {
        manager.create(sessions, userId, FIVE_MINUTES, NOW);

        List<Session> rows = sessions.listForUser(userId);
        assertEquals(1, rows.size());
        Session row = rows.get(0);
        assertEquals(NOW, row.createdAt);
        assertEquals(NOW + FIVE_MINUTES, row.expiresAt);
        // a SHA-256 hash in Base64 is 44 characters, the raw token is 43 URL-safe ones
        assertEquals(44, row.tokenHash.length());
        assertNotNull(sessions.findValid(userId, row.tokenHash, NOW + 1));
    }

    @Test
    public void aNewLoginReplacesTheUsersOlderSession() {
        manager.create(sessions, userId, FIVE_MINUTES, NOW);
        String firstHash = sessions.listForUser(userId).get(0).tokenHash;
        manager.create(sessions, userId, FIVE_MINUTES, NOW + 10);

        List<Session> rows = sessions.listForUser(userId);
        assertEquals(1, rows.size());
        assertNotEquals(firstHash, rows.get(0).tokenHash);
    }

    @Test
    public void creatingASessionDoesNotTouchOtherUsersOrKeepsExpiredRows() {
        Session stale = new Session();
        stale.userId = otherUserId;
        stale.tokenHash = "old";
        stale.createdAt = 1L;
        stale.expiresAt = 2L; // long expired
        sessions.insert(stale);

        Session live = new Session();
        live.userId = otherUserId;
        live.tokenHash = "live";
        live.createdAt = NOW;
        live.expiresAt = NOW + 999_999L;
        sessions.insert(live);

        manager.create(sessions, userId, FIVE_MINUTES, NOW);

        List<Session> others = sessions.listForUser(otherUserId);
        assertEquals(1, others.size());
        assertEquals("live", others.get(0).tokenHash); // expired row removed, live row kept
    }

    @Test
    public void ifTheDatabaseRowIsGoneTheMemoryTokenIsNotTrusted() {
        manager.create(sessions, userId, FIVE_MINUTES, NOW);
        sessions.deleteForUser(userId);

        assertEquals(SessionManager.State.NONE, manager.check(sessions, NOW + 1));
    }

    @Test
    public void clearForgetsTheSessionAndReportsWhoItWas() {
        manager.create(sessions, userId, FIVE_MINUTES, NOW);
        long rowId = sessions.listForUser(userId).get(0).id;

        SessionManager.Snapshot ended = manager.clear();
        assertNotNull(ended);
        assertEquals(userId, ended.userId);
        assertEquals(rowId, ended.sessionId);

        assertEquals(-1L, manager.getUserId());
        assertEquals(SessionManager.State.NONE, manager.check(sessions, NOW + 1));
        assertNull(manager.clear()); // nothing left to clear
    }

    @Test
    public void autoLockDefaultsToFiveMinutesAndStaysBetweenOneAndThirty() {
        assertEquals(FIVE_MINUTES, SessionManager.getAutoLockMs(context));

        android.content.SharedPreferences.Editor prefs =
                context.getSharedPreferences("secureauth_prefs", Context.MODE_PRIVATE).edit();
        prefs.putLong("auto_lock_ms", 10 * 60 * 1000L).commit();
        assertEquals(10 * 60 * 1000L, SessionManager.getAutoLockMs(context));

        prefs.putLong("auto_lock_ms", 5_000L).commit(); // too small
        assertEquals(60 * 1000L, SessionManager.getAutoLockMs(context));

        prefs.putLong("auto_lock_ms", 3 * 60 * 60 * 1000L).commit(); // too large
        assertEquals(30 * 60 * 1000L, SessionManager.getAutoLockMs(context));
    }
}
