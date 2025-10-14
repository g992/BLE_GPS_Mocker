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
}

@SuppressLint("MissingPermission")
class BleDataSource(
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
    private val tag = "BleDataSource"
    private var gpsService: android.bluetooth.BluetoothGattService? = null

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
                        connectionListener?.onDisconnected(device)
                        closeGatt()
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(tag, "MTU changed to $mtu")
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
                    connectionListener?.onServicesDiscovered(gatt.device)
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
        data?.let { parseAndNotify(characteristic.uuid, it) }
    }

    private fun parseAndNotify(uuid: UUID, data: ByteArray) {
        val stringValue = data.toString(Charsets.UTF_8).trim()
        try {
            when (uuid) {
                BleUuids.CHAR_COORDINATES_UUID -> handleCoordinatesPayload(stringValue)
                BleUuids.CHAR_STATUS_UUID -> handleStatusPayload(stringValue)
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

    private fun StringBuilder.appendSignals(array: JSONArray) {
        for (index in 0 until array.length()) {
            if (index > 0) append(',')
            append(array.optInt(index))
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
    ) {
        val characteristic = service.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            Log.e(tag, "Characteristic $characteristicUuid not found for read")
            return
        }
        if (!hasConnectPermission()) {
            connectionListener?.onError(
                "Missing BLUETOOTH_CONNECT permission to read characteristic"
            )
            return
        }
        if (!gatt.readCharacteristic(characteristic)) {
            Log.w(tag, "Failed to initiate read for characteristic $characteristicUuid")
        } else {
            Log.i(tag, "Requested read for characteristic $characteristicUuid")
        }
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
        bluetoothGatt?.disconnect()
    }

    fun closeGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        gpsService = null
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
        readCharacteristicInternal(gatt, service, BleUuids.CHAR_STATUS_UUID)
        return true
    }
}
