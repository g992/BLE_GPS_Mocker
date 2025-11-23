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
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
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

    private TextView statusBadge;
    private TextView connectionBadge;
    private TextView dataAgeBadge;
    private TextView locationText;
    private TextView satellitesBadge;
    private TextView providerBadge;
    private TextView ageBadge;
    private TextView additionalInfoText;
    private TextView lastUpdateText;
    private MaterialButton requestPermissionsButton;
    private TextView permissionsStatusText;
    private TextView mockLocationStatusText;
    private MaterialButton serviceToggleButton;
    private TextView serviceStatusText;
    private SwitchMaterial apHotspotSwitch;
    private SwitchMaterial bridgeModeSwitch;
    private TextInputLayout gnssProfileLayout;
    private MaterialAutoCompleteTextView gnssProfileDropdown;
    private TextInputLayout gpsBaudRateLayout;
    private MaterialAutoCompleteTextView gpsBaudRateDropdown;

    @Nullable
    private Boolean apControlState = null;
    @Nullable
    private Boolean bridgeModeState = null;
    @Nullable
    private Integer gnssProfile = null;
    @Nullable
    private Integer gpsBaudRate = null;
    private String apSsidHint = null;
    private boolean suppressApSwitchChange = false;
    private boolean suppressBridgeSwitchChange = false;
    private boolean suppressGnssProfileChange = false;
    private boolean suppressGpsBaudChange = false;
    private int[] gnssProfileValues = new int[0];
    private String[] gnssProfileLabels = new String[0];
    private int[] gpsBaudRateValues = new int[0];
    private String[] gpsBaudRateLabels = new String[0];

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
                    int strongSatellites =
                            intent.getIntExtra(GNSSClientService.EXTRA_SATELLITES_STRONG, 0);
                    int mediumSatellites =
                            intent.getIntExtra(GNSSClientService.EXTRA_SATELLITES_MEDIUM, 0);
                    int weakSatellites =
                            intent.getIntExtra(GNSSClientService.EXTRA_SATELLITES_WEAK, 0);
                    String provider =
                            intent.getStringExtra(GNSSClientService.EXTRA_PROVIDER);
                    float locationAge =
                            intent.getFloatExtra(GNSSClientService.EXTRA_LOCATION_AGE, 0f);
                    updateLocationInfo(
                            location,
                            satellites,
                            provider,
                            locationAge,
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
                    updateMockLocationStatus(message);
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
                    String ssid = intent.getStringExtra(GNSSClientService.EXTRA_AP_SSID_HINT);
                    applyDeviceSettingsUpdate(
                            apState,
                            true,
                            bridgeState,
                            true,
                            gnssProfileValue,
                            true,
                            baudRate,
                            true,
                            ssid);
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
                            0f,
                            0,
                            0,
                            0
                    );
                    updateServiceStatus();
                    applyDeviceSettingsUpdate(
                            clientService.getApControlState(),
                            clientService.getBridgeModeState(),
                            clientService.getGnssProfile(),
                            clientService.getGpsBaudRate(),
                            clientService.getApControlSsidHint()
                    );
                    clientService.refreshDeviceSettings();
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
        startUIUpdates();
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
        uiHandler.removeCallbacksAndMessages(null);
    }

    private void initializeViews() {
        statusBadge = findViewById(R.id.statusBadge);
        connectionBadge = findViewById(R.id.connectionBadge);
        dataAgeBadge = findViewById(R.id.dataAgeBadge);
        locationText = findViewById(R.id.locationText);
        satellitesBadge = findViewById(R.id.satellitesBadge);
        providerBadge = findViewById(R.id.providerBadge);
        ageBadge = findViewById(R.id.ageBadge);
        additionalInfoText = findViewById(R.id.additionalInfoText);
        lastUpdateText = findViewById(R.id.lastUpdateText);
        requestPermissionsButton = findViewById(R.id.requestPermissionsButton);
        permissionsStatusText = findViewById(R.id.permissionsStatusText);
        mockLocationStatusText = findViewById(R.id.mockLocationStatusText);
        serviceToggleButton = findViewById(R.id.serviceToggleButton);
        serviceStatusText = findViewById(R.id.serviceStatusText);
        apHotspotSwitch = findViewById(R.id.apHotspotSwitch);
        bridgeModeSwitch = findViewById(R.id.bridgeModeSwitch);
        gnssProfileLayout = findViewById(R.id.gnssProfileLayout);
        gnssProfileDropdown = findViewById(R.id.gnssProfileDropdown);
        gpsBaudRateLayout = findViewById(R.id.gpsBaudRateLayout);
        gpsBaudRateDropdown = findViewById(R.id.gpsBaudRateDropdown);

        gnssProfileLabels = getResources().getStringArray(R.array.gnss_profile_labels);
        gnssProfileValues = getResources().getIntArray(R.array.gnss_profile_values);
        if (gnssProfileDropdown != null) {
            ArrayAdapter<String> profileAdapter =
                    new NoFilterArrayAdapter(this, android.R.layout.simple_list_item_1, Arrays.asList(gnssProfileLabels));
            gnssProfileDropdown.setAdapter(profileAdapter);
            gnssProfileDropdown.setKeyListener(null);
            gnssProfileDropdown.setText("", false);
            gnssProfileDropdown.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < gnssProfileValues.length) {
                    handleGnssProfileSelection(gnssProfileValues[position]);
                }
            });
            gnssProfileDropdown.setOnClickListener(v -> showGnssProfileDropdown());
            if (gnssProfileLayout != null) {
                gnssProfileLayout.setEndIconOnClickListener(v -> {
                    if (gnssProfileDropdown != null) {
                        gnssProfileDropdown.requestFocus();
                    }
                    showGnssProfileDropdown();
                });
            }
        }

        gpsBaudRateLabels = getResources().getStringArray(R.array.gps_baud_rate_labels);
        gpsBaudRateValues = getResources().getIntArray(R.array.gps_baud_rate_values);
        if (gpsBaudRateDropdown != null) {
            ArrayAdapter<String> baudAdapter =
                    new NoFilterArrayAdapter(this, android.R.layout.simple_list_item_1, Arrays.asList(gpsBaudRateLabels));
            gpsBaudRateDropdown.setAdapter(baudAdapter);
            gpsBaudRateDropdown.setKeyListener(null);
            gpsBaudRateDropdown.setText("", false);
            gpsBaudRateDropdown.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < gpsBaudRateValues.length) {
                    handleGpsBaudSelection(gpsBaudRateValues[position]);
                }
            });
            gpsBaudRateDropdown.setOnClickListener(v -> {
                showGpsBaudDropdown();
            });
            if (gpsBaudRateLayout != null) {
                gpsBaudRateLayout.setEndIconOnClickListener(v -> {
                    if (gpsBaudRateDropdown != null) {
                        gpsBaudRateDropdown.requestFocus();
                    }
                    showGpsBaudDropdown();
                });
            }
        }

        statusBadge.setText(getString(R.string.unknown));
        connectionBadge.setText(getString(R.string.unknown));
        dataAgeBadge.setText(getString(R.string.unknown));
        applyBadgeStyle(statusBadge, R.color.chip_neutral, R.color.chip_text_light);
        applyBadgeStyle(connectionBadge, R.color.chip_neutral, R.color.chip_text_light);
        applyBadgeStyle(dataAgeBadge, R.color.chip_neutral, R.color.chip_text_light);
        resetLocationUi();

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
        apHotspotSwitch.setEnabled(false);
        bridgeModeSwitch.setEnabled(false);
        apHotspotSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressApSwitchChange) {
                return;
            }
            handleApSwitchToggle(isChecked);
        });
        bridgeModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressBridgeSwitchChange) {
                return;
            }
            handleBridgeSwitchToggle(isChecked);
        });
        updateServiceStatus();
        updateDeviceSettingsUi();
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, connectionFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(locationReceiver, locationFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(mockLocationStatusReceiver, mockStatusFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(deviceSettingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(connectionReceiver, connectionFilter);
            registerReceiver(locationReceiver, locationFilter);
            registerReceiver(mockLocationStatusReceiver, mockStatusFilter);
            registerReceiver(deviceSettingsReceiver, settingsFilter);
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
            permissionsStatusText.setText(R.string.all_permissions_granted);
            permissionsStatusText.setTextColor(
                    ContextCompat.getColor(this, R.color.chip_success));
            requestPermissionsButton.setVisibility(View.GONE);
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
                    String statusValue =
                            getString(connected ? R.string.connected : R.string.disconnected);
                    statusBadge.setText(statusValue);

                    String connectionValue =
                            getString(
                                    connected
                                            ? R.string.connection_status_connected
                                            : R.string.connection_status_disconnected
                            );
                    connectionBadge.setText(connectionValue);

                    if (connected) {
                        applyBadgeStyle(statusBadge, R.color.chip_success, R.color.chip_text_dark);
                        applyBadgeStyle(connectionBadge, R.color.chip_success, R.color.chip_text_dark);
                    } else {
                        applyBadgeStyle(statusBadge, R.color.chip_error, R.color.chip_text_light);
                        applyBadgeStyle(connectionBadge, R.color.chip_error, R.color.chip_text_light);
                        applyBadgeStyle(dataAgeBadge, R.color.chip_neutral, R.color.chip_text_light);
                        dataAgeBadge.setText(getString(R.string.unknown));
                        resetLocationUi();
                    }
                    updateDeviceSettingsUi();
                });
    }

    private void resetLocationUi() {
        locationText.setText(getString(R.string.location_unknown));
        satellitesBadge.setText(getString(R.string.unknown));
        providerBadge.setText(getString(R.string.unknown));
        ageBadge.setText(getString(R.string.unknown));
        additionalInfoText.setText(getString(R.string.additional_info_unknown));
        lastUpdateText.setText(getString(R.string.movement_last_update_unknown));
        applyBadgeStyle(satellitesBadge, R.color.chip_neutral, R.color.chip_text_light);
        applyBadgeStyle(providerBadge, R.color.chip_neutral, R.color.chip_text_light);
        applyBadgeStyle(ageBadge, R.color.chip_neutral, R.color.chip_text_light);
    }

    private void refreshDeviceSettingsOnFocus() {
        if (serviceBound && clientService != null) {
            clientService.refreshDeviceSettings();
        }
    }

    private void applyBadgeStyle(TextView badge, @ColorRes int backgroundColorRes, @ColorRes int textColorRes) {
        Drawable background = badge.getBackground();
        if (background != null) {
            Drawable wrapped = DrawableCompat.wrap(background.mutate());
            DrawableCompat.setTint(wrapped, ContextCompat.getColor(this, backgroundColorRes));
            badge.setBackground(wrapped);
        } else {
            badge.setBackgroundColor(ContextCompat.getColor(this, backgroundColorRes));
        }
        badge.setTextColor(ContextCompat.getColor(this, textColorRes));
    }

    private void applyDeviceSettingsUpdate(
            @Nullable Boolean apState,
            boolean updateAp,
            @Nullable Boolean bridgeState,
            boolean updateBridge,
            @Nullable Integer gnssProfileValue,
            boolean updateGnssProfile,
            @Nullable Integer baudRate,
            boolean updateBaud,
            @Nullable String ssid
    ) {
        runOnUiThread(
                () -> {
                    if (updateAp) {
                        apControlState = apState;
                    }
                    if (updateBridge) {
                        bridgeModeState = bridgeState;
                    }
                    if (updateGnssProfile) {
                        gnssProfile = gnssProfileValue;
                    }
                    if (updateBaud) {
                        gpsBaudRate = baudRate;
                    }
                    if (ssid != null && !ssid.isEmpty()) {
                        apSsidHint = ssid;
                    }
                    updateDeviceSettingsUi();
                });
    }

    private void applyDeviceSettingsUpdate(
            @Nullable Boolean apState,
            @Nullable Boolean bridgeState,
            @Nullable Integer gnssProfileValue,
            @Nullable Integer baudRate,
            @Nullable String ssid
    ) {
        applyDeviceSettingsUpdate(
                apState,
                true,
                bridgeState,
                true,
                gnssProfileValue,
                true,
                baudRate,
                true,
                ssid
        );
    }

    private void updateDeviceSettingsUi() {
        boolean connected = isServiceReadyForSettings();

        if (apHotspotSwitch != null) {
            suppressApSwitchChange = true;
            boolean apKnown = apControlState != null;
            apHotspotSwitch.setEnabled(connected);
            if (apKnown) {
                apHotspotSwitch.setChecked(Boolean.TRUE.equals(apControlState));
            }
            suppressApSwitchChange = false;
        }

        if (bridgeModeSwitch != null) {
            suppressBridgeSwitchChange = true;
            boolean bridgeKnown = bridgeModeState != null;
            bridgeModeSwitch.setEnabled(connected);
            if (bridgeKnown) {
                bridgeModeSwitch.setChecked(Boolean.TRUE.equals(bridgeModeState));
            }
            suppressBridgeSwitchChange = false;
        }

        if (gnssProfileLayout != null && gnssProfileDropdown != null) {
            gnssProfileLayout.setEnabled(connected);
            gnssProfileDropdown.setEnabled(connected);
            suppressGnssProfileChange = true;
            if (gnssProfile != null) {
                String label = findGnssProfileLabel(gnssProfile);
                if (label != null) {
                    gnssProfileDropdown.setText(label, false);
                } else {
                    gnssProfileDropdown.setText(String.valueOf(gnssProfile), false);
                }
            } else {
                gnssProfileDropdown.setText("", false);
            }
            suppressGnssProfileChange = false;
        }

        if (gpsBaudRateLayout != null && gpsBaudRateDropdown != null) {
            gpsBaudRateLayout.setEnabled(connected);
            gpsBaudRateDropdown.setEnabled(connected);
            suppressGpsBaudChange = true;
            if (gpsBaudRate != null) {
                String label = findGpsBaudLabel(gpsBaudRate);
                if (label != null) {
                    gpsBaudRateDropdown.setText(label, false);
                } else {
                    gpsBaudRateDropdown.setText(String.valueOf(gpsBaudRate), false);
                }
            } else {
                gpsBaudRateDropdown.setText("", false);
            }
            suppressGpsBaudChange = false;
        }
    }

    @Nullable
    private String findGnssProfileLabel(@Nullable Integer profileValue) {
        if (profileValue == null || gnssProfileValues == null || gnssProfileLabels == null) {
            return null;
        }
        int length = Math.min(gnssProfileValues.length, gnssProfileLabels.length);
        for (int index = 0; index < length; index++) {
            if (gnssProfileValues[index] == profileValue) {
                return gnssProfileLabels[index];
            }
        }
        return null;
    }

    @Nullable
    private String findGpsBaudLabel(@Nullable Integer baudRateValue) {
        if (baudRateValue == null || gpsBaudRateValues == null || gpsBaudRateLabels == null) {
            return null;
        }
        int length = Math.min(gpsBaudRateValues.length, gpsBaudRateLabels.length);
        for (int index = 0; index < length; index++) {
            if (gpsBaudRateValues[index] == baudRateValue) {
                return gpsBaudRateLabels[index];
            }
        }
        return null;
    }

    private void showGnssProfileDropdown() {
        if (gnssProfileDropdown == null || !gnssProfileDropdown.isEnabled()) {
            return;
        }
        gnssProfileDropdown.post(
                () -> {
                    if (gnssProfileDropdown == null || !gnssProfileDropdown.isEnabled()) {
                        return;
                    }
                    if (gnssProfileDropdown.isAttachedToWindow()) {
                        gnssProfileDropdown.showDropDown();
                    }
                }
        );
    }

    private void showGpsBaudDropdown() {
        if (gpsBaudRateDropdown == null || !gpsBaudRateDropdown.isEnabled()) {
            return;
        }
        gpsBaudRateDropdown.post(
                () -> {
                    if (gpsBaudRateDropdown == null || !gpsBaudRateDropdown.isEnabled()) {
                        return;
                    }
                    if (gpsBaudRateDropdown.isAttachedToWindow()) {
                        gpsBaudRateDropdown.showDropDown();
                    }
                }
        );
    }

    private boolean isServiceReadyForSettings() {
        return serviceBound && clientService != null && clientService.isConnectedToServer();
    }

    private String getApSsidForDialog() {
        if (apSsidHint != null && !apSsidHint.isEmpty()) {
            return apSsidHint;
        }
        if (clientService != null) {
            String serviceHint = clientService.getApControlSsidHint();
            if (serviceHint != null && !serviceHint.isEmpty()) {
                apSsidHint = serviceHint;
                return serviceHint;
            }
        }
        return getString(R.string.settings_ap_default_ssid);
    }

    private void handleApSwitchToggle(boolean desiredState) {
        if (!isServiceReadyForSettings()) {
            Toast.makeText(this, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            updateDeviceSettingsUi();
            return;
        }
        boolean currentState = apControlState != null && apControlState;
        if (desiredState == currentState) {
            return;
        }
        if (desiredState) {
            suppressApSwitchChange = true;
            apHotspotSwitch.setChecked(currentState);
            suppressApSwitchChange = false;

            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_ap_dialog_title)
                    .setMessage(getString(R.string.settings_ap_dialog_message, getApSsidForDialog()))
                    .setPositiveButton(
                            R.string.dialog_ok,
                            (dialog, which) -> {
                                if (clientService == null) {
                                    updateDeviceSettingsUi();
                                    return;
                                }
                                boolean accepted = clientService.requestApControlChange(true);
                                if (!accepted) {
                                    Toast.makeText(this, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                                    updateDeviceSettingsUi();
                                    return;
                                }
                                uiHandler.postDelayed(
                                        () -> {
                                            if (clientService != null) {
                                                clientService.refreshDeviceSettings();
                                            }
                                            updateDeviceSettingsUi();
                                        },
                                        500
                                );
                            })
                    .setNegativeButton(R.string.dialog_cancel, (dialog, which) -> updateDeviceSettingsUi())
                    .setOnCancelListener(dialog -> updateDeviceSettingsUi())
                    .show();
        } else {
            boolean accepted = clientService != null && clientService.requestApControlChange(false);
            if (!accepted) {
                Toast.makeText(this, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                updateDeviceSettingsUi();
                return;
            }
            uiHandler.postDelayed(
                    () -> {
                        if (clientService != null) {
                            clientService.refreshDeviceSettings();
                        }
                        updateDeviceSettingsUi();
                    },
                    500
            );
        }
    }

    private void handleBridgeSwitchToggle(boolean desiredState) {
        if (!isServiceReadyForSettings()) {
            Toast.makeText(this, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            updateDeviceSettingsUi();
            return;
        }
        boolean currentState = bridgeModeState != null && bridgeModeState;
        if (desiredState == currentState) {
            return;
        }
        if (desiredState) {
            suppressBridgeSwitchChange = true;
            bridgeModeSwitch.setChecked(currentState);
            suppressBridgeSwitchChange = false;

            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_bridge_dialog_title)
                    .setMessage(R.string.settings_bridge_dialog_message)
                    .setPositiveButton(
                            R.string.dialog_ok,
                            (dialog, which) -> {
                                if (clientService == null) {
                                    updateDeviceSettingsUi();
                                    return;
                                }
                                boolean accepted = clientService.requestBridgeModeChange(true);
                                if (!accepted) {
                                    Toast.makeText(this, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                                    updateDeviceSettingsUi();
                                    return;
                                }
                                uiHandler.postDelayed(
                                        () -> {
                                            if (clientService != null) {
                                                clientService.refreshDeviceSettings();
                                            }
                                            updateDeviceSettingsUi();
                                        },
                                        500
                                );
                            })
                    .setNegativeButton(R.string.dialog_cancel, (dialog, which) -> updateDeviceSettingsUi())
                    .setOnCancelListener(dialog -> updateDeviceSettingsUi())
                    .show();
        } else {
            boolean accepted = clientService != null && clientService.requestBridgeModeChange(false);
            if (!accepted) {
                Toast.makeText(this, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                updateDeviceSettingsUi();
                return;
            }
            uiHandler.postDelayed(
                    () -> {
                        if (clientService != null) {
                            clientService.refreshDeviceSettings();
                        }
                        updateDeviceSettingsUi();
                    },
                    500
            );
        }
    }

    private void handleGnssProfileSelection(int desiredProfile) {
        if (suppressGnssProfileChange) {
            return;
        }
        if (gnssProfileDropdown != null) {
            gnssProfileDropdown.dismissDropDown();
        }
        if (!isServiceReadyForSettings() || clientService == null) {
            Toast.makeText(this, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            uiHandler.post(this::updateDeviceSettingsUi);
            return;
        }
        Integer current = gnssProfile;
        if (current != null && current == desiredProfile) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_gnss_profile_dialog_title)
                .setMessage(R.string.settings_gnss_profile_dialog_message)
                .setPositiveButton(
                        R.string.dialog_ok,
                        (dialog, which) -> {
                            if (clientService == null) {
                                updateDeviceSettingsUi();
                                return;
                            }
                            boolean accepted = clientService.requestGnssProfileChange(desiredProfile);
                            if (!accepted) {
                                Toast.makeText(this, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                                updateDeviceSettingsUi();
                                return;
                            }
                            uiHandler.postDelayed(
                                    () -> {
                                        if (clientService != null) {
                                            clientService.refreshDeviceSettings();
                                        }
                                    },
                                    500
                            );
                        })
                .setNegativeButton(R.string.dialog_cancel, (dialog, which) -> updateDeviceSettingsUi())
                .setOnCancelListener(dialog -> updateDeviceSettingsUi())
                .show();
    }

    private void handleGpsBaudSelection(int desiredBaudRate) {
        if (suppressGpsBaudChange) {
            return;
        }
        if (gpsBaudRateDropdown != null) {
            gpsBaudRateDropdown.dismissDropDown();
        }
        if (!isServiceReadyForSettings() || clientService == null) {
            Toast.makeText(this, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            uiHandler.post(this::updateDeviceSettingsUi);
            return;
        }
        Integer current = gpsBaudRate;
        if (current != null && current == desiredBaudRate) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_gps_baud_dialog_title)
                .setMessage(R.string.settings_gps_baud_dialog_message)
                .setPositiveButton(
                        R.string.dialog_ok,
                        (dialog, which) -> {
                            if (clientService == null) {
                                updateDeviceSettingsUi();
                                return;
                            }
                            boolean accepted = clientService.requestGpsBaudRateChange(desiredBaudRate);
                            if (!accepted) {
                                Toast.makeText(this, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                                updateDeviceSettingsUi();
                                return;
                            }
                            uiHandler.postDelayed(
                                    () -> {
                                        if (clientService != null) {
                                            clientService.refreshDeviceSettings();
                                        }
                                    },
                                    500
                            );
                        })
                .setNegativeButton(R.string.dialog_cancel, (dialog, which) -> updateDeviceSettingsUi())
                .setOnCancelListener(dialog -> updateDeviceSettingsUi())
                .show();
    }

    private static class NoFilterArrayAdapter extends ArrayAdapter<String> {
        private final List<String> items;

        NoFilterArrayAdapter(@NonNull Context context, int resource, @NonNull List<String> values) {
            super(context, resource, new ArrayList<>(values));
            this.items = new ArrayList<>(values);
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Nullable
        @Override
        public String getItem(int position) {
            return items.get(position);
        }

        @NonNull
        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    results.count = items.size();
                    results.values = items;
                    return results;
                }

                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    notifyDataSetChanged();
                }

                @Override
                public CharSequence convertResultToString(Object resultValue) {
                    return resultValue instanceof CharSequence ? (CharSequence) resultValue : super.convertResultToString(resultValue);
                }
            };
        }
    }

    private CharSequence buildSatelliteBadgeText(
            int total,
            int strongSatellites,
            int mediumSatellites,
            int weakSatellites
    ) {
        int strong = Math.max(strongSatellites, 0);
        int medium = Math.max(mediumSatellites, 0);
        int weak = Math.max(weakSatellites, 0);
        int breakdownSum = strong + medium + weak;
        int effectiveTotal = total > 0 ? total : breakdownSum;
        if (effectiveTotal < 0) {
            effectiveTotal = 0;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(String.valueOf(effectiveTotal));
        if (breakdownSum > 0) {
            builder.append(' ');
            builder.append('(');
            int strongStart = builder.length();
            builder.append(String.valueOf(strong));
            builder.setSpan(
                    new ForegroundColorSpan(ContextCompat.getColor(this, R.color.chip_success)),
                    strongStart,
                    builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            builder.append('/');
            int mediumStart = builder.length();
            builder.append(String.valueOf(medium));
            builder.setSpan(
                    new ForegroundColorSpan(ContextCompat.getColor(this, R.color.chip_warning)),
                    mediumStart,
                    builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            builder.append('/');
            int weakStart = builder.length();
            builder.append(String.valueOf(weak));
            builder.setSpan(
                    new ForegroundColorSpan(ContextCompat.getColor(this, R.color.chip_error)),
                    weakStart,
                    builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            builder.append(')');
        }
        return builder;
    }

    private void updateLocationInfo(
            @Nullable Location location,
            int satellites,
            @Nullable String provider,
            float locationAge,
            int strongSatellites,
            int mediumSatellites,
            int weakSatellites
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

                    satellitesBadge.setText(
                            buildSatelliteBadgeText(
                                    satellites,
                                    strongSatellites,
                                    mediumSatellites,
                                    weakSatellites
                            )
                    );
                    applyBadgeStyle(satellitesBadge, R.color.chip_neutral, R.color.chip_text_light);

                    providerBadge.setText(provider != null ? provider : getString(R.string.unknown));
                    applyBadgeStyle(providerBadge, R.color.chip_neutral, R.color.chip_text_light);

                    ageBadge.setText(
                            String.format(getString(R.string.age_format), locationAge)
                    );
                    applyBadgeStyle(ageBadge, R.color.chip_neutral, R.color.chip_text_light);

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
                    } else {
                        additionalInfoText.setText(getString(R.string.additional_info_unknown));
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
                            String ageDisplay;
                            int backgroundRes;
                            if (ageSeconds < 60) {
                                ageDisplay =
                                        String.format(
                                                getString(R.string.data_age_format_s),
                                                ageSeconds
                                        );
                                backgroundRes = R.color.chip_success;
                            } else {
                                ageDisplay =
                                        String.format(
                                                getString(R.string.data_age_format_ms),
                                                ageSeconds / 60,
                                                ageSeconds % 60
                                        );
                                backgroundRes = R.color.chip_warning;
                            }
                            dataAgeBadge.setText(ageDisplay);
                            applyBadgeStyle(dataAgeBadge, backgroundRes, R.color.chip_text_dark);
                        });
                return;
            }
        }
        runOnUiThread(
                () -> {
                    dataAgeBadge.setText(getString(R.string.unknown));
                    applyBadgeStyle(dataAgeBadge, R.color.chip_neutral, R.color.chip_text_light);
                });
    }
}
