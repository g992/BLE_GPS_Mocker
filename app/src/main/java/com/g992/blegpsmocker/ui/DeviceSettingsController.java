package com.g992.blegpsmocker.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.g992.blegpsmocker.AppPrefs;
import com.g992.blegpsmocker.GNSSClientService;
import com.g992.blegpsmocker.MainActivity;
import com.g992.blegpsmocker.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Arrays;

/**
 * Encapsulates the device settings UI and related interactions to keep {@link MainActivity} lean.
 */
public class DeviceSettingsController {

    public interface DeviceSettingsActions {
        boolean isServiceReady();

        @Nullable
        GNSSClientService getService();

        void refreshDeviceSettings();

        void refreshOtaState();

        void stopRefreshIndicator();
    }

    private final MainActivity activity;
    private final DeviceSettingsActions actions;
    private final int gnssCustomValue;
    private final int baseCustomValue;
    private final Handler uiHandler;

    private final SwitchMaterial apHotspotSwitch;
    private final SwitchMaterial bridgeModeSwitch;
    private final TextInputLayout gnssProfileLayout;
    private final MaterialAutoCompleteTextView gnssProfileDropdown;
    private final TextInputLayout customGnssFrameLayout;
    private final TextInputEditText customGnssFrameInput;
    private final MaterialButton customGnssFrameApplyButton;
    private final TextInputLayout baseSettingsProfileLayout;
    private final MaterialAutoCompleteTextView baseSettingsProfileDropdown;
    private final TextInputLayout customBaseFrameLayout;
    private final TextInputEditText customBaseFrameInput;
    private final MaterialButton customBaseFrameApplyButton;
    private final TextInputLayout gpsBaudRateLayout;
    private final MaterialAutoCompleteTextView gpsBaudRateDropdown;
    private final TextView wifiStatusText;
    private final SwitchMaterial otaSwitch;
    private final TextView inputVoltageText;
    private final TextView deviceVersionText;
    private final SwitchMaterial alwaysMovingSwitch;

    @Nullable
    private Boolean apControlState = null;
    @Nullable
    private Boolean bridgeModeState = null;
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
    private int[] gnssProfileValues = new int[0];
    private String[] gnssProfileLabels = new String[0];
    private int[] baseSettingsProfileValues = new int[0];
    private String[] baseSettingsProfileLabels = new String[0];
    private int[] gpsBaudRateValues = new int[0];
    private String[] gpsBaudRateLabels = new String[0];
    @Nullable
    private Integer pendingGnssProfileSelection = null;
    @Nullable
    private Integer pendingBaseProfileSelection = null;

    public DeviceSettingsController(
            @NonNull MainActivity activity,
            @NonNull DeviceSettingsActions actions,
            int gnssCustomValue,
            int baseCustomValue
    ) {
        this.activity = activity;
        this.actions = actions;
        this.gnssCustomValue = gnssCustomValue;
        this.baseCustomValue = baseCustomValue;
        this.uiHandler = new Handler(activity.getMainLooper());

        apHotspotSwitch = activity.findViewById(R.id.apHotspotSwitch);
        bridgeModeSwitch = activity.findViewById(R.id.bridgeModeSwitch);
        gnssProfileLayout = activity.findViewById(R.id.gnssProfileLayout);
        gnssProfileDropdown = activity.findViewById(R.id.gnssProfileDropdown);
        customGnssFrameLayout = activity.findViewById(R.id.customGnssFrameLayout);
        customGnssFrameInput = activity.findViewById(R.id.customGnssFrameInput);
        customGnssFrameApplyButton = activity.findViewById(R.id.customGnssFrameApplyButton);
        baseSettingsProfileLayout = activity.findViewById(R.id.baseSettingsProfileLayout);
        baseSettingsProfileDropdown = activity.findViewById(R.id.baseSettingsProfileDropdown);
        customBaseFrameLayout = activity.findViewById(R.id.customBaseFrameLayout);
        customBaseFrameInput = activity.findViewById(R.id.customBaseFrameInput);
        customBaseFrameApplyButton = activity.findViewById(R.id.customBaseFrameApplyButton);
        gpsBaudRateLayout = activity.findViewById(R.id.gpsBaudRateLayout);
        gpsBaudRateDropdown = activity.findViewById(R.id.gpsBaudRateDropdown);
        wifiStatusText = activity.findViewById(R.id.wifiStatusText);
        otaSwitch = activity.findViewById(R.id.otaSwitch);
        inputVoltageText = activity.findViewById(R.id.inputVoltageText);
        deviceVersionText = activity.findViewById(R.id.deviceVersionText);
        alwaysMovingSwitch = activity.findViewById(R.id.alwaysMovingSwitch);

        bindDropdowns(activity);
        bindSwitches();
        bindCustomFrames();
        refreshAlwaysMovingUi();
    }

