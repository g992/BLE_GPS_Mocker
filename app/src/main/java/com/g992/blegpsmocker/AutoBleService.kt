package com.g992.blegpsmocker

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AutoBleService : Service() {

    private val tag = "AutoBleService"
    private var bleDataSource: BleDataSource? = null
    private var isScanning = false
    private var isConnected = false
    private var isConnecting = false
    private var autoScanJob: Job? = null

    private var fixRaw: String? = null
    private var hdop: Double? = null
    private var signalLevels: String? = null
    private var ttffSeconds: Long? = null
    private var altitudeMeters: Double? = null
    private var speedMetersPerSecond: Double? = null
    private var headingDegrees: Double? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var fusedClient: FusedLocationProviderClient? = null
    private var mockEnabled = false
    private val providerNames =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private val bleLock = Any()

    companion object {
        const val ACTION_BLE_STATUS = "com.g992.blegpsmocker.action.BLE_STATUS"
        const val ACTION_BLE_STATUS_REQUEST =
            "com.g992.blegpsmocker.action.BLE_STATUS_REQUEST"
        const val ACTION_BLE_CMD_START_SCAN =
            "com.g992.blegpsmocker.action.BLE_CMD_START_SCAN"
        const val ACTION_BLE_CMD_STOP_SCAN =
            "com.g992.blegpsmocker.action.BLE_CMD_STOP_SCAN"
        const val ACTION_BLE_CMD_DISCONNECT =
            "com.g992.blegpsmocker.action.BLE_CMD_DISCONNECT"
        const val EXTRA_BLE_CONNECTED = "ble_connected"
        const val EXTRA_BLE_SCANNING = "ble_scanning"

        const val ACTION_START_MOCK = "com.g992.blegpsmocker.action.START_MOCK"
        const val ACTION_STOP_MOCK = "com.g992.blegpsmocker.action.STOP_MOCK"
        const val ACTION_STATUS = "com.g992.blegpsmocker.action.STATUS"
        const val ACTION_FEED_COORD = "com.g992.blegpsmocker.action.FEED_COORD"
        const val ACTION_APPLY_PREFS = "com.g992.blegpsmocker.action.APPLY_PREFS"
        const val ACTION_START_FULL = "com.g992.blegpsmocker.action.START_FULL"

        const val EXTRA_RUNNING = "running"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_ERROR = "error"
        const val EXTRA_FIX_RAW = "fix_raw"
        const val EXTRA_FIX_BOOL = "fix"
        const val EXTRA_HDOP = "hdop"
        const val EXTRA_SIGNAL_LEVELS = "signal_levels"
        const val EXTRA_SAT_COUNT = "sat_count"
        const val EXTRA_SIGNAL_PERCENT = "signal_percent"
        const val EXTRA_FIX_TYPE = "fix_type"
        const val EXTRA_TTFF = "ttff_seconds"
        const val EXTRA_ALTITUDE = "altitude_meters"
        const val EXTRA_SPEED_MS = "speed_meters_per_second"
        const val EXTRA_HEADING_DEGREES = "heading_degrees"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForegroundService()
        ensureBleDataSource()
        ensureAutoScanJob()
        handleAction(intent?.action, intent)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { bleDataSource?.stopScan() }
        runCatching { bleDataSource?.disconnect() }
        bleDataSource = null
        autoScanJob?.cancel()
        autoScanJob = null
        stopMocking()
        super.onDestroy()
    }

    private fun startAsForegroundService() {
        val notification =
            NotificationUtils.buildForegroundNotification(
                this,
                "BLE + мок геопозиции активны"
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationUtils.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID, notification)
        }
    }

    private fun ensureBleDataSource() {
        if (bleDataSource != null) return
        bleDataSource =
            BleDataSource(
                context = this,
                scanListener = createScanListener(),
                connectionListener = createConnectionListener()
            )
    }

    private fun createScanListener(): BleScanListener =
        object : BleScanListener {
            override fun onDeviceFound(device: BluetoothDevice) {
                Log.d(tag, "Device detected: ${device.address} - ${device.name ?: "Unknown"}")
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(tag, "Scan failed: $errorCode")
                synchronized(bleLock) {
                    isScanning = false
                    sendBleStatus()
                }
            }

            override fun onScanStopped(foundDevice: Boolean) {
                Log.d(tag, "Scan finished. Device found: $foundDevice")
                synchronized(bleLock) {
                    isScanning = false
                    sendBleStatus()
                }
            }
        }

    private fun createConnectionListener(): BleConnectionDataListener =
        object : BleConnectionDataListener {
            override fun onConnecting(device: BluetoothDevice) {
                synchronized(bleLock) {
                    isConnecting = true
                    sendBleStatus()
                }
            }

            override fun onConnected(device: BluetoothDevice) {
                Log.i(tag, "Connected to ${device.address}")
                synchronized(bleLock) {
                    isConnected = true
                    isScanning = false
                    isConnecting = false
                    sendBleStatus()
                }
            }

            override fun onDisconnected(device: BluetoothDevice) {
                Log.i(tag, "Disconnected from ${device.address}")
                synchronized(bleLock) {
                    isConnected = false
                    isConnecting = false
                    sendBleStatus()
                }
            }

            override fun onServicesDiscovered(device: BluetoothDevice) {
                Log.i(tag, "Services discovered on ${device.address}")
            }

            override fun onError(message: String) {
                Log.e(tag, "BLE error: $message")
                synchronized(bleLock) {
                    isConnecting = false
                    sendBleStatus()
                }
            }

            override fun onCoordinatesReceived(latitude: Double, longitude: Double) {
                runCatching {
                    val feedIntent = Intent(this@AutoBleService, AutoBleService::class.java).apply {
                        action = ACTION_FEED_COORD
                        putExtra(EXTRA_LAT, latitude)
                        putExtra(EXTRA_LON, longitude)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(feedIntent)
                    } else {
                        startService(feedIntent)
                    }
                }
            }

            override fun onFixStatusReceived(status: String) {
                fixRaw = status
                sendTelemetryUpdate()
            }

            override fun onHdopReceived(hdop: Double) {
                this@AutoBleService.hdop = hdop
                sendTelemetryUpdate()
            }

            override fun onSignalLevelsReceived(levels: String) {
                signalLevels = levels
                sendTelemetryUpdate()
            }

            override fun onAltitudeReceived(altitudeMeters: Double) {
                this@AutoBleService.altitudeMeters = altitudeMeters
                sendTelemetryUpdate()
            }

            override fun onSpeedReceived(speedMetersPerSecond: Double) {
                this@AutoBleService.speedMetersPerSecond = speedMetersPerSecond
                sendTelemetryUpdate()
            }

            override fun onHeadingReceived(headingDegrees: Double) {
                this@AutoBleService.headingDegrees = headingDegrees
                sendTelemetryUpdate()
            }

            override fun onDeviceStatusReceived(status: String) = Unit

            override fun onTtffReceived(ttffSeconds: Long) {
                this@AutoBleService.ttffSeconds = ttffSeconds
                sendTelemetryUpdate()
            }
        }

    private fun ensureAutoScanJob() {
        if (autoScanJob != null) return
        autoScanJob =
            serviceScope.launch {
                while (isActive) {
                    runCatching {
                        synchronized(bleLock) {
                            if (!isConnected && !isScanning && !isConnecting) {
                                tryStartScan()
                            }
                        }
                    }
                    delay(3_000)
                }
            }
    }

    private fun handleAction(action: String?, intent: Intent?) {
        when (action) {
            ACTION_START_FULL -> {
                applyPreferences()
                startBleIfNeeded()
            }

            ACTION_BLE_CMD_START_SCAN -> tryStartScan()
            ACTION_BLE_CMD_STOP_SCAN -> stopBleScan()
            ACTION_BLE_CMD_DISCONNECT -> disconnectBle()
            ACTION_BLE_STATUS_REQUEST -> sendBleStatus()
            ACTION_START_MOCK -> {
                AppPrefs.setMockEnabled(this, true)
                sendMockStatus(true, null, null, null)
                startMocking()
            }

            ACTION_STOP_MOCK -> {
                AppPrefs.setMockEnabled(this, false)
                sendMockStatus(false, null, null, null)
                stopMocking()
            }

            ACTION_APPLY_PREFS -> applyPreferences()
            ACTION_FEED_COORD -> handleFeedCoord(intent)
        }
    }

    private fun stopBleScan() {
        synchronized(bleLock) {
            runCatching { bleDataSource?.stopScan() }
            isScanning = false
            sendBleStatus()
        }
    }

    private fun disconnectBle() {
        runCatching { bleDataSource?.disconnect() }
        synchronized(bleLock) {
            isConnecting = false
            sendBleStatus()
        }
    }

    private fun handleFeedCoord(intent: Intent?) {
        val lat = intent?.getDoubleExtra(EXTRA_LAT, Double.NaN) ?: Double.NaN
        val lon = intent?.getDoubleExtra(EXTRA_LON, Double.NaN) ?: Double.NaN
        if (lat.isNaN() || lon.isNaN() || !mockEnabled) return

        runCatching {
            val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            ensureTestProviders(manager)
            if (fusedClient == null) {
                fusedClient = LocationServices.getFusedLocationProviderClient(this)
                runCatching { fusedClient?.setMockMode(true) }
            }
            pushLocation(manager, lat, lon)
        }
    }

    private fun applyPreferences() {
        val shouldEnable = AppPrefs.isMockEnabled(this)
        sendMockStatus(shouldEnable, null, null, null)
        if (shouldEnable) {
            startMocking()
        } else {
            stopMocking()
        }
    }

    private fun startBleIfNeeded() {
        synchronized(bleLock) {
            if (!isConnected && !isScanning && !isConnecting) {
                tryStartScan()
            }
        }
    }

    private fun tryStartScan() {
        runCatching {
            val dataSource = bleDataSource ?: return
            val hasAllPermissions =
                dataSource.requiredPermissions().all { perm ->
                    checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                }
            if (!hasAllPermissions) {
                Log.w(tag, "BLE scan skipped: permissions missing")
                return
            }
            dataSource.startScan()
            synchronized(bleLock) {
                isScanning = true
                sendBleStatus()
            }
        }
    }

    private fun sendBleStatus() {
        val statusIntent = Intent(ACTION_BLE_STATUS).apply {
            putExtra(EXTRA_BLE_CONNECTED, isConnected)
            putExtra(EXTRA_BLE_SCANNING, isScanning)
        }
        sendBroadcast(statusIntent)
    }

    private fun hasMockLocationPermission(): Boolean {
        val fine =
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarse =
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun ensureTestProviders(locationManager: LocationManager) {
        providerNames.forEach { provider ->
            runCatching { locationManager.removeTestProvider(provider) }
            runCatching {
                locationManager.addTestProvider(
                    provider,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
            }
            runCatching { locationManager.setTestProviderEnabled(provider, true) }
        }
    }

    private fun startMocking() {
        if (mockEnabled) return
        if (!hasMockLocationPermission()) {
            mockEnabled = false
            sendMockStatus(false, null, null, "Нет разрешений на локацию")
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ensureTestProviders(manager)
        runCatching {
            fusedClient = LocationServices.getFusedLocationProviderClient(this)
            fusedClient?.setMockMode(true)
        }
        runCatching {
            providerNames.forEach { provider ->
                manager.setTestProviderEnabled(provider, true)
            }
        }.onFailure {
            mockEnabled = false
            sendMockStatus(false, null, null, "Не назначен mock-провайдер")
            return
        }
        mockEnabled = true
        sendMockStatus(true, null, null, null)
    }

    private fun stopMocking() {
        mockEnabled = false
        runCatching { fusedClient?.setMockMode(false) }
        sendMockStatus(false, null, null, null)
    }

    private fun pushLocation(locationManager: LocationManager, lat: Double, lon: Double) {
        try {
            val timestamp = System.currentTimeMillis()
            val elapsedNanos =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    SystemClock.elapsedRealtimeNanos()
                } else {
                    0L
                }
            providerNames.forEach { provider ->
                val location = Location(provider).apply {
                    latitude = lat
                    longitude = lon
                    accuracy = 3.0f
                    altitudeMeters?.let { altitude = it }
                    speedMetersPerSecond?.let { speed = it.toFloat() }
                    headingDegrees?.let { bearing = it.toFloat() }
                    time = timestamp
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        elapsedRealtimeNanos = elapsedNanos
                    }
                }
                runCatching { locationManager.setTestProviderLocation(provider, location) }
            }
            val fusedLocation = Location("fused").apply {
                latitude = lat
                longitude = lon
                accuracy = 3.0f
                altitudeMeters?.let { altitude = it }
                speedMetersPerSecond?.let { speed = it.toFloat() }
                headingDegrees?.let { bearing = it.toFloat() }
                time = timestamp
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    elapsedRealtimeNanos = elapsedNanos
                }
            }
            runCatching { fusedClient?.setMockLocation(fusedLocation) }
            val latitude = if (mockEnabled) lat else null
            val longitude = if (mockEnabled) lon else null
            sendMockStatus(mockEnabled, latitude, longitude, null)
        } catch (exception: SecurityException) {
            sendMockStatus(false, null, null, "Нет прав или не назначен mock-провайдер")
            mockEnabled = false
        }
    }

    private fun sendTelemetryUpdate() {
        val intent = Intent(ACTION_STATUS)
        applyTelemetryExtras(intent)
        sendBroadcast(intent)
    }

    private fun sendMockStatus(
        isRunning: Boolean,
        lat: Double?,
        lon: Double?,
        error: String?
    ) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_RUNNING, isRunning)
            if (lat != null && lon != null) {
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LON, lon)
            }
            error?.let { putExtra(EXTRA_ERROR, it) }
        }
        applyTelemetryExtras(intent)
        sendBroadcast(intent)
    }

    private fun applyTelemetryExtras(intent: Intent) {
        fixRaw?.let { intent.putExtra(EXTRA_FIX_RAW, it) }
        fixRaw
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.toIntOrNull()
            ?.let { intent.putExtra(EXTRA_FIX_BOOL, it == 1) }

        hdop?.let {
            intent.putExtra(EXTRA_HDOP, it)
            val percent = (100 - it * 10.0).toInt().coerceIn(0, 100)
            intent.putExtra(EXTRA_SIGNAL_PERCENT, percent)
        }
        signalLevels?.let {
            intent.putExtra(EXTRA_SIGNAL_LEVELS, it)
            val satellites =
                it.split(',')
                    .map { value -> value.trim() }
                    .count { value -> value.isNotEmpty() }
            intent.putExtra(EXTRA_SAT_COUNT, satellites)
        }
        ttffSeconds?.let { intent.putExtra(EXTRA_TTFF, it) }
        altitudeMeters?.let { intent.putExtra(EXTRA_ALTITUDE, it) }
        speedMetersPerSecond?.let { intent.putExtra(EXTRA_SPEED_MS, it) }
        headingDegrees?.let { intent.putExtra(EXTRA_HEADING_DEGREES, it) }
        fixRaw
            ?.split(',')
            ?.getOrNull(1)
            ?.trim()
            ?.toIntOrNull()
            ?.let { intent.putExtra(EXTRA_FIX_TYPE, it) }
    }
}
