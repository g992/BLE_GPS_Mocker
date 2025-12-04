package com.g992.blegpsmocker.ota

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log
import com.g992.blegpsmocker.GNSSClientService
import com.g992.blegpsmocker.R
import com.g992.blegpsmocker.ble.BleUuids
import com.g992.blegpsmocker.ble.ConnectionManager

private const val TAG = "OtaPortalController"

class OtaPortalController(
    private val context: Context,
    private val handler: Handler,
    private val connectionManagerProvider: () -> ConnectionManager?,
    private val isConnectedProvider: () -> Boolean,
    private val broadcaster: (Intent) -> Unit
) {

    private var portalState = OtaPortalState()
    private var guardPending = false

    private val wifiStatusPollRunnable =
        object : Runnable {
            override fun run() {
                if (!isConnectedProvider()) {
                    return
                }
                val manager = connectionManagerProvider()
                if (manager?.hasOtaService() == true) {
                    manager.readCharacteristic(BleUuids.CHAR_WIFI_STATUS_UUID)
                }
                handler.postDelayed(this, GNSSClientService.WIFI_STATUS_POLL_INTERVAL_MS)
            }
        }

    fun initialize() {
        portalState = OtaPortalState(message = context.getString(R.string.ota_status_closed))
    }

    fun onConnected() {
        startWifiStatusPolling(immediate = true)
        emitCurrentState()
    }

    fun onDisconnected() {
        resetPortalState(context.getString(R.string.ota_status_closed))
        stopWifiStatusPolling()
    }

    fun requestPortal(enable: Boolean): Boolean {
        if (!isConnectedProvider()) {
            updatePortalState(message = context.getString(R.string.ota_error_not_connected))
            return false
        }
        val manager = connectionManagerProvider() ?: return false
        guardPending = true
        val payload = if (enable) "1" else "0"
        val enqueued = manager.writeCharacteristic(BleUuids.CHAR_OTA_CONTROL_UUID, payload)
        if (!enqueued) {
            guardPending = false
            updatePortalState(message = context.getString(R.string.ota_guard_write_failed))
            return false
        }
        val pendingMessage =
            if (enable) {
                context.getString(R.string.ota_status_request_open)
            } else {
                context.getString(R.string.ota_status_request_close)
            }
        updatePortalState(message = pendingMessage)
        handler.postDelayed({ refreshPortalState() }, 500)
        return true
    }

    fun refreshPortalState() {
        val manager = connectionManagerProvider() ?: return
        if (!isConnectedProvider()) return
        manager.readCharacteristic(BleUuids.CHAR_OTA_CONTROL_UUID)
        handler.postDelayed(
            { manager.readCharacteristic(BleUuids.CHAR_WIFI_STATUS_UUID) },
            250
        )
    }

    fun emitCurrentState() {
        broadcastPortalState()
    }

    fun handleGuardStateChanged(enabled: Boolean) {
        guardPending = false
        val message =
            if (enabled) context.getString(R.string.ota_status_open)
            else context.getString(R.string.ota_status_closed)
        updatePortalState(enabled = enabled, message = message)
        if (enabled) {
            handler.postDelayed({ refreshPortalState() }, 300)
        }
    }

    fun handleWifiStatus(status: String, ip: String?) {
        val normalized =
            when (status.lowercase()) {
                "connected" -> "connected"
                "connecting" -> "connecting"
                "disconnected" -> "disconnected"
                else -> "unknown"
            }
        updatePortalState(wifiStatus = normalized, ip = ip)
    }

    fun handleOtaStatusMessage(status: String) {
        Log.d(TAG, "OTA status (legacy): $status")
    }

    private fun startWifiStatusPolling(immediate: Boolean = false) {
        handler.removeCallbacks(wifiStatusPollRunnable)
        if (!isConnectedProvider()) {
            return
        }
        val delayMillis = if (immediate) 0L else GNSSClientService.WIFI_STATUS_POLL_INTERVAL_MS
        handler.postDelayed(wifiStatusPollRunnable, delayMillis)
    }

    private fun stopWifiStatusPolling() {
        handler.removeCallbacks(wifiStatusPollRunnable)
    }

    private fun updatePortalState(
        enabled: Boolean? = null,
        wifiStatus: String? = null,
        ip: String? = null,
        message: String? = null
    ) {
        val updated =
            portalState.copy(
                enabled = enabled ?: portalState.enabled,
                wifiStatus = wifiStatus ?: portalState.wifiStatus,
                ip = ip ?: portalState.ip,
                message = message ?: portalState.message
            )
        portalState = updated
        broadcastPortalState(message ?: updated.message)
    }

    private fun resetPortalState(reasonMessage: String? = null) {
        guardPending = false
        portalState = OtaPortalState(message = reasonMessage)
        broadcastPortalState(reasonMessage)
    }

    private fun broadcastPortalState(message: String? = portalState.message) {
        val current = portalState.copy(message = message ?: portalState.message)
        val intent =
            Intent(GNSSClientService.ACTION_OTA_STATUS).apply {
                putExtra(GNSSClientService.EXTRA_OTA_STATE, if (current.enabled) "enabled" else "disabled")
                putExtra(GNSSClientService.EXTRA_OTA_GUARD_ENABLED, current.enabled)
                putExtra(GNSSClientService.EXTRA_OTA_WIFI_STATE, current.wifiStatus)
                current.ip?.let { putExtra(GNSSClientService.EXTRA_OTA_WIFI_IP, it) }
                current.portalUrl?.let { putExtra(GNSSClientService.EXTRA_OTA_PORTAL_URL, it) }
                putExtra(
                    GNSSClientService.EXTRA_OTA_PORTAL_FALLBACK,
                    "http://${GNSSClientService.OTA_AP_FALLBACK_HOST}${GNSSClientService.OTA_PORTAL_PATH}"
                )
                message?.takeIf { it.isNotBlank() }?.let { putExtra(GNSSClientService.EXTRA_OTA_MESSAGE, it) }
            }
        broadcaster(intent)
    }
}
