package com.g992.blegpsmocker;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.g992.blegpsmocker.ui.DeviceSettingsController;
import com.g992.blegpsmocker.ui.DeviceSettingsPayload;
import com.g992.blegpsmocker.ui.StatusUiController;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int MOCK_LOCATION_SETTINGS_REQUEST_CODE = 1002;

    private static final int GNSS_PROFILE_CUSTOM_VALUE = 3;
    private static final int BASE_PROFILE_CUSTOM_VALUE = 1;

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

    private TextView connectionBadge;
    private TextView dataAgeBadge;
    private TextView locationText;
    private TextView satellitesBadge;
    private TextView additionalInfoText;
    private MaterialButton requestPermissionsButton;
    private TextView permissionsStatusText;
    private TextView mockLocationStatusText;
    private MaterialButton serviceToggleButton;
    private TextView serviceStatusText;
    private View permissionsCard;
    private SwipeRefreshLayout swipeRefreshLayout;
    private DeviceSettingsController deviceSettingsController;
    private StatusUiController statusUiController;

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
                    statusUiController.onConnectionChanged(connected);
                    if (deviceSettingsController != null) {
                        deviceSettingsController.onConnectionChanged(connected);
                        deviceSettingsController.updateDeviceSettingsUi();
                    }
                    stopRefreshIndicator();
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
                    int strongSatellites =
                            intent.getIntExtra(GNSSClientService.EXTRA_SATELLITES_STRONG, 0);
                    int mediumSatellites =
                            intent.getIntExtra(GNSSClientService.EXTRA_SATELLITES_MEDIUM, 0);
                    int weakSatellites =
                            intent.getIntExtra(GNSSClientService.EXTRA_SATELLITES_WEAK, 0);
                    statusUiController.updateLocationInfo(
                            location,
                            satellites,
                            strongSatellites,
                            mediumSatellites,
                            weakSatellites
                    );
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
                    statusUiController.updateMockLocationStatus(message);
                }
            };

    private final BroadcastReceiver deviceSettingsReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!GNSSClientService.ACTION_DEVICE_SETTINGS_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    boolean apKnown =
                            intent.getBooleanExtra(GNSSClientService.EXTRA_AP_CONTROL_KNOWN, false);
                    Boolean apState = apKnown
                            ? intent.getBooleanExtra(GNSSClientService.EXTRA_AP_CONTROL_ENABLED, false)
                            : null;
                    boolean bridgeKnown =
                            intent.getBooleanExtra(GNSSClientService.EXTRA_BRIDGE_MODE_KNOWN, false);
                    Boolean bridgeState = bridgeKnown
                            ? intent.getBooleanExtra(GNSSClientService.EXTRA_BRIDGE_MODE_ENABLED, false)
                            : null;
                    boolean baudKnown =
                            intent.getBooleanExtra(GNSSClientService.EXTRA_GPS_BAUD_KNOWN, false);
                    Integer baudRate = baudKnown
                            ? intent.getIntExtra(GNSSClientService.EXTRA_GPS_BAUD_RATE, 0)
                            : null;
                    boolean gnssProfileKnown =
                            intent.getBooleanExtra(GNSSClientService.EXTRA_GNSS_PROFILE_KNOWN, false);
                    Integer gnssProfileValue = gnssProfileKnown
                            ? intent.getIntExtra(GNSSClientService.EXTRA_GNSS_PROFILE, 0)
                            : null;
                    boolean baseProfileKnown =
                            intent.getBooleanExtra(GNSSClientService.EXTRA_BASE_SETTINGS_PROFILE_KNOWN, false);
                    Integer baseProfileValue = baseProfileKnown
                            ? intent.getIntExtra(GNSSClientService.EXTRA_BASE_SETTINGS_PROFILE, 0)
                            : null;
                    boolean customGnssKnown =
                            intent.getBooleanExtra(GNSSClientService.EXTRA_CUSTOM_GNSS_PROFILE_KNOWN, false);
                    String customGnssFrame = customGnssKnown
                            ? intent.getStringExtra(GNSSClientService.EXTRA_CUSTOM_GNSS_PROFILE_FRAME)
                            : null;
                    boolean customBaseKnown =
                            intent.getBooleanExtra(GNSSClientService.EXTRA_CUSTOM_BASE_SETTINGS_KNOWN, false);
                    String customBaseFrame = customBaseKnown
                            ? intent.getStringExtra(GNSSClientService.EXTRA_CUSTOM_BASE_SETTINGS_FRAME)
                            : null;
                    String ssid = intent.getStringExtra(GNSSClientService.EXTRA_AP_SSID_HINT);
                    boolean versionKnown =
                            intent.getBooleanExtra(GNSSClientService.EXTRA_DEVICE_VERSION_KNOWN, false);
                    String versionValue =
                            versionKnown ? intent.getStringExtra(GNSSClientService.EXTRA_DEVICE_VERSION) : null;
                    boolean voltageKnown =
                            intent.getBooleanExtra(GNSSClientService.EXTRA_INPUT_VOLTAGE_KNOWN, false);
                    Double voltageValue = null;
                    if (voltageKnown) {
                        double rawVoltage =
                                intent.getDoubleExtra(GNSSClientService.EXTRA_INPUT_VOLTAGE, Double.NaN);
                        if (!Double.isNaN(rawVoltage)) {
                            voltageValue = rawVoltage;
                        }
                    }
                    DeviceSettingsPayload payload = new DeviceSettingsPayload();
                    payload.apKnown = apKnown;
                    payload.apState = apState;
                    payload.bridgeKnown = bridgeKnown;
                    payload.bridgeState = bridgeState;
                    payload.gnssProfileKnown = gnssProfileKnown;
                    payload.gnssProfile = gnssProfileValue;
                    payload.baseSettingsProfileKnown = baseProfileKnown;
                    payload.baseSettingsProfile = baseProfileValue;
                    payload.gpsBaudKnown = baudKnown;
                    payload.gpsBaudRate = baudRate;
                    payload.ssidHint = ssid;
                    payload.deviceVersionKnown = versionKnown;
                    payload.deviceVersion = versionValue;
                    payload.inputVoltageKnown = voltageKnown;
                    payload.inputVoltage = voltageValue;
                    payload.customGnssProfileFrameKnown = customGnssKnown;
                    payload.customGnssProfileFrame = customGnssFrame;
                    payload.customBaseSettingsFrameKnown = customBaseKnown;
                    payload.customBaseSettingsFrame = customBaseFrame;
                    if (deviceSettingsController != null) {
                        deviceSettingsController.applyDeviceSettingsUpdate(payload);
                    }
                }
            };

    private final BroadcastReceiver otaStatusReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!GNSSClientService.ACTION_OTA_STATUS.equals(intent.getAction())) {
                        return;
                    }
                    boolean guardEnabled = intent.getBooleanExtra(GNSSClientService.EXTRA_OTA_GUARD_ENABLED, false);
                    String wifiState = intent.getStringExtra(GNSSClientService.EXTRA_OTA_WIFI_STATE);
                    String wifiIp = intent.getStringExtra(GNSSClientService.EXTRA_OTA_WIFI_IP);
                    String message = intent.getStringExtra(GNSSClientService.EXTRA_OTA_MESSAGE);
                    if (deviceSettingsController != null) {
                        deviceSettingsController.updateOtaStatus(guardEnabled, wifiState, wifiIp, message);
                    }
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

                    statusUiController.onConnectionChanged(clientService.isConnectedToServer());
                    statusUiController.updateLocationInfo(
                            clientService.getLastReceivedLocation(),
                            0,
                            0,
                            0,
                            0
                    );
                    updateServiceStatus();
                    DeviceSettingsPayload payload = new DeviceSettingsPayload();
                    payload.apKnown = true;
                    payload.apState = clientService.getApControlState();
                    payload.bridgeKnown = true;
                    payload.bridgeState = clientService.getBridgeModeState();
                    payload.gnssProfileKnown = true;
                    payload.gnssProfile = clientService.getGnssProfile();
                    payload.baseSettingsProfileKnown = true;
                    payload.baseSettingsProfile = clientService.getBaseSettingsProfile();
                    payload.gpsBaudKnown = true;
                    payload.gpsBaudRate = clientService.getGpsBaudRate();
                    payload.ssidHint = clientService.getApControlSsidHint();
                    payload.deviceVersionKnown = true;
                    payload.deviceVersion = clientService.getDeviceVersion();
                    payload.inputVoltageKnown = true;
                    payload.inputVoltage = clientService.getInputVoltage();
                    payload.customGnssProfileFrameKnown = true;
                    payload.customGnssProfileFrame = clientService.getCustomGnssProfileFrame();
                    payload.customBaseSettingsFrameKnown = true;
                    payload.customBaseSettingsFrame = clientService.getCustomBaseSettingsFrame();
                    if (deviceSettingsController != null) {
                        deviceSettingsController.applyDeviceSettingsUpdate(payload);
                    }
                    clientService.refreshDeviceSettings();
                    clientService.emitCurrentOtaPortalState();
                    clientService.setAlwaysMovingEnabled(AppPrefs.isAlwaysMovingEnabled(MainActivity.this));
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
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        setContentView(R.layout.activity_main);

        initializeViews();
        startAndBindService();
        registerReceivers();

        updatePermissionsStatus();
        statusUiController.startDynamicUpdates(this::getLastUpdateTimestamp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDeviceSettingsOnFocus();
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
        unregisterReceiver(deviceSettingsReceiver);
        unregisterReceiver(otaStatusReceiver);
        if (statusUiController != null) {
            statusUiController.stopDynamicUpdates();
        }
        uiHandler.removeCallbacksAndMessages(null);
    }

    private void initializeViews() {
        connectionBadge = findViewById(R.id.connectionBadge);
        dataAgeBadge = findViewById(R.id.dataAgeBadge);
        locationText = findViewById(R.id.locationText);
        satellitesBadge = findViewById(R.id.satellitesBadge);
        additionalInfoText = findViewById(R.id.additionalInfoText);
        requestPermissionsButton = findViewById(R.id.requestPermissionsButton);
        permissionsStatusText = findViewById(R.id.permissionsStatusText);
        mockLocationStatusText = findViewById(R.id.mockLocationStatusText);
        permissionsCard = findViewById(R.id.permissionsCard);
        serviceToggleButton = findViewById(R.id.serviceToggleButton);
        serviceStatusText = findViewById(R.id.serviceStatusText);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);

        statusUiController =
                new StatusUiController(
                        this,
                        connectionBadge,
                        dataAgeBadge,
                        locationText,
                        satellitesBadge,
                        additionalInfoText,
                        mockLocationStatusText
                );
        statusUiController.onConnectionChanged(false);

        requestPermissionsButton.setOnClickListener(v -> requestPermissions());
        serviceToggleButton.setOnClickListener(
                v -> {
                    if (GNSSClientService.isServiceEnabled(this)) {
                        stopGNSSService();
                    } else {
                        startGNSSService();
                    }
                });
        mockLocationStatusText.setVisibility(View.GONE);
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setColorSchemeResources(
                    R.color.md_primary,
                    R.color.chip_success,
                    R.color.chip_error
            );
            swipeRefreshLayout.setOnRefreshListener(this::triggerManualRefresh);
        }

        deviceSettingsController =
                new DeviceSettingsController(
                        this,
                        createDeviceSettingsActions(),
                        GNSS_PROFILE_CUSTOM_VALUE,
                        BASE_PROFILE_CUSTOM_VALUE
                );
        updateServiceStatus();
        deviceSettingsController.updateDeviceSettingsUi();
    }

    private DeviceSettingsController.DeviceSettingsActions createDeviceSettingsActions() {
        return new DeviceSettingsController.DeviceSettingsActions() {
            @Override
            public boolean isServiceReady() {
                return isServiceReadyForSettings();
            }

            @Override
            public GNSSClientService getService() {
                return clientService;
            }

            @Override
            public void refreshDeviceSettings() {
                if (clientService != null) {
                    clientService.refreshDeviceSettings();
                }
            }

            @Override
            public void refreshOtaState() {
                if (clientService != null) {
                    clientService.refreshOtaPortalState();
                }
            }

            @Override
            public void stopRefreshIndicator() {
                MainActivity.this.stopRefreshIndicator();
            }
        };
    }

    private long getLastUpdateTimestamp() {
        if (serviceBound && clientService != null) {
            return clientService.getLastUpdateTime();
        }
        return 0L;
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
        IntentFilter settingsFilter = new IntentFilter(GNSSClientService.ACTION_DEVICE_SETTINGS_CHANGED);
        IntentFilter otaFilter = new IntentFilter(GNSSClientService.ACTION_OTA_STATUS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, connectionFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(locationReceiver, locationFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(mockLocationStatusReceiver, mockStatusFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(deviceSettingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(otaStatusReceiver, otaFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(connectionReceiver, connectionFilter);
            registerReceiver(locationReceiver, locationFilter);
            registerReceiver(mockLocationStatusReceiver, mockStatusFilter);
            registerReceiver(deviceSettingsReceiver, settingsFilter);
            registerReceiver(otaStatusReceiver, otaFilter);
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
        if (statusUiController != null) {
            statusUiController.onConnectionChanged(false);
        }
        Toast.makeText(this, getString(R.string.toast_service_disabled), Toast.LENGTH_LONG).show();
    }

    private void triggerManualRefresh() {
        if (!isServiceReadyForSettings() || clientService == null) {
            Toast.makeText(this, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            stopRefreshIndicator();
            return;
        }
        clientService.refreshDeviceSettings();
        clientService.refreshOtaPortalState();
        uiHandler.postDelayed(this::stopRefreshIndicator, 1800);
    }

    private void stopRefreshIndicator() {
        if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
            swipeRefreshLayout.setRefreshing(false);
        }
    }

    private void updateServiceStatus() {
        boolean enabled = GNSSClientService.isServiceEnabled(this);
        serviceStatusText.setText(enabled ? R.string.service_running : R.string.service_stopped);
        int statusColorRes = enabled ? R.color.chip_success : R.color.chip_error;
        serviceStatusText.setTextColor(ContextCompat.getColor(this, statusColorRes));

        int toggleTextRes =
                enabled ? R.string.button_stop_service : R.string.button_start_service;
        int backgroundColorRes = enabled ? R.color.chip_error : R.color.md_primary;
        int textColorRes = enabled ? R.color.chip_text_light : R.color.md_on_primary;

        serviceToggleButton.setText(toggleTextRes);
        serviceToggleButton.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, backgroundColorRes))
        );
        serviceToggleButton.setTextColor(ContextCompat.getColor(this, textColorRes));
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
            requestPermissionsButton.setVisibility(View.GONE);
            mockLocationStatusText.setVisibility(View.GONE);
            if (permissionsCard != null) {
                permissionsCard.setVisibility(View.GONE);
            }
        } else {
            String text =
                    String.format(
                            getString(R.string.missing_permissions),
                            String.join(", ", missingLabels)
                    );
            permissionsStatusText.setText(text);
            permissionsStatusText.setTextColor(
                    ContextCompat.getColor(this, R.color.chip_error));
            requestPermissionsButton.setVisibility(View.VISIBLE);
            if (permissionsCard != null) {
                permissionsCard.setVisibility(View.VISIBLE);
            }
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

    private void refreshDeviceSettingsOnFocus() {
        if (serviceBound && clientService != null) {
            clientService.refreshDeviceSettings();
            clientService.refreshOtaPortalState();
        }
    }
    private boolean isServiceReadyForSettings() {
        return serviceBound && clientService != null && clientService.isConnectedToServer();
    }
}
