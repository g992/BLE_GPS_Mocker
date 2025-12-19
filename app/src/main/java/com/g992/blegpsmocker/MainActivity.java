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
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int MOCK_LOCATION_SETTINGS_REQUEST_CODE = 1002;

    private static final int GNSS_PROFILE_CUSTOM_VALUE = 3;
    private static final int BASE_PROFILE_CUSTOM_VALUE = 1;
    private static final int GNSS_RECEIVER_TYPE_UBLOX = 0;
    private static final int GNSS_RECEIVER_TYPE_GENERIC = 1;

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
    private SwitchMaterial apHotspotSwitch;
    private SwitchMaterial bridgeModeSwitch;
    private TextInputLayout gnssReceiverTypeLayout;
    private MaterialAutoCompleteTextView gnssReceiverTypeDropdown;
    private TextInputLayout gnssProfileLayout;
    private MaterialAutoCompleteTextView gnssProfileDropdown;
    private TextInputLayout customGnssFrameLayout;
    private TextInputEditText customGnssFrameInput;
    private MaterialButton customGnssFrameApplyButton;
    private TextInputLayout baseSettingsProfileLayout;
    private MaterialAutoCompleteTextView baseSettingsProfileDropdown;
    private TextInputLayout customBaseFrameLayout;
    private TextInputEditText customBaseFrameInput;
    private MaterialButton customBaseFrameApplyButton;
    private TextInputLayout gpsBaudRateLayout;
    private MaterialAutoCompleteTextView gpsBaudRateDropdown;
    private TextView wifiStatusText;
    private SwitchMaterial otaSwitch;
    private View permissionsCard;
    private SwipeRefreshLayout swipeRefreshLayout;
    private TextView inputVoltageText;
    private TextView deviceVersionText;
    private SwitchMaterial alwaysMovingSwitch;

    @Nullable
    private Boolean apControlState = null;
    @Nullable
    private Boolean bridgeModeState = null;
    @Nullable
    private Integer gnssReceiverType = null;
    @Nullable
    private Integer gnssProfile = null;
    @Nullable
    private Integer gpsBaudRate = null;
    @Nullable
    private Integer baseSettingsProfile = null;
    @Nullable
    private String customGnssProfileFrame = null;
    @Nullable
    private String customBaseSettingsFrame = null;
    private String apSsidHint = null;
    private boolean suppressApSwitchChange = false;
    private boolean suppressBridgeSwitchChange = false;
    private boolean suppressGnssReceiverTypeChange = false;
    private boolean suppressGnssProfileChange = false;
    private boolean suppressBaseProfileChange = false;
    private boolean suppressGpsBaudChange = false;
    private boolean otaGuardEnabled = false;
    private boolean suppressOtaSwitchChange = false;
    private boolean suppressAlwaysMovingChange = false;
    @Nullable
    private String wifiIp = null;
    @Nullable
    private String wifiState = null;
    @Nullable
    private String deviceVersion = null;
    @Nullable
    private Double inputVoltage = null;
    private int[] gnssReceiverTypeValues = new int[0];
    private String[] gnssReceiverTypeLabels = new String[0];
    private int[] gnssProfileValues = new int[0];
    private String[] gnssProfileLabels = new String[0];
    private int[] baseSettingsProfileValues = new int[0];
    private String[] baseSettingsProfileLabels = new String[0];
    private int[] gpsBaudRateValues = new int[0];
    private String[] gpsBaudRateLabels = new String[0];
    @Nullable
    private Integer pendingGnssReceiverTypeSelection = null;
    @Nullable
    private Integer pendingGnssProfileSelection = null;
    @Nullable
    private Integer pendingBaseProfileSelection = null;

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
                    updateLocationInfo(
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
                    boolean gnssReceiverTypeKnown =
                            intent.getBooleanExtra(GNSSClientService.EXTRA_GNSS_RECEIVER_TYPE_KNOWN, false);
                    Integer gnssReceiverTypeValue = gnssReceiverTypeKnown
                            ? intent.getIntExtra(GNSSClientService.EXTRA_GNSS_RECEIVER_TYPE, 0)
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
                    payload.gnssReceiverTypeKnown = gnssReceiverTypeKnown;
                    payload.gnssReceiverType = gnssReceiverTypeValue;
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
                    applyDeviceSettingsUpdate(payload);
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
                    updateOtaStatus(guardEnabled, wifiState, wifiIp, message);
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
                    payload.gnssReceiverTypeKnown = true;
                    payload.gnssReceiverType = clientService.getGnssReceiverType();
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
                    applyDeviceSettingsUpdate(payload);
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
        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        setSupportActionBar(topAppBar);

        initializeViews();
        startAndBindService();
        registerReceivers();

        updatePermissionsStatus();
        startUIUpdates();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_debug) {
            Intent debugIntent = new Intent(this, DebugActivity.class);
            startActivity(debugIntent);
            return true;
        }
        return super.onOptionsItemSelected(item);
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
        apHotspotSwitch = findViewById(R.id.apHotspotSwitch);
        bridgeModeSwitch = findViewById(R.id.bridgeModeSwitch);
        gnssReceiverTypeLayout = findViewById(R.id.gnssReceiverTypeLayout);
        gnssReceiverTypeDropdown = findViewById(R.id.gnssReceiverTypeDropdown);
        gnssProfileLayout = findViewById(R.id.gnssProfileLayout);
        gnssProfileDropdown = findViewById(R.id.gnssProfileDropdown);
        customGnssFrameLayout = findViewById(R.id.customGnssFrameLayout);
        customGnssFrameInput = findViewById(R.id.customGnssFrameInput);
        customGnssFrameApplyButton = findViewById(R.id.customGnssFrameApplyButton);
        baseSettingsProfileLayout = findViewById(R.id.baseSettingsProfileLayout);
        baseSettingsProfileDropdown = findViewById(R.id.baseSettingsProfileDropdown);
        customBaseFrameLayout = findViewById(R.id.customBaseFrameLayout);
        customBaseFrameInput = findViewById(R.id.customBaseFrameInput);
        customBaseFrameApplyButton = findViewById(R.id.customBaseFrameApplyButton);
        gpsBaudRateLayout = findViewById(R.id.gpsBaudRateLayout);
        gpsBaudRateDropdown = findViewById(R.id.gpsBaudRateDropdown);
        wifiStatusText = findViewById(R.id.wifiStatusText);
        otaSwitch = findViewById(R.id.otaSwitch);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        inputVoltageText = findViewById(R.id.inputVoltageText);
        deviceVersionText = findViewById(R.id.deviceVersionText);
        alwaysMovingSwitch = findViewById(R.id.alwaysMovingSwitch);

        gnssReceiverTypeLabels = getResources().getStringArray(R.array.gnss_receiver_type_labels);
        gnssReceiverTypeValues = getResources().getIntArray(R.array.gnss_receiver_type_values);
        if (gnssReceiverTypeDropdown != null) {
            ArrayAdapter<String> receiverAdapter =
                    new NoFilterArrayAdapter(this, android.R.layout.simple_list_item_1, Arrays.asList(gnssReceiverTypeLabels));
            gnssReceiverTypeDropdown.setAdapter(receiverAdapter);
            gnssReceiverTypeDropdown.setKeyListener(null);
            gnssReceiverTypeDropdown.setText("", false);
            gnssReceiverTypeDropdown.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < gnssReceiverTypeValues.length) {
                    handleGnssReceiverTypeSelection(gnssReceiverTypeValues[position]);
                }
            });
            gnssReceiverTypeDropdown.setOnClickListener(v -> showGnssReceiverTypeDropdown());
            if (gnssReceiverTypeLayout != null) {
                gnssReceiverTypeLayout.setEndIconOnClickListener(v -> {
                    if (gnssReceiverTypeDropdown != null) {
                        gnssReceiverTypeDropdown.requestFocus();
                    }
                    showGnssReceiverTypeDropdown();
                });
            }
        }

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

        baseSettingsProfileLabels = getResources().getStringArray(R.array.base_settings_profile_labels);
        baseSettingsProfileValues = getResources().getIntArray(R.array.base_settings_profile_values);
        if (baseSettingsProfileDropdown != null) {
            ArrayAdapter<String> baseAdapter =
                    new NoFilterArrayAdapter(this, android.R.layout.simple_list_item_1, Arrays.asList(baseSettingsProfileLabels));
            baseSettingsProfileDropdown.setAdapter(baseAdapter);
            baseSettingsProfileDropdown.setKeyListener(null);
            baseSettingsProfileDropdown.setText("", false);
            baseSettingsProfileDropdown.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < baseSettingsProfileValues.length) {
                    handleBaseProfileSelection(baseSettingsProfileValues[position]);
                }
            });
            baseSettingsProfileDropdown.setOnClickListener(v -> showBaseProfileDropdown());
            if (baseSettingsProfileLayout != null) {
                baseSettingsProfileLayout.setEndIconOnClickListener(v -> {
                    if (baseSettingsProfileDropdown != null) {
                        baseSettingsProfileDropdown.requestFocus();
                    }
                    showBaseProfileDropdown();
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

        connectionBadge.setText(getString(R.string.unknown));
        dataAgeBadge.setText(getString(R.string.unknown));
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
        otaSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressOtaSwitchChange) {
                return;
            }
            toggleOtaPortal(isChecked);
        });
        if (customGnssFrameApplyButton != null) {
            customGnssFrameApplyButton.setOnClickListener(v -> applyCustomGnssFrame());
        }
        if (customBaseFrameApplyButton != null) {
            customBaseFrameApplyButton.setOnClickListener(v -> applyCustomBaseFrame());
        }
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setColorSchemeResources(
                    R.color.md_primary,
                    R.color.chip_success,
                    R.color.chip_error
            );
            swipeRefreshLayout.setOnRefreshListener(this::triggerManualRefresh);
        }
        setupAlwaysMovingToggle();
        updateServiceStatus();
        updateDeviceSettingsUi();
    }

    private void setupAlwaysMovingToggle() {
        if (alwaysMovingSwitch == null) {
            return;
        }
        suppressAlwaysMovingChange = true;
        alwaysMovingSwitch.setChecked(AppPrefs.isAlwaysMovingEnabled(this));
        suppressAlwaysMovingChange = false;
        alwaysMovingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressAlwaysMovingChange) {
                return;
            }
            if (isChecked) {
                showAlwaysMovingDialog();
            } else {
                AppPrefs.setAlwaysMovingEnabled(this, false);
                syncAlwaysMovingSetting();
            }
            refreshAlwaysMovingUi();
        });
    }

    private void refreshAlwaysMovingUi() {
        if (alwaysMovingSwitch == null) {
            return;
        }
        suppressAlwaysMovingChange = true;
        alwaysMovingSwitch.setChecked(AppPrefs.isAlwaysMovingEnabled(this));
        suppressAlwaysMovingChange = false;
    }

    private void showAlwaysMovingDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.always_moving_dialog_title)
                .setMessage(R.string.always_moving_dialog_message)
                .setPositiveButton(
                        R.string.dialog_ok,
                        (dialog, which) -> {
                            AppPrefs.setAlwaysMovingEnabled(this, true);
                            refreshAlwaysMovingUi();
                            syncAlwaysMovingSetting();
                        })
                .setNegativeButton(
                        R.string.dialog_cancel,
                        (dialog, which) -> {
                            AppPrefs.setAlwaysMovingEnabled(this, false);
                            refreshAlwaysMovingUi();
                        })
                .setOnCancelListener(dialog -> {
                    AppPrefs.setAlwaysMovingEnabled(this, false);
                    refreshAlwaysMovingUi();
                })
                .show();
    }

    private void syncAlwaysMovingSetting() {
        if (serviceBound && clientService != null) {
            clientService.setAlwaysMovingEnabled(AppPrefs.isAlwaysMovingEnabled(this));
        }
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
        updateConnectionStatus(false);
        Toast.makeText(this, getString(R.string.toast_service_disabled), Toast.LENGTH_LONG).show();
    }

    private void toggleOtaPortal(boolean desired) {
        if (!isServiceReadyForSettings() || clientService == null) {
            Toast.makeText(this, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            updateOtaControlsState();
            return;
        }
        boolean accepted = clientService.requestOtaPortal(desired);
        if (!accepted) {
            Toast.makeText(this, R.string.ota_guard_write_failed, Toast.LENGTH_LONG).show();
        }
        updateOtaControlsState();
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

    private void makeWifiClickable(boolean enabled) {
        if (wifiStatusText == null) {
            return;
        }
        if (enabled && wifiIp != null) {
            wifiStatusText.setPaintFlags(wifiStatusText.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            wifiStatusText.setClickable(true);
            wifiStatusText.setOnClickListener(v -> openUpdatePage());
        } else {
            wifiStatusText.setPaintFlags(wifiStatusText.getPaintFlags() & (~Paint.UNDERLINE_TEXT_FLAG));
            wifiStatusText.setClickable(false);
            wifiStatusText.setOnClickListener(null);
        }
    }

    private void openUpdatePage() {
        String ip = wifiIp;
        if (ip == null || ip.isEmpty()) {
            Toast.makeText(this, R.string.ota_link_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        String url = "http://" + ip + "/";
        try {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(viewIntent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.ota_link_unavailable, Toast.LENGTH_LONG).show();
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

    private void updateOtaStatus(
            boolean guardEnabled,
            @Nullable String wifiState,
            @Nullable String wifiIp,
            @Nullable String message
    ) {
        runOnUiThread(
                () -> {
                    otaGuardEnabled = guardEnabled;
                    this.wifiState = wifiState;
                    this.wifiIp = (wifiIp != null && !wifiIp.isEmpty()) ? wifiIp : null;

                    String wifiText;
                    String effectiveState = wifiState != null ? wifiState : "unknown";
                    switch (effectiveState) {
                        case "connected":
                            wifiText =
                                    this.wifiIp != null
                                            ? getString(R.string.ota_wifi_connected_ip, this.wifiIp)
                                            : getString(R.string.ota_wifi_connected);
                            break;
                        case "connecting":
                            wifiText = getString(R.string.ota_wifi_connecting);
                            break;
                        case "disconnected":
                            wifiText = getString(R.string.ota_wifi_disconnected);
                            break;
                        default:
                    wifiText = getString(R.string.ota_wifi_unknown);
                    break;
            }
            wifiStatusText.setText(wifiText);

            makeWifiClickable(this.wifiIp != null);

            suppressOtaSwitchChange = true;
            otaSwitch.setChecked(otaGuardEnabled);
            suppressOtaSwitchChange = false;
            updateOtaControlsState();
            stopRefreshIndicator();
        });
    }

    private void updateOtaControlsState() {
        boolean connected = isServiceReadyForSettings();
        if (otaSwitch != null) {
            otaSwitch.setEnabled(connected);
            suppressOtaSwitchChange = true;
            otaSwitch.setChecked(otaGuardEnabled);
            suppressOtaSwitchChange = false;
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
                    String connectionValue =
                            getString(
                                    connected
                                            ? R.string.connection_status_connected
                                            : R.string.connection_status_disconnected
                            );
                    connectionBadge.setText(connectionValue);

                    if (connected) {
                        applyBadgeStyle(connectionBadge, R.color.chip_success, R.color.chip_text_dark);
                    } else {
                        applyBadgeStyle(connectionBadge, R.color.chip_error, R.color.chip_text_light);
                        applyBadgeStyle(dataAgeBadge, R.color.chip_neutral, R.color.chip_text_light);
                        dataAgeBadge.setText(getString(R.string.unknown));
                        resetLocationUi();
                        otaGuardEnabled = false;
                        wifiIp = null;
                        wifiState = null;
                        wifiStatusText.setText(R.string.ota_wifi_unknown);
                        makeWifiClickable(false);
                        suppressOtaSwitchChange = true;
                        otaSwitch.setChecked(false);
                        suppressOtaSwitchChange = false;
                        pendingGnssReceiverTypeSelection = null;
                        pendingGnssProfileSelection = null;
                        pendingBaseProfileSelection = null;
                    }
                    updateDeviceSettingsUi();
                    stopRefreshIndicator();
                });
    }

    private void resetLocationUi() {
        locationText.setText(getString(R.string.location_unknown));
        satellitesBadge.setText(getString(R.string.unknown));
        additionalInfoText.setText(getString(R.string.additional_info_unknown));
        applyBadgeStyle(satellitesBadge, R.color.chip_neutral, R.color.chip_text_light);
    }

    private void refreshDeviceSettingsOnFocus() {
        if (serviceBound && clientService != null) {
            clientService.refreshDeviceSettings();
            clientService.refreshOtaPortalState();
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

    private void applyDeviceSettingsUpdate(@NonNull DeviceSettingsPayload payload) {
        runOnUiThread(
                () -> {
                    if (payload.apKnown) {
                        apControlState = payload.apState;
                    }
                    if (payload.bridgeKnown) {
                        bridgeModeState = payload.bridgeState;
                    }
                    if (payload.gnssReceiverTypeKnown) {
                        gnssReceiverType = payload.gnssReceiverType;
                        pendingGnssReceiverTypeSelection = null;
                    }
                    if (payload.gnssProfileKnown) {
                        gnssProfile = payload.gnssProfile;
                        pendingGnssProfileSelection = null;
                    }
                    if (payload.baseSettingsProfileKnown) {
                        baseSettingsProfile = payload.baseSettingsProfile;
                        pendingBaseProfileSelection = null;
                    }
                    if (payload.gpsBaudKnown) {
                        gpsBaudRate = payload.gpsBaudRate;
                    }
                    if (payload.ssidHint != null && !payload.ssidHint.isEmpty()) {
                        apSsidHint = payload.ssidHint;
                    }
                    if (payload.deviceVersionKnown) {
                        deviceVersion = payload.deviceVersion;
                    }
                    if (payload.inputVoltageKnown) {
                        inputVoltage = payload.inputVoltage;
                    }
                    if (payload.customGnssProfileFrameKnown) {
                        customGnssProfileFrame = payload.customGnssProfileFrame;
                    }
                    if (payload.customBaseSettingsFrameKnown) {
                        customBaseSettingsFrame = payload.customBaseSettingsFrame;
                    }
                    updateDeviceSettingsUi();
                });
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

        if (gnssReceiverTypeLayout != null && gnssReceiverTypeDropdown != null) {
            gnssReceiverTypeLayout.setEnabled(connected);
            gnssReceiverTypeDropdown.setEnabled(connected);
            suppressGnssReceiverTypeChange = true;
            Integer typeToShow = gnssReceiverType != null ? gnssReceiverType : pendingGnssReceiverTypeSelection;
            if (typeToShow != null) {
                String label = findGnssReceiverTypeLabel(typeToShow);
                if (label != null) {
                    gnssReceiverTypeDropdown.setText(label, false);
                } else {
                    gnssReceiverTypeDropdown.setText(String.valueOf(typeToShow), false);
                }
            } else {
                gnssReceiverTypeDropdown.setText("", false);
            }
            suppressGnssReceiverTypeChange = false;
        }

        if (gnssProfileLayout != null && gnssProfileDropdown != null) {
            gnssProfileLayout.setEnabled(connected);
            gnssProfileDropdown.setEnabled(connected);
            suppressGnssProfileChange = true;
            Integer profileToShow = gnssProfile != null ? gnssProfile : pendingGnssProfileSelection;
            if (profileToShow != null) {
                String label = findGnssProfileLabel(profileToShow);
                if (label != null) {
                    gnssProfileDropdown.setText(label, false);
                } else {
                    gnssProfileDropdown.setText(String.valueOf(profileToShow), false);
                }
            } else {
                gnssProfileDropdown.setText("", false);
            }
            suppressGnssProfileChange = false;
        }

        if (customGnssFrameLayout != null) {
            boolean showCustomGnss = isCustomGnssProfileSelected();
            customGnssFrameLayout.setVisibility(showCustomGnss ? View.VISIBLE : View.GONE);
            customGnssFrameLayout.setEnabled(connected && showCustomGnss);
            if (customGnssFrameInput != null) {
                if (!showCustomGnss) {
                    customGnssFrameInput.setError(null);
                }
                customGnssFrameInput.setEnabled(connected && showCustomGnss);
                if (!customGnssFrameInput.hasFocus()) {
                    String value = customGnssProfileFrame != null ? customGnssProfileFrame : "";
                    customGnssFrameInput.setText(value);
                }
            }
        }
        if (customGnssFrameApplyButton != null) {
            customGnssFrameApplyButton.setVisibility(isCustomGnssProfileSelected() ? View.VISIBLE : View.GONE);
            customGnssFrameApplyButton.setEnabled(connected && isCustomGnssProfileSelected());
        }

        if (baseSettingsProfileLayout != null && baseSettingsProfileDropdown != null) {
            baseSettingsProfileLayout.setEnabled(connected);
            baseSettingsProfileDropdown.setEnabled(connected);
            suppressBaseProfileChange = true;
            Integer profileToShow = baseSettingsProfile != null ? baseSettingsProfile : pendingBaseProfileSelection;
            if (profileToShow != null) {
                String label = findBaseProfileLabel(profileToShow);
                if (label != null) {
                    baseSettingsProfileDropdown.setText(label, false);
                } else {
                    baseSettingsProfileDropdown.setText(String.valueOf(profileToShow), false);
                }
            } else {
                baseSettingsProfileDropdown.setText("", false);
            }
            suppressBaseProfileChange = false;
        }

        if (customBaseFrameLayout != null) {
            boolean showCustomBase = isCustomBaseProfileSelected();
            customBaseFrameLayout.setVisibility(showCustomBase ? View.VISIBLE : View.GONE);
            customBaseFrameLayout.setEnabled(connected && showCustomBase);
            if (customBaseFrameInput != null) {
                if (!showCustomBase) {
                    customBaseFrameInput.setError(null);
                }
                customBaseFrameInput.setEnabled(connected && showCustomBase);
                if (!customBaseFrameInput.hasFocus()) {
                    String value = customBaseSettingsFrame != null ? customBaseSettingsFrame : "";
                    customBaseFrameInput.setText(value);
                }
            }
        }
        if (customBaseFrameApplyButton != null) {
            customBaseFrameApplyButton.setVisibility(isCustomBaseProfileSelected() ? View.VISIBLE : View.GONE);
            customBaseFrameApplyButton.setEnabled(connected && isCustomBaseProfileSelected());
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

        if (inputVoltageText != null) {
            String voltageValue =
                    inputVoltage != null
                            ? getString(R.string.settings_input_voltage_label, inputVoltage)
                            : getString(R.string.settings_input_voltage_unknown);
            inputVoltageText.setText(voltageValue);
        }

        if (deviceVersionText != null) {
            String versionValue =
                    deviceVersion != null
                            ? getString(R.string.settings_device_version_label, deviceVersion)
                            : getString(R.string.settings_device_version_unknown);
            deviceVersionText.setText(versionValue);
        }

        updateOtaControlsState();
        stopRefreshIndicator();
    }

    @Nullable
    private String findGnssReceiverTypeLabel(@Nullable Integer typeValue) {
        if (typeValue == null || gnssReceiverTypeValues == null || gnssReceiverTypeLabels == null) {
            return null;
        }
        int length = Math.min(gnssReceiverTypeValues.length, gnssReceiverTypeLabels.length);
        for (int index = 0; index < length; index++) {
            if (gnssReceiverTypeValues[index] == typeValue) {
                return gnssReceiverTypeLabels[index];
            }
        }
        return null;
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
    private String findBaseProfileLabel(@Nullable Integer profileValue) {
        if (profileValue == null || baseSettingsProfileValues == null || baseSettingsProfileLabels == null) {
            return null;
        }
        int length = Math.min(baseSettingsProfileValues.length, baseSettingsProfileLabels.length);
        for (int index = 0; index < length; index++) {
            if (baseSettingsProfileValues[index] == profileValue) {
                return baseSettingsProfileLabels[index];
            }
        }
        return null;
    }

    private boolean isCustomGnssProfileSelected() {
        Integer profileValue = gnssProfile != null ? gnssProfile : pendingGnssProfileSelection;
        return profileValue != null && profileValue == GNSS_PROFILE_CUSTOM_VALUE;
    }

    private boolean isCustomBaseProfileSelected() {
        Integer profileValue = baseSettingsProfile != null ? baseSettingsProfile : pendingBaseProfileSelection;
        return profileValue != null && profileValue == BASE_PROFILE_CUSTOM_VALUE;
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

    private void showGnssReceiverTypeDropdown() {
        if (gnssReceiverTypeDropdown == null || !gnssReceiverTypeDropdown.isEnabled()) {
            return;
        }
        gnssReceiverTypeDropdown.post(
                () -> {
                    if (gnssReceiverTypeDropdown == null || !gnssReceiverTypeDropdown.isEnabled()) {
                        return;
                    }
                    if (gnssReceiverTypeDropdown.isAttachedToWindow()) {
                        gnssReceiverTypeDropdown.showDropDown();
                    }
                }
        );
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

    private void showBaseProfileDropdown() {
        if (baseSettingsProfileDropdown == null || !baseSettingsProfileDropdown.isEnabled()) {
            return;
        }
        baseSettingsProfileDropdown.post(
                () -> {
                    if (baseSettingsProfileDropdown == null || !baseSettingsProfileDropdown.isEnabled()) {
                        return;
                    }
                    if (baseSettingsProfileDropdown.isAttachedToWindow()) {
                        baseSettingsProfileDropdown.showDropDown();
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

    private void handleGnssReceiverTypeSelection(int desiredType) {
        if (suppressGnssReceiverTypeChange) {
            return;
        }
        if (desiredType != GNSS_RECEIVER_TYPE_UBLOX && desiredType != GNSS_RECEIVER_TYPE_GENERIC) {
            return;
        }
        if (gnssReceiverTypeDropdown != null) {
            gnssReceiverTypeDropdown.dismissDropDown();
        }
        pendingGnssReceiverTypeSelection = desiredType;
        updateDeviceSettingsUi();
        if (!isServiceReadyForSettings() || clientService == null) {
            Toast.makeText(this, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            pendingGnssReceiverTypeSelection = null;
            uiHandler.post(this::updateDeviceSettingsUi);
            return;
        }
        Integer current = gnssReceiverType;
        if (current != null && current == desiredType) {
            pendingGnssReceiverTypeSelection = null;
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_gnss_receiver_type_dialog_title)
                .setMessage(R.string.settings_gnss_receiver_type_dialog_message)
                .setPositiveButton(
                        R.string.dialog_ok,
                        (dialog, which) -> {
                            if (clientService == null) {
                                pendingGnssReceiverTypeSelection = null;
                                updateDeviceSettingsUi();
                                return;
                            }
                            boolean accepted = clientService.requestGnssReceiverTypeChange(desiredType);
                            if (!accepted) {
                                Toast.makeText(this, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                                pendingGnssReceiverTypeSelection = null;
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
                .setNegativeButton(R.string.dialog_cancel, (dialog, which) -> {
                    pendingGnssReceiverTypeSelection = null;
                    updateDeviceSettingsUi();
                })
                .setOnCancelListener(dialog -> {
                    pendingGnssReceiverTypeSelection = null;
                    updateDeviceSettingsUi();
                })
                .show();
    }

    private void handleGnssProfileSelection(int desiredProfile) {
        if (suppressGnssProfileChange) {
            return;
        }
        if (gnssProfileDropdown != null) {
            gnssProfileDropdown.dismissDropDown();
        }
        pendingGnssProfileSelection = desiredProfile;
        updateDeviceSettingsUi();
        if (!isServiceReadyForSettings() || clientService == null) {
            Toast.makeText(this, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            pendingGnssProfileSelection = null;
            uiHandler.post(this::updateDeviceSettingsUi);
            return;
        }
        Integer current = gnssProfile;
        if (current != null && current == desiredProfile) {
            pendingGnssProfileSelection = null;
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
                                pendingGnssProfileSelection = null;
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
                .setNegativeButton(R.string.dialog_cancel, (dialog, which) -> {
                    pendingGnssProfileSelection = null;
                    updateDeviceSettingsUi();
                })
                .setOnCancelListener(dialog -> {
                    pendingGnssProfileSelection = null;
                    updateDeviceSettingsUi();
                })
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

    private void handleBaseProfileSelection(int desiredProfile) {
        if (suppressBaseProfileChange) {
            return;
        }
        if (baseSettingsProfileDropdown != null) {
            baseSettingsProfileDropdown.dismissDropDown();
        }
        pendingBaseProfileSelection = desiredProfile;
        updateDeviceSettingsUi();
        if (!isServiceReadyForSettings() || clientService == null) {
            Toast.makeText(this, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            pendingBaseProfileSelection = null;
            uiHandler.post(this::updateDeviceSettingsUi);
            return;
        }
        Integer current = baseSettingsProfile;
        if (current != null && current == desiredProfile) {
            pendingBaseProfileSelection = null;
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_base_profile_dialog_title)
                .setMessage(R.string.settings_base_profile_dialog_message)
                .setPositiveButton(
                        R.string.dialog_ok,
                        (dialog, which) -> {
                            if (clientService == null) {
                                pendingBaseProfileSelection = null;
                                updateDeviceSettingsUi();
                                return;
                            }
                            boolean accepted = clientService.requestBaseSettingsProfileChange(desiredProfile);
                            if (!accepted) {
                                Toast.makeText(this, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                                pendingBaseProfileSelection = null;
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
                .setNegativeButton(R.string.dialog_cancel, (dialog, which) -> {
                    pendingBaseProfileSelection = null;
                    updateDeviceSettingsUi();
                })
                .setOnCancelListener(dialog -> {
                    pendingBaseProfileSelection = null;
                    updateDeviceSettingsUi();
                })
                .show();
    }

    private void applyCustomGnssFrame() {
        if (customGnssFrameInput == null) {
            return;
        }
        String frame = customGnssFrameInput.getText() != null
                ? customGnssFrameInput.getText().toString().trim()
                : "";
        if (frame.isEmpty()) {
            customGnssFrameInput.setError(getString(R.string.settings_custom_frame_empty_error));
            return;
        }
        customGnssFrameInput.setError(null);
        if (!isServiceReadyForSettings() || clientService == null) {
            Toast.makeText(this, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            return;
        }
        boolean accepted = clientService.updateCustomGnssProfileFrame(frame);
        if (!accepted) {
            Toast.makeText(this, R.string.settings_custom_frame_write_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.settings_custom_frame_saved, Toast.LENGTH_SHORT).show();
        uiHandler.postDelayed(
                () -> {
                    if (clientService != null) {
                        clientService.refreshDeviceSettings();
                    }
                },
                400
        );
    }

    private void applyCustomBaseFrame() {
        if (customBaseFrameInput == null) {
            return;
        }
        String frame = customBaseFrameInput.getText() != null
                ? customBaseFrameInput.getText().toString().trim()
                : "";
        if (frame.isEmpty()) {
            customBaseFrameInput.setError(getString(R.string.settings_custom_frame_empty_error));
            return;
        }
        customBaseFrameInput.setError(null);
        if (!isServiceReadyForSettings() || clientService == null) {
            Toast.makeText(this, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            return;
        }
        boolean accepted = clientService.updateCustomBaseSettingsFrame(frame);
        if (!accepted) {
            Toast.makeText(this, R.string.settings_custom_frame_write_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.settings_custom_frame_saved, Toast.LENGTH_SHORT).show();
        uiHandler.postDelayed(
                () -> {
                    if (clientService != null) {
                        clientService.refreshDeviceSettings();
                    }
                },
                400
        );
    }

    private static class DeviceSettingsPayload {
        @Nullable
        Boolean apState;
        boolean apKnown;
        @Nullable
        Boolean bridgeState;
        boolean bridgeKnown;
        @Nullable
        Integer gnssReceiverType;
        boolean gnssReceiverTypeKnown;
        @Nullable
        Integer gnssProfile;
        boolean gnssProfileKnown;
        @Nullable
        Integer baseSettingsProfile;
        boolean baseSettingsProfileKnown;
        @Nullable
        Integer gpsBaudRate;
        boolean gpsBaudKnown;
        @Nullable
        String ssidHint;
        @Nullable
        String deviceVersion;
        boolean deviceVersionKnown;
        @Nullable
        Double inputVoltage;
        boolean inputVoltageKnown;
        @Nullable
        String customGnssProfileFrame;
        boolean customGnssProfileFrameKnown;
        @Nullable
        String customBaseSettingsFrame;
        boolean customBaseSettingsFrameKnown;
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