    public void applyDeviceSettingsUpdate(@NonNull DeviceSettingsPayload payload) {
        activity.runOnUiThread(
                () -> {
                    if (payload.apKnown) {
                        apControlState = payload.apState;
                    }
                    if (payload.bridgeKnown) {
                        bridgeModeState = payload.bridgeState;
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
                    if (!TextUtils.isEmpty(payload.ssidHint)) {
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

    public void updateOtaStatus(
            boolean guardEnabled,
            @Nullable String wifiState,
            @Nullable String wifiIp,
            @Nullable String message
    ) {
        activity.runOnUiThread(
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
                                            ? activity.getString(R.string.ota_wifi_connected_ip, this.wifiIp)
                                            : activity.getString(R.string.ota_wifi_connected);
                            break;
                        case "connecting":
                            wifiText = activity.getString(R.string.ota_wifi_connecting);
                            break;
                        case "disconnected":
                            wifiText = activity.getString(R.string.ota_wifi_disconnected);
                            break;
                        default:
                            wifiText = activity.getString(R.string.ota_wifi_unknown);
                            break;
                    }
                    wifiStatusText.setText(wifiText);

                    makeWifiClickable(this.wifiIp != null);

                    suppressOtaSwitchChange = true;
                    otaSwitch.setChecked(otaGuardEnabled);
                    suppressOtaSwitchChange = false;
                    updateOtaControlsState();
                    actions.stopRefreshIndicator();
                });
    }

    public void onConnectionChanged(boolean connected) {
        if (connected) {
            updateDeviceSettingsUi();
            return;
        }
        otaGuardEnabled = false;
        wifiIp = null;
        wifiState = null;
        pendingGnssProfileSelection = null;
        pendingBaseProfileSelection = null;
        updateDeviceSettingsUi();
    }

    public void updateDeviceSettingsUi() {
        activity.runOnUiThread(
                () -> {
                    boolean connected = actions.isServiceReady();

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
                                        ? activity.getString(R.string.settings_input_voltage_label, inputVoltage)
                                        : activity.getString(R.string.settings_input_voltage_unknown);
                        inputVoltageText.setText(voltageValue);
                    }

                    if (deviceVersionText != null) {
                        String versionValue =
                                deviceVersion != null
                                        ? activity.getString(R.string.settings_device_version_label, deviceVersion)
                                        : activity.getString(R.string.settings_device_version_unknown);
                        deviceVersionText.setText(versionValue);
                    }

                    updateOtaControlsState();
                    actions.stopRefreshIndicator();
                });
    }

    private void bindDropdowns(Context context) {
        gnssProfileLabels = context.getResources().getStringArray(R.array.gnss_profile_labels);
        gnssProfileValues = context.getResources().getIntArray(R.array.gnss_profile_values);
        if (gnssProfileDropdown != null) {
            ArrayAdapter<String> profileAdapter =
                    new NoFilterArrayAdapter(context, android.R.layout.simple_list_item_1, Arrays.asList(gnssProfileLabels));
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

        baseSettingsProfileLabels = context.getResources().getStringArray(R.array.base_settings_profile_labels);
        baseSettingsProfileValues = context.getResources().getIntArray(R.array.base_settings_profile_values);
        if (baseSettingsProfileDropdown != null) {
            ArrayAdapter<String> baseAdapter =
                    new NoFilterArrayAdapter(context, android.R.layout.simple_list_item_1, Arrays.asList(baseSettingsProfileLabels));
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

        gpsBaudRateLabels = context.getResources().getStringArray(R.array.gps_baud_rate_labels);
        gpsBaudRateValues = context.getResources().getIntArray(R.array.gps_baud_rate_values);
        if (gpsBaudRateDropdown != null) {
            ArrayAdapter<String> baudAdapter =
                    new NoFilterArrayAdapter(context, android.R.layout.simple_list_item_1, Arrays.asList(gpsBaudRateLabels));
            gpsBaudRateDropdown.setAdapter(baudAdapter);
            gpsBaudRateDropdown.setKeyListener(null);
            gpsBaudRateDropdown.setText("", false);
            gpsBaudRateDropdown.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < gpsBaudRateValues.length) {
                    handleGpsBaudSelection(gpsBaudRateValues[position]);
                }
            });
            gpsBaudRateDropdown.setOnClickListener(v -> showGpsBaudDropdown());
            if (gpsBaudRateLayout != null) {
                gpsBaudRateLayout.setEndIconOnClickListener(v -> {
                    if (gpsBaudRateDropdown != null) {
                        gpsBaudRateDropdown.requestFocus();
                    }
                    showGpsBaudDropdown();
                });
            }
        }
    }

    private void bindSwitches() {
        if (apHotspotSwitch != null) {
            apHotspotSwitch.setEnabled(false);
            apHotspotSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressApSwitchChange) {
                    return;
                }
                handleApSwitchToggle(isChecked);
            });
        }

        if (bridgeModeSwitch != null) {
            bridgeModeSwitch.setEnabled(false);
            bridgeModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressBridgeSwitchChange) {
                    return;
                }
                handleBridgeSwitchToggle(isChecked);
            });
        }

        if (otaSwitch != null) {
            otaSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressOtaSwitchChange) {
                    return;
                }
                toggleOtaPortal(isChecked);
            });
        }

        if (alwaysMovingSwitch != null) {
            suppressAlwaysMovingChange = true;
            alwaysMovingSwitch.setChecked(AppPrefs.isAlwaysMovingEnabled(activity));
            suppressAlwaysMovingChange = false;
            alwaysMovingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressAlwaysMovingChange) {
                    return;
                }
                if (isChecked) {
                    showAlwaysMovingDialog();
                } else {
                    AppPrefs.setAlwaysMovingEnabled(activity, false);
                    syncAlwaysMovingSetting();
                    refreshAlwaysMovingUi();
                }
            });
        }
    }

    private void bindCustomFrames() {
        if (customGnssFrameApplyButton != null) {
            customGnssFrameApplyButton.setOnClickListener(v -> applyCustomGnssFrame());
        }
        if (customBaseFrameApplyButton != null) {
            customBaseFrameApplyButton.setOnClickListener(v -> applyCustomBaseFrame());
        }
    }

    private void refreshAlwaysMovingUi() {
        if (alwaysMovingSwitch == null) {
            return;
        }
        suppressAlwaysMovingChange = true;
        alwaysMovingSwitch.setChecked(AppPrefs.isAlwaysMovingEnabled(activity));
        suppressAlwaysMovingChange = false;
    }

    private void showAlwaysMovingDialog() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.always_moving_dialog_title)
                .setMessage(R.string.always_moving_dialog_message)
                .setPositiveButton(
                        R.string.dialog_ok,
                        (dialog, which) -> {
                            AppPrefs.setAlwaysMovingEnabled(activity, true);
                            refreshAlwaysMovingUi();
                            syncAlwaysMovingSetting();
                        })
                .setNegativeButton(
                        R.string.dialog_cancel,
                        (dialog, which) -> {
                            AppPrefs.setAlwaysMovingEnabled(activity, false);
                            refreshAlwaysMovingUi();
                        })
                .setOnCancelListener(dialog -> {
                    AppPrefs.setAlwaysMovingEnabled(activity, false);
                    refreshAlwaysMovingUi();
                })
                .show();
    }

    private void syncAlwaysMovingSetting() {
        GNSSClientService service = actions.getService();
        if (service != null) {
            service.setAlwaysMovingEnabled(AppPrefs.isAlwaysMovingEnabled(activity));
        }
    }

    private boolean isCustomGnssProfileSelected() {
        Integer profileValue = gnssProfile != null ? gnssProfile : pendingGnssProfileSelection;
        return profileValue != null && profileValue == gnssCustomValue;
    }

    private boolean isCustomBaseProfileSelected() {
        Integer profileValue = baseSettingsProfile != null ? baseSettingsProfile : pendingBaseProfileSelection;
        return profileValue != null && profileValue == baseCustomValue;
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
        return actions.isServiceReady();
    }

    private String getApSsidForDialog() {
        if (apSsidHint != null && !apSsidHint.isEmpty()) {
            return apSsidHint;
        }
        GNSSClientService service = actions.getService();
        if (service != null) {
            String serviceHint = service.getApControlSsidHint();
            if (serviceHint != null && !serviceHint.isEmpty()) {
                apSsidHint = serviceHint;
                return serviceHint;
            }
        }
        return activity.getString(R.string.settings_ap_default_ssid);
    }

    private void handleApSwitchToggle(boolean desiredState) {
        if (!isServiceReadyForSettings()) {
            Toast.makeText(activity, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
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

            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.settings_ap_dialog_title)
                    .setMessage(activity.getString(R.string.settings_ap_dialog_message, getApSsidForDialog()))
                    .setPositiveButton(
                            R.string.dialog_ok,
                            (dialog, which) -> {
                                GNSSClientService service = actions.getService();
                                if (service == null) {
                                    updateDeviceSettingsUi();
                                    return;
                                }
                                boolean accepted = service.requestApControlChange(true);
                                if (!accepted) {
                                    Toast.makeText(activity, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                                    updateDeviceSettingsUi();
                                    return;
                                }
                                uiHandler.postDelayed(actions::refreshDeviceSettings, 500);
                                uiHandler.postDelayed(this::updateDeviceSettingsUi, 500);
                            })
                    .setNegativeButton(R.string.dialog_cancel, (dialog, which) -> updateDeviceSettingsUi())
                    .setOnCancelListener(dialog -> updateDeviceSettingsUi())
                    .show();
        } else {
            GNSSClientService service = actions.getService();
            boolean accepted = service != null && service.requestApControlChange(false);
            if (!accepted) {
                Toast.makeText(activity, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                updateDeviceSettingsUi();
                return;
            }
            uiHandler.postDelayed(actions::refreshDeviceSettings, 500);
            uiHandler.postDelayed(this::updateDeviceSettingsUi, 500);
        }
    }

    private void handleBridgeSwitchToggle(boolean desiredState) {
        if (!isServiceReadyForSettings()) {
            Toast.makeText(activity, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
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

            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.settings_bridge_dialog_title)
                    .setMessage(R.string.settings_bridge_dialog_message)
                    .setPositiveButton(
                            R.string.dialog_ok,
                            (dialog, which) -> {
                                GNSSClientService service = actions.getService();
                                if (service == null) {
                                    updateDeviceSettingsUi();
                                    return;
                                }
                                boolean accepted = service.requestBridgeModeChange(true);
                                if (!accepted) {
                                    Toast.makeText(activity, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                                    updateDeviceSettingsUi();
                                    return;
                                }
                                uiHandler.postDelayed(actions::refreshDeviceSettings, 500);
                                uiHandler.postDelayed(this::updateDeviceSettingsUi, 500);
                            })
                    .setNegativeButton(R.string.dialog_cancel, (dialog, which) -> updateDeviceSettingsUi())
                    .setOnCancelListener(dialog -> updateDeviceSettingsUi())
                    .show();
        } else {
            GNSSClientService service = actions.getService();
            boolean accepted = service != null && service.requestBridgeModeChange(false);
            if (!accepted) {
                Toast.makeText(activity, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                updateDeviceSettingsUi();
                return;
            }
            uiHandler.postDelayed(actions::refreshDeviceSettings, 500);
            uiHandler.postDelayed(this::updateDeviceSettingsUi, 500);
        }
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
        if (!isServiceReadyForSettings()) {
            Toast.makeText(activity, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            pendingGnssProfileSelection = null;
            updateDeviceSettingsUi();
            return;
        }
        Integer current = gnssProfile;
        if (current != null && current == desiredProfile) {
            pendingGnssProfileSelection = null;
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.settings_gnss_profile_dialog_title)
                .setMessage(R.string.settings_gnss_profile_dialog_message)
                .setPositiveButton(
                        R.string.dialog_ok,
                        (dialog, which) -> {
                            GNSSClientService service = actions.getService();
                            if (service == null) {
                                updateDeviceSettingsUi();
                                return;
                            }
                            boolean accepted = service.requestGnssProfileChange(desiredProfile);
                            if (!accepted) {
                                Toast.makeText(activity, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                                pendingGnssProfileSelection = null;
                                updateDeviceSettingsUi();
                                return;
                            }
                            uiHandler.postDelayed(actions::refreshDeviceSettings, 500);
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

    private void handleBaseProfileSelection(int desiredProfile) {
        if (suppressBaseProfileChange) {
            return;
        }
        if (baseSettingsProfileDropdown != null) {
            baseSettingsProfileDropdown.dismissDropDown();
        }
        pendingBaseProfileSelection = desiredProfile;
        updateDeviceSettingsUi();
        if (!isServiceReadyForSettings()) {
            Toast.makeText(activity, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            pendingBaseProfileSelection = null;
            updateDeviceSettingsUi();
            return;
        }
        Integer current = baseSettingsProfile;
        if (current != null && current == desiredProfile) {
            pendingBaseProfileSelection = null;
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.settings_base_profile_dialog_title)
                .setMessage(R.string.settings_base_profile_dialog_message)
                .setPositiveButton(
                        R.string.dialog_ok,
                        (dialog, which) -> {
                            GNSSClientService service = actions.getService();
                            if (service == null) {
                                updateDeviceSettingsUi();
                                return;
                            }
                            boolean accepted = service.requestBaseSettingsProfileChange(desiredProfile);
                            if (!accepted) {
                                Toast.makeText(activity, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                                pendingBaseProfileSelection = null;
                                updateDeviceSettingsUi();
                                return;
                            }
                            uiHandler.postDelayed(actions::refreshDeviceSettings, 500);
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

    private void handleGpsBaudSelection(int desiredBaudRate) {
        if (suppressGpsBaudChange) {
            return;
        }
        if (gpsBaudRateDropdown != null) {
            gpsBaudRateDropdown.dismissDropDown();
        }
        if (!isServiceReadyForSettings()) {
            Toast.makeText(activity, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            updateDeviceSettingsUi();
            return;
        }
        Integer current = gpsBaudRate;
        if (current != null && current == desiredBaudRate) {
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.settings_gps_baud_dialog_title)
                .setMessage(R.string.settings_gps_baud_dialog_message)
                .setPositiveButton(
                        R.string.dialog_ok,
                        (dialog, which) -> {
                            GNSSClientService service = actions.getService();
                            if (service == null) {
                                updateDeviceSettingsUi();
                                return;
                            }
                            boolean accepted = service.requestGpsBaudRateChange(desiredBaudRate);
                            if (!accepted) {
                                Toast.makeText(activity, R.string.settings_write_failed, Toast.LENGTH_LONG).show();
                                updateDeviceSettingsUi();
                                return;
                            }
                            uiHandler.postDelayed(actions::refreshDeviceSettings, 500);
                        })
                .setNegativeButton(R.string.dialog_cancel, (dialog, which) -> updateDeviceSettingsUi())
                .setOnCancelListener(dialog -> updateDeviceSettingsUi())
                .show();
    }

    private void applyCustomGnssFrame() {
        if (customGnssFrameInput == null) {
            return;
        }
        String frame =
                customGnssFrameInput.getText() != null ? customGnssFrameInput.getText().toString() : "";
        if (frame.isEmpty()) {
            customGnssFrameInput.setError(activity.getString(R.string.settings_custom_frame_empty_error));
            return;
        }
        customGnssFrameInput.setError(null);
        GNSSClientService service = actions.getService();
        if (!isServiceReadyForSettings() || service == null) {
            Toast.makeText(activity, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            return;
        }
        boolean accepted = service.updateCustomGnssProfileFrame(frame);
        if (!accepted) {
            Toast.makeText(activity, R.string.settings_custom_frame_write_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, R.string.settings_custom_frame_saved, Toast.LENGTH_SHORT).show();
        uiHandler.postDelayed(actions::refreshDeviceSettings, 400);
    }

    private void applyCustomBaseFrame() {
        if (customBaseFrameInput == null) {
            return;
        }
        String frame =
                customBaseFrameInput.getText() != null ? customBaseFrameInput.getText().toString()
                        : "";
        if (frame.isEmpty()) {
            customBaseFrameInput.setError(activity.getString(R.string.settings_custom_frame_empty_error));
            return;
        }
        customBaseFrameInput.setError(null);
        GNSSClientService service = actions.getService();
        if (!isServiceReadyForSettings() || service == null) {
            Toast.makeText(activity, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            return;
        }
        boolean accepted = service.updateCustomBaseSettingsFrame(frame);
        if (!accepted) {
            Toast.makeText(activity, R.string.settings_custom_frame_write_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, R.string.settings_custom_frame_saved, Toast.LENGTH_SHORT).show();
        uiHandler.postDelayed(actions::refreshDeviceSettings, 400);
    }

    private void toggleOtaPortal(boolean desired) {
        GNSSClientService service = actions.getService();
        if (!isServiceReadyForSettings() || service == null) {
            Toast.makeText(activity, R.string.settings_not_connected, Toast.LENGTH_LONG).show();
            updateOtaControlsState();
            return;
        }
        boolean accepted = service.requestOtaPortal(desired);
        if (!accepted) {
            Toast.makeText(activity, R.string.ota_guard_write_failed, Toast.LENGTH_LONG).show();
        }
        updateOtaControlsState();
    }

    private void updateOtaControlsState() {
        boolean connected = actions.isServiceReady();
        if (otaSwitch != null) {
            otaSwitch.setEnabled(connected);
            suppressOtaSwitchChange = true;
            otaSwitch.setChecked(otaGuardEnabled);
            suppressOtaSwitchChange = false;
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
            Toast.makeText(activity, R.string.ota_link_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        String url = "http://" + ip + "/";
        try {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(viewIntent);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.ota_link_unavailable, Toast.LENGTH_LONG).show();
        }
    }
}
