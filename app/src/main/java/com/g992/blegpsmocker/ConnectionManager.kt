package com.g992.blegpsmocker

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlin.text.Charsets

object BleUuids {
    val GPS_SERVICE_UUID: UUID = UUID.fromString("14f0514a-e15f-4ad3-89a6-b4cb3ac86abe")
    val CHAR_COORDINATES_UUID: UUID = UUID.fromString("12c64fea-7ed9-40be-9c7e-9912a5050d23")
    val CHAR_STATUS_UUID: UUID = UUID.fromString("3e4f5d6c-7b8a-9d0e-1f2a-3b4c5d6e7f8a")
    val CHAR_AP_CONTROL_UUID: UUID = UUID.fromString("a37f8c1b-281d-4e15-8fb2-0b7e6ebd21c0")
    val CHAR_MODE_CONTROL_UUID: UUID = UUID.fromString("d047f6b3-5f7c-4e5b-9c21-4c0f2b6a8f10")
    val CHAR_GPS_BAUD_UUID: UUID = UUID.fromString("f3a1a816-28f2-4b6d-9f76-6f7aa2d06123")
    val CHAR_GNSS_PROFILE_UUID: UUID = UUID.fromString("1fd95e59-993e-4bf5-a0b7-f481508c9a94")
    val CHAR_BASE_SETTINGS_PROFILE_UUID: UUID =
        UUID.fromString("7f0c9ad9-c6e8-4d2a-b3c1-1703708c6c2d")
    val CHAR_GNSS_RECEIVER_TYPE_UUID: UUID = UUID.fromString("2ffc9c6e-34e2-4ad4-af74-9493f5276965")
    val CHAR_CUSTOM_GNSS_PROFILE_UUID: UUID =
        UUID.fromString("0abf4f57-12a2-47d9-9c61-96e0d47f332b")
    val CHAR_CUSTOM_BASE_SETTINGS_UUID: UUID =
        UUID.fromString("4b88f5a8-3b35-4c64-a241-0c7fdfced0e0")
    val CHAR_KEEPALIVE_UUID: UUID = UUID.fromString("6b5d5304-4523-4db4-9a31-0f3d88c2ce11")
    val OTA_SERVICE_UUID: UUID = UUID.fromString("c7b44a0c-24c6-4af3-97ec-19ff34d45095")
    val CHAR_OTA_CONTROL_UUID: UUID = UUID.fromString("0f6f8ff7-1b61-4d44-9f31-3536c3a601a7")
    val CHAR_OTA_DATA_UUID: UUID = UUID.fromString("cb08c9fd-6c57-4b51-8bbe-20f3214bf3e9")
    val CHAR_OTA_STATUS_UUID: UUID = UUID.fromString("d19d3c86-9ba9-4a52-9244-99118bd88d08")
    val CHAR_WIFI_STATUS_UUID: UUID = UUID.fromString("9b9a3f07-3a36-4c74-a48a-4ad0d68f1d39")
    val CHAR_DEVICE_VERSION_UUID: UUID = UUID.fromString("c4e6f890-6b5e-4f1b-9d2e-7a3c8d2f1b01")
    val CHAR_INPUT_VOLTAGE_UUID: UUID = UUID.fromString("81b2c6f8-cb9e-4069-9a2e-9e5abca5d56e")
    val CHAR_GNSS_DEBUG_UUID: UUID = UUID.fromString("f877c02d-5a02-4cc7-a4f6-e4bb49519eb9")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

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
    fun onGnssReceiverTypeChanged(type: Int)
    fun onBaseSettingsProfileChanged(profile: Int)
    fun onCustomGnssProfileFrameChanged(frame: String)
    fun onCustomBaseSettingsFrameChanged(frame: String)
    fun onOtaStatusReceived(status: String)
    fun onOtaGuardStateChanged(enabled: Boolean)
    fun onWifiStatusReceived(status: String, ip: String?)
    fun onDeviceVersionReceived(version: String)
    fun onInputVoltageReceived(voltage: Double)
    fun onGnssDebugSnapshot(rawJson: String)
}

