package com.secureauth.simulator.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.KeystoreHelper;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.AppSettings;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.LoginHandshake;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TotpAccount;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;
import com.secureauth.simulator.databinding.ActivityEnrollBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Links an authenticator app to the account. Shows a QR code (otpauth:// link) and the
 * Base32 secret. The secret is only saved, encrypted by the Android Keystore, after the user
 * types a code that TotpEngine accepts, which proves the authenticator really has it.
 */
public class EnrollActivity extends BaseActivity {

    public static final String EXTRA_USER_ID = "user_id";

    private static final String ISSUER = "SecureAuth";
    private static final int QR_SIZE_PX = 800;
    private static final String QR_DIR = "qr";
    private static final String QR_FILE = "enroll_qr.png";

    private ActivityEnrollBinding binding;
    private EnrollViewModel model;
    private UserDao userDao;
    private TotpAccountDao accountDao;
    private AuditLogDao auditDao;

    private long userId;
    // The code length, period and algorithm for the new secret. They start as the defaults Google
    // Authenticator expects (6 digits, 30 s, SHA-1) and follow the choices made in Settings.
    private final TotpAccount settings = new TotpAccount();
    private Bitmap qrBitmap;

    @Override
    protected boolean isSecureScreen() {
        return true; // shows the QR code and the secret in text
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        userId = getIntent().getLongExtra(EXTRA_USER_ID, -1L);
        if (userId < 0) {
            finish(); // started without a user: nothing to enroll
            return;
        }
        // Replacing an authenticator is sensitive: only right after registering, after a correct
        // password, or from a logged-in session of the same user (Settings)
        long now = System.currentTimeMillis();
        boolean allowed = LoginHandshake.getInstance().allowsEnroll(userId, now)
                || SessionManager.getInstance().getUserId() == userId;
        if (!allowed) {
            goToLogin();
            return;
        }

        settings.digits = AppSettings.getDigits(this);
        settings.period = AppSettings.getPeriod(this);
        settings.algo = AppSettings.getAlgo(this);

        binding = ActivityEnrollBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.enrollRoot);
        // The text and the length limit depend on the chosen number of digits
        binding.textConfirmPrompt.setText(getString(R.string.enroll_confirm_prompt, settings.digits));
        binding.layoutCode.setHint(getString(R.string.hint_code, settings.digits));
        binding.inputCode.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(settings.digits)});

        DbHelper db = DbHelper.getInstance(this);
        userDao = new UserDao(db);
        accountDao = new TotpAccountDao(db);
        auditDao = new AuditLogDao(db);
        model = new ViewModelProvider(this).get(EnrollViewModel.class);

        binding.textSecret.setText(groupInFours(model.getBase32Secret()));
        showConfirmSection(model.confirming);
        loadQrCode();

        binding.buttonCopy.setOnClickListener(v -> copySecret());
        binding.buttonScanned.setOnClickListener(v -> {
            model.confirming = true;
            showConfirmSection(true);
        });
        binding.buttonConfirm.setOnClickListener(v -> onConfirmClicked());
        binding.inputCode.addTextChangedListener(new AfterTextChangedListener() {
            @Override
            public void afterTextChanged(Editable s) {
                binding.layoutCode.setError(null);
            }
        });

        // Long-press on the QR opens the context menu (Copy secret / Share QR image)
        registerForContextMenu(binding.imageQr);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            new File(new File(getCacheDir(), QR_DIR), QR_FILE).delete(); // the QR image is the secret
        }
    }

    // ---- QR code ----------------------------------------------------------------------------

    /** Builds the otpauth:// link and draws it as a QR code, off the UI thread. */
    private void loadQrCode() {
        runInBackground(() -> {
            User user = userDao.getById(userId);
            if (user == null) {
                return null;
            }
            String uri = TotpEngine.buildOtpAuthUri(ISSUER, user.username, model.getBase32Secret(),
                    settings.digits, settings.period, settings.algo);
            try {
                return new BarcodeEncoder().encodeBitmap(uri, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX);
            } catch (WriterException e) {
                return null;
            }
        }, bitmap -> {
            if (bitmap == null) {
                finish();
                return;
            }
            qrBitmap = bitmap;
            binding.imageQr.setImageBitmap(bitmap);
        });
    }

    /**
     * "ABCDEFGH..." becomes "ABCD EFGH ..." so it is easier to read and type. A line break after
     * every 4th group keeps the lines even instead of leaving one group alone on the last line.
     */
    private static String groupInFours(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i += 4) {
            if (i > 0) {
                sb.append(i % 16 == 0 ? '\n' : ' ');
            }
            sb.append(text, i, Math.min(i + 4, text.length()));
        }
        return sb.toString();
    }

    // ---- copy / share (button and context menu) ---------------------------------------------

    private void copySecret() {
        // Copy without the spaces: authenticator apps accept the raw Base32 text
        copyToClipboard(getString(R.string.enroll_secret_label), model.getBase32Secret());
        showToast(R.string.secret_copied);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        getMenuInflater().inflate(R.menu.context_enroll_qr, menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_copy_secret) {
            copySecret();
            return true;
        }
        if (id == R.id.action_share_qr) {
            confirmShareQr();
            return true;
        }
        return super.onContextItemSelected(item);
    }

    /** The QR image contains the secret, so make the user confirm before it leaves the app. */
    private void confirmShareQr() {
        if (qrBitmap == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.share_warning_title)
                .setMessage(R.string.share_warning_message)
                .setPositiveButton(R.string.button_share, (dialog, which) -> shareQrImage())
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    private void shareQrImage() {
        runInBackground(this::writeQrToCache, uri -> {
            if (uri == null) {
                showToast(R.string.share_failed);
                return;
            }
            // Implicit Intent: we only say "send an image"; the user picks which app handles it
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)));
        });
    }

    /** Saves the QR as a PNG in cache/qr and returns a FileProvider link other apps may read. */
    private Uri writeQrToCache() {
        File dir = new File(getCacheDir(), QR_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File file = new File(dir, QR_FILE);
        try (OutputStream out = new FileOutputStream(file)) {
            qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            return null;
        }
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
    }

    // ---- confirmation -----------------------------------------------------------------------

    private void showConfirmSection(boolean visible) {
        binding.sectionConfirm.setVisibility(visible ? View.VISIBLE : View.GONE);
        binding.buttonScanned.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private void onConfirmClicked() {
        String code = String.valueOf(binding.inputCode.getText()).trim();
        if (!code.matches("\\d{" + settings.digits + "}")) {
            binding.layoutCode.setError(getString(R.string.error_code_length, settings.digits));
            return;
        }

        byte[] secret = model.copySecret();
        boolean correct = TotpEngine.verify(secret, code, System.currentTimeMillis(),
                settings.digits, settings.period, settings.algo);
        if (!correct) {
            Arrays.fill(secret, (byte) 0);
            binding.layoutCode.setError(getString(R.string.error_code_wrong));
            runInBackground(() -> auditDao.log(userId, AuditEvent.ENROLL_VERIFY_FAIL,
                    AuditEvent.RESULT_FAIL), id -> { });
            return;
        }

        binding.buttonConfirm.setEnabled(false);
        runInBackground(() -> saveAccount(secret), this::onSaveFinished);
    }

    /** Background thread: encrypt with the Keystore, then replace any older account. */
    private boolean saveAccount(byte[] secret) {
        try {
            KeystoreHelper.Result encrypted = KeystoreHelper.encrypt(secret);
            TotpAccount account = new TotpAccount();
            account.userId = userId;
            account.secretEnc = encrypted.ciphertext;
            account.iv = encrypted.iv;
            account.issuer = ISSUER;
            account.digits = settings.digits;
            account.period = settings.period;
            account.algo = settings.algo;
            account.createdAt = System.currentTimeMillis();

            if (accountDao.replaceForUser(account) < 0) {
                return false;
            }
            auditDao.log(userId, AuditEvent.TOTP_ENROLLED, AuditEvent.RESULT_SUCCESS);
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private void onSaveFinished(boolean saved) {
        if (!saved) {
            binding.buttonConfirm.setEnabled(true);
            showToast(R.string.enroll_save_failed);
            return;
        }
        showToast(R.string.enroll_success);
        setResult(RESULT_OK); // lets TotpVerifyActivity carry on after a first-time enrollment
        finish();
    }
}
