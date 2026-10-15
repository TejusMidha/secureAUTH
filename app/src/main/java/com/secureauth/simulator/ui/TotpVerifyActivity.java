package com.secureauth.simulator.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.KeystoreHelper;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.DeviceTrust;
import com.secureauth.simulator.data.LockoutManager;
import com.secureauth.simulator.data.LoginHandshake;
import com.secureauth.simulator.data.TotpAccount;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.TrustedDeviceDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;
import com.secureauth.simulator.databinding.ActivityTotpVerifyBinding;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

/**
 * Step 2 of the login handshake: the 6-digit code from the authenticator app. Typing the last
 * digit submits automatically. Wrong codes count toward the same 3-strike lockout as wrong
 * passwords, and only a correct code resets that counter.
 */
public class TotpVerifyActivity extends BaseActivity {

    public static final String EXTRA_USER_ID = "user_id";

    private enum Status { OK, WRONG, LOCKED, SECRET_ERROR }

    /** Result of one background check, handed to the UI thread. */
    private static final class Outcome {
        final Status status;
        final int attemptsLeft;
        final long remainingMs;

        Outcome(Status status, int attemptsLeft, long remainingMs) {
            this.status = status;
            this.attemptsLeft = attemptsLeft;
            this.remainingMs = remainingMs;
        }
    }

    /** What the screen needs to know when it opens. */
    private static final class Loaded {
        final User user;
        final TotpAccount account; // null if the user never finished enrolling

        Loaded(User user, TotpAccount account) {
            this.user = user;
            this.account = account;
        }
    }

    private ActivityTotpVerifyBinding binding;
    private UserDao userDao;
    private TotpAccountDao accountDao;
    private TrustedDeviceDao deviceDao;
    private AuditLogDao auditDao;
    private LockoutManager lockout;

    private long userId;
    private TotpAccount account;
    private boolean checking; // true while a code is being verified; blocks double submits

