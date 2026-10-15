package com.secureauth.simulator.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import android.view.WindowManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.BackupCodeGenerator;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.BackupCode;
import com.secureauth.simulator.data.BackupCodeDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;
import com.secureauth.simulator.databinding.ActivityBackupCodesBinding;
import com.secureauth.simulator.databinding.DialogBackupCodesBinding;
import com.secureauth.simulator.io.FileExporter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Creates and manages the 10 single-use recovery codes. A new set replaces the old one; the
 * plain codes are shown once, in a dialog, and only their salted hashes are stored.
 */
public class BackupCodesActivity extends BaseActivity {

    private ActivityBackupCodesBinding binding;
    private BackupCodesViewModel model;
    private BackupCodeDao codeDao;

    private long userId;
    private AlertDialog codesDialog;

    // "Save as .txt": the system file picker asks the user where to put the file (no storage permission)
    private final ActivityResultLauncher<String> saveLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument(FileExporter.MIME_TEXT), this::onSaveLocationChosen);

    @Override
    protected boolean requiresSession() {
        return true;
    }

    @Override
    protected boolean isSecureScreen() {
        return true; // shows live codes / backup codes
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!enforceSession()) {
            return;
        }
        userId = SessionManager.getInstance().getUserId();

        binding = ActivityBackupCodesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.backupCodesRoot);

        codeDao = new BackupCodeDao(DbHelper.getInstance(this));
        model = new ViewModelProvider(this).get(BackupCodesViewModel.class);

        binding.buttonGenerate.setOnClickListener(v -> confirmGenerate());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (model != null) {
            model.setOnChange(this::render); // also draws the current state right away
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (model != null) {
            model.setOnChange(null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (codesDialog != null) {
            codesDialog.dismiss(); // the codes stay in the ViewModel; the new screen shows them again
        }
    }

    // ---- showing state ----------------------------------------------------------------------

    /** Redraws everything from the ViewModel: busy button, failure message, codes dialog, count. */
    private void render() {
        binding.buttonGenerate.setEnabled(!model.generating);
        binding.buttonGenerate.setText(model.generating
                ? R.string.generating_codes : R.string.button_generate_codes);

        if (model.failed) {
            model.failed = false;
            showToast(R.string.codes_generate_failed);
        }
        if (model.pending != null && (codesDialog == null || !codesDialog.isShowing())) {
            showCodesDialog();
        }
        refreshRemaining();
    }

    /** "7 of 10 codes remaining": unused codes out of all codes stored for this user. */
    private void refreshRemaining() {
        runInBackground(() -> {
            List<BackupCode> all = codeDao.listForUser(userId);
            int unused = 0;
            for (BackupCode code : all) {
                if (!code.used) {
                    unused++;
                }
            }
            return new int[]{unused, all.size()};
        }, counts -> binding.textRemaining.setText(counts[1] == 0
                ? getString(R.string.backup_codes_none)
                : getString(R.string.backup_codes_remaining, counts[0], counts[1])));
    }

    // ---- generating -------------------------------------------------------------------------

    /** Making new codes kills the old ones, so ask first. */
    private void confirmGenerate() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.generate_confirm_title)
                .setMessage(R.string.generate_confirm_message)
                .setPositiveButton(R.string.button_generate, (d, w) -> model.generate(generationWork()))
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    /**
     * The slow part: create 10 codes, hash each one, swap them into the database in one
     * transaction and log it. Built from local variables only, so it does not hold on to this
     * Activity while it runs (the ViewModel may outlive the Activity).
     */
    private java.util.function.Supplier<BackupCodesViewModel.Generation> generationWork() {
        final long forUser = userId;
        final BackupCodeDao codes = codeDao;
        final DbHelper db = DbHelper.getInstance(this);
        return () -> {
            List<BackupCodeGenerator.Generated> generated = BackupCodeGenerator.generate();
            List<BackupCode> rows = new ArrayList<>();
            List<String> plain = new ArrayList<>();
            for (BackupCodeGenerator.Generated g : generated) {
                BackupCode row = new BackupCode();
                row.userId = forUser;
                row.codeHash = g.hashed.hash;
                row.salt = g.hashed.salt;
                rows.add(row);
                plain.add(g.plain);
            }
            if (!codes.replaceForUser(forUser, rows)) {
                return null;
            }
            new AuditLogDao(db).log(forUser, AuditEvent.BACKUP_CODES_GENERATED, AuditEvent.RESULT_SUCCESS);
            User user = new UserDao(db).getById(forUser);
            return new BackupCodesViewModel.Generation(plain,
                    user == null ? "" : user.username, System.currentTimeMillis());
        };
    }

    // ---- the one-time dialog ----------------------------------------------------------------

    private void showCodesDialog() {
        DialogBackupCodesBinding dialogBinding = DialogBackupCodesBinding.inflate(getLayoutInflater());

        StringBuilder lines = new StringBuilder();
        List<String> codes = model.pending.codes;
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) {
                lines.append('\n');
            }
            lines.append(String.format(Locale.ROOT, "%2d.  %s", i + 1, codes.get(i)));
        }
        dialogBinding.textCodes.setText(lines);

        dialogBinding.buttonSaveTxt.setOnClickListener(v -> saveLauncher.launch(FileExporter.BACKUP_CODES_FILE_NAME));
        dialogBinding.buttonShareCodes.setOnClickListener(v -> shareCodes());

        codesDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.codes_dialog_title)
                .setView(dialogBinding.getRoot())
                .setCancelable(false) // a stray tap outside must not throw the codes away
                .setPositiveButton(R.string.button_done, (d, w) -> {
                    model.clearPending();
                    refreshRemaining();
                })
                .create();
        // A dialog is its own window, so the Activity's FLAG_SECURE does not cover it
        codesDialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        codesDialog.show();
    }

    /** The text that goes into the .txt file and the share message. */
    private String codesText() {
        BackupCodesViewModel.Generation g = model.pending;
        String created = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(g.createdAt));
        return FileExporter.formatBackupCodes(g.codes, g.username, created);
    }

    private void onSaveLocationChosen(@Nullable Uri destination) {
        if (destination == null || model.pending == null) {
            return; // the user closed the picker
        }
        final String content = codesText();
        runInBackground(() -> FileExporter.writeText(getContentResolver(), destination, content),
                ok -> showToast(ok ? R.string.codes_saved : R.string.codes_save_failed));
    }

    /** Implicit Intent: "send this text"; the user picks which app receives it. */
    private void shareCodes() {
        if (model.pending == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(FileExporter.MIME_TEXT)
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_codes_subject))
                .putExtra(Intent.EXTRA_TEXT, codesText());
        startActivity(Intent.createChooser(send, getString(R.string.share_codes_chooser)));
    }
}
