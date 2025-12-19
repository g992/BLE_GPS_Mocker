package com.g992.blegpsmocker;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DebugActivity extends AppCompatActivity {

    private SwipeRefreshLayout swipeRefreshLayout;
    private TextView visibleCountText;
    private TextView activeCountText;
    private TextView uptimeText;
    private TextView tempText;
    private TextView lastUpdatedText;
    private TextView signalsPlaceholder;
    private ChipGroup signalsGroup;
    private TextView satellitesPlaceholder;
    private LinearLayout satellitesContainer;
    private TextView rawJsonText;

    private GNSSClientService clientService;
    private boolean serviceBound = false;

    private final ServiceConnection serviceConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    GNSSClientService.GNSSClientBinder binder =
                            (GNSSClientService.GNSSClientBinder) service;
                    clientService = binder.getService();
                    serviceBound = true;
                    clientService.emitGnssDebugSnapshot();
                    requestDebugSnapshot();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    serviceBound = false;
                    clientService = null;
                }
            };

    private final BroadcastReceiver debugReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (GNSSClientService.ACTION_GNSS_DEBUG_SNAPSHOT.equals(intent.getAction())) {
                        String raw =
                                intent.getStringExtra(GNSSClientService.EXTRA_GNSS_DEBUG_RAW);
                        long timestamp =
                                intent.getLongExtra(GNSSClientService.EXTRA_GNSS_DEBUG_TIMESTAMP, 0L);
                        renderDebugSnapshot(raw, timestamp);
                    }
                }
            };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        setContentView(R.layout.activity_debug);

        MaterialToolbar toolbar = findViewById(R.id.debugToolbar);
        setSupportActionBar(toolbar);

        swipeRefreshLayout = findViewById(R.id.debugSwipeRefresh);
        visibleCountText = findViewById(R.id.visibleCountText);
        activeCountText = findViewById(R.id.activeCountText);
        uptimeText = findViewById(R.id.uptimeText);
        tempText = findViewById(R.id.tempText);
        lastUpdatedText = findViewById(R.id.lastUpdatedText);
        signalsPlaceholder = findViewById(R.id.signalsPlaceholder);
        signalsGroup = findViewById(R.id.signalsGroup);
        satellitesPlaceholder = findViewById(R.id.satellitesPlaceholder);
        satellitesContainer = findViewById(R.id.satellitesContainer);
        rawJsonText = findViewById(R.id.rawJsonText);

        swipeRefreshLayout.setOnRefreshListener(this::requestDebugSnapshot);

        MaterialButton exitButton = findViewById(R.id.exitButton);
        exitButton.setOnClickListener(v -> finish());
        Intent serviceIntent = new Intent(this, GNSSClientService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(GNSSClientService.ACTION_GNSS_DEBUG_SNAPSHOT);
        ContextCompat.registerReceiver(
                this,
                debugReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(debugReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.debug_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_close) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void requestDebugSnapshot() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(true);
        }
        if (!serviceBound || clientService == null) {
            Toast.makeText(this, R.string.debug_not_connected, Toast.LENGTH_SHORT).show();
            stopRefreshing();
            return;
        }
        boolean enqueued = clientService.requestGnssDebugSnapshot(0);
        if (!enqueued) {
            Toast.makeText(this, R.string.debug_request_failed, Toast.LENGTH_SHORT).show();
            stopRefreshing();
        }
    }

    private void renderDebugSnapshot(@Nullable String raw, long timestampMillis) {
        stopRefreshing();
        if (raw == null || raw.isEmpty()) {
            resetPlaceholders();
            return;
        }
        rawJsonText.setText(raw);
        if (timestampMillis > 0) {
            lastUpdatedText.setText(
                    getString(R.string.debug_refreshed_at, timestampMillis)
            );
        } else {
            lastUpdatedText.setText(R.string.debug_last_updated_placeholder);
        }
        try {
            JSONObject payload = new JSONObject(raw);
            int visible = payload.optInt("visible", -1);
            int active = payload.optInt("active", -1);
            long uptimeSeconds = payload.optLong("uptime", -1);
            Double tempValue = payload.isNull("temp") ? null : payload.optDouble("temp");
            visibleCountText.setText(
                    visible >= 0 ? String.valueOf(visible) : getString(R.string.debug_placeholder_value)
            );
            activeCountText.setText(
                    active >= 0 ? String.valueOf(active) : getString(R.string.debug_placeholder_value)
            );
            uptimeText.setText(
                    uptimeSeconds >= 0 ? String.valueOf(uptimeSeconds) : getString(R.string.debug_placeholder_value)
            );
            if (tempValue != null && !tempValue.isNaN()) {
                tempText.setText(getString(R.string.debug_temp_value, tempValue));
            } else {
                tempText.setText(R.string.debug_temp_unknown);
            }
            updateSignals(payload.optJSONArray("signalsDb"));
            updateSatellites(payload.optJSONArray("satellites"));
        } catch (JSONException exception) {
            showParseError(raw);
        }
    }

    private void updateSignals(@Nullable JSONArray signalsArray) throws JSONException {
        signalsGroup.removeAllViews();
        if (signalsArray == null || signalsArray.length() == 0) {
            signalsPlaceholder.setVisibility(View.VISIBLE);
            signalsGroup.setVisibility(View.GONE);
            return;
        }
        signalsPlaceholder.setVisibility(View.GONE);
        signalsGroup.setVisibility(View.VISIBLE);
        for (int i = 0; i < signalsArray.length(); i++) {
            int value = signalsArray.optInt(i, 0);
            Chip chip = new Chip(this);
            chip.setText(String.valueOf(value));
            chip.setCheckable(false);
            signalsGroup.addView(chip);
        }
    }

    private void updateSatellites(@Nullable JSONArray satellitesArray) throws JSONException {
        satellitesContainer.removeAllViews();
        if (satellitesArray == null || satellitesArray.length() == 0) {
            satellitesPlaceholder.setVisibility(View.VISIBLE);
            satellitesContainer.setVisibility(View.GONE);
            return;
        }
        satellitesPlaceholder.setVisibility(View.GONE);
        satellitesContainer.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < satellitesArray.length(); i++) {
            JSONObject satellite = satellitesArray.optJSONObject(i);
            if (satellite == null) continue;
            View row = inflater.inflate(R.layout.item_satellite_debug, satellitesContainer, false);
            TextView idText = row.findViewById(R.id.satelliteId);
            TextView constellationText = row.findViewById(R.id.satelliteConstellation);
            TextView snrText = row.findViewById(R.id.satelliteSnr);
            TextView anglesText = row.findViewById(R.id.satelliteAngles);

            int id = satellite.optInt("id", -1);
            int snr = satellite.optInt("snr", 0);
            int constellation = satellite.optInt("c", 0);
            boolean active = satellite.optInt("active", 0) == 1;
            int elevation = satellite.optInt("el", 0);
            int azimuth = satellite.optInt("az", 0);

            idText.setText(id >= 0 ? String.valueOf(id) : getString(R.string.debug_placeholder_value));
            constellationText.setText(getConstellationLabel(constellation));
            String activeLabel =
                    getString(active ? R.string.debug_active_suffix : R.string.debug_inactive_suffix);
            snrText.setText(getString(R.string.debug_snr_value, snr, activeLabel));
            anglesText.setText(getString(R.string.debug_angles_value, elevation, azimuth));
            satellitesContainer.addView(row);
        }
    }

    private String getConstellationLabel(int code) {
        switch (code) {
            case 1:
                return getString(R.string.debug_constellation_gps);
            case 2:
                return getString(R.string.debug_constellation_glonass);
            case 3:
                return getString(R.string.debug_constellation_galileo);
            case 4:
                return getString(R.string.debug_constellation_beidou);
            case 5:
                return getString(R.string.debug_constellation_qzss);
            default:
                return getString(R.string.debug_constellation_unknown);
        }
    }

    private void resetPlaceholders() {
        visibleCountText.setText(R.string.debug_placeholder_value);
        activeCountText.setText(R.string.debug_placeholder_value);
        uptimeText.setText(R.string.debug_placeholder_value);
        tempText.setText(R.string.debug_temp_unknown);
        lastUpdatedText.setText(R.string.debug_last_updated_placeholder);
        signalsPlaceholder.setVisibility(View.VISIBLE);
        signalsGroup.setVisibility(View.GONE);
        signalsGroup.removeAllViews();
        satellitesPlaceholder.setVisibility(View.VISIBLE);
        satellitesContainer.setVisibility(View.GONE);
        satellitesContainer.removeAllViews();
        rawJsonText.setText(R.string.debug_raw_placeholder);
    }

    private void showParseError(String raw) {
        resetPlaceholders();
        rawJsonText.setText(raw);
    }

    private void stopRefreshing() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }
    }
}
