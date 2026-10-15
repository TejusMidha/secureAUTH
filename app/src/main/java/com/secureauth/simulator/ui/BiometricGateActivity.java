package com.secureauth.simulator.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.secureauth.simulator.R;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.LockoutManager;
import com.secureauth.simulator.data.LoginHandshake;
import com.secureauth.simulator.data.SessionDao;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.UserDao;
import com.secureauth.simulator.databinding.ActivityBiometricGateBinding;
import com.secureauth.simulator.location.LocationHelper;

/**
 * Step 3 of the login handshake: the system fingerprint prompt (or the screen lock as a
 * fallback). Only a successful check here creates a session, and nothing else does, so
 * the Dashboard cannot be reached without passing password, TOTP code and this step.
 */
public class BiometricGateActivity extends BaseActivity {

    public static final String EXTRA_USER_ID = "user_id";

    /**
     * Optional, true by default. Tests pass false so the system fingerprint prompt does not open
     * over the screen under test (it cannot be pressed from a test, and it hides the screen when the
     * phone has a screen lock). The app itself never sets it. With the prompt off no check can
     * succeed, so no session can be created through this switch.
     */
    public static final String EXTRA_SHOW_PROMPT = "show_prompt";

    /** Returned by the login step when the place is fine (a real distance is always positive). */
    private static final double NOT_SUSPICIOUS = -1.0;

    private ActivityBiometricGateBinding binding;
    private AuditLogDao auditDao;
    private SessionDao sessionDao;
    private LockoutManager lockout;

    private long userId;
    private BiometricPrompt prompt;
    private BiometricPrompt.PromptInfo promptInfo;
    private int authenticators;

    /** Set once this screen has finished its job (success, lock or cancel); later callbacks are ignored. */
    private boolean flowDone;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        userId = getIntent().getLongExtra(EXTRA_USER_ID, -1L);
        if (userId < 0) {
            finish();
            return;
        }
        // Steps 1 and 2 must really have passed; an Intent saying so is not proof
        if (!LoginHandshake.getInstance().allowsBiometricStep(userId, System.currentTimeMillis())) {
            goToLogin();
            return;
        }

