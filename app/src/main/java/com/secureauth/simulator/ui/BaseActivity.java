package com.secureauth.simulator.ui;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.Editable;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.secureauth.simulator.R;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.LockoutManager;
import com.secureauth.simulator.data.SessionDao;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.location.LocationHelper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Parent class for every screen. Keeping shared behaviour here means later phases
 * (session check, broadcast receivers) can be added once instead of in every Activity.
 */
public abstract class BaseActivity extends AppCompatActivity {

    // One worker thread: database and PBKDF2 work run here so the UI never freezes
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Where the logged-in action happens. Package-private so tests can swap in a fixed place.
    LocationHelper locationHelper;

    // The system "allow location?" dialog. Registered here once so every screen can use it.
    private final ActivityResultLauncher<String[]> locationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> continueAfterLocationPermission());
    private Runnable afterLocationPermission;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Draw behind the status/navigation bars (this is the default look on Android 15+)
        EdgeToEdge.enable(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back to a protected screen (for example after the app was in the background
        // longer than the auto-lock time) must prove the session is still valid
        enforceSession();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    // ---- session protection -----------------------------------------------------------------

    /** Screens that show codes or secrets override this to return true. */
    protected boolean requiresSession() {
        return false;
    }

    /**
     * For protected screens: checks that a valid session exists. If not, ends it (writing
     * SESSION_EXPIRED when it simply timed out) and sends the user to Login.
     * Protected screens call this at the top of onCreate and must stop if it returns false.
     */
    protected boolean enforceSession() {
        if (!requiresSession()) {
            return true;
        }
        SessionDao sessions = new SessionDao(DbHelper.getInstance(this));
        SessionManager.State state = SessionManager.getInstance()
                .check(sessions, System.currentTimeMillis());
        if (state == SessionManager.State.ACTIVE) {
            return true;
        }

        boolean timedOut = state == SessionManager.State.EXPIRED;
        endSession(timedOut ? AuditEvent.SESSION_EXPIRED : null);
        if (timedOut) {
            showToast(R.string.session_expired);
        }
        goToLogin();
        return false;
    }

    /**
     * Ends the current session: forgets the token in memory straight away, then (in the
     * background) deletes its database row and writes the audit entry, if one is given.
     */
    protected void endSession(@Nullable String auditEvent) {
        SessionManager.Snapshot ended = SessionManager.getInstance().clear();
        if (ended == null) {
            return;
        }
        DbHelper db = DbHelper.getInstance(this);
        runInBackground(() -> {
            new SessionDao(db).delete(ended.sessionId);
            if (auditEvent != null) {
                new AuditLogDao(db).log(ended.userId, auditEvent, AuditEvent.RESULT_SUCCESS);
            }
            return null;
        }, ignored -> { });
    }

    // ---- location ---------------------------------------------------------------------------

    LocationHelper locationHelper() {
        if (locationHelper == null) {
            locationHelper = new LocationHelper(getApplicationContext());
        }
        return locationHelper;
    }

    /**
     * Makes sure the user has been asked about location, then runs {@code proceed} either way.
     * The first time, a dialog explains why location is wanted BEFORE the system permission
     * dialog appears. Afterwards the user is never asked again here: a "no" is respected and
     * the login simply carries on without a location.
     */
    protected void ensureLocationPermission(Runnable proceed) {
        LocationHelper helper = locationHelper();
        if (!helper.isRecordingEnabled() || helper.hasPermission() || helper.wasAsked()) {
            proceed.run();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.location_rationale_title)
                .setMessage(R.string.location_rationale_message)
                .setCancelable(false)
                .setPositiveButton(R.string.button_allow, (d, w) -> {
                    helper.markAsked();
                    afterLocationPermission = proceed;
                    locationPermissionLauncher.launch(LocationHelper.PERMISSIONS);
                })
                .setNegativeButton(R.string.button_not_now, (d, w) -> {
                    helper.markAsked();
                    proceed.run();
                })
                .show();
    }

    private void continueAfterLocationPermission() {
        Runnable next = afterLocationPermission;
        afterLocationPermission = null;
        if (next != null) {
            next.run(); // allowed or denied, the login goes on
        }
    }

    /**
     * Looks up the position (if allowed) and passes it on, or passes null. If this screen was
     * closed while waiting, nothing happens: its background thread is already gone.
     */
    protected void withCurrentLocation(Consumer<Location> next) {
        locationHelper().requestCurrentLocation(location -> {
            if (!isFinishing() && !isDestroyed()) {
                next.accept(location);
            }
        });
    }

    /** Opens Login on an empty back stack, so Back cannot return to a protected screen. */
    protected void goToLogin() {
        startActivity(new Intent(this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    /**
     * Adds padding equal to the system bars (and the keyboard) so content is not hidden.
     * Call this after setContentView, passing the layout's root view.
     */
    protected void applySystemBarPadding(View root) {
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            // Including the keyboard (ime) lets the form scroll above it instead of hiding behind it
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            // Add to the padding already set in the layout instead of replacing it
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            return insets;
        });
    }

    /**
     * Runs {@code work} on the background thread, then hands its result to {@code onDone}
     * on the UI thread. If the screen was closed meanwhile, the result is dropped.
     */
    protected <T> void runInBackground(Supplier<T> work, Consumer<T> onDone) {
        executor.execute(() -> {
            T result = work.get();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    onDone.accept(result);
                }
            });
        });
    }

    /**
     * Copies what is typed in a field into a new char[]. Passwords are kept as char[] rather
     * than String because a char[] can be overwritten after use; a String stays in memory.
     */
    protected static char[] readChars(EditText field) {
        Editable text = field.getText();
        char[] chars = new char[text.length()];
        text.getChars(0, chars.length, chars, 0);
        return chars;
    }

    /**
     * Copies text to the clipboard. Marked "sensitive" (Android 13+) so the system hides it
     * from the on-screen clipboard preview; use it for secrets and one-time codes.
     */
    protected void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = ClipData.newPlainText(label, text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
    }

    /**
     * "Account locked" dialog with the wait shown as m:ss. {@code onClosed} (may be null) runs
     * when the user dismisses it, e.g. to leave the screen.
     */
    protected void showLockDialog(long remainingMs, @Nullable Runnable onClosed) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.lock_title)
                .setMessage(getString(R.string.lock_message, LockoutManager.formatRemaining(remainingMs)))
                .setPositiveButton(R.string.button_ok, null);
        if (onClosed != null) {
            builder.setOnDismissListener(dialog -> onClosed.run());
        }
        builder.show();
    }

    /** Short feedback message used by all screens. */
    protected void showToast(@StringRes int messageRes) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
    }

    /** Same, for text built at run time (for example with a name inserted). */
    protected void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
