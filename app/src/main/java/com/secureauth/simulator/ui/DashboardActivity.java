package com.secureauth.simulator.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.KeystoreHelper;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TotpAccount;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;
import com.secureauth.simulator.databinding.ActivityDashboardBinding;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

/**
 * The home screen after a full login: the live code, a ring counting down to the next one,
 * tap-to-copy, pull-to-refresh and an options menu. The decrypted secret is kept in memory
 * only while this screen is visible.
 */
public class DashboardActivity extends BaseActivity {

    /** Set by the login step when this login was far from the previous one (distance in km). */
    public static final String EXTRA_SUSPICIOUS_KM = "suspicious_km";

    /** The user, their authenticator and its decrypted secret. */
    private static final class Data {
        final User user;
        final TotpAccount account;
        final byte[] secret;

        Data(User user, TotpAccount account, byte[] secret) {
            this.user = user;
            this.account = account;
            this.secret = secret;
        }
    }

    private ActivityDashboardBinding binding;
    private UserDao userDao;
    private TotpAccountDao accountDao;

    private long userId;
    private TotpAccount account;
    private byte[] secret;     // only non-null between a successful load and onStop
    private boolean visible;   // between onStart and onStop
    private String lastCode = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            // The auto-lock time can run out while the screen is open and nobody touches it
            if (!SessionManager.getInstance().isActive(System.currentTimeMillis())) {
                enforceSession();
                return;
            }
            updateCode();
            // Wake up again right after the next full second so the display never lags behind
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 1000 - (now % 1000) + 5);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No valid session means no Dashboard. The user comes from the session itself, never
        // from an Intent extra, because an extra could name any user.
        if (!enforceSession()) {
            return;
        }
        userId = SessionManager.getInstance().getUserId();

        binding = ActivityDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.dashboardRoot);
        setSupportActionBar(binding.toolbar);

        DbHelper db = DbHelper.getInstance(this);
        userDao = new UserDao(db);
        accountDao = new TotpAccountDao(db);

        binding.swipeRefresh.setOnRefreshListener(this::loadData);
        setUpTapToCopy();

        // Only on a fresh start: a rotation must not show the warning a second time
        double suspiciousKm = getIntent().getDoubleExtra(EXTRA_SUSPICIOUS_KM, -1.0);
        if (savedInstanceState == null && suspiciousKm > 0) {
            showSuspiciousLoginDialog(suspiciousKm);
        }
    }

    /** "This login was far from your last one": a warning after login, with a shortcut to the map. */
    private void showSuspiciousLoginDialog(double km) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.suspicious_title)
                .setMessage(getString(R.string.suspicious_message, Math.round(km)))
                .setPositiveButton(R.string.button_view_map,
                        (d, w) -> startActivity(new Intent(this, LoginMapActivity.class)))
                .setNegativeButton(R.string.button_ok, null)
                .show();
    }

    /**
     * Pressing the code dims it (OnTouchListener gives instant feedback); lifting the finger
     * inside it counts as a click, which copies the code.
     */
    private void setUpTapToCopy() {
        binding.textCode.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setAlpha(0.5f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setAlpha(1f);
                    break;
                default:
                    break;
            }
            return false; // not consumed, so the normal click still happens
        });
        binding.textCode.setOnClickListener(v -> {
            if (lastCode.isEmpty()) {
                return;
            }
            copyToClipboard(getString(R.string.app_name), lastCode);
            showToast(R.string.code_copied);
        });
    }

    @Override
    protected boolean requiresSession() {
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Check before decrypting anything: onStart runs before onResume's own check
        if (userId < 0 || !enforceSession()) {
            return;
        }
        visible = true;
        loadData();
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        handler.removeCallbacks(tick);
        wipeSecret(); // do not keep the secret in memory while the screen is hidden
    }

    // ---- loading ----------------------------------------------------------------------------

    /** Loads the account and decrypts the secret off the UI thread. */
    private void loadData() {
        runInBackground(() -> {
            User user = userDao.getById(userId);
            List<TotpAccount> accounts = accountDao.listForUser(userId);
            if (user == null || accounts.isEmpty()) {
                return null;
            }
            TotpAccount found = accounts.get(0);
            try {
                return new Data(user, found, KeystoreHelper.decrypt(found.secretEnc, found.iv));
            } catch (GeneralSecurityException e) {
                return null;
            }
        }, this::onDataLoaded);
    }

    private void onDataLoaded(@Nullable Data data) {
        binding.swipeRefresh.setRefreshing(false);
        if (data == null) {
            showToast(R.string.totp_secret_error);
            finish();
            return;
        }
        if (!visible) {
            Arrays.fill(data.secret, (byte) 0); // the screen was hidden while we were decrypting
            return;
        }

        wipeSecret();
        secret = data.secret;
        account = data.account;
        binding.textIssuer.setText(account.issuer);
        binding.textAccount.setText(data.user.username);
        binding.ring.setPeriodSeconds(account.period);

        lastCode = ""; // forces the display to redraw even if the code did not change
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void wipeSecret() {
        if (secret != null) {
            Arrays.fill(secret, (byte) 0);
            secret = null;
        }
    }

    // ---- ticking ----------------------------------------------------------------------------

    /** Runs every second: refreshes the code when its window has rolled over, and the ring. */
    private void updateCode() {
        if (secret == null || account == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String code = TotpEngine.generate(secret, now, account.digits, account.period, account.algo);
        if (!code.equals(lastCode)) {
            lastCode = code;
            binding.textCode.setText(TotpEngine.formatForDisplay(code));
        }

        int secondsLeft = TotpEngine.secondsRemaining(now, account.period);
        long periodMs = account.period * 1000L;
        // How far we are through this window, so the ring shrinks from full to empty
        float progress = 1f - (float) Math.floorMod(now, periodMs) / periodMs;
        binding.ring.setProgress(progress);
        binding.textSeconds.setText(getString(R.string.dashboard_refreshes_in, secondsLeft));
        binding.ring.setContentDescription(getString(R.string.cd_countdown_ring, secondsLeft));
    }

    // ---- options menu -----------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_dashboard, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_logout) {
            logout();
            return true;
        }
        if (item.getItemId() == R.id.action_backup_codes) {
            startActivity(new Intent(this, BackupCodesActivity.class)); // explicit Intent
            return true;
        }
        if (item.getItemId() == R.id.action_login_history) {
            startActivity(new Intent(this, LoginHistoryActivity.class)); // explicit Intent
            return true;
        }
        if (item.getItemId() == R.id.action_trusted_devices) {
            startActivity(new Intent(this, TrustedDevicesActivity.class)); // explicit Intent
            return true;
        }
        if (item.getItemId() == R.id.action_profile
                || item.getItemId() == R.id.action_settings
                || item.getItemId() == R.id.action_help) {
            // These screens are built in later phases
            showToast(getString(R.string.coming_later, item.getTitle()));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Ends the session (memory + database row + LOGOUT audit) and returns to Login. */
    private void logout() {
        endSession(AuditEvent.LOGOUT);
        goToLogin();
    }
}
