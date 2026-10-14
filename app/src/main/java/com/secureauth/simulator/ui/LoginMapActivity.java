package com.secureauth.simulator.ui;

import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.secureauth.simulator.R;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.databinding.ActivityLoginMapBinding;

import java.text.DateFormat;
import java.util.List;

/**
 * Shows every login that has a location on a Google map: green markers for successful entries,
 * red for failures and suspicious logins. Tapping a marker shows its event and date-time, and
 * the camera is moved so all markers are visible.
 */
public class LoginMapActivity extends BaseActivity implements OnMapReadyCallback {

    private static final int FIT_PADDING_PX = 120;
    private static final float SINGLE_MARKER_ZOOM = 12f;

    private ActivityLoginMapBinding binding;
    private AuditLogDao auditDao;
    private long userId;

    private GoogleMap map;
    private int markerCount; // how many markers were drawn (read by tests)

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

        binding = ActivityLoginMapBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.mapRoot);
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        auditDao = new AuditLogDao(DbHelper.getInstance(this));

        // The map loads by itself; onMapReady is called when it can be drawn on
        SupportMapFragment fragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        if (fragment != null) {
            fragment.getMapAsync(this);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        DateFormat timeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        runInBackground(() -> MapMarkers.from(auditDao.listForUser(userId), timeFormat), this::drawMarkers);
    }

    private void drawMarkers(List<MapMarkers.Spec> specs) {
        map.clear();
        markerCount = 0;
        binding.textEmpty.setVisibility(specs.isEmpty() ? View.VISIBLE : View.GONE);
        if (specs.isEmpty()) {
            return;
        }

        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (MapMarkers.Spec spec : specs) {
            LatLng position = new LatLng(spec.lat, spec.lng);
            map.addMarker(new MarkerOptions()
                    .position(position)
                    .title(spec.title)      // the info window shows the event...
                    .snippet(spec.snippet)  // ...and the date and time under it
                    .icon(BitmapDescriptorFactory.defaultMarker(spec.warning
                            ? BitmapDescriptorFactory.HUE_RED : BitmapDescriptorFactory.HUE_GREEN)));
            bounds.include(position);
            markerCount++;
        }

        if (markerCount == 1) {
            MapMarkers.Spec only = specs.get(0);
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(only.lat, only.lng), SINGLE_MARKER_ZOOM));
        } else {
            // The size is given explicitly because the map may not have been laid out yet,
            // and newLatLngBounds without a size would fail in that case
            DisplayMetrics screen = getResources().getDisplayMetrics();
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(
                    bounds.build(), screen.widthPixels, screen.heightPixels, FIT_PADDING_PX));
        }
    }

    /** For tests: how many markers are on the map right now. */
    int getMarkerCount() {
        return markerCount;
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
