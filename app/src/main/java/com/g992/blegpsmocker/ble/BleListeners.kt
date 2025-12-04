package com.g992.blegpsmocker.ble

import android.bluetooth.BluetoothDevice

interface BleScanListener {
    fun onDeviceFound(device: BluetoothDevice)
    fun onScanFailed(errorCode: Int)
    fun onScanStopped(foundDevice: Boolean)
}

interface BleConnectionDataListener {
    fun onConnecting(device: BluetoothDevice)
    fun onConnected(device: BluetoothDevice)
    fun onDisconnected(device: BluetoothDevice)
    fun onServicesDiscovered(device: BluetoothDevice)
    fun onError(message: String)
    fun onCoordinatesReceived(latitude: Double, longitude: Double)
    fun onFixStatusReceived(status: String)
    fun onHdopReceived(hdop: Double)
    fun onSignalLevelsReceived(levels: String)
    fun onAltitudeReceived(altitudeMeters: Double)
    fun onSpeedReceived(speedMetersPerSecond: Double)
    fun onHeadingReceived(headingDegrees: Double)
    fun onDeviceStatusReceived(status: String)
    fun onTtffReceived(ttffSeconds: Long)
    fun onApControlChanged(enabled: Boolean)
    fun onBridgeModeChanged(enabled: Boolean)
    fun onGpsBaudRateChanged(baudRate: Int)
    fun onGnssProfileChanged(profile: Int)
    fun onBaseSettingsProfileChanged(profile: Int)
    fun onCustomGnssProfileFrameChanged(frame: String)
    fun onCustomBaseSettingsFrameChanged(frame: String)
    fun onOtaStatusReceived(status: String)
    fun onOtaGuardStateChanged(enabled: Boolean)
    fun onWifiStatusReceived(status: String, ip: String?)
    fun onDeviceVersionReceived(version: String)
    fun onInputVoltageReceived(voltage: Double)
}
