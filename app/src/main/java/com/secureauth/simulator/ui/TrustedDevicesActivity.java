package com.secureauth.simulator.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.SimpleAdapter;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.secureauth.simulator.R;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.BackupCodeDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.DeviceTrust;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TrustedDevice;
import com.secureauth.simulator.data.TrustedDeviceDao;
import com.secureauth.simulator.databinding.ActivityTrustedDevicesBinding;
import com.secureauth.simulator.databinding.DialogRenameDeviceBinding;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phones this user has chosen to trust, in a ListView. Long-press a row for a context menu:
 * Rename (dialog with a text box) or Revoke (asks first, then sets revoked = 1). A revoked phone
 * stays in the list, so the user can see that it was revoked, and its next login needs a
 * backup code on top of the 6-digit code.
 */
public class TrustedDevicesActivity extends BaseActivity {

    // Keys of the Map that describes one row (SimpleAdapter reads these by name)
    private static final String KEY_ICON = "icon";
    private static final String KEY_NAME = "name";
    private static final String KEY_ADDED = "added";
    private static final String KEY_STATE = "state";

    /** What one load of the screen found. */
    private static final class Loaded {
        final List<TrustedDevice> devices;
        final long currentDeviceId;

        Loaded(List<TrustedDevice> devices, long currentDeviceId) {
            this.devices = devices;
            this.currentDeviceId = currentDeviceId;
        }
    }

    private ActivityTrustedDevicesBinding binding;
    private TrustedDeviceDao deviceDao;
    private AuditLogDao auditDao;
    private BackupCodeDao codeDao;
    private long userId;

    private List<TrustedDevice> shown = new ArrayList<>(); // same order as the rows on screen
    private long currentDeviceId = -1L;

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
        userId = SessionManager.getInstance().getUserId();

        binding = ActivityTrustedDevicesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.devicesRoot);
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        DbHelper db = DbHelper.getInstance(this);
        deviceDao = new TrustedDeviceDao(db);
        auditDao = new AuditLogDao(db);
        codeDao = new BackupCodeDao(db);

        binding.listDevices.setEmptyView(binding.textEmpty);
        // Long-press on a row opens the context menu below
        registerForContextMenu(binding.listDevices);

        loadDevices();
    }

    // ---- loading and showing ----------------------------------------------------------------

    private void loadDevices() {
        final Context appContext = getApplicationContext();
        runInBackground(() -> new Loaded(deviceDao.listForUser(userId),
                DeviceTrust.currentDeviceId(appContext, userId)), this::showDevices);
    }

    private void showDevices(Loaded loaded) {
        shown = loaded.devices;
        currentDeviceId = loaded.currentDeviceId;

        DateFormat dayFormat = DateFormat.getDateInstance(DateFormat.MEDIUM);
        List<Map<String, Object>> data = new ArrayList<>();
        for (TrustedDevice device : shown) {
            Map<String, Object> item = new HashMap<>();
            item.put(KEY_ICON, device.revoked ? R.drawable.ic_status_fail : R.drawable.ic_status_ok);
            item.put(KEY_NAME, device.deviceName);
            item.put(KEY_ADDED, getString(R.string.device_added, dayFormat.format(new Date(device.addedAt))));
            item.put(KEY_STATE, stateText(device));
            data.add(item);
        }

        SimpleAdapter adapter = new SimpleAdapter(this, data, R.layout.item_trusted_device,
                new String[]{KEY_ICON, KEY_NAME, KEY_ADDED, KEY_STATE},
                new int[]{R.id.image_status, R.id.text_name, R.id.text_added, R.id.text_state});
        // The default binder would set the icon, but not describe it for screen readers
        adapter.setViewBinder((view, value, text) -> {
            if (view instanceof ImageView && value instanceof Integer) {
                int resId = (Integer) value;
                ImageView icon = (ImageView) view;
                icon.setImageResource(resId);
                icon.setContentDescription(getString(resId == R.drawable.ic_status_ok
                        ? R.string.cd_device_trusted : R.string.cd_device_revoked));
                return true;
            }
            return false;
        });
        binding.listDevices.setAdapter(adapter);
    }

    /** "Trusted", "Revoked", with " - This device" added for the phone in the user's hand. */
    private String stateText(TrustedDevice device) {
        String state = getString(device.revoked ? R.string.device_status_revoked : R.string.device_status_trusted);
        if (device.id == currentDeviceId) {
            state = getString(R.string.device_state_current, state, getString(R.string.device_this_device));
        }
        return state;
    }

    // ---- context menu (long-press) ----------------------------------------------------------

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        TrustedDevice device = deviceAt(menuInfo);
        if (device == null) {
            return;
        }
        menu.setHeaderTitle(device.deviceName);
        getMenuInflater().inflate(R.menu.context_trusted_device, menu);
        if (device.revoked) {
            menu.removeItem(R.id.action_revoke_device); // nothing left to revoke
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TrustedDevice device = deviceAt(item.getMenuInfo());
        if (device == null) {
            return super.onContextItemSelected(item);
        }
        if (item.getItemId() == R.id.action_rename_device) {
            showRenameDialog(device);
            return true;
        }
        if (item.getItemId() == R.id.action_revoke_device) {
            askToRevoke(device);
            return true;
        }
        return super.onContextItemSelected(item);
    }

    /** The row the long-press landed on, or null if there is none. */
    @Nullable
    private TrustedDevice deviceAt(@Nullable ContextMenu.ContextMenuInfo menuInfo) {
        if (!(menuInfo instanceof AdapterView.AdapterContextMenuInfo)) {
            return null;
        }
        int position = ((AdapterView.AdapterContextMenuInfo) menuInfo).position;
        return position >= 0 && position < shown.size() ? shown.get(position) : null;
    }

    // ---- rename -----------------------------------------------------------------------------

    private void showRenameDialog(TrustedDevice device) {
        DialogRenameDeviceBinding dialogBinding = DialogRenameDeviceBinding.inflate(getLayoutInflater());
        dialogBinding.inputDeviceName.setText(device.deviceName);
        dialogBinding.inputDeviceName.setSelection(dialogBinding.inputDeviceName.length());

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rename_title)
                .setView(dialogBinding.getRoot())
                .setPositiveButton(R.string.button_save, null) // replaced below so a bad name keeps the dialog open
                .setNegativeButton(R.string.button_cancel, null)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = DeviceTrust.cleanName(String.valueOf(dialogBinding.inputDeviceName.getText()));
            if (!DeviceTrust.isValidName(name)) {
                dialogBinding.layoutDeviceName.setError(getString(R.string.error_device_name));
                return;
            }
            dialog.dismiss();
            rename(device.id, name);
        });
    }

    private void rename(long deviceId, String newName) {
        runInBackground(() -> {
            // Read the row again: only this user's own rows may be changed
            TrustedDevice row = deviceDao.getById(deviceId);
            if (row == null || row.userId != userId) {
                return false;
            }
            row.deviceName = newName;
            if (deviceDao.update(row) != 1) {
                return false;
            }
            auditDao.log(userId, AuditEvent.DEVICE_RENAMED, AuditEvent.RESULT_SUCCESS);
            return true;
        }, ok -> {
            showToast(ok ? R.string.device_renamed : R.string.device_action_failed);
            loadDevices();
        });
    }

    // ---- revoke -----------------------------------------------------------------------------

    /**
     * Revoking the phone in your hand locks you out of the normal login path (it will always need
     * a backup code), so that case first checks that at least one unused code exists.
     */
    private void askToRevoke(TrustedDevice device) {
        if (device.id != currentDeviceId) {
            showRevokeDialog(device);
            return;
        }
        runInBackground(() -> codeDao.listUnused(userId).size(), unused -> {
            if (unused == 0) {
                showToast(R.string.revoke_needs_codes);
            } else {
                showRevokeDialog(device);
            }
        });
    }

    private void showRevokeDialog(TrustedDevice device) {
        boolean current = device.id == currentDeviceId;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.revoke_title)
                .setMessage(getString(current ? R.string.revoke_message_current : R.string.revoke_message,
                        device.deviceName))
                .setPositiveButton(R.string.button_revoke, (d, w) -> revoke(device.id))
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    private void revoke(long deviceId) {
        runInBackground(() -> {
            TrustedDevice row = deviceDao.getById(deviceId);
            if (row == null || row.userId != userId) {
                return false;
            }
            if (row.revoked) {
                return true; // already done
            }
            row.revoked = true;
            if (deviceDao.update(row) != 1) {
                return false;
            }
            auditDao.log(userId, AuditEvent.DEVICE_REVOKED, AuditEvent.RESULT_SUCCESS);
            return true;
        }, ok -> {
            showToast(ok ? R.string.device_revoked : R.string.device_action_failed);
            loadDevices();
        });
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
