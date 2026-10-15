package com.secureauth.simulator.ui;

import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.secureauth.simulator.R;
import com.secureauth.simulator.data.AppSettings;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.databinding.ActivitySettingsBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * What the user can change:
 * <ul>
 *   <li>Spinners for code length, how often it changes and the algorithm. They are used the NEXT
 *       time an authenticator is set up, because the secret already shared with the authenticator
 *       app was made with the old values. A warning appears when a choice is not what Google
 *       Authenticator expects.</li>
 *   <li>A time picker for the auto-lock time (1 to 30 minutes).</li>
 *   <li>A check box for recording the place of each login.</li>
 * </ul>
 * Every change is saved the moment it is made, in SharedPreferences (see {@link AppSettings}).
 */
public class SettingsActivity extends BaseActivity {

    private ActivitySettingsBinding binding;

    @Override
    protected boolean requiresSession() {
        return true;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!enforceSession()) {
            return;
        }

        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.settingsRoot);
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        setUpAuthenticatorSpinners();
        setUpAutoLock();
        setUpLocationCheckBox();
        binding.buttonSetupAgain.setOnClickListener(v -> confirmSetupAgain());
    }

    // ---- authenticator spinners -------------------------------------------------------------

    private void setUpAuthenticatorSpinners() {
        List<String> digitLabels = new ArrayList<>();
        for (int d : AppSettings.DIGIT_CHOICES) {
            digitLabels.add(getString(R.string.choice_digits, d));
        }
        List<String> periodLabels = new ArrayList<>();
        for (int p : AppSettings.PERIOD_CHOICES) {
            periodLabels.add(getString(R.string.choice_period, p));
        }
        List<String> algoLabels = new ArrayList<>();
        for (String algo : AppSettings.ALGO_CHOICES) {
            algoLabels.add(getString(algo.equals(AppSettings.DEFAULT_ALGO)
                    ? R.string.choice_sha1 : R.string.choice_sha256));
        }

        prepare(binding.spinnerDigits, digitLabels, indexOf(AppSettings.DIGIT_CHOICES, AppSettings.getDigits(this)));
        prepare(binding.spinnerPeriod, periodLabels, indexOf(AppSettings.PERIOD_CHOICES, AppSettings.getPeriod(this)));
        prepare(binding.spinnerAlgorithm, algoLabels, indexOf(AppSettings.ALGO_CHOICES, AppSettings.getAlgo(this)));
        showWarningIfNeeded();

        // The listeners are attached AFTER the starting choice is selected, so opening the screen
        // does not count as the user choosing something
        binding.spinnerDigits.setOnItemSelectedListener(new ChoiceListener(position -> {
            AppSettings.setDigits(this, AppSettings.DIGIT_CHOICES[position]);
            showWarningIfNeeded();
        }));
        binding.spinnerPeriod.setOnItemSelectedListener(new ChoiceListener(position -> {
            AppSettings.setPeriod(this, AppSettings.PERIOD_CHOICES[position]);
            showWarningIfNeeded();
        }));
        binding.spinnerAlgorithm.setOnItemSelectedListener(new ChoiceListener(position -> {
            AppSettings.setAlgo(this, AppSettings.ALGO_CHOICES[position]);
            showWarningIfNeeded();
        }));
    }

    private void prepare(Spinner spinner, List<String> labels, int selected) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected, false);
    }

    private static int indexOf(int[] values, int wanted) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == wanted) {
                return i;
            }
        }
        return 0;
    }

    private static int indexOf(String[] values, String wanted) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(wanted)) {
                return i;
            }
        }
        return 0;
    }

    /** The red note under the spinners, shown while any choice is not the Google Authenticator default. */
    private void showWarningIfNeeded() {
        boolean differs = AppSettings.differsFromDefaults(
                AppSettings.getDigits(this), AppSettings.getPeriod(this), AppSettings.getAlgo(this));
        binding.textWarning.setVisibility(differs ? View.VISIBLE : View.GONE);
    }

    /** "The user picked item number N", without the other callback methods a Spinner listener needs. */
    private static final class ChoiceListener implements AdapterView.OnItemSelectedListener {
        interface OnChosen {
            void chosen(int position);
        }

        private final OnChosen action;

        ChoiceListener(OnChosen action) {
            this.action = action;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            action.chosen(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    // ---- "set up again" ---------------------------------------------------------------------

    /** The new choices only matter once a new secret is made, so offer to do that now. */
    private void confirmSetupAgain() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setup_again_title)
                .setMessage(R.string.setup_again_message)
                .setPositiveButton(R.string.button_continue, (d, w) -> startActivity(
                        new Intent(this, EnrollActivity.class) // explicit Intent
                                .putExtra(EnrollActivity.EXTRA_USER_ID, SessionManager.getInstance().getUserId())))
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    // ---- auto-lock --------------------------------------------------------------------------

    private void setUpAutoLock() {
        showAutoLock();
        binding.buttonAutolock.setOnClickListener(v -> showAutoLockPicker());
    }

    private void showAutoLock() {
        binding.textAutolock.setText(getString(R.string.settings_autolock_value,
                formatAutoLock(AppSettings.getAutoLockMs(this))));
    }

    /** "5 min", or "1 min 30 s" when the time is not a whole number of minutes. */
    private String formatAutoLock(long ms) {
        long minutes = ms / 60_000L;
        long seconds = (ms / 1000L) % 60L;
        return seconds == 0
                ? getString(R.string.duration_minutes, minutes)
                : getString(R.string.duration_minutes_seconds, minutes, seconds);
    }

    /**
     * A TimePickerDialog in 24-hour mode. It only offers hours and minutes, so it is used as
     * "hours : minutes" for a short time: 0:01 to 0:30 is 1 to 30 minutes.
     */
    private void showAutoLockPicker() {
        int minutes = (int) (AppSettings.getAutoLockMs(this) / 60_000L);
        TimePickerDialog dialog = new TimePickerDialog(this,
                (picker, hour, minute) -> onAutoLockPicked(hour, minute), 0, minutes, true);
        dialog.setTitle(R.string.autolock_picker_title);
        dialog.show();
    }

    /** The picker answered. Anything outside 1 to 30 minutes is refused and the picker opens again. */
    void onAutoLockPicked(int hour, int minute) {
        long ms = AppSettings.autoLockMsFromPicker(hour, minute);
        if (ms < 0) {
            showToast(R.string.autolock_range_error);
            showAutoLockPicker();
            return;
        }
        AppSettings.setAutoLockMs(this, ms);
        showAutoLock();
    }

    // ---- location ---------------------------------------------------------------------------

    private void setUpLocationCheckBox() {
        binding.checkboxRecordLocation.setChecked(AppSettings.isRecordLocation(this));
        binding.checkboxRecordLocation.setOnCheckedChangeListener(
                (button, checked) -> AppSettings.setRecordLocation(this, checked));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
