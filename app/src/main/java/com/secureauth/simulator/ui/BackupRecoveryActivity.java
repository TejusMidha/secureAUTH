package com.secureauth.simulator.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.BackupCodeGenerator;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.BackupCode;
import com.secureauth.simulator.data.BackupCodeDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.LockoutManager;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;
import com.secureauth.simulator.databinding.ActivityBackupRecoveryBinding;

import java.util.List;

/**
 * Lost your authenticator? Spend one backup code either to log in this once (it replaces the
 * 6-digit code step) or to set up a new authenticator. Every code works only once, and wrong
 * codes count toward the same 3-strike lockout as wrong passwords.
 *
 * Result for the caller (TotpVerifyActivity): RESULT_OK = the code step is done, RESULT_LOCKED =
 * the account got locked, RESULT_CANCELED = nothing was completed.
 */
public class BackupRecoveryActivity extends BaseActivity {

    public static final String EXTRA_USER_ID = "user_id";
    /** true = this phone was revoked: just one more backup code is asked for, no recovery choices. */
    public static final String EXTRA_REVOKED_DEVICE = "revoked_device";
    public static final int RESULT_LOCKED = RESULT_FIRST_USER;

    private enum Status { OK, WRONG, LOCKED }

    /** What one background check found. */
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

    /** State when the screen opens. */
    private static final class Loaded {
        final long lockRemainingMs;
        final int unusedCodes;

        Loaded(long lockRemainingMs, int unusedCodes) {
            this.lockRemainingMs = lockRemainingMs;
            this.unusedCodes = unusedCodes;
        }
    }

    private ActivityBackupRecoveryBinding binding;
    private UserDao userDao;
    private BackupCodeDao codeDao;
    private AuditLogDao auditDao;
    private LockoutManager lockout;
    private long userId;
    private boolean revokedDevice;

    // The "set up a new authenticator" path hands over to the Enroll screen and waits for it
    private final ActivityResultLauncher<Intent> enrollLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                setResult(result.getResultCode() == RESULT_OK ? RESULT_OK : RESULT_CANCELED);
                finish();
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        userId = getIntent().getLongExtra(EXTRA_USER_ID, -1L);
        if (userId < 0) {
            finish();
            return;
        }
        revokedDevice = getIntent().getBooleanExtra(EXTRA_REVOKED_DEVICE, false);

        binding = ActivityBackupRecoveryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.recoveryRoot);

        DbHelper db = DbHelper.getInstance(this);
        userDao = new UserDao(db);
        codeDao = new BackupCodeDao(db);
        auditDao = new AuditLogDao(db);
        lockout = new LockoutManager(userDao, auditDao);

        binding.inputBackupCode.addTextChangedListener(new AfterTextChangedListener() {
            @Override
            public void afterTextChanged(Editable s) {
                binding.layoutBackupCode.setError(null);
            }
        });
        binding.buttonContinue.setOnClickListener(v -> onContinueClicked());

        if (revokedDevice) {
            // Only the "log in with a code" path makes sense here (it is the radio that stays ticked)
            binding.radioGroup.setVisibility(android.view.View.GONE);
            binding.textSubtitle.setText(R.string.recovery_subtitle_revoked);
            binding.textNoCodes.setText(R.string.recovery_revoked_no_codes);
        }

        loadState();
    }

    /** Is the account locked, and are there any unused codes at all? */
    private void loadState() {
        runInBackground(() -> {
            long now = System.currentTimeMillis();
            User user = userDao.getById(userId);
            if (user == null) {
                return null;
            }
            lockout.clearExpiredLock(user, now);
            user = userDao.getById(userId);
            return new Loaded(lockout.remainingLockMs(user, now), codeDao.listUnused(userId).size());
        }, loaded -> {
            if (loaded == null) {
                finish();
            } else if (loaded.lockRemainingMs > 0) {
                finishLocked(loaded.lockRemainingMs);
            } else if (loaded.unusedCodes == 0) {
                binding.textNoCodes.setVisibility(android.view.View.VISIBLE); // nothing to try
            } else {
                setInputEnabled(true);
            }
        });
    }

    private void onContinueClicked() {
        String typed = String.valueOf(binding.inputBackupCode.getText());
        if (!BackupCodeGenerator.isWellFormed(typed)) {
            // Obviously not a code: tell the user without counting it as a failed attempt
            binding.layoutBackupCode.setError(getString(R.string.error_backup_code_format));
            return;
        }
        setInputEnabled(false);
        binding.buttonContinue.setText(R.string.please_wait);
        runInBackground(() -> checkCode(typed), this::onChecked);
    }

    /**
     * Background thread. Every stored code has its own salt, so the typed code is hashed once
     * per unused code until one matches (slow by design, up to 10 hashes).
     */
    private Outcome checkCode(String typed) {
        long now = System.currentTimeMillis();
        User user = userDao.getById(userId);
        lockout.clearExpiredLock(user, now);
        user = userDao.getById(userId);
        long remaining = lockout.remainingLockMs(user, now);
        if (remaining > 0) {
            return new Outcome(Status.LOCKED, 0, remaining);
        }

        List<BackupCode> unused = codeDao.listUnused(userId);
        for (BackupCode candidate : unused) {
            if (BackupCodeGenerator.matches(typed, candidate.codeHash, candidate.salt)) {
                // markUsed only succeeds once per code, so two quick tries cannot both win
                if (codeDao.markUsed(candidate.id) == 1) {
                    auditDao.log(userId, AuditEvent.BACKUP_CODE_USED, AuditEvent.RESULT_SUCCESS);
                    lockout.recordSuccess(userId);
                    return new Outcome(Status.OK, 0, 0);
                }
                break; // it was spent a moment ago: same as a wrong code
            }
        }

        auditDao.log(userId, AuditEvent.BACKUP_CODE_FAIL, AuditEvent.RESULT_FAIL);
        if (lockout.recordFailure(userId, now)) {
            return new Outcome(Status.LOCKED, 0, LockoutManager.LOCK_DURATION_MS);
        }
        int left = LockoutManager.MAX_FAILED_ATTEMPTS - userDao.getById(userId).failedAttempts;
        return new Outcome(Status.WRONG, left, 0);
    }

    private void onChecked(Outcome outcome) {
        binding.buttonContinue.setText(R.string.button_continue);
        switch (outcome.status) {
            case OK:
                showToast(R.string.recovery_code_accepted);
                if (binding.radioReenroll.isChecked()) {
                    // Path 2: the code was only the key; now link a new authenticator app
                    enrollLauncher.launch(new Intent(this, EnrollActivity.class)
                            .putExtra(EnrollActivity.EXTRA_USER_ID, userId));
                } else {
                    // Path 1: the code replaces the 6-digit step, so the caller moves on
                    setResult(RESULT_OK);
                    finish();
                }
                break;
            case LOCKED:
                binding.inputBackupCode.setText("");
                finishLocked(outcome.remainingMs);
                break;
            default:
                setInputEnabled(true);
                binding.inputBackupCode.setText("");
                binding.layoutBackupCode.setError(outcome.attemptsLeft == 1
                        ? getString(R.string.error_backup_code_wrong_last)
                        : getString(R.string.error_backup_code_wrong, outcome.attemptsLeft));
                break;
        }
    }

    /** Locked: say so, then leave (the caller sends the user back to Login). */
    private void finishLocked(long remainingMs) {
        showLockDialog(remainingMs, () -> {
            setResult(RESULT_LOCKED);
            finish();
        });
    }

    private void setInputEnabled(boolean enabled) {
        binding.inputBackupCode.setEnabled(enabled);
        binding.buttonContinue.setEnabled(enabled);
    }
}