        binding = ActivityBiometricGateBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.biometricRoot);

        DbHelper db = DbHelper.getInstance(this);
        auditDao = new AuditLogDao(db);
        sessionDao = new SessionDao(db);
        lockout = new LockoutManager(new UserDao(db), auditDao);

        authenticators = BiometricSupport.authenticators(Build.VERSION.SDK_INT);
        // The prompt reports back through this callback, always on the main thread
        prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), new PromptCallback());
        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_prompt_title))
                .setSubtitle(getString(R.string.biometric_prompt_subtitle))
                .setAllowedAuthenticators(authenticators) // no negative button: the screen lock is the fallback
                .build();

        binding.buttonTryAgain.setOnClickListener(v -> startAuthentication());
        binding.buttonBackToLogin.setOnClickListener(v -> finish());

        // After a rotation the system keeps the open prompt and re-attaches our new callback
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_SHOW_PROMPT, true)) {
            startAuthentication();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            // Cancelled, locked or finished: the earlier steps may not be reused to open this screen again
            LoginHandshake.getInstance().clear();
        }
    }

    // ---- showing the prompt -----------------------------------------------------------------

    private void startAuthentication() {
        binding.buttonTryAgain.setVisibility(View.GONE);
        int canAuthenticate = BiometricManager.from(this).canAuthenticate(authenticators);
        switch (BiometricSupport.fromCanAuthenticate(canAuthenticate)) {
            case READY:
                binding.textStatus.setText(R.string.biometric_status_waiting);
                prompt.authenticate(promptInfo);
                break;
            case NOT_ENROLLED:
                showNotEnrolledDialog();
                break;
            default:
                showUnavailableDialog();
                break;
        }
    }

    /** The phone could check, but nothing is set up: explain how to fix it. */
    private void showNotEnrolledDialog() {
        binding.textStatus.setText(R.string.biometric_not_enrolled_title);
        binding.buttonTryAgain.setVisibility(View.VISIBLE);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.biometric_not_enrolled_title)
                .setMessage(R.string.biometric_not_enrolled_message)
                .setPositiveButton(R.string.button_open_settings, (d, w) -> openEnrollmentSettings())
                .setNegativeButton(R.string.button_back_to_login, (d, w) -> finish())
                .show();
    }

    private void showUnavailableDialog() {
        binding.textStatus.setText(R.string.biometric_unavailable_title);
        // We never skip this step: without a working check the login cannot be completed
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.biometric_unavailable_title)
                .setMessage(R.string.biometric_unavailable_message)
                .setPositiveButton(R.string.button_back_to_login, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    /** Implicit Intent to the system screen where a fingerprint or screen lock is added. */
    private void openEnrollmentSettings() {
        Intent enroll;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            enroll = new Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                    .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, authenticators);
        } else {
            enroll = new Intent(Settings.ACTION_SECURITY_SETTINGS);
        }
        try {
            startActivity(enroll);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ---- the prompt's results ---------------------------------------------------------------

    private class PromptCallback extends BiometricPrompt.AuthenticationCallback {
        @Override
        public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
            handleSuccess();
        }

        @Override
        public void onAuthenticationFailed() {
            handleFailedAttempt(); // a finger that did not match; the prompt stays open
        }

        @Override
        public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
            handleError(errorCode, errString);
        }
    }

    /** Check passed: find the place (if allowed), then finish the login with it. */
    void handleSuccess() {
        if (flowDone) {
            return;
        }
        flowDone = true;
        withCurrentLocation(location -> completeLogin(
                location == null ? null : location.getLatitude(),
                location == null ? null : location.getLongitude()));
    }

    /**
     * Creates the session and opens the Dashboard on a fresh back stack. If the login place is
     * more than 100 km from the previous successful login, also records SUSPICIOUS_LOCATION and
     * tells the Dashboard to warn the user. lat/lng are null when there is no location.
     */
    void completeLogin(@Nullable Double lat, @Nullable Double lng) {
        final long autoLockMs = SessionManager.getAutoLockMs(this);

        runInBackground(() -> {
            long now = System.currentTimeMillis();
            try {
                lockout.recordSuccess(userId); // the whole handshake has now passed: fresh start
                auditDao.log(userId, AuditEvent.BIOMETRIC_OK, AuditEvent.RESULT_SUCCESS);

                // Compare with the previous login BEFORE this one is written, or it would be
                // compared with itself. No place now, or no earlier place, means nothing to compare.
                double suspiciousKm = NOT_SUSPICIOUS;
                if (lat != null && lng != null) {
                    AuditLog previous = auditDao.findLastWithLocation(userId, AuditEvent.SESSION_START);
                    if (previous != null) {
                        double km = LocationHelper.distanceKm(previous.lat, previous.lng, lat, lng);
                        if (LocationHelper.isSuspicious(km)) {
                            suspiciousKm = km;
                        }
                    }
                }

                SessionManager.getInstance().create(sessionDao, userId, autoLockMs, now);
                LoginHandshake.getInstance().clear(); // the handshake is complete: nothing left to prove
                auditDao.log(userId, AuditEvent.SESSION_START, AuditEvent.RESULT_SUCCESS, lat, lng);
                if (suspiciousKm != NOT_SUSPICIOUS) {
                    auditDao.log(userId, AuditEvent.SUSPICIOUS_LOCATION, AuditEvent.RESULT_WARNING, lat, lng);
                }
                return suspiciousKm;
            } catch (IllegalStateException e) {
                return null; // the session could not be stored
            }
        }, suspiciousKm -> {
            if (suspiciousKm == null) {
                flowDone = false;
                binding.textStatus.setText(R.string.session_error);
                binding.buttonTryAgain.setVisibility(View.VISIBLE);
                return;
            }
            Intent dashboard = new Intent(this, DashboardActivity.class)
                    // Empty back stack: Back from the Dashboard leaves the app instead of
                    // returning to a login screen
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (suspiciousKm != NOT_SUSPICIOUS) {
                dashboard.putExtra(DashboardActivity.EXTRA_SUSPICIOUS_KM, suspiciousKm);
            }
            startActivity(dashboard);
            finish();
        });
    }

    /** One wrong attempt: log it and count it toward the 3-strike lockout. */
    void handleFailedAttempt() {
        if (flowDone) {
            return;
        }
        binding.textStatus.setText(R.string.biometric_status_wrong);
        runInBackground(() -> {
            auditDao.log(userId, AuditEvent.BIOMETRIC_FAIL, AuditEvent.RESULT_FAIL);
            return lockout.recordFailure(userId, System.currentTimeMillis());
        }, nowLocked -> {
            if (nowLocked && !flowDone) {
                flowDone = true;
                prompt.cancelAuthentication(); // close the system prompt before our dialog
                showLockDialog(LockoutManager.LOCK_DURATION_MS, this::finish);
            }
        });
    }

    /** The user backed out: leave the handshake and return to Login. */
    void handleCancelled() {
        if (flowDone) {
            return;
        }
        flowDone = true;
        runInBackground(() -> auditDao.log(userId, AuditEvent.BIOMETRIC_FAIL,
                AuditEvent.RESULT_CANCELLED), id -> finish());
    }

    /** The prompt ended with an error (cancelled, too many tries, hardware problem...). */
    void handleError(int errorCode, CharSequence message) {
        if (flowDone) {
            return;
        }
        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED
                || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                || errorCode == BiometricPrompt.ERROR_CANCELED) {
            handleCancelled();
            return;
        }

        runInBackground(() -> auditDao.log(userId, AuditEvent.BIOMETRIC_FAIL,
                AuditEvent.RESULT_ERROR), id -> { });
        boolean lockedByDevice = errorCode == BiometricPrompt.ERROR_LOCKOUT
                || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT;
        binding.textStatus.setText(lockedByDevice
                ? getString(R.string.biometric_status_lockout)
                : getString(R.string.biometric_status_error, message));
        binding.buttonTryAgain.setVisibility(View.VISIBLE);
    }
}
