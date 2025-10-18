package com.g992.blegpsmocker;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int MOCK_LOCATION_SETTINGS_REQUEST_CODE = 1002;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    };

    private TextView statusText;
    private TextView connectionText;
    private TextView dataAgeText;
    private TextView locationText;
    private TextView satellitesText;
    private TextView providerText;
    private TextView ageText;
    private TextView additionalInfoText;
    private TextView lastUpdateText;
    private Button requestPermissionsButton;
    private TextView permissionsStatusText;
    private TextView mockLocationStatusText;
    private Button startServiceButton;
    private Button stopServiceButton;
    private TextView serviceStatusText;

    private GNSSClientService clientService;
    private boolean serviceBound = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver connectionReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!GNSSClientService.ACTION_CONNECTION_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    boolean connected =
                            intent.getBooleanExtra(GNSSClientService.EXTRA_CONNECTED, false);
                    updateConnectionStatus(connected);
                }
            };

    private final BroadcastReceiver locationReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!GNSSClientService.ACTION_LOCATION_UPDATE.equals(intent.getAction())) {
                        return;
                    }
                    Location location = intent.getParcelableExtra(GNSSClientService.EXTRA_LOCATION);
                    int satellites =
                            intent.getIntExtra(GNSSClientService.EXTRA_SATELLITES, 0);
                    String provider =
                            intent.getStringExtra(GNSSClientService.EXTRA_PROVIDER);
                    float locationAge =
                            intent.getFloatExtra(GNSSClientService.EXTRA_LOCATION_AGE, 0f);
                    updateLocationInfo(location, satellites, provider, locationAge);
                }
            };

    private final BroadcastReceiver mockLocationStatusReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!GNSSClientService.ACTION_MOCK_LOCATION_STATUS.equals(intent.getAction())) {
                        return;
                    }
                    String message = intent.getStringExtra(GNSSClientService.EXTRA_MESSAGE);
                    updateMockLocationStatus(message);
                }
            };

    private final ServiceConnection serviceConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    GNSSClientService.GNSSClientBinder binder =
                            (GNSSClientService.GNSSClientBinder) service;
                    clientService = binder.getService();
                    serviceBound = true;

                    updateConnectionStatus(clientService.isConnectedToServer());
                    updateLocationInfo(
                            clientService.getLastReceivedLocation(),
                            0,
                            null,
                            0f
                    );
                    updateServiceStatus();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    serviceBound = false;
                    clientService = null;
                }
            };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        startAndBindService();
        registerReceivers();

        updatePermissionsStatus();
        startUIUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        unregisterReceiver(connectionReceiver);
        unregisterReceiver(locationReceiver);
        unregisterReceiver(mockLocationStatusReceiver);
        uiHandler.removeCallbacksAndMessages(null);
    }

    private void initializeViews() {
        statusText = findViewById(R.id.statusText);
        connectionText = findViewById(R.id.connectionText);
        dataAgeText = findViewById(R.id.dataAgeText);
        locationText = findViewById(R.id.locationText);
        satellitesText = findViewById(R.id.satellitesText);
        providerText = findViewById(R.id.providerText);
        ageText = findViewById(R.id.ageText);
        additionalInfoText = findViewById(R.id.additionalInfoText);
        lastUpdateText = findViewById(R.id.lastUpdateText);
        requestPermissionsButton = findViewById(R.id.requestPermissionsButton);
        permissionsStatusText = findViewById(R.id.permissionsStatusText);
        mockLocationStatusText = findViewById(R.id.mockLocationStatusText);
        startServiceButton = findViewById(R.id.startServiceButton);
        stopServiceButton = findViewById(R.id.stopServiceButton);
        serviceStatusText = findViewById(R.id.serviceStatusText);

        statusText.setText(getString(R.string.status_unknown));
        connectionText.setText(getString(R.string.connection_unknown));
        dataAgeText.setText(getString(R.string.data_age_unknown));
        locationText.setText(getString(R.string.location_unknown));
        satellitesText.setText(getString(R.string.satellites_unknown));
        providerText.setText(getString(R.string.provider_unknown));
        ageText.setText(getString(R.string.age_unknown));
        additionalInfoText.setText(getString(R.string.additional_info_unknown));
        lastUpdateText.setText(getString(R.string.movement_last_update_unknown));

        requestPermissionsButton.setOnClickListener(v -> requestPermissions());
        startServiceButton.setOnClickListener(v -> startGNSSService());
        stopServiceButton.setOnClickListener(v -> stopGNSSService());

        updateServiceStatus();
    }

    private void startAndBindService() {
        if (!GNSSClientService.isServiceEnabled(this)) {
            return;
        }
        Intent serviceIntent = new Intent(this, GNSSClientService.class);
        startForegroundService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void registerReceivers() {
        IntentFilter connectionFilter = new IntentFilter(GNSSClientService.ACTION_CONNECTION_CHANGED);
        IntentFilter locationFilter = new IntentFilter(GNSSClientService.ACTION_LOCATION_UPDATE);
        IntentFilter mockStatusFilter = new IntentFilter(GNSSClientService.ACTION_MOCK_LOCATION_STATUS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, connectionFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(locationReceiver, locationFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(mockLocationStatusReceiver, mockStatusFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(connectionReceiver, connectionFilter);
            registerReceiver(locationReceiver, locationFilter);
            registerReceiver(mockLocationStatusReceiver, mockStatusFilter);
        }
    }

    private void startGNSSService() {
        GNSSClientService.setServiceEnabled(this, true);
        Intent serviceIntent = new Intent(this, GNSSClientService.class);
        startForegroundService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        updateServiceStatus();
        Toast.makeText(this, getString(R.string.toast_service_enabled), Toast.LENGTH_LONG).show();
    }

    private void stopGNSSService() {
        GNSSClientService.setServiceEnabled(this, false);

        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
            clientService = null;
        }
        Intent serviceIntent = new Intent(this, GNSSClientService.class);
        stopService(serviceIntent);
        updateServiceStatus();
        updateConnectionStatus(false);
        Toast.makeText(this, getString(R.string.toast_service_disabled), Toast.LENGTH_LONG).show();
    }

    private void updateServiceStatus() {
        boolean enabled = GNSSClientService.isServiceEnabled(this);
        if (enabled) {
            startServiceButton.setEnabled(false);
            stopServiceButton.setEnabled(true);
            serviceStatusText.setText(R.string.service_running);
            serviceStatusText.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_green_dark));
        } else {
            startServiceButton.setEnabled(true);
            stopServiceButton.setEnabled(false);
            serviceStatusText.setText(R.string.service_stopped);
            serviceStatusText.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_red_dark));
        }
    }

    private void requestPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            checkMockLocationSettings();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    missing.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE
            );
        }
    }

    private void checkMockLocationSettings() {
        Toast.makeText(this, getString(R.string.mock_location_enable_message), Toast.LENGTH_LONG)
                .show();
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            startActivityForResult(intent, MOCK_LOCATION_SETTINGS_REQUEST_CODE);
        } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            startActivityForResult(intent, MOCK_LOCATION_SETTINGS_REQUEST_CODE);
        }
    }

    private void updatePermissionsStatus() {
        boolean allGranted = true;
        List<String> missingLabels = new ArrayList<>();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                missingLabels.add(getPermissionName(permission));
            }
        }
        if (allGranted) {
            permissionsStatusText.setText(R.string.all_permissions_granted);
            permissionsStatusText.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_green_dark));
            requestPermissionsButton.setVisibility(View.GONE);
        } else {
            String text =
                    String.format(
                            getString(R.string.missing_permissions),
                            String.join(", ", missingLabels)
                    );
            permissionsStatusText.setText(text);
            permissionsStatusText.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_red_dark));
            requestPermissionsButton.setVisibility(View.VISIBLE);
        }
    }

    private String getPermissionName(String permission) {
        if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)) {
            return getString(R.string.permission_fine_location);
        }
        if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)) {
            return getString(R.string.permission_coarse_location);
        }
        if (Manifest.permission.ACCESS_NETWORK_STATE.equals(permission)) {
            return getString(R.string.permission_network_state);
        }
        if (Manifest.permission.ACCESS_WIFI_STATE.equals(permission)) {
            return getString(R.string.permission_wifi_state);
        }
        if (Manifest.permission.CHANGE_WIFI_STATE.equals(permission)) {
            return getString(R.string.permission_change_wifi);
        }
        if (Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS.equals(permission)) {
            return getString(R.string.permission_location_extra_commands);
        }
        if (Manifest.permission.FOREGROUND_SERVICE.equals(permission)) {
            return getString(R.string.permission_foreground_service);
        }
        if (Manifest.permission.WAKE_LOCK.equals(permission)) {
            return getString(R.string.permission_wake_lock);
        }
        if (Manifest.permission.RECEIVE_BOOT_COMPLETED.equals(permission)) {
            return getString(R.string.permission_receive_boot_completed);
        }
        if (Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.equals(permission)) {
            return getString(R.string.permission_request_ignore_battery_optimizations);
        }
        int dot = permission.lastIndexOf('.');
        return dot >= 0 ? permission.substring(dot + 1) : permission;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(this, R.string.all_permissions_granted_toast, Toast.LENGTH_SHORT).show();
                checkMockLocationSettings();
            } else {
                Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
                updatePermissionsStatus();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MOCK_LOCATION_SETTINGS_REQUEST_CODE) {
            updatePermissionsStatus();
        }
    }

    private void updateConnectionStatus(boolean connected) {
        runOnUiThread(
                () -> {
                    statusText.setText(
                            String.format(
                                    getString(R.string.status_format),
                                    getString(R.string.app_name),
                                    getString(connected ? R.string.connected : R.string.disconnected)
                            )
                    );
                    if (connected) {
                        connectionText.setText(
                                String.format(
                                        getString(R.string.connection_status),
                                        getString(R.string.connection_status_connected)
                                )
                        );
                        connectionText.setTextColor(
                                ContextCompat.getColor(this, android.R.color.holo_green_dark));
                    } else {
                        connectionText.setText(
                                String.format(
                                        getString(R.string.connection_status),
                                        getString(R.string.connection_status_disconnected)
                                )
                        );
                        connectionText.setTextColor(
                                ContextCompat.getColor(this, android.R.color.holo_red_dark));
                        locationText.setText(getString(R.string.location_unknown));
                        satellitesText.setText(getString(R.string.satellites_unknown));
                        providerText.setText(getString(R.string.provider_unknown));
                        ageText.setText(getString(R.string.age_unknown));
                    }
                });
    }

    private void updateLocationInfo(
            @Nullable Location location,
            int satellites,
            @Nullable String provider,
            float locationAge
    ) {
        if (location == null) {
            return;
        }
        runOnUiThread(
                () -> {
                    StringBuilder builder = new StringBuilder();
                    builder.append(
                            String.format(
                                    getString(R.string.location_status),
                                    String.format(
                                            getString(R.string.location_format),
                                            location.getLatitude(),
                                            location.getLongitude()
                                    )
                            )
                    );
                    if (location.hasAltitude()) {
                        builder.append(
                                String.format(
                                        getString(R.string.altitude_format),
                                        location.getAltitude()
                                )
                        );
                    }
                    if (location.hasAccuracy()) {
                        builder.append(
                                String.format(
                                        getString(R.string.location_accuracy_format),
                                        location.getAccuracy()
                                )
                        );
                    }
                    locationText.setText(builder.toString());

                    satellitesText.setText(
                            String.format(getString(R.string.satellites_status), satellites));

                    providerText.setText(
                            String.format(
                                    getString(R.string.provider_status),
                                    provider != null ? provider : getString(R.string.unknown)
                            )
                    );

                    ageText.setText(
                            String.format(
                                    getString(R.string.age_status),
                                    String.format(getString(R.string.age_format), locationAge)
                            )
                    );

                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                    lastUpdateText.setText(
                            String.format(
                                    getString(R.string.movement_last_update),
                                    sdf.format(new Date())
                            )
                    );

                    StringBuilder infoBuilder = new StringBuilder();
                    if (location.hasSpeed()) {
                        infoBuilder.append(
                                String.format(
                                        getString(R.string.movement_speed),
                                        String.format(
                                                getString(R.string.speed_format),
                                                location.getSpeed()
                                        )
                                )
                        );
                    }
                    if (location.hasBearing()) {
                        if (infoBuilder.length() > 0) {
                            infoBuilder.append("  ");
                        }
                        infoBuilder.append(
                                String.format(
                                        getString(R.string.movement_bearing),
                                        String.format(
                                                getString(R.string.bearing_format),
                                                location.getBearing()
                                        )
                                )
                        );
                    }
                    if (infoBuilder.length() > 0) {
                        additionalInfoText.setText(infoBuilder.toString());
                    }
                });
    }

    private void updateMockLocationStatus(@Nullable String message) {
        runOnUiThread(
                () -> {
                    if (mockLocationStatusText != null) {
                        if (message == null || message.isEmpty()) {
                            mockLocationStatusText.setVisibility(View.GONE);
                        } else {
                            mockLocationStatusText.setVisibility(View.VISIBLE);
                            mockLocationStatusText.setText(message);
                        }
                    }
                });
    }

    private void startUIUpdates() {
        uiHandler.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        updateDynamicInfo();
                        uiHandler.postDelayed(this, 1000);
                    }
                },
                1000
        );
    }

    private void updateDynamicInfo() {
        if (serviceBound && clientService != null) {
            long lastUpdate = clientService.getLastUpdateTime();
            if (lastUpdate > 0) {
                long ageSeconds = (System.currentTimeMillis() - lastUpdate) / 1000;
                runOnUiThread(
                        () -> {
                            if (ageSeconds < 60) {
                                dataAgeText.setText(
                                        String.format(
                                                getString(R.string.data_age_status),
                                                String.format(
                                                        getString(R.string.data_age_format_s),
                                                        ageSeconds
                                                )
                                        )
                                );
                                dataAgeText.setTextColor(
                                        ContextCompat.getColor(
                                                this,
                                                android.R.color.holo_green_dark
                                        )
                                );
                            } else {
                                dataAgeText.setText(
                                        String.format(
                                                getString(R.string.data_age_status),
                                                String.format(
                                                        getString(R.string.data_age_format_ms),
                                                        ageSeconds / 60,
                                                        ageSeconds % 60
                                                )
                                        )
                                );
                                dataAgeText.setTextColor(
                                        ContextCompat.getColor(
                                                this,
                                                android.R.color.holo_orange_dark
                                        )
                                );
                            }
                        });
            }
        }
    }
}