    // First-time users have no authenticator yet: Enroll runs first, then we carry on
    private final ActivityResultLauncher<Intent> enrollLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    loadScreenData();
                } else {
                    finish(); // the user left enrollment, so they cannot pass this step
                }
            });

    // "Use a backup code instead": a good backup code stands in for the 6-digit code
    private final ActivityResultLauncher<Intent> recoveryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    continueAfterCodeStep();
                } else if (result.getResultCode() == BackupRecoveryActivity.RESULT_LOCKED) {
                    finish(); // the account is locked, so back to Login
                }
                // otherwise the user backed out: stay here and let them type a code
            });

    // A revoked phone must also hand over a backup code before the biometric step. If that
    // screen ends in anything but success (wrong codes, lock, Back), the login stops here.
    private final ActivityResultLauncher<Intent> revokedDeviceLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    goToBiometricGate();
                } else {
                    finish();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        userId = getIntent().getLongExtra(EXTRA_USER_ID, -1L);
        if (userId < 0) {
            finish();
            return;
        }
        // The user id arrives in an Intent, which proves nothing. Step 1 must really have passed.
        if (!LoginHandshake.getInstance().allowsCodeStep(userId, System.currentTimeMillis())) {
            goToLogin();
            return;
        }

        binding = ActivityTotpVerifyBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.totpRoot);

        DbHelper db = DbHelper.getInstance(this);
        userDao = new UserDao(db);
        accountDao = new TotpAccountDao(db);
        deviceDao = new TrustedDeviceDao(db);
        auditDao = new AuditLogDao(db);
        lockout = new LockoutManager(userDao, auditDao);

        binding.inputCode.addTextChangedListener(new AfterTextChangedListener() {
            @Override
            public void afterTextChanged(Editable s) {
                binding.layoutCode.setError(null);
                // Auto-submit the moment the last digit is typed
                if (account != null && !checking && s.length() == account.digits) {
                    submit(s.toString());
                }
            }
        });
        binding.buttonBackupCode.setOnClickListener(v -> recoveryLauncher.launch(
                new Intent(this, BackupRecoveryActivity.class)
                        .putExtra(BackupRecoveryActivity.EXTRA_USER_ID, userId)));

        loadScreenData();
    }

    /** Loads the user and their authenticator, then decides what to show. */
    private void loadScreenData() {
        runInBackground(() -> {
            User user = userDao.getById(userId);
            if (user == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            lockout.clearExpiredLock(user, now);
            // Re-read so a cleared lock is reflected in what we check below
            user = userDao.getById(userId);
            List<TotpAccount> accounts = accountDao.listForUser(userId);
            return new Loaded(user, accounts.isEmpty() ? null : accounts.get(0));
        }, this::onLoaded);
    }

    private void onLoaded(@Nullable Loaded loaded) {
        if (loaded == null) {
            finish();
            return;
        }
        long remaining = lockout.remainingLockMs(loaded.user, System.currentTimeMillis());
        if (remaining > 0) {
            showLockDialog(remaining, this::finish); // still locked from earlier attempts
            return;
        }
        if (loaded.account == null) {
            showToast(R.string.enroll_first);
            enrollLauncher.launch(new Intent(this, EnrollActivity.class)
                    .putExtra(EnrollActivity.EXTRA_USER_ID, userId));
            return;
        }
        account = loaded.account;
        binding.inputCode.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(account.digits)});
        binding.inputCode.setEnabled(true);
        binding.inputCode.requestFocus();
    }

    private void submit(String code) {
        checking = true;
        binding.inputCode.setEnabled(false);
        final TotpAccount toCheck = account;
        runInBackground(() -> checkCode(toCheck, code), this::onChecked);
    }

    /** Background thread: lock check, decrypt the secret, verify, audit, count failures. */
    private Outcome checkCode(TotpAccount toCheck, String code) {
        long now = System.currentTimeMillis();
        User user = userDao.getById(userId);
        lockout.clearExpiredLock(user, now);
        user = userDao.getById(userId);
        long remaining = lockout.remainingLockMs(user, now);
        if (remaining > 0) {
            return new Outcome(Status.LOCKED, 0, remaining);
        }

        byte[] secret;
        try {
            secret = KeystoreHelper.decrypt(toCheck.secretEnc, toCheck.iv);
        } catch (GeneralSecurityException e) {
            return new Outcome(Status.SECRET_ERROR, 0, 0);
        }
        boolean correct;
        try {
            correct = TotpEngine.verify(secret, code, now, toCheck.digits, toCheck.period, toCheck.algo);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }

        if (correct) {
            auditDao.log(userId, AuditEvent.TOTP_OK, AuditEvent.RESULT_SUCCESS);
            lockout.recordSuccess(userId); // the handshake is past password AND code: fresh start
            return new Outcome(Status.OK, 0, 0);
        }

        auditDao.log(userId, AuditEvent.TOTP_FAIL, AuditEvent.RESULT_FAIL);
        if (lockout.recordFailure(userId, now)) {
            return new Outcome(Status.LOCKED, 0, LockoutManager.LOCK_DURATION_MS);
        }
        int left = LockoutManager.MAX_FAILED_ATTEMPTS - userDao.getById(userId).failedAttempts;
        return new Outcome(Status.WRONG, left, 0);
    }

    /**
     * Step 2 is done (by the 6-digit code or by a backup code). If this phone was revoked in the
     * trusted devices list, one more backup code is needed first; otherwise on to step 3.
     */
    private void continueAfterCodeStep() {
        final android.content.Context appContext = getApplicationContext();
        runInBackground(() -> DeviceTrust.isCurrentDeviceRevoked(appContext, deviceDao, userId), revoked -> {
            if (revoked) {
                revokedDeviceLauncher.launch(new Intent(this, BackupRecoveryActivity.class)
                        .putExtra(BackupRecoveryActivity.EXTRA_USER_ID, userId)
                        .putExtra(BackupRecoveryActivity.EXTRA_REVOKED_DEVICE, true));
            } else {
                goToBiometricGate();
            }
        });
    }

    /** Step 3 of the handshake (explicit Intent). This screen is finished, so Back cannot return here. */
    private void goToBiometricGate() {
        // Record that step 2 is done (this only works right after step 1 for the same user)
        if (!LoginHandshake.getInstance().codeAccepted(userId, System.currentTimeMillis())) {
            goToLogin(); // the password step went stale while the code was being entered: start over
            return;
        }
        startActivity(new Intent(this, BiometricGateActivity.class)
                .putExtra(BiometricGateActivity.EXTRA_USER_ID, userId));
        finish();
    }

    private void onChecked(Outcome outcome) {
        checking = false;
        switch (outcome.status) {
            case OK:
                continueAfterCodeStep();
                break;
            case LOCKED:
                binding.inputCode.setText("");
                showLockDialog(outcome.remainingMs, this::finish); // back to Login
                break;
            case SECRET_ERROR:
                showToast(R.string.totp_secret_error);
                finish();
                break;
            default:
                binding.inputCode.setEnabled(true);
                binding.inputCode.setText("");
                binding.inputCode.requestFocus();
                binding.layoutCode.setError(outcome.attemptsLeft == 1
                        ? getString(R.string.totp_wrong_code_last)
                        : getString(R.string.totp_wrong_code, outcome.attemptsLeft));
                break;
        }
    }
}
