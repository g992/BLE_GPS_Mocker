package com.g992.blegpsmocker.ui;

import androidx.annotation.Nullable;

public class DeviceSettingsPayload {
    @Nullable
    public Boolean apState;
    public boolean apKnown;
    @Nullable
    public Boolean bridgeState;
    public boolean bridgeKnown;
    @Nullable
    public Integer gnssProfile;
    public boolean gnssProfileKnown;
    @Nullable
    public Integer baseSettingsProfile;
    public boolean baseSettingsProfileKnown;
    @Nullable
    public Integer gpsBaudRate;
    public boolean gpsBaudKnown;
    @Nullable
    public String ssidHint;
    @Nullable
    public String deviceVersion;
    public boolean deviceVersionKnown;
    @Nullable
    public Double inputVoltage;
    public boolean inputVoltageKnown;
    @Nullable
    public String customGnssProfileFrame;
    public boolean customGnssProfileFrameKnown;
    @Nullable
    public String customBaseSettingsFrame;
    public boolean customBaseSettingsFrameKnown;
}
