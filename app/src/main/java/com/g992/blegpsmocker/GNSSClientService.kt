package com.g992.blegpsmocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.jvm.JvmStatic

class GNSSClientService : Service() {

    private val tag = "GNSSClientService"
    private var connectionManager: ConnectionManager? = null
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
    private var mockEnabled = false
    private val providerCandidates =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private val registeredProviders = mutableSetOf<String>()
    @Volatile
    private var testProvidersConfigured = false
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

        @Volatile
        private var serviceRunning = false

        @JvmStatic
        fun isServiceRunning(): Boolean = serviceRunning

        @JvmStatic
        fun isServiceEnabled(context: Context): Boolean =
            AppPrefs.isMockEnabled(context)

        @JvmStatic
        fun setServiceEnabled(context: Context, enabled: Boolean) {
            AppPrefs.setMockEnabled(context, enabled)
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceRunning = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForegroundService()
        ensureConnectionManager()
        ensureAutoScanJob()
        val action = intent?.action
        if (action == null) {
            applyPreferences()
            startBleIfNeeded()
        } else {
            handleAction(action, intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceRunning = false
        runCatching { connectionManager?.stopScan() }
        runCatching { connectionManager?.disconnect() }
        connectionManager = null
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

    private fun ensureConnectionManager() {
        if (connectionManager != null) return
        connectionManager =
            ConnectionManager(
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
                    val feedIntent = Intent(this@GNSSClientService, GNSSClientService::class.java).apply {
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
                this@GNSSClientService.hdop = hdop
                sendTelemetryUpdate()
            }

            override fun onSignalLevelsReceived(levels: String) {
                signalLevels = levels
                sendTelemetryUpdate()
            }

            override fun onAltitudeReceived(altitudeMeters: Double) {
                this@GNSSClientService.altitudeMeters = altitudeMeters
                sendTelemetryUpdate()
            }

            override fun onSpeedReceived(speedMetersPerSecond: Double) {
                this@GNSSClientService.speedMetersPerSecond = speedMetersPerSecond
                sendTelemetryUpdate()
            }

            override fun onHeadingReceived(headingDegrees: Double) {
                this@GNSSClientService.headingDegrees = headingDegrees
                sendTelemetryUpdate()
            }

            override fun onDeviceStatusReceived(status: String) = Unit

            override fun onTtffReceived(ttffSeconds: Long) {
                this@GNSSClientService.ttffSeconds = ttffSeconds
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
            setServiceEnabled(this, true)
                sendMockStatus(true, null, null, null)
                startMocking()
            }

            ACTION_STOP_MOCK -> {
            setServiceEnabled(this, false)
                sendMockStatus(false, null, null, null)
                stopMocking()
            }

            ACTION_APPLY_PREFS -> applyPreferences()
            ACTION_FEED_COORD -> handleFeedCoord(intent)
        }
    }

    private fun stopBleScan() {
        synchronized(bleLock) {
            runCatching { connectionManager?.stopScan() }
            isScanning = false
            sendBleStatus()
        }
    }

    private fun disconnectBle() {
        runCatching { connectionManager?.disconnect() }
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
            val providersReady = ensureTestProviders(manager)
            if (!providersReady) {
                Log.w(tag, "Test providers not configured, skipping mock location push")
                return@runCatching
            }
            ensureTestProvidersActive(manager)
            pushLocation(manager, lat, lon)
        }
    }

    private fun applyPreferences() {
        val shouldEnable = isServiceEnabled(this)
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
            val dataSource = connectionManager ?: return
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

    private fun ensureTestProviders(locationManager: LocationManager): Boolean {
        if (testProvidersConfigured) return true

        var anyConfigured = false
        providerCandidates.forEach { provider ->
            if (registeredProviders.contains(provider)) {
                anyConfigured = true
            } else if (registerTestProvider(locationManager, provider)) {
                anyConfigured = true
            }
        }
        testProvidersConfigured = anyConfigured
        return anyConfigured
    }

    private fun ensureTestProvidersActive(locationManager: LocationManager): Boolean {
        var anyEnabled = false
        val currentProviders = registeredProviders.toList()
        currentProviders.forEach { provider ->
            var enabled = false
            repeat(2) { attempt ->
                val result =
                    runCatching { locationManager.setTestProviderEnabled(provider, true) }
                if (result.isSuccess) {
                    Log.d(tag, "Test provider $provider confirmed active")
                    enabled = true
                    return@repeat
                }
                val error = result.exceptionOrNull()
                if (error is IllegalArgumentException &&
                    error.message?.contains("not a test provider", true) == true &&
                    attempt == 0
                ) {
                    Log.w(
                        tag,
                        "Test provider $provider lost mock status, attempting re-registration"
                    )
                    if (!registerTestProvider(locationManager, provider)) {
                        registeredProviders.remove(provider)
                        testProvidersConfigured = registeredProviders.isNotEmpty()
                        return@repeat
                    }
                } else {
                    Log.w(tag, "Failed to ensure test provider $provider active", error)
                    return@repeat
                }
            }
            anyEnabled = anyEnabled || enabled
        }
        testProvidersConfigured = registeredProviders.isNotEmpty()
        return anyEnabled
    }

    private fun disableTestProviders(locationManager: LocationManager) {
        registeredProviders.forEach { provider ->
            runCatching {
                locationManager.setTestProviderEnabled(provider, false)
                Log.i(tag, "Disabled test provider $provider")
            }
            runCatching {
                locationManager.removeTestProvider(provider)
                Log.i(tag, "Removed test provider $provider")
            }
        }
        registeredProviders.clear()
        testProvidersConfigured = false
    }

    private fun startMocking() {
        if (mockEnabled) return
        if (!hasMockLocationPermission()) {
            mockEnabled = false
            sendMockStatus(false, null, null, "Нет разрешений на локацию")
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providersReady = ensureTestProviders(manager)
        val providersActive = ensureTestProvidersActive(manager)
        if (!providersReady || !providersActive) {
            mockEnabled = false
            sendMockStatus(false, null, null, "Не назначен mock-провайдер")
            return
        }
        mockEnabled = true
        sendMockStatus(true, null, null, null)
    }

    private fun stopMocking() {
        mockEnabled = false
        val manager = runCatching {
            getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }.getOrNull()
        if (manager != null) {
            disableTestProviders(manager)
        }
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
            val latFormatted = String.format(Locale.US, "%.6f", lat)
            val lonFormatted = String.format(Locale.US, "%.6f", lon)
            val providersToUse = registeredProviders.toList()
            if (providersToUse.isEmpty()) {
                Log.w(tag, "No registered test providers available for location push")
            }
            providersToUse.forEach { provider ->
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
                pushLocationToProvider(
                    locationManager = locationManager,
                    provider = provider,
                    location = location,
                    latFormatted = latFormatted,
                    lonFormatted = lonFormatted
                )
            }
            Log.i(tag, "Maps link: https://maps.google.com/?q=$latFormatted,$lonFormatted")
            val latitude = if (mockEnabled) lat else null
            val longitude = if (mockEnabled) lon else null
            sendMockStatus(mockEnabled, latitude, longitude, null)
        } catch (exception: SecurityException) {
            sendMockStatus(false, null, null, "Нет прав или не назначен mock-провайдер")
            mockEnabled = false
        }
    }

    private fun pushLocationToProvider(
        locationManager: LocationManager,
        provider: String,
        location: Location,
        latFormatted: String,
        lonFormatted: String
    ) {
        var attempts = 0
        while (attempts < 2) {
            val result =
                runCatching { locationManager.setTestProviderLocation(provider, location) }
            if (result.isSuccess) {
                Log.i(
                    tag,
                    "Mock location pushed to $provider lat=$latFormatted lon=$lonFormatted"
                )
                return
            }
            val error = result.exceptionOrNull()
            if (error is IllegalArgumentException &&
                error.message?.contains("not a test provider", ignoreCase = true) == true &&
                attempts == 0
            ) {
                Log.w(tag, "Provider $provider lost test status, reconfiguring")
                testProvidersConfigured = false
                val configured = ensureTestProviders(locationManager)
                if (!configured) {
                    Log.e(tag, "Reconfiguration failed: test providers not available")
                    break
                }
                ensureTestProvidersActive(locationManager)
                attempts++
                continue
            } else {
                Log.e(tag, "Failed to push mock location to $provider", error)
                registeredProviders.remove(provider)
                testProvidersConfigured = registeredProviders.isNotEmpty()
                break
            }
        }
    }

    private fun registerTestProvider(
        locationManager: LocationManager,
        provider: String
    ): Boolean {
        runCatching {
            locationManager.removeTestProvider(provider)
            Log.i(tag, "Removed existing test provider $provider")
        }
        val addResult =
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
        if (addResult.isFailure) {
            Log.w(tag, "Failed to add test provider $provider", addResult.exceptionOrNull())
            registeredProviders.remove(provider)
            return false
        }
        val enableResult =
            runCatching { locationManager.setTestProviderEnabled(provider, true) }
        return if (enableResult.isSuccess) {
            Log.i(tag, "Test provider $provider registered and enabled")
            registeredProviders.add(provider)
            testProvidersConfigured = registeredProviders.isNotEmpty()
            true
        } else {
            Log.w(tag, "Failed to enable test provider $provider", enableResult.exceptionOrNull())
            registeredProviders.remove(provider)
            testProvidersConfigured = registeredProviders.isNotEmpty()
            false
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

object NotificationUtils {
    const val CHANNEL_ID = "mock_location_channel"
    const val CHANNEL_NAME = "Мок геопозиции"
    const val NOTIFICATION_ID = 1001

    const val CHANNEL_ATTENTION_ID = "mock_location_attention"
    const val CHANNEL_ATTENTION_NAME = "Требуется действие пользователя"
    const val NOTIFICATION_ATTENTION_ID = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel =
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(false) }
                manager.createNotificationChannel(channel)
            }
            if (manager.getNotificationChannel(CHANNEL_ATTENTION_ID) == null) {
                val channel =
                    NotificationChannel(
                        CHANNEL_ATTENTION_ID,
                        CHANNEL_ATTENTION_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { setShowBadge(false) }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun buildForegroundNotification(context: Context, contentText: String): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(CHANNEL_NAME)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    fun showNeedsUserActionNotification(context: Context, contentText: String) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ATTENTION_ID)
                .setContentTitle(CHANNEL_ATTENTION_NAME)
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val granted =
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ATTENTION_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
