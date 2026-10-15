package com.secureauth.simulator.data;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Remembers who is logged in. The raw session token lives ONLY in this object's memory
 * (so it disappears when the app process dies); the database keeps just its SHA-256 hash.
 * A screen is allowed to open only if the token in memory has not expired AND its hash
 * is still in the sessions table.
 */
public final class SessionManager {

    public enum State { NONE, EXPIRED, ACTIVE }

    /** Who the ended session belonged to, so the caller can delete the row and write the audit log. */
    public static final class Snapshot {
        public final long userId;
        public final long sessionId;

        Snapshot(long userId, long sessionId) {
            this.userId = userId;
            this.sessionId = sessionId;
        }
    }

    public static final long DEFAULT_AUTO_LOCK_MS = AppSettings.DEFAULT_AUTO_LOCK_MS;
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final SessionManager INSTANCE = new SessionManager();

    private String token;          // raw token, memory only
    private long userId = -1;
    private long sessionId = -1;
    private long expiresAt;

    private SessionManager() {
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    /** 32 random bytes from SecureRandom as URL-safe text (43 characters). */
    public static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 of the token, as Base64. A fast hash is enough here (unlike passwords): the token
     * is 256 random bits, so it cannot be guessed, only stolen, and the hash just stops a copy
     * of the database from containing a usable token.
     */
    public static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** The auto-lock time from Settings (default 5 minutes), kept inside 1 to 30 minutes. */
    public static long getAutoLockMs(Context context) {
        return AppSettings.getAutoLockMs(context);
    }

    /**
     * Starts a session for the user: clears out old rows, stores the token's hash with an
     * expiry time, and keeps the raw token in memory. Replaces any earlier session.
     */
    public synchronized void create(SessionDao dao, long forUserId, long durationMs, long now) {
        dao.deleteExpired(now);
        dao.deleteForUser(forUserId);

        String rawToken = generateToken();
        Session row = new Session();
        row.userId = forUserId;
        row.tokenHash = hashToken(rawToken);
        row.createdAt = now;
        row.expiresAt = now + durationMs;
        long rowId = dao.insert(row);
        if (rowId < 0) {
            throw new IllegalStateException("Could not store the session");
        }

        this.token = rawToken;
        this.userId = forUserId;
        this.sessionId = rowId;
        this.expiresAt = row.expiresAt;
    }

    /** Full check: token in memory, not expired, and its hash is still in the database. */
    public synchronized State check(SessionDao dao, long now) {
        if (token == null) {
            return State.NONE;
        }
        if (now >= expiresAt) {
            return State.EXPIRED;
        }
        // If the row was deleted (for example by a logout), the memory copy is no longer trusted
        if (dao.findValid(userId, hashToken(token), now) == null) {
            return State.NONE;
        }
        return State.ACTIVE;
    }

    /** Cheap check with no database access, used once a second while a screen is open. */
    public synchronized boolean isActive(long now) {
        return token != null && now < expiresAt;
    }

    /** The logged-in user, or -1. Screens use this instead of an Intent extra, which anyone could fake. */
    public synchronized long getUserId() {
        return token == null ? -1 : userId;
    }

    public synchronized long getExpiresAt() {
        return expiresAt;
    }

    /** Forgets the session. Returns who it was (null if there was none) so the row can be deleted. */
    public synchronized Snapshot clear() {
        Snapshot snapshot = token == null ? null : new Snapshot(userId, sessionId);
        token = null;
        userId = -1;
        sessionId = -1;
        expiresAt = 0;
        return snapshot;
    }
}
