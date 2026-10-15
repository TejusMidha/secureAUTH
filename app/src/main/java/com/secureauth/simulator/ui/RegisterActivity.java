package com.secureauth.simulator.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.color.MaterialColors;
import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.PasswordHasher;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.DeviceTrust;
import com.secureauth.simulator.data.LoginHandshake;
import com.secureauth.simulator.data.TrustedDeviceDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;
import com.secureauth.simulator.databinding.ActivityRegisterBinding;

import java.util.Arrays;

/**
 * Creates an account: unique username, password (min 8 chars, strength hint) and a
 * "Trust this device" check box. The password is stored only as a salted PBKDF2 hash.
 */
public class RegisterActivity extends BaseActivity {

    /** Result extra: the username that was just created, so Login can pre-fill it. */
    public static final String EXTRA_USERNAME = "username";

    private static final long USERNAME_TAKEN = -1L;

    private ActivityRegisterBinding binding;
    private UserDao userDao;
    private AuditLogDao auditDao;
    private TrustedDeviceDao deviceDao;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.registerRoot);

        DbHelper db = DbHelper.getInstance(this);
        userDao = new UserDao(db);
        auditDao = new AuditLogDao(db);
        deviceDao = new TrustedDeviceDao(db);

        // Clear an error as soon as the user starts fixing it
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
                showStrengthHint();
            }
        });
        binding.inputConfirm.addTextChangedListener(new AfterTextChangedListener() {
            @Override
            public void afterTextChanged(Editable s) {
                binding.layoutConfirm.setError(null);
            }
        });

        binding.buttonRegister.setOnClickListener(v -> onRegisterClicked());
        binding.buttonHaveAccount.setOnClickListener(v -> finish());
    }

    /** Shows weak / fair / strong under the password field while typing. */
    private void showStrengthHint() {
        char[] password = readChars(binding.inputPassword);
        if (password.length == 0) {
            binding.textStrength.setVisibility(android.view.View.GONE);
            return;
        }
        InputRules.Strength strength = InputRules.strength(password);
        Arrays.fill(password, '\0');

        int textRes;
        int colorAttr;
        switch (strength) {
            case STRONG:
                textRes = R.string.strength_strong;
                colorAttr = com.google.android.material.R.attr.colorSecondary;
                break;
            case FAIR:
                textRes = R.string.strength_fair;
                colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
                break;
            default:
                textRes = R.string.strength_weak;
                colorAttr = androidx.appcompat.R.attr.colorError;
                break;
        }
        binding.textStrength.setText(textRes);
        binding.textStrength.setTextColor(MaterialColors.getColor(binding.textStrength, colorAttr));
        binding.textStrength.setVisibility(android.view.View.VISIBLE);
    }

    private void onRegisterClicked() {
        String username = InputRules.normalizeUsername(String.valueOf(binding.inputUsername.getText()));
        char[] password = readChars(binding.inputPassword);
        char[] confirm = readChars(binding.inputConfirm);

        // Inline errors on the fields (short feedback elsewhere uses Toasts)
        int usernameError = InputRules.usernameError(username);
        int passwordError = InputRules.passwordError(password);
        boolean mismatch = !Arrays.equals(password, confirm);
        Arrays.fill(confirm, '\0'); // only needed for the comparison

        binding.layoutUsername.setError(usernameError == 0 ? null : getString(usernameError));
        binding.layoutPassword.setError(passwordError == 0 ? null : getString(passwordError));
        // Only complain about a mismatch once the password itself is acceptable
        binding.layoutConfirm.setError(passwordError == 0 && mismatch
                ? getString(R.string.error_confirm_mismatch) : null);

        if (usernameError != 0 || passwordError != 0 || mismatch) {
            Arrays.fill(password, '\0');
            return;
        }

        setBusy(true);
        // The box is read here, on the UI thread; the background thread only gets the answer
        final boolean trustThisDevice = binding.checkboxTrust.isChecked();
        // Hashing takes a noticeable moment on purpose, so it must not run on the UI thread
        runInBackground(() -> createAccount(username, password, trustThisDevice), this::onRegisterFinished);
    }

    /**
     * Background thread: checks the name is free, hashes the password, saves the user and, if the
     * box was ticked, adds this phone to their trusted devices. Returns the new id, or -1.
     */
    private long createAccount(String username, char[] password, boolean trustThisDevice) {
        if (userDao.findByUsername(username) != null) {
            Arrays.fill(password, '\0');
            return USERNAME_TAKEN;
        }

        PasswordHasher.Result hashed = PasswordHasher.hash(password); // also wipes the password
        User user = new User();
        user.username = username;
        user.passwordHash = hashed.hash;
        user.salt = hashed.salt;
        user.createdAt = System.currentTimeMillis();

        long userId = userDao.insert(user);
        if (userId < 0) {
            return USERNAME_TAKEN; // someone took it between the check and the insert
        }
        auditDao.log(userId, AuditEvent.REGISTER, AuditEvent.RESULT_SUCCESS);
        LoginHandshake.getInstance().registered(userId, System.currentTimeMillis()); // enrollment may open for this account
        if (trustThisDevice) {
            DeviceTrust.trustCurrentDevice(getApplicationContext(), deviceDao, auditDao, userId);
        }
        return userId;
    }

    private void onRegisterFinished(long userId) {
        setBusy(false);
        if (userId == USERNAME_TAKEN) {
            binding.layoutUsername.setError(getString(R.string.error_username_taken));
            return;
        }
        showToast(R.string.register_success);
        // Hand the username back to LoginActivity, which opened this screen for a result.
        Intent result = new Intent().putExtra(EXTRA_USERNAME,
                InputRules.normalizeUsername(String.valueOf(binding.inputUsername.getText())));
        setResult(RESULT_OK, result);

        // Next step is linking an authenticator app. Enroll opens on top of Login, so when it
        // closes the user lands on Login with the username already filled in.
        Intent enroll = new Intent(this, EnrollActivity.class);
        enroll.putExtra(EnrollActivity.EXTRA_USER_ID, userId);
        startActivity(enroll);
        finish();
    }

    private void setBusy(boolean busy) {
        binding.buttonRegister.setEnabled(!busy);
        @StringRes int label = busy ? R.string.please_wait : R.string.button_register;
        binding.buttonRegister.setText(label);
    }
}
