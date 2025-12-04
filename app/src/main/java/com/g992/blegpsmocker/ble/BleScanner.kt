package com.g992.blegpsmocker.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log

internal class BleScanner(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val permissionChecker: BlePermissionChecker,
    private val handler: Handler,
    private val tag: String,
    private var scanListener: BleScanListener?,
    private var connectionListener: BleConnectionDataListener?,
    private val onDeviceFound: (BluetoothDevice) -> Unit
) {

    private var isScanning = false
    private var foundDeviceDuringScan = false

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
                onDeviceFound(result.device)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                super.onBatchScanResults(results)
                if (!isScanning || results.isEmpty()) return

                Log.d(tag, "Batch scan results: ${results.size}")
                results.firstOrNull()?.let {
                    foundDeviceDuringScan = true
                    scanListener?.onDeviceFound(it.device)
                    stopScan()
                    onDeviceFound(it.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e(tag, "Scan failed with error: $errorCode")
                isScanning = false
                scanListener?.onScanFailed(errorCode)
            }
        }

    fun startScan() {
        if (isScanning) {
            Log.w(tag, "Scan already in progress")
            return
        }
        if (!permissionChecker.hasScanPermission()) {
            val message =
                "Missing permissions for BLE scan. Required: ${
                    permissionChecker.requiredPermissions().joinToString()
                }"
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

    fun stopScan() {
        if (!isScanning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !permissionChecker.hasScanPermission()) {
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

    fun updateListeners(
        scanListener: BleScanListener?,
        connectionListener: BleConnectionDataListener?
    ) {
        this.scanListener = scanListener
        this.connectionListener = connectionListener
    }

    fun isScanning(): Boolean = isScanning
}
