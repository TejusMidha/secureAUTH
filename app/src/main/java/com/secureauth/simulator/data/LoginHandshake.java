package com.secureauth.simulator.data;

/**
 * Remembers HOW FAR the current login has got, so a later step can check that the earlier steps
 * really happened instead of believing a user id passed in an Intent.
 *
 * <pre>
 *   Login screen: password correct        -> PASSWORD_OK  (allows the code step and its recovery)
 *   Code step: code (or backup code) ok   -> CODE_OK      (allows the fingerprint step)
 *   Fingerprint step: session created     -> cleared
 * </pre>
 *
 * A new account may go straight to enrollment, which is its own small stage (REGISTERED).
 *
 * Only the app's own screens can start the screens that use this (none of them is exported), so
 * this is a second lock behind the first: it also protects against a mistake inside the app that
 * would open a later step without the earlier ones. It lives in memory only, for one user at a
 * time, and a stage expires after {@link #MAX_AGE_MS}.
 */
public final class LoginHandshake {

    /** How long a stage stays valid. Long enough to read a QR code, short enough to go stale. */
    public static final long MAX_AGE_MS = 10 * 60 * 1000L;

    private enum Stage { NONE, REGISTERED, PASSWORD_OK, CODE_OK }

    private static final LoginHandshake INSTANCE = new LoginHandshake();

    private Stage stage = Stage.NONE;
    private long userId = -1;
    private long since;

    private LoginHandshake() {
    }

    public static LoginHandshake getInstance() {
        return INSTANCE;
    }

    // ---- moving forward ---------------------------------------------------------------------

    /** The user just created this account: enrollment may open for it. Replaces any earlier stage. */
    public synchronized void registered(long forUserId, long now) {
        enter(Stage.REGISTERED, forUserId, now);
    }

    /** The password was correct. Replaces any earlier stage (a new login starts from here). */
    public synchronized void passwordAccepted(long forUserId, long now) {
        enter(Stage.PASSWORD_OK, forUserId, now);
    }

    /**
     * The 6-digit code (or a backup code in its place) was accepted. Only valid right after a
     * correct password for the SAME user; returns false, and changes nothing, otherwise.
     */
    public synchronized boolean codeAccepted(long forUserId, long now) {
        if (!is(Stage.PASSWORD_OK, forUserId, now)) {
            return false;
        }
        enter(Stage.CODE_OK, forUserId, now);
        return true;
    }

    /** Forgets everything: back to the start of the login. */
    public synchronized void clear() {
        stage = Stage.NONE;
        userId = -1;
        since = 0;
    }

    // ---- asking -----------------------------------------------------------------------------

    /** May the 6-digit code step (and the backup-code screen) open for this user? */
    public synchronized boolean allowsCodeStep(long forUserId, long now) {
        return is(Stage.PASSWORD_OK, forUserId, now);
    }

    /** May the fingerprint step open for this user? Only after the code step. */
    public synchronized boolean allowsBiometricStep(long forUserId, long now) {
        return is(Stage.CODE_OK, forUserId, now);
    }

    /** May enrollment open for this user (without a session)? After registering or the password step. */
    public synchronized boolean allowsEnroll(long forUserId, long now) {
        return is(Stage.REGISTERED, forUserId, now) || is(Stage.PASSWORD_OK, forUserId, now);
    }

    // ---- inside -----------------------------------------------------------------------------

    private void enter(Stage next, long forUserId, long now) {
        stage = next;
        userId = forUserId;
        since = now;
    }

    private boolean is(Stage wanted, long forUserId, long now) {
        return stage == wanted
                && forUserId >= 0
                && userId == forUserId
                && now >= since
                && now - since <= MAX_AGE_MS;
    }
}
