package com.secureauth.simulator.data;

import java.util.Locale;

/**
 * Brute-force protection: 3 failures in a row lock the account for 5 minutes.
 * The counters live in the users table (failed_attempts, locked_until), so the lock
 * survives closing the app. Phase 7 reuses this for wrong TOTP codes.
 */
public class LockoutManager {

    public static final int MAX_FAILED_ATTEMPTS = 3;
    public static final long LOCK_DURATION_MS = 5 * 60 * 1000L;

    private final UserDao users;
    private final AuditLogDao audit;

    public LockoutManager(UserDao users, AuditLogDao audit) {
        this.users = users;
        this.audit = audit;
    }

    /** Milliseconds the user is still locked out at time {@code now}; 0 if not locked. */
    public long remainingLockMs(User user, long now) {
        return Math.max(0L, user.lockedUntil - now);
    }

    /**
     * If an earlier lock has run out, start fresh with 0 failures. Without this the user
     * would be locked again by their very next mistake, because the old count of 3 was kept.
     */
    public void clearExpiredLock(User user, long now) {
        if (user.lockedUntil != 0L && user.lockedUntil <= now) {
            users.resetFailedAttempts(user.id);
            users.setLockedUntil(user.id, 0L);
        }
    }

    /**
     * Counts one more failure. On the 3rd it sets the lock and writes a LOCKED_OUT audit row.
     * Returns true if the account is now locked.
     */
    public boolean recordFailure(long userId, long now) {
        users.incrementFailedAttempts(userId);
        User fresh = users.getById(userId);
        if (fresh.failedAttempts < MAX_FAILED_ATTEMPTS) {
            return false;
        }
        users.setLockedUntil(userId, now + LOCK_DURATION_MS);
        audit.log(userId, AuditEvent.LOCKED_OUT, AuditEvent.RESULT_FAIL);
        return true;
    }

    /** A correct step clears the failure counter and any lock. */
    public void recordSuccess(long userId) {
        users.resetFailedAttempts(userId);
        users.setLockedUntil(userId, 0L);
    }

    /** Formats a wait as m:ss for the lock dialog, rounding up (299.5 s shows as 5:00). */
    public static String formatRemaining(long ms) {
        long totalSeconds = (ms + 999) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
