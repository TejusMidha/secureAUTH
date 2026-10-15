package com.secureauth.simulator.ui;

import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.text.Editable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.PasswordHasher;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.LockoutManager;
import com.secureauth.simulator.data.LoginHandshake;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;
import com.secureauth.simulator.databinding.ActivityLoginBinding;

import java.util.Arrays;
import java.util.Base64;

/**
 * First step of the login handshake: username + password. A wrong password counts toward the
 * 3-strike lockout. A correct one goes on to the TOTP check (explicit Intent, passing userId).
 */
public class LoginActivity extends BaseActivity {

    /**
     * Used when the username does not exist, so we still spend the same time hashing.
     * Otherwise a fast "no such user" answer would reveal which usernames are registered.
     */
    private static final String DUMMY_HASH = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String DUMMY_SALT = Base64.getEncoder().encodeToString(new byte[16]);

    private enum Status { OK, BAD_CREDENTIALS, LOCKED }

    /** What the background check found, handed back to the UI thread. */
    private static final class Outcome {
        final Status status;
        final long userId;
        final long remainingMs;

        Outcome(Status status, long userId, long remainingMs) {
            this.status = status;
            this.userId = userId;
            this.remainingMs = remainingMs;
        }
    }

    private ActivityLoginBinding binding;
    private UserDao userDao;
    private AuditLogDao auditDao;
    private LockoutManager lockout;

    // Opens Register and gets the new username back (replaces startActivityForResult)
    private final ActivityResultLauncher<Intent> registerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                if (result.getResultCode() == RESULT_OK && data != null) {
                    binding.inputUsername.setText(data.getStringExtra(RegisterActivity.EXTRA_USERNAME));
                    binding.inputPassword.requestFocus();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.loginRoot);

        DbHelper db = DbHelper.getInstance(this);
        userDao = new UserDao(db);
        auditDao = new AuditLogDao(db);
        lockout = new LockoutManager(userDao, auditDao);

        binding.inputUsername.addTextChangedListener(new AfterTextChangedListener() {
            @Override
            public void afterTextChanged(Editable s) {
                binding.layoutUsername.setError(null);
            }
        });
        binding.inputPassword.addTextChangedListener(new AfterTextChangedListener() {
            @Override
            public void afterTextChanged(Editable s) {
                binding.layoutPassword.setError(null);
            }
        });

        binding.buttonLogin.setOnClickListener(v -> onLoginClicked());
        binding.buttonCreateAccount.setOnClickListener(
                v -> registerLauncher.launch(new Intent(this, RegisterActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back at the first step (also after cancelling a later one): whatever was reached is forgotten
        LoginHandshake.getInstance().clear();
    }

    private void onLoginClicked() {
        String username = InputRules.normalizeUsername(String.valueOf(binding.inputUsername.getText()));
        char[] password = readChars(binding.inputPassword);

        boolean usernameMissing = username.isEmpty();
        boolean passwordMissing = password.length == 0;
        binding.layoutUsername.setError(usernameMissing ? getString(R.string.error_username_required) : null);
        binding.layoutPassword.setError(passwordMissing ? getString(R.string.error_password_required) : null);
        if (usernameMissing || passwordMissing) {
            Arrays.fill(password, '\0');
            return;
        }

        // These checks only needed the length. The password is read again below, right before it
        // is used, so it is not kept in memory while a location dialog is open.
        Arrays.fill(password, '\0');

        setBusy(true);
        // First time only: explain and ask about location. Then find the place (if allowed) and
        // check the password. A "no" or a timeout just means "no location"; the login goes on.
        ensureLocationPermission(() -> withCurrentLocation(
                location -> startPasswordCheck(username, location)));
    }

    private void startPasswordCheck(String username, @Nullable Location location) {
        char[] password = readChars(binding.inputPassword);
        final Double lat = location == null ? null : location.getLatitude();
        final Double lng = location == null ? null : location.getLongitude();
        runInBackground(() -> checkPassword(username, password, lat, lng), this::onCheckFinished);
    }

    /** Background thread: the whole password step, including lockout and audit rows. */
    private Outcome checkPassword(String username, char[] password, Double lat, Double lng) {
        User user = userDao.findByUsername(username);
        long now = System.currentTimeMillis();

        if (user == null) {
            PasswordHasher.verify(password, DUMMY_HASH, DUMMY_SALT); // timing cover, result ignored
            return new Outcome(Status.BAD_CREDENTIALS, -1, 0);
        }

        lockout.clearExpiredLock(user, now);
        long remaining = lockout.remainingLockMs(user, now);
        if (remaining > 0) {
            // Locked: do not even look at the password, so guessing during a lock gets nowhere
            Arrays.fill(password, '\0');
            return new Outcome(Status.LOCKED, user.id, remaining);
        }

        if (PasswordHasher.verify(password, user.passwordHash, user.salt)) {
            // The failure counter is NOT reset here. It is reset only after the TOTP code is
            // accepted; otherwise someone who knows the password could log in again to get fresh
            // attempts at the 6-digit code every time.
            auditDao.log(user.id, AuditEvent.PASSWORD_OK, AuditEvent.RESULT_SUCCESS, lat, lng);
            // Step 1 is done and proven in memory: the code step will refuse to open without this
            LoginHandshake.getInstance().passwordAccepted(user.id, now);
            return new Outcome(Status.OK, user.id, 0);
        }

        // Failures carry the place too, so a failed attempt from somewhere odd shows up on the map
        auditDao.log(user.id, AuditEvent.PASSWORD_FAIL, AuditEvent.RESULT_FAIL, lat, lng);
        if (lockout.recordFailure(user.id, now)) {
            return new Outcome(Status.LOCKED, user.id, LockoutManager.LOCK_DURATION_MS);
        }
        return new Outcome(Status.BAD_CREDENTIALS, user.id, 0);
    }

    private void onCheckFinished(Outcome outcome) {
        setBusy(false);
        switch (outcome.status) {
            case OK:
                binding.inputPassword.setText("");
                Intent next = new Intent(this, TotpVerifyActivity.class);
                next.putExtra(TotpVerifyActivity.EXTRA_USER_ID, outcome.userId);
                startActivity(next);
                break;
            case LOCKED:
                binding.inputPassword.setText("");
                showLockDialog(outcome.remainingMs, null);
                break;
            default:
                // Same message whether the username or the password was wrong. The wrong password
                // is cleared first (changing the text also clears any error, so set the error after).
                binding.inputPassword.setText("");
                showToast(R.string.login_failed);
                binding.layoutPassword.setError(getString(R.string.login_failed));
                break;
        }
    }

    private void setBusy(boolean busy) {
        binding.buttonLogin.setEnabled(!busy);
        binding.buttonLogin.setText(busy ? R.string.please_wait : R.string.button_login);
    }
}
