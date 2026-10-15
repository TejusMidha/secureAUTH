package com.secureauth.simulator.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/** The order of the login steps, as LoginHandshake enforces it (time is passed in, so no waiting). */
public class LoginHandshakeTest {

    private static final long T0 = 1_000_000L;
    private static final long ALICE = 1;
    private static final long BOB = 2;

    private LoginHandshake handshake;

    @Before
    public void setUp() {
        handshake = LoginHandshake.getInstance();
        handshake.clear();
    }

    @Test
    public void nothingOpensBeforeAnyStepHasPassed() {
        assertFalse(handshake.allowsCodeStep(ALICE, T0));
        assertFalse(handshake.allowsBiometricStep(ALICE, T0));
        assertFalse(handshake.allowsEnroll(ALICE, T0));
    }

    @Test
    public void aCorrectPasswordOpensTheCodeStepAndEnrollmentButNotTheFingerprintStep() {
        handshake.passwordAccepted(ALICE, T0);

        assertTrue(handshake.allowsCodeStep(ALICE, T0 + 1));
        assertTrue(handshake.allowsEnroll(ALICE, T0 + 1));
        assertFalse("the code step cannot be skipped", handshake.allowsBiometricStep(ALICE, T0 + 1));
    }

    @Test
    public void theFingerprintStepOpensOnlyAfterTheCodeStep() {
        handshake.passwordAccepted(ALICE, T0);
        assertTrue(handshake.codeAccepted(ALICE, T0 + 10));

        assertTrue(handshake.allowsBiometricStep(ALICE, T0 + 11));
        assertFalse("the code step is behind us", handshake.allowsCodeStep(ALICE, T0 + 11));
    }

    @Test
    public void aCodeCannotBeAcceptedWithoutAPasswordFirst() {
        assertFalse(handshake.codeAccepted(ALICE, T0));
        assertFalse(handshake.allowsBiometricStep(ALICE, T0));
    }

    @Test
    public void aCodeCannotBeAcceptedTwiceOrAfterRegisteringOnly() {
        handshake.registered(ALICE, T0);
        assertFalse("registering is not a password step", handshake.codeAccepted(ALICE, T0 + 1));

        handshake.passwordAccepted(ALICE, T0 + 2);
        assertTrue(handshake.codeAccepted(ALICE, T0 + 3));
        assertFalse("already past the code step", handshake.codeAccepted(ALICE, T0 + 4));
    }

    @Test
    public void aStageBelongsToOneUserOnly() {
        handshake.passwordAccepted(ALICE, T0);

        assertFalse(handshake.allowsCodeStep(BOB, T0 + 1));
        assertFalse(handshake.codeAccepted(BOB, T0 + 1));
        assertFalse(handshake.allowsEnroll(BOB, T0 + 1));
        assertTrue(handshake.allowsCodeStep(ALICE, T0 + 1));
    }

    @Test
    public void aNewPasswordForAnotherUserReplacesTheEarlierProgress() {
        handshake.passwordAccepted(ALICE, T0);
        handshake.codeAccepted(ALICE, T0 + 1);

        handshake.passwordAccepted(BOB, T0 + 2);

        assertFalse("Alice's progress must not carry over", handshake.allowsBiometricStep(ALICE, T0 + 3));
        assertTrue(handshake.allowsCodeStep(BOB, T0 + 3));
        assertFalse(handshake.allowsBiometricStep(BOB, T0 + 3));
    }

    @Test
    public void aStageExpiresAfterTenMinutes() {
        handshake.passwordAccepted(ALICE, T0);

        assertTrue(handshake.allowsCodeStep(ALICE, T0 + LoginHandshake.MAX_AGE_MS));
        assertFalse(handshake.allowsCodeStep(ALICE, T0 + LoginHandshake.MAX_AGE_MS + 1));
        assertFalse("a stale password step cannot be turned into a code step",
                handshake.codeAccepted(ALICE, T0 + LoginHandshake.MAX_AGE_MS + 1));
    }

    @Test
    public void aClockThatMovesBackwardsIsNotTrusted() {
        handshake.passwordAccepted(ALICE, T0);
        assertFalse(handshake.allowsCodeStep(ALICE, T0 - 1));
    }

    @Test
    public void registeringOpensEnrollmentOnly() {
        handshake.registered(ALICE, T0);

        assertTrue(handshake.allowsEnroll(ALICE, T0 + 1));
        assertFalse(handshake.allowsCodeStep(ALICE, T0 + 1));
        assertFalse(handshake.allowsBiometricStep(ALICE, T0 + 1));
    }

    @Test
    public void clearForgetsEverything() {
        handshake.passwordAccepted(ALICE, T0);
        handshake.codeAccepted(ALICE, T0 + 1);

        handshake.clear();

        assertFalse(handshake.allowsBiometricStep(ALICE, T0 + 2));
        assertFalse(handshake.allowsCodeStep(ALICE, T0 + 2));
        assertFalse(handshake.allowsEnroll(ALICE, T0 + 2));
    }

    @Test
    public void anInvalidUserIdIsNeverAllowed() {
        handshake.passwordAccepted(-1, T0);
        assertFalse(handshake.allowsCodeStep(-1, T0));
    }
}
