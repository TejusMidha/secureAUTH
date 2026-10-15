package com.secureauth.simulator.ui;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.SimpleAdapter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.secureauth.simulator.R;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.databinding.ActivityLoginHistoryBinding;
import com.secureauth.simulator.io.CsvImporter;
import com.secureauth.simulator.io.FileExporter;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * The audit trail of the logged-in user, newest first, in a ListView filled by a SimpleAdapter.
 * Can be narrowed to a date range, exported to a CSV file and filled from one.
 */
public class LoginHistoryActivity extends BaseActivity {

    // Keys of the Map that describes one row (SimpleAdapter reads these by name)
    private static final String KEY_ICON = "icon";
    private static final String KEY_EVENT = "event";
    private static final String KEY_TIME = "time";
    private static final String KEY_LOCATION = "location";

    private static final String STATE_FROM = "filter_from";
    private static final String STATE_TO = "filter_to";
    private static final long NO_FILTER = -1L;

    private ActivityLoginHistoryBinding binding;
    private AuditLogDao auditDao;
    private long userId;

    private long filterFrom = NO_FILTER;
    private long filterTo = NO_FILTER;
    private List<AuditLog> shown = new ArrayList<>(); // what the list currently displays; this is what gets exported

    // The system file picker: "where should audit_log.csv go?"
    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument(FileExporter.MIME_CSV), this::exportTo);

    // The system file picker: "which file do you want to import?"
    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::importFrom);

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

        binding = ActivityLoginHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.historyRoot);
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState != null) {
            filterFrom = savedInstanceState.getLong(STATE_FROM, NO_FILTER);
            filterTo = savedInstanceState.getLong(STATE_TO, NO_FILTER);
        }
        auditDao = new AuditLogDao(DbHelper.getInstance(this));

        binding.listHistory.setEmptyView(binding.textEmpty);
        binding.buttonFilter.setOnClickListener(v -> showFromPicker());
        binding.chipClearFilter.setOnClickListener(v -> clearFilter());

        loadHistory();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_FROM, filterFrom);
        outState.putLong(STATE_TO, filterTo);
    }

    // ---- loading and showing ----------------------------------------------------------------

    /** Reads the rows (all, or only those inside the date range) and shows them. */
    private void loadHistory() {
        final long from = filterFrom;
        final long to = filterTo;
        runInBackground(() -> from == NO_FILTER
                        ? auditDao.listForUser(userId)
                        : auditDao.listForUserBetween(userId, from, to),
                this::showRows);
    }

    private void showRows(List<AuditLog> rows) {
        shown = rows;

        DateFormat timeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        List<Map<String, Object>> data = new ArrayList<>();
        for (AuditLog row : rows) {
            Map<String, Object> item = new HashMap<>();
            item.put(KEY_ICON, HistoryFormat.isFailure(row.result) ? R.drawable.ic_status_fail : R.drawable.ic_status_ok);
            item.put(KEY_EVENT, row.event);
            item.put(KEY_TIME, timeFormat.format(new Date(row.ts)));
            item.put(KEY_LOCATION, HistoryFormat.location(row.lat, row.lng, getString(R.string.no_location)));
            data.add(item);
        }

        // SimpleAdapter copies each Map entry into the view whose id is at the same position in "to"
        SimpleAdapter adapter = new SimpleAdapter(this, data, R.layout.item_history,
                new String[]{KEY_ICON, KEY_EVENT, KEY_TIME, KEY_LOCATION},
                new int[]{R.id.image_status, R.id.text_event, R.id.text_time, R.id.text_location});
        // The default binder would set the icon, but not describe it for screen readers
        adapter.setViewBinder((view, value, text) -> {
            if (view instanceof ImageView && value instanceof Integer) {
                ImageView icon = (ImageView) view;
                int resId = (Integer) value;
                icon.setImageResource(resId);
                icon.setContentDescription(getString(resId == R.drawable.ic_status_ok
                        ? R.string.cd_status_ok : R.string.cd_status_fail));
                return true;
            }
            return false;
        });
        binding.listHistory.setAdapter(adapter);

        showFilterState();
    }

    private void showFilterState() {
        boolean filtered = filterFrom != NO_FILTER;
        binding.chipClearFilter.setVisibility(filtered ? View.VISIBLE : View.GONE);
        binding.textFilterRange.setVisibility(filtered ? View.VISIBLE : View.GONE);
        if (filtered) {
            DateFormat day = DateFormat.getDateInstance(DateFormat.MEDIUM);
            binding.textFilterRange.setText(getString(R.string.filter_showing,
                    day.format(new Date(filterFrom)), day.format(new Date(filterTo))));
        }
    }

    // ---- date filter ------------------------------------------------------------------------

    /** Step 1 of 2: pick the first day. */
    private void showFromPicker() {
        Calendar today = Calendar.getInstance();
        DatePickerDialog dialog = new DatePickerDialog(this,
                (picker, year, month, day) -> showToPicker(DateRange.startOfDay(year, month, day)),
                today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMaxDate(DateRange.endOfToday()); // nothing has happened in the future
        dialog.setTitle(R.string.filter_from_title);
        dialog.show();
    }

    /** Step 2 of 2: pick the last day, which cannot be before the first. */
    private void showToPicker(long fromStart) {
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(fromStart);
        DatePickerDialog dialog = new DatePickerDialog(this,
                (picker, year, month, day) -> applyFilter(fromStart, DateRange.endOfDay(year, month, day)),
                start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMinDate(fromStart);
        dialog.getDatePicker().setMaxDate(DateRange.endOfToday());
        dialog.setTitle(R.string.filter_to_title);
        dialog.show();
    }

    private void applyFilter(long from, long to) {
        filterFrom = from;
        filterTo = to;
        loadHistory();
    }

    private void clearFilter() {
        filterFrom = NO_FILTER;
        filterTo = NO_FILTER;
        loadHistory();
    }

    // ---- options menu: export and import ----------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_login_history, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_login_map) {
            startActivity(new Intent(this, LoginMapActivity.class)); // explicit Intent
            return true;
        }
        if (id == R.id.action_export_csv) {
            exportLauncher.launch(FileExporter.AUDIT_LOG_FILE_NAME);
            return true;
        }
        if (id == R.id.action_import_csv) {
            // Any text-like file may be chosen; the content is checked after it is read
            importLauncher.launch(new String[]{"text/*", "application/csv",
                    "application/vnd.ms-excel", "application/octet-stream"});
            return true;
        }
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Writes the entries currently shown (so an active date filter applies) to the chosen file. */
    void exportTo(@Nullable Uri destination) {
        if (destination == null) {
            return; // the user closed the picker
        }
        final List<AuditLog> rows = new ArrayList<>(shown);
        final String csv = FileExporter.formatAuditCsv(rows, TimeZone.getDefault());
        runInBackground(() -> FileExporter.writeText(getContentResolver(), destination, csv),
                ok -> showToast(ok ? getResources().getQuantityString(R.plurals.export_done, rows.size(), rows.size())
                        : getString(R.string.export_failed)));
    }

    /** Reads the chosen file, adds its new rows to the history, then redraws the list. */
    void importFrom(@Nullable Uri source) {
        if (source == null) {
            return;
        }
        runInBackground(() -> {
            try {
                String text = CsvImporter.readText(getContentResolver(), source);
                return CsvImporter.importInto(auditDao, userId, text);
            } catch (IOException | IllegalArgumentException e) {
                return null; // unreadable, too big, or not a CSV we understand
            }
        }, result -> {
            if (result == null) {
                showToast(R.string.import_failed);
                return;
            }
            showToast(getString(R.string.import_result, result.imported, result.duplicates, result.invalid));
            loadHistory();
        });
    }
}