@SuppressLint("MissingPermission")
class ConnectionManager(
    private val context: Context,
    private var scanListener: BleScanListener? = null,
    private var connectionListener: BleConnectionDataListener? = null
) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var foundDeviceDuringScan = false
    private val tag = "ConnectionManager"
    private var gpsService: android.bluetooth.BluetoothGattService? = null
    private var otaService: android.bluetooth.BluetoothGattService? = null
    private var keepAliveRunnable: Runnable? = null
    private var keepAliveFailCount = 0
    private var keepAliveRunning = false
    private var keepAliveBlocked = false
    private var currentMtu: Int = 23

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                if (!isScanning) return

                Log.d(
                    tag,
                    "Device found: ${result.device.address} - ${result.device.name ?: "Unknown"}"
                )
                foundDeviceDuringScan = true
                scanListener?.onDeviceFound(result.device)
                stopScan()
                connect(result.device)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                super.onBatchScanResults(results)
                if (!isScanning || results.isEmpty()) return

                Log.d(tag, "Batch scan results: ${results.size}")
                results.firstOrNull()?.let {
                    foundDeviceDuringScan = true
                    scanListener?.onDeviceFound(it.device)
                    stopScan()
                    connect(it.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e(tag, "Scan failed with error: $errorCode")
                isScanning = false
                scanListener?.onScanFailed(errorCode)
            }
        }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                val device = gatt.device
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(tag, "Connected to GATT server at ${device.address}")
                        bluetoothGatt = gatt
                        connectionListener?.onConnected(device)
                        if (hasConnectPermission()) {
                            handler.post { gatt.requestMtu(185) }
                        } else {
                            val message =
                                "Missing BLUETOOTH_CONNECT permission for MTU request/discoverServices"
                            Log.e(tag, message)
                            connectionListener?.onError(message)
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(tag, "Disconnected from GATT server at ${device.address}")
                        stopKeepAliveLoop()
                        connectionListener?.onDisconnected(device)
                        closeGatt()
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(tag, "MTU changed to $mtu")
                    currentMtu = mtu
                } else {
                    Log.w(tag, "MTU change failed, status: $status, mtu: $mtu")
                }
                if (hasConnectPermission()) {
                    handler.post { gatt.discoverServices() }
                } else {
                    val message = "Missing BLUETOOTH_CONNECT for discoverServices after MTU change"
                    Log.e(tag, message)
                    connectionListener?.onError(message)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(tag, "Services discovered for device ${gatt.device.address}")
                    val service = gatt.getService(BleUuids.GPS_SERVICE_UUID)
                    gpsService = service
                    if (service == null) {
                        val message = "GPS Service (${BleUuids.GPS_SERVICE_UUID}) not found"
                        Log.e(tag, message)
                        connectionListener?.onError(message)
                        return
                    }

                    enableNotificationsInternal(gatt, service, BleUuids.CHAR_COORDINATES_UUID)
                    handler.postDelayed(
                        { enableNotificationsInternal(gatt, service, BleUuids.CHAR_STATUS_UUID) },
                        100
                    )
                    handler.postDelayed(
                        { enableNotificationsInternal(gatt, service, BleUuids.CHAR_INPUT_VOLTAGE_UUID) },
                        150
                    )

                    val ota = gatt.getService(BleUuids.OTA_SERVICE_UUID)
                    otaService = ota
                    if (ota == null) {
                        Log.w(tag, "OTA service ${BleUuids.OTA_SERVICE_UUID} not found")
                    } else {
                        handler.postDelayed(
                            { enableNotificationsInternal(gatt, ota, BleUuids.CHAR_OTA_STATUS_UUID) },
                            200
                        )
                        handler.postDelayed(
                            { enableNotificationsInternal(gatt, ota, BleUuids.CHAR_OTA_CONTROL_UUID) },
                            300
                        )
                    }

                    connectionListener?.onServicesDiscovered(gatt.device)
                } else {
                    val message =
                        "onServicesDiscovered received error: $status for device ${gatt.device.address}"
                    Log.w(tag, message)
                    connectionListener?.onError(message)
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                handleCharacteristicRead(characteristic, characteristic.value ?: value, status)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleCharacteristicChange(characteristic, characteristic.value ?: value)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                handleCharacteristicRead(characteristic, characteristic.value, status)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                handleCharacteristicChange(characteristic, characteristic.value)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                handleCharacteristicWrite(characteristic, characteristic.value, status)
            }


            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(
                        tag,
                        "Descriptor ${descriptor.uuid} written for char ${descriptor.characteristic.uuid}"
                    )
                } else {
                    val message = "Descriptor write failed for ${descriptor.uuid}, status: $status"
                    Log.e(tag, message)
                    connectionListener?.onError(message)
                }
            }
        }

    private fun handleCharacteristicRead(
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray?,
        status: Int
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS && data != null) {
            Log.i(
                tag,
                "Characteristic read ${characteristic.uuid}: ${data.toString(Charsets.UTF_8)}"
            )
            parseAndNotify(characteristic.uuid, data)
        } else if (status != BluetoothGatt.GATT_SUCCESS) {
            val message =
                "Characteristic read failed for ${characteristic.uuid}, status: $status"
            Log.e(tag, message)
            connectionListener?.onError(message)
        }
    }

    private fun handleCharacteristicChange(
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray?
    ) {
        if (data == null) {
            Log.w(tag, "Characteristic ${characteristic.uuid} changed with null data")
            return
        }
        Log.v(
            tag,
            "Characteristic ${characteristic.uuid} changed (${data.size} bytes)"
        )
        parseAndNotify(characteristic.uuid, data)
    }

    private fun handleCharacteristicWrite(
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray?,
        status: Int
    ) {
        val uuid = characteristic.uuid
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.i(tag, "Characteristic $uuid written successfully")
            if (data != null) {
                if (uuid == BleUuids.CHAR_KEEPALIVE_UUID) {
                    val stringValue = data.toString(Charsets.UTF_8).trim()
                    Log.d(tag, "Keepalive write ack timestamp=$stringValue")
                }
                parseAndNotify(uuid, data)
            } else {
                Log.d(tag, "Characteristic $uuid write success with no value payload")
            }
        } else {
            val message = "Characteristic write failed for $uuid, status: $status"
            Log.e(tag, message)
            connectionListener?.onError(message)
        }
    }

    private fun parseAndNotify(uuid: UUID, data: ByteArray) {
        val stringValue = data.toString(Charsets.UTF_8).trim()
        Log.d(tag, "Incoming payload for $uuid: $stringValue")
        try {
            when (uuid) {
                BleUuids.CHAR_COORDINATES_UUID -> handleCoordinatesPayload(stringValue)
                BleUuids.CHAR_STATUS_UUID -> handleStatusPayload(stringValue)
                BleUuids.CHAR_OTA_STATUS_UUID -> connectionListener?.onOtaStatusReceived(stringValue)
                BleUuids.CHAR_AP_CONTROL_UUID -> {
                    val enabled = stringValue == "1"
                    connectionListener?.onApControlChanged(enabled)
                }
                BleUuids.CHAR_MODE_CONTROL_UUID -> {
                    val enabled = stringValue == "1"
                    connectionListener?.onBridgeModeChanged(enabled)
                }
                BleUuids.CHAR_GPS_BAUD_UUID -> {
                    val baudRate = stringValue.toIntOrNull()
                    if (baudRate != null) {
                        connectionListener?.onGpsBaudRateChanged(baudRate)
                    } else {
                        Log.w(tag, "Invalid GPS baud payload: $stringValue")
                    }
                }
                BleUuids.CHAR_GNSS_RECEIVER_TYPE_UUID -> {
                    val type = stringValue.toIntOrNull()
                    if (type != null) {
                        connectionListener?.onGnssReceiverTypeChanged(type)
                    } else {
                        Log.w(tag, "Invalid GNSS receiver type payload: $stringValue")
                    }
                }
                BleUuids.CHAR_GNSS_PROFILE_UUID -> {
                    val profile = stringValue.toIntOrNull()
                    if (profile != null) {
                        connectionListener?.onGnssProfileChanged(profile)
                    } else {
                        Log.w(tag, "Invalid GNSS profile payload: $stringValue")
                    }
                }
                BleUuids.CHAR_BASE_SETTINGS_PROFILE_UUID -> {
                    val profile = stringValue.toIntOrNull()
                    if (profile != null) {
                        connectionListener?.onBaseSettingsProfileChanged(profile)
                    } else {
                        Log.w(tag, "Invalid base settings profile payload: $stringValue")
                    }
                }
                BleUuids.CHAR_CUSTOM_GNSS_PROFILE_UUID -> {
                    connectionListener?.onCustomGnssProfileFrameChanged(stringValue)
                }
                BleUuids.CHAR_CUSTOM_BASE_SETTINGS_UUID -> {
                    connectionListener?.onCustomBaseSettingsFrameChanged(stringValue)
                }
                BleUuids.CHAR_OTA_CONTROL_UUID -> {
                    val enabled = stringValue == "1"
                    connectionListener?.onOtaGuardStateChanged(enabled)
                }
                BleUuids.CHAR_WIFI_STATUS_UUID -> handleWifiStatusPayload(stringValue)
                BleUuids.CHAR_DEVICE_VERSION_UUID -> {
                    connectionListener?.onDeviceVersionReceived(stringValue)
                }
                BleUuids.CHAR_INPUT_VOLTAGE_UUID -> handleInputVoltagePayload(stringValue)
                BleUuids.CHAR_GNSS_DEBUG_UUID -> connectionListener?.onGnssDebugSnapshot(stringValue)
                else -> Log.d(tag, "No specific parsing for UUID $uuid")
            }
        } catch (exception: Exception) {
            Log.e(tag, "Error parsing data for UUID $uuid, value: $stringValue", exception)
            connectionListener?.onError(
                "Error parsing data for $uuid: ${exception.localizedMessage}"
            )
        }
    }

    private fun handleCoordinatesPayload(raw: String) {
        try {
            val payload = JSONObject(raw)
            val lat = payload.optDouble("lt")
            val lon = payload.optDouble("lg")
            Log.i(
                tag,
                "Coordinates payload parsed lt=$lat lg=$lon spd=${payload.optDouble("spd")} alt=${payload.optDouble("alt")}"
            )
            if (!lat.isNaN() && !lon.isNaN()) {
                connectionListener?.onCoordinatesReceived(lat, lon)
            }
            payload.optDouble("hd").takeIf { !it.isNaN() }?.let {
                connectionListener?.onHeadingReceived(it)
            }
            payload.optDouble("spd").takeIf { !it.isNaN() }?.let {
                connectionListener?.onSpeedReceived(it)
            }
            payload.optDouble("alt").takeIf { !it.isNaN() }?.let {
                connectionListener?.onAltitudeReceived(it)
            }
        } catch (exception: Exception) {
            Log.w(tag, "Invalid Navigation JSON: $raw", exception)
        }
    }

    private fun handleStatusPayload(raw: String) {
        try {
            val payload = JSONObject(raw)
            val fixValue = payload.optInt("fix", -1)
            Log.d(
                tag,
                "Status payload parsed fix=$fixValue hdop=${payload.optDouble("hdop")} signals=${payload.optJSONArray("signals")}"
            )
            if (fixValue != -1) {
                val type = if (fixValue == 1) 1 else 0
                connectionListener?.onFixStatusReceived("$fixValue,$type")
            }
            payload.optDouble("hdop").takeIf { !it.isNaN() }?.let {
                connectionListener?.onHdopReceived(it)
            }
            val signals = payload.optJSONArray("signals")
            if (signals != null) {
                val values = buildString {
                    appendSignals(signals)
                }
                connectionListener?.onSignalLevelsReceived(values)
            }
            if (payload.has("ttff")) {
                val ttff = payload.optLong("ttff")
                Log.d(tag, "TTFF: $ttff")
                try {
                    connectionListener?.onTtffReceived(ttff)
                } catch (_: Exception) {
                }
            }
        } catch (exception: Exception) {
            Log.w(tag, "Invalid Status JSON: $raw", exception)
        }
    }

    private fun handleWifiStatusPayload(raw: String) {
        try {
            val payload = JSONObject(raw)
            val status = payload.optString("st", "unknown")
            val ip = payload.optString("ip").takeIf { it.isNotBlank() }
            connectionListener?.onWifiStatusReceived(status, ip)
        } catch (exception: Exception) {
            Log.w(tag, "Invalid Wi-Fi status JSON: $raw", exception)
        }
    }

    private fun handleInputVoltagePayload(raw: String) {
        val voltage = runCatching { JSONObject(raw).optDouble("vin") }.getOrNull()
        if (voltage == null || voltage.isNaN()) {
            Log.w(tag, "Invalid input voltage payload: $raw")
            return
        }
        connectionListener?.onInputVoltageReceived(voltage)
    }

    private fun StringBuilder.appendSignals(array: JSONArray) {
        for (index in 0 until array.length()) {
            if (index > 0) append(',')
            val rawValue = array.opt(index)
            val level =
                when (rawValue) {
                    is Number -> rawValue.toInt()
                    is String -> rawValue.trim().toIntOrNull() ?: 0
                    else -> 0
                }
            append(level)
        }
    }

    private fun enableNotificationsInternal(
        gatt: BluetoothGatt,
        service: android.bluetooth.BluetoothGattService,
        characteristicUuid: UUID
    ) {
        val characteristic = service.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            Log.e(tag, "Characteristic $characteristicUuid not found in service ${service.uuid}")
            return
        }
        if (!hasConnectPermission()) {
            connectionListener?.onError(
                "Missing BLUETOOTH_CONNECT permission to set characteristic notification"
            )
            return
        }
        val descriptor = characteristic.getDescriptor(BleUuids.CCCD_UUID)
        if (descriptor == null) {
            Log.e(tag, "CCCD not found for characteristic $characteristicUuid")
            return
        }

        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            val message =
                "Failed to enable client characteristic notification for $characteristicUuid"
            Log.e(tag, message)
            connectionListener?.onError(message)
            return
        }

        val value =
            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            descriptor.setValue(value)
            gatt.writeDescriptor(descriptor)
        }
        Log.i(tag, "Requested notifications/indications for $characteristicUuid")
    }

    private fun readCharacteristicInternal(
        gatt: BluetoothGatt,
        service: android.bluetooth.BluetoothGattService,
        characteristicUuid: UUID
    ): Boolean {
        val characteristic = service.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            Log.e(tag, "Characteristic $characteristicUuid not found for read")
            return false
        }
        if (!hasConnectPermission()) {
            connectionListener?.onError(
                "Missing BLUETOOTH_CONNECT permission to read characteristic"
            )
            return false
        }
        if (!gatt.readCharacteristic(characteristic)) {
            Log.w(tag, "Failed to initiate read for characteristic $characteristicUuid")
            return false
        }
        Log.i(tag, "Requested read for characteristic $characteristicUuid")
        return true
    }

    fun readCharacteristic(uuid: UUID): Boolean {
        val gatt = bluetoothGatt ?: run {
            Log.w(tag, "readCharacteristic($uuid) skipped: GATT not connected")
            return false
        }
        val service = resolveServiceForCharacteristic(uuid) ?: run {
            Log.w(tag, "readCharacteristic($uuid) skipped: service unavailable")
            return false
        }
        return readCharacteristicInternal(gatt, service, uuid)
    }

    fun writeCharacteristic(uuid: UUID, payload: String): Boolean {
        val data = payload.toByteArray(Charsets.UTF_8)
        return writeRawCharacteristic(uuid, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    fun writeOtaControl(payload: String): Boolean {
        val data = payload.toByteArray(Charsets.UTF_8)
        return writeRawCharacteristic(
            BleUuids.CHAR_OTA_CONTROL_UUID,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
    }

    fun writeOtaData(payload: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: run {
            Log.w(tag, "writeOtaData skipped: GATT not connected")
            return false
        }
        val service = resolveServiceForCharacteristic(BleUuids.CHAR_OTA_DATA_UUID) ?: run {
            Log.w(tag, "writeOtaData skipped: OTA service unavailable")
            return false
        }
        val characteristic = service.getCharacteristic(BleUuids.CHAR_OTA_DATA_UUID)
            ?: run {
                Log.e(tag, "OTA data characteristic not found for write")
                return false
            }
        val supportsWrite =
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val supportsWriteNoResp =
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val writeType =
            when {
                supportsWriteNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                supportsWrite -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else -> {
                    Log.e(tag, "OTA data characteristic is not writable (properties=${characteristic.properties})")
                    return false
                }
            }
        return writeRawCharacteristic(BleUuids.CHAR_OTA_DATA_UUID, payload, writeType)
    }

    fun hasOtaService(): Boolean = otaService != null

    private fun resolveServiceForCharacteristic(uuid: UUID): android.bluetooth.BluetoothGattService? {
        val mapped =
            when (uuid) {
                BleUuids.CHAR_OTA_CONTROL_UUID,
                BleUuids.CHAR_OTA_DATA_UUID,
                BleUuids.CHAR_OTA_STATUS_UUID,
                BleUuids.CHAR_WIFI_STATUS_UUID -> otaService
                else -> gpsService
            }
        if (mapped != null && mapped.getCharacteristic(uuid) != null) {
            return mapped
        }
        val gatt = bluetoothGatt ?: return mapped
        val dynamic = gatt.services?.firstOrNull { service ->
            service.getCharacteristic(uuid) != null
        }
        if (dynamic != null && mapped == null) {
            if (uuid == BleUuids.CHAR_WIFI_STATUS_UUID) {
                otaService = dynamic
            }
        }
        return dynamic ?: mapped
    }

    private fun writeRawCharacteristic(
        uuid: UUID,
        payload: ByteArray,
        writeType: Int
    ): Boolean {
        val gatt = bluetoothGatt ?: run {
            Log.w(tag, "writeCharacteristic($uuid) skipped: GATT not connected")
            return false
        }
        val service = resolveServiceForCharacteristic(uuid) ?: run {
            Log.w(tag, "writeCharacteristic($uuid) skipped: service unavailable")
            return false
        }
        val characteristic = service.getCharacteristic(uuid)
        if (characteristic == null) {
            Log.e(tag, "Characteristic $uuid not found for write")
            return false
        }
        if (!hasConnectPermission()) {
            connectionListener?.onError("Missing BLUETOOTH_CONNECT permission to write characteristic")
            return false
        }
        val supportsWrite =
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        if (!supportsWrite) {
            Log.e(tag, "Characteristic $uuid is not writable (properties=${characteristic.properties})")
            return false
        }

        characteristic.writeType = writeType
        characteristic.value = payload
        val result = gatt.writeCharacteristic(characteristic)
        if (!result) {
            Log.e(tag, "writeCharacteristic($uuid) failed to enqueue GATT write")
        } else {
            Log.i(tag, "Enqueued write for $uuid payloadLength=${payload.size}")
        }
        return result
    }

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.distinct()
    }

    @Synchronized
    fun startScan() {
        if (isScanning) {
            Log.w(tag, "Scan already in progress")
            return
        }
        if (!hasScanPermission()) {
            val message =
                "Missing permissions for BLE scan. Required: ${requiredPermissions().joinToString()}"
            Log.e(tag, message)
            scanListener?.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            connectionListener?.onError(message)
            return
        }
        if (bluetoothAdapter?.isEnabled == false) {
            val message = "Bluetooth is not enabled"
            Log.e(tag, message)
            scanListener?.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            connectionListener?.onError(message)
            return
        }

        val filterByService =
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUuids.GPS_SERVICE_UUID)).build()
        val filterByName = ScanFilter.Builder().setDeviceName("GPS-C3").build()
        val scanSettings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        bluetoothAdapter?.bluetoothLeScanner?.startScan(
            listOf(filterByService, filterByName),
            scanSettings,
            scanCallback
        )
        isScanning = true
        foundDeviceDuringScan = false
        Log.d(tag, "BLE scan started for service ${BleUuids.GPS_SERVICE_UUID}")

        handler.postDelayed(
            {
                if (isScanning) {
                    Log.w(tag, "Scan timeout, stopping scan")
                    stopScan()
                }
            },
            10_000
        )
    }

    @Synchronized
    fun stopScan() {
        if (!isScanning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasScanPermission()) {
            Log.e(tag, "Missing BLUETOOTH_SCAN permission to stop scan")
        }
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        val wasScanning = isScanning
        isScanning = false
        if (wasScanning) {
            scanListener?.onScanStopped(foundDeviceDuringScan)
        }
        Log.d(tag, "BLE scan stopped. Device found during this scan: $foundDeviceDuringScan")
    }

    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            val message = "Missing BLUETOOTH_CONNECT permission to connect to ${device.address}"
            Log.e(tag, message)
            connectionListener?.onError(message)
            return
        }
        if (bluetoothGatt != null && bluetoothGatt?.device?.address != device.address) {
            Log.w(
                tag,
                "New device connection requested. Closing previous GATT connection to ${bluetoothGatt?.device?.address}"
            )
            bluetoothGatt?.disconnect()
            closeGatt()
        } else if (bluetoothGatt != null && bluetoothGatt?.device?.address == device.address) {
            Log.i(
                tag,
                "Already connected or connecting to ${device.address}. Attempting to reconnect if necessary."
            )
        }

        Log.i(tag, "Connecting to device: ${device.address} - ${device.name ?: "Unknown"}")
        connectionListener?.onConnecting(device)
        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt != null) {
            bluetoothGatt = gatt
        } else {
            val message = "connectGatt returned null for ${device.address}"
            Log.e(tag, message)
            connectionListener?.onError(message)
        }
    }

    fun disconnect() {
        if (!hasConnectPermission()) {
            val message = "Missing BLUETOOTH_CONNECT permission to disconnect"
            Log.e(tag, message)
            connectionListener?.onError(message)
            return
        }
        if (bluetoothGatt == null) {
            Log.w(tag, "No active GATT connection to disconnect")
            return
        }
        Log.i(tag, "Disconnecting from ${bluetoothGatt?.device?.address}")
        stopKeepAliveLoop()
        bluetoothGatt?.disconnect()
    }

    fun closeGatt() {
        stopKeepAliveLoop()
        bluetoothGatt?.close()
        bluetoothGatt = null
        gpsService = null
        otaService = null
        Log.d(tag, "GATT client resources released")
    }

    fun setScanListener(listener: BleScanListener?) {
        scanListener = listener
    }

    fun setConnectionDataListener(listener: BleConnectionDataListener?) {
        connectionListener = listener
    }

    fun pollTelemetry(): Boolean {
        val gatt = bluetoothGatt ?: return false
        val service = gpsService ?: return false
        if (!hasConnectPermission()) {
            connectionListener?.onError("Missing BLUETOOTH_CONNECT permission to poll telemetry")
            return false
        }
        return readCharacteristicInternal(gatt, service, BleUuids.CHAR_STATUS_UUID)
    }

    @Synchronized
    fun startKeepAlive(delayMillis: Long = 0L) {
        if (keepAliveBlocked) {
            Log.d(tag, "Keepalive blocked, not starting loop")
            return
        }
        if (keepAliveRunning) {
            Log.d(tag, "Keepalive loop already running, ignoring start request")
            return
        }
        if (keepAliveRunnable != null) {
            Log.d(tag, "Keepalive loop already running, restarting")
            stopKeepAliveLoop()
        }
        keepAliveFailCount = 0
        val service = gpsService ?: run {
            Log.w(tag, "Cannot start keepalive: GPS service unavailable")
            return
        }
        val characteristic = service.getCharacteristic(BleUuids.CHAR_KEEPALIVE_UUID)
        if (characteristic == null) {
            Log.w(tag, "Keepalive characteristic ${BleUuids.CHAR_KEEPALIVE_UUID} not found")
            return
        }
        val supportsWrite =
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        if (!supportsWrite) {
            Log.w(
                tag,
                "Keepalive characteristic ${BleUuids.CHAR_KEEPALIVE_UUID} is not writable (properties=${characteristic.properties})"
            )
            return
        }

        val runnable =
            object : Runnable {
                override fun run() {
                    if (keepAliveBlocked) {
                        Log.d(tag, "Keepalive blocked, skipping tick")
                        keepAliveRunning = false
                        keepAliveRunnable = null
                        return
                    }
                    val timestampSeconds = (System.currentTimeMillis() / 1000L).toString()
                    Log.d(tag, "Sending keepalive timestamp=$timestampSeconds")
                    val enqueued = writeCharacteristic(BleUuids.CHAR_KEEPALIVE_UUID, timestampSeconds)
                    if (!enqueued) {
                        keepAliveFailCount += 1
                        if (keepAliveFailCount < KEEPALIVE_MAX_RETRIES) {
                            Log.w(
                                tag,
                                "Keepalive enqueue failed (attempt $keepAliveFailCount/$KEEPALIVE_MAX_RETRIES), retrying in ${KEEPALIVE_RETRY_INTERVAL_MS}ms"
                            )
                            handler.postDelayed(this, KEEPALIVE_RETRY_INTERVAL_MS)
                        } else {
                            Log.e(
                                tag,
                                "Keepalive enqueue failed $KEEPALIVE_MAX_RETRIES times, backing off for ${KEEPALIVE_INTERVAL_MS}ms"
                            )
                            keepAliveFailCount = 0
                            handler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
                        }
                        return
                    }
                    keepAliveFailCount = 0
                    handler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
                }
            }
        keepAliveRunnable = runnable
        keepAliveRunning = true
        if (delayMillis <= 0L) {
            handler.post(runnable)
        } else {
            handler.postDelayed(runnable, delayMillis)
        }
        Log.d(tag, "Keepalive loop scheduled to start in ${delayMillis}ms")
    }

    @Synchronized
    fun stopKeepAliveLoop() {
        val runnable = keepAliveRunnable ?: return
        handler.removeCallbacks(runnable)
        keepAliveRunnable = null
        keepAliveFailCount = 0
        keepAliveRunning = false
        Log.d(tag, "Keepalive loop stopped")
    }

    fun isKeepAliveRunning(): Boolean = keepAliveRunning

    fun setKeepAliveBlocked(blocked: Boolean) {
        keepAliveBlocked = blocked
        if (blocked) {
            stopKeepAliveLoop()
            Log.d(tag, "Keepalive blocked")
        } else {
            Log.d(tag, "Keepalive unblocked")
        }
    }

    fun getCurrentMtu(): Int = currentMtu

    companion object {
        private const val KEEPALIVE_INTERVAL_MS = 1_000L
        private const val KEEPALIVE_RETRY_INTERVAL_MS = 1_000L
        private const val KEEPALIVE_MAX_RETRIES = 3
    }
}
