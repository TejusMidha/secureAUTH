package com.secureauth.simulator.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Lockout rules on an in-memory database. */
public class LockoutManagerTest {

    private static final long NOW = 1_000_000L;

    private DbHelper helper;
    private UserDao users;
    private AuditLogDao audit;
    private LockoutManager lockout;
    private long userId;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null);
        users = new UserDao(helper);
        audit = new AuditLogDao(helper);
        lockout = new LockoutManager(users, audit);

        User u = new User();
        u.username = "alice";
        u.passwordHash = "h";
        u.salt = "s";
        u.createdAt = 1L;
        userId = users.insert(u);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    @Test
    public void firstTwoFailuresDoNotLock() {
        assertFalse(lockout.recordFailure(userId, NOW));
        assertFalse(lockout.recordFailure(userId, NOW));
        User u = users.getById(userId);
        assertEquals(2, u.failedAttempts);
        assertEquals(0L, u.lockedUntil);
        assertEquals(0L, lockout.remainingLockMs(u, NOW));
    }

    @Test
    public void thirdFailureLocksForFiveMinutesAndIsAudited() {
        lockout.recordFailure(userId, NOW);
        lockout.recordFailure(userId, NOW);
        assertTrue(lockout.recordFailure(userId, NOW));

        User u = users.getById(userId);
        assertEquals(NOW + 5 * 60 * 1000L, u.lockedUntil);
        assertEquals(5 * 60 * 1000L, lockout.remainingLockMs(u, NOW));
        assertEquals(1, audit.listForUser(userId).size());
        assertEquals(AuditEvent.LOCKED_OUT, audit.listForUser(userId).get(0).event);
    }

    @Test
    public void remainingTimeShrinksAndHitsZero() {
        for (int i = 0; i < 3; i++) {
            lockout.recordFailure(userId, NOW);
        }
        User u = users.getById(userId);
        assertEquals(60_000L, lockout.remainingLockMs(u, NOW + 4 * 60_000L));
        assertEquals(0L, lockout.remainingLockMs(u, NOW + 5 * 60_000L));
        assertEquals(0L, lockout.remainingLockMs(u, NOW + 10 * 60_000L));
    }

    @Test
    public void expiredLockIsClearedSoUserGetsThreeFreshTries() {
        for (int i = 0; i < 3; i++) {
            lockout.recordFailure(userId, NOW);
        }
        long later = NOW + 5 * 60_000L;
        lockout.clearExpiredLock(users.getById(userId), later);

        User u = users.getById(userId);
        assertEquals(0, u.failedAttempts);
        assertEquals(0L, u.lockedUntil);
        // one more mistake must not lock again immediately
        assertFalse(lockout.recordFailure(userId, later));
    }

    @Test
    public void activeLockIsNotClearedEarly() {
        for (int i = 0; i < 3; i++) {
            lockout.recordFailure(userId, NOW);
        }
        lockout.clearExpiredLock(users.getById(userId), NOW + 60_000L);
        assertEquals(3, users.getById(userId).failedAttempts);
        assertTrue(users.getById(userId).lockedUntil > 0);
    }

    @Test
    public void successResetsCounterAndLock() {
        lockout.recordFailure(userId, NOW);
        lockout.recordFailure(userId, NOW);
        lockout.recordSuccess(userId);

        User u = users.getById(userId);
        assertEquals(0, u.failedAttempts);
        assertEquals(0L, u.lockedUntil);
    }

    @Test
    public void remainingTimeIsFormattedAsMinutesAndSeconds() {
        assertEquals("5:00", LockoutManager.formatRemaining(300_000L));
        assertEquals("4:59", LockoutManager.formatRemaining(299_000L));
        assertEquals("4:59", LockoutManager.formatRemaining(298_001L));
        assertEquals("0:01", LockoutManager.formatRemaining(1L));
        assertEquals("0:00", LockoutManager.formatRemaining(0L));
    }
}
