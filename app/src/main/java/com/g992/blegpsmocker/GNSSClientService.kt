package com.g992.blegpsmocker

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import org.json.JSONObject
import kotlin.random.Random

private const val TAG = "GNSSClientService"
private const val DEFAULT_HDOP_FALLBACK = 1.5
private const val HDOP_TO_ACCURACY_METERS = 5.0
private const val MIN_ACCURACY_METERS = 3f
private const val MAX_ACCURACY_METERS = 50f
private const val MIN_MOVEMENT_THRESHOLD_METERS = 0.1
private const val SATELLITE_SIGNAL_STRONG_THRESHOLD = 35
private const val SATELLITE_SIGNAL_MEDIUM_THRESHOLD = 20
private const val RESCAN_DELAY_MS = 3_000L
private const val STATIC_AP_SSID = "GPS-C3-xxxxxx"
private const val GPS_BAUD_MIN = 4_800
private const val GPS_BAUD_MAX = 921_600
private val GNSS_PROFILE_VALUES = setOf(0, 1, 2, 3)
private val BASE_SETTINGS_PROFILE_VALUES = setOf(0, 1)
private const val DEVICE_SETTING_READ_RETRY_MAX = 3
private const val DEVICE_SETTING_READ_RETRY_DELAY_MS = 300L
private const val OTA_PORTAL_PATH = "/update"
private const val OTA_AP_FALLBACK_HOST = "192.168.4.1"
private const val IDLE_DRIFT_DELAY_MS = 3_000L
private const val IDLE_DRIFT_INTERVAL_MS = 3_000L
private const val MIN_DRIFT_SPEED_MPS = 0.1
private const val MIN_DRIFT_RADIUS_METERS = 0.3
private const val WIFI_STATUS_POLL_INTERVAL_MS = 5_000L

data class OtaPortalState(
    val enabled: Boolean = false,
    val wifiStatus: String = "unknown",
    val ip: String? = null,
    val message: String? = null
) {
    val portalUrl: String?
        get() = ip?.takeIf { it.isNotBlank() }?.let { "http://$it$OTA_PORTAL_PATH" }
}

class GNSSClientService :
    Service(),
    BleScanListener,
    BleConnectionDataListener {

    private var connectionManager: ConnectionManager? = null
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private val rescanRunnable =
        Runnable {
            if (!isConnected && AppPrefs.isMockEnabled(this)) {
                startBleWorkflow()
            }
        }
    private val isReceivingUpdates = AtomicBoolean(false)
    private val registeredProviders = mutableSetOf<String>()
    private val mockProvidersConfigured = AtomicBoolean(false)

    private var isConnected = false
    private var lastReceivedLocation: Location? = null
    private var lastUpdateTime: Long = 0L
    private var lastCoordinatesTimestamp: Long = 0L
    private var lastProvider: String? = null
    private var hasReceivedModuleFix = false
    private var lastRealUpdateTimestamp: Long = 0L
    private var lastRealLocation: Location? = null

    private var hdop: Double? = null
    private var signalLevels: String? = null
    private var altitudeMeters: Double? = null
    private var speedMetersPerSecond: Double? = null
    private var headingDegrees: Double? = null
    private var ttffSeconds: Long? = null
    private var apControlEnabled: Boolean? = null
    private var bridgeModeEnabled: Boolean? = null
    private var gpsBaudRate: Int? = null
    private var gnssProfile: Int? = null
    private var baseSettingsProfile: Int? = null
    private var customGnssProfileFrame: String? = null
    private var customBaseSettingsFrame: String? = null
    private var deviceVersion: String? = null
    private var inputVoltage: Double? = null
    private var otaPortalState = OtaPortalState()
    private var otaGuardPending = false
    private var alwaysMovingEnabled = false
    private val idleMovementRunnable = Runnable { emitIdleMovement() }
    private val wifiStatusPollRunnable =
        object : Runnable {
            override fun run() {
                if (!isConnected) {
                    return
                }
                val manager = connectionManager
                if (manager?.hasOtaService() == true) {
                    manager.readCharacteristic(BleUuids.CHAR_WIFI_STATUS_UUID)
                }
                handler.postDelayed(this, WIFI_STATUS_POLL_INTERVAL_MS)
            }
        }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        notificationManager = getSystemService(NotificationManager::class.java)
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock =
            powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GNSSClientService:WakeLock"
            )
        ensureConnectionManager()
        val currentMockApp =
            runCatching {
                Settings.Secure.getString(contentResolver, "mock_location_app")
            }.getOrNull()
        Log.d(TAG, "Current mock_location_app = $currentMockApp")
        serviceRunning = true
        otaPortalState = OtaPortalState(message = getString(R.string.ota_status_closed))
        alwaysMovingEnabled = AppPrefs.isAlwaysMovingEnabled(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NotificationUtils.NOTIFICATION_ID,
            NotificationUtils.buildStatusNotification(this, isConnected, null)
        )
        ensureConnectionManager()
        alwaysMovingEnabled = AppPrefs.isAlwaysMovingEnabled(this)
        if (AppPrefs.isMockEnabled(this)) {
            startBleWorkflow()
        } else {
            stopBleWorkflow()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceivingLocationUpdates()
        stopBleWorkflow()
        connectionManager = null
        handler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(rescanRunnable)
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        disableTestProviders()
        serviceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder = GNSSClientBinder()

    inner class GNSSClientBinder : Binder() {
        fun getService(): GNSSClientService = this@GNSSClientService
    }

    fun getApControlState(): Boolean? = apControlEnabled

    fun getBridgeModeState(): Boolean? = bridgeModeEnabled

    fun getApControlSsidHint(): String = STATIC_AP_SSID

    fun getGpsBaudRate(): Int? = gpsBaudRate

    fun getGnssProfile(): Int? = gnssProfile

    fun getBaseSettingsProfile(): Int? = baseSettingsProfile

    fun getCustomGnssProfileFrame(): String? = customGnssProfileFrame

    fun getCustomBaseSettingsFrame(): String? = customBaseSettingsFrame

    fun getDeviceVersion(): String? = deviceVersion

    fun getInputVoltage(): Double? = inputVoltage

    fun setAlwaysMovingEnabled(enabled: Boolean) {
        alwaysMovingEnabled = enabled
        if (!enabled) {
            handler.removeCallbacks(idleMovementRunnable)
        } else if (hasReceivedModuleFix) {
            scheduleIdleMovement()
        }
    }

    fun requestApControlChange(enabled: Boolean): Boolean {
        if (!isConnected) {
            Log.w(TAG, "requestApControlChange skipped: not connected")
            return false
        }
        val payload = if (enabled) "1" else "0"
        return connectionManager?.writeCharacteristic(BleUuids.CHAR_AP_CONTROL_UUID, payload)
            ?: false
    }

    fun requestBridgeModeChange(enabled: Boolean): Boolean {
        if (!isConnected) {
            Log.w(TAG, "requestBridgeModeChange skipped: not connected")
            return false
        }
        val payload = if (enabled) "1" else "0"
        return connectionManager?.writeCharacteristic(BleUuids.CHAR_MODE_CONTROL_UUID, payload)
            ?: false
    }

    fun requestGpsBaudRateChange(baudRate: Int): Boolean {
        if (!isConnected) {
            Log.w(TAG, "requestGpsBaudRateChange skipped: not connected")
            return false
        }
        val sanitized = baudRate.coerceIn(GPS_BAUD_MIN, GPS_BAUD_MAX)
        if (sanitized != baudRate) {
            Log.w(TAG, "GPS baud rate $baudRate clamped to $sanitized")
        }
        val payload = sanitized.toString()
        return connectionManager?.writeCharacteristic(BleUuids.CHAR_GPS_BAUD_UUID, payload)
            ?: false
    }

    fun requestGnssProfileChange(profile: Int): Boolean {
        if (!isConnected) {
            Log.w(TAG, "requestGnssProfileChange skipped: not connected")
            return false
        }
        if (!GNSS_PROFILE_VALUES.contains(profile)) {
            Log.w(TAG, "requestGnssProfileChange skipped: unsupported profile $profile")
            return false
        }
        val payload = profile.toString()
        return connectionManager?.writeCharacteristic(BleUuids.CHAR_GNSS_PROFILE_UUID, payload)
            ?: false
    }

    fun requestBaseSettingsProfileChange(profile: Int): Boolean {
        if (!isConnected) {
            Log.w(TAG, "requestBaseSettingsProfileChange skipped: not connected")
            return false
        }
        if (!BASE_SETTINGS_PROFILE_VALUES.contains(profile)) {
            Log.w(TAG, "requestBaseSettingsProfileChange skipped: unsupported profile $profile")
            return false
        }
        val payload = profile.toString()
        return connectionManager?.writeCharacteristic(
            BleUuids.CHAR_BASE_SETTINGS_PROFILE_UUID,
            payload
        ) ?: false
    }

    fun updateCustomGnssProfileFrame(frame: String): Boolean {
        if (!isConnected) {
            Log.w(TAG, "updateCustomGnssProfileFrame skipped: not connected")
            return false
        }
        val sanitized = frame.trim()
        if (sanitized.isEmpty()) {
            Log.w(TAG, "updateCustomGnssProfileFrame skipped: empty payload")
            return false
        }
        return connectionManager?.writeCharacteristic(
            BleUuids.CHAR_CUSTOM_GNSS_PROFILE_UUID,
            sanitized
        ) ?: false
    }

    fun updateCustomBaseSettingsFrame(frame: String): Boolean {
        if (!isConnected) {
            Log.w(TAG, "updateCustomBaseSettingsFrame skipped: not connected")
            return false
        }
        val sanitized = frame.trim()
        if (sanitized.isEmpty()) {
            Log.w(TAG, "updateCustomBaseSettingsFrame skipped: empty payload")
            return false
        }
        return connectionManager?.writeCharacteristic(
            BleUuids.CHAR_CUSTOM_BASE_SETTINGS_UUID,
            sanitized
        ) ?: false
    }

    fun refreshDeviceSettings() {
        requestDeviceSettingsRead()
    }

    private fun ensureConnectionManager() {
        if (connectionManager != null) return
        connectionManager =
            ConnectionManager(
                context = this,
                scanListener = this,
                connectionListener = this
            )
    }

    private fun startBleWorkflow() {
        val manager = connectionManager ?: return
        val hasPermissions =
            manager.requiredPermissions().all { permission ->
                ContextCompat.checkSelfPermission(this, permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        if (!hasPermissions) {
            Log.w(TAG, "BLE workflow skipped: permissions missing")
            return
        }
        manager.startScan()
    }

    private fun stopBleWorkflow() {
        connectionManager?.let { manager ->
            manager.stopScan()
            manager.disconnect()
        }
        isConnected = false
        handler.removeCallbacks(idleMovementRunnable)
        hasReceivedModuleFix = false
        lastRealUpdateTimestamp = 0L
        lastRealLocation = null
        updateNotification()
    }

    private fun scheduleRescan() {
        handler.removeCallbacks(rescanRunnable)
        handler.postDelayed(rescanRunnable, RESCAN_DELAY_MS)
    }

    private fun startReceivingLocationUpdates() {
        if (isReceivingUpdates.get()) {
            return
        }

        if (!isMockLocationEnabled()) {
            Log.w(TAG, "Mock locations are not enabled in developer settings")
            broadcastMockLocationStatus(getString(R.string.mock_location_enable_message))
            return
        }

        if (!hasMockLocationPermission()) {
            Log.w(TAG, "Location permissions missing for mock locations")
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied))
            return
        }

        if (!ensureMockProvidersConfigured()) {
            broadcastMockLocationStatus(
                getString(R.string.mock_location_setup_failed, "providers")
            )
            return
        }

        if (!ensureMockProvidersActive()) {
            broadcastMockLocationStatus(
                getString(R.string.mock_location_setup_failed, "activate")
            )
            return
        }

        isReceivingUpdates.set(true)
        broadcastMockLocationStatus(getString(R.string.mock_location_provider_ready))
    }

    private fun stopReceivingLocationUpdates() {
        if (!isReceivingUpdates.get()) {
            return
        }
        isReceivingUpdates.set(false)
        disableTestProviders()
    }

    private fun ensureMockProvidersConfigured(): Boolean {
        if (mockProvidersConfigured.get()) {
            return true
        }

        var configured = true
        providerCandidates.forEach { provider ->
            if (!registerTestProvider(provider)) {
                configured = false
            }
        }

        mockProvidersConfigured.set(configured)
        return configured
    }

    private fun ensureMockProvidersActive(): Boolean {
        var anyEnabled = false
        registeredProviders.toList().forEach { provider ->
            val result =
                runCatching {
                    locationManager.setTestProviderEnabled(provider, true)
                }
            if (result.isSuccess) {
                anyEnabled = true
            } else {
                val reason = result.exceptionOrNull()?.message ?: "unknown error"
                Log.w(TAG, "Failed to ensure provider $provider active: $reason")
                registeredProviders.remove(provider)
            }
        }
        mockProvidersConfigured.set(registeredProviders.isNotEmpty())
        return anyEnabled
    }

    private fun disableTestProviders() {
        registeredProviders.forEach { provider ->
            runCatching { locationManager.setTestProviderEnabled(provider, false) }
        }
        registeredProviders.clear()
        mockProvidersConfigured.set(false)
    }

    private fun registerTestProvider(provider: String): Boolean {
        runCatching { locationManager.removeTestProvider(provider) }

        val addResult =
            runCatching {
                locationManager.addTestProvider(
                    provider,
                    false,
                    true,
                    false,
                    false,
                    true,
                    true,
                    true,
                    ProviderProperties.POWER_USAGE_HIGH,
                    ProviderProperties.ACCURACY_FINE
                )
            }

        if (addResult.isFailure) {
            val reason = addResult.exceptionOrNull()?.message ?: "unknown error"
            Log.w(TAG, "Failed to add test provider $provider: $reason")
            return false
        }

        val enableResult =
            runCatching { locationManager.setTestProviderEnabled(provider, true) }
        return if (enableResult.isSuccess) {
            registeredProviders.add(provider)
            true
        } else {
            val reason = enableResult.exceptionOrNull()?.message ?: "unknown error"
            Log.w(TAG, "Failed to enable test provider $provider: $reason")
            runCatching { locationManager.removeTestProvider(provider) }
            false
        }
    }

    private fun handleLocationUpdate(
        latitude: Double,
        longitude: Double,
        synthetic: Boolean = false,
        speedOverride: Double? = null,
        bearingOverride: Float? = null
    ): Location? {
        if (!isReceivingUpdates.get()) {
            startReceivingLocationUpdates()
            if (!isReceivingUpdates.get()) {
                return null
            }
        }

        ensureMockProvidersActive()

        val now = System.currentTimeMillis()
        if (lastUpdateTime > 0 && now - lastUpdateTime < 200) {
            Log.v(TAG, "Skipping mock update due to rate limit: ${now - lastUpdateTime}ms since last")
            return null
        }

        if (lastUpdateTime > 0L) {
            Log.d(TAG, "handleLocationUpdate deltaMillis=${now - lastUpdateTime}")
        } else {
            Log.d(TAG, "handleLocationUpdate first update")
        }

        val timestamp = now
        val elapsedNanos =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                SystemClock.elapsedRealtimeNanos()
            } else {
                0L
            }

        val accuracy = computeAccuracyMeters()
        val altitude = resolveAltitudeMeters()
        val speed = speedOverride ?: resolveSpeedMetersPerSecond(latitude, longitude, timestamp)
        Log.d(
            TAG,
            "Resolved location data accuracy=$accuracy altitude=${altitude ?: "n/a"} speed=${speed ?: "n/a"} hdop=$hdop"
        )

        val baseLocation = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = longitude
            this.time = timestamp
            this.accuracy = accuracy
            if (altitude != null) {
                this.altitude = altitude
            }
            if (speed != null) {
                this.speed = speed.toFloat()
            }
            if (bearingOverride != null) {
                this.bearing = bearingOverride
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                this.elapsedRealtimeNanos = elapsedNanos
            }
        }

        val providers = registeredProviders.ifEmpty { providerCandidates }
        providers.forEach { provider ->
            val providerLocation = Location(provider).apply {
                this.latitude = baseLocation.latitude
                this.longitude = baseLocation.longitude
                this.accuracy = baseLocation.accuracy
                this.time = baseLocation.time
                if (baseLocation.hasAltitude()) {
                    this.altitude = baseLocation.altitude
                }
                if (baseLocation.hasSpeed()) {
                    this.speed = baseLocation.speed
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    this.elapsedRealtimeNanos = baseLocation.elapsedRealtimeNanos
                }
            }
            handler.post {
                try {
                    locationManager.setTestProviderLocation(provider, providerLocation)
                    Log.d(
                        TAG,
                        "Mock location sent to $provider lat=${providerLocation.latitude} lon=${providerLocation.longitude}"
                    )
                } catch (error: IllegalArgumentException) {
                    if (error.message?.contains("not a test provider") == true) {
                        Log.w(TAG, "Provider $provider reverted to real source, re-enabling mock")
                        registerTestProvider(provider)
                    } else {
                        Log.e(TAG, "Failed to push mock location to $provider", error)
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Unexpected mock push error for provider $provider", error)
                }
            }
        }

        lastReceivedLocation = baseLocation
        lastUpdateTime = timestamp
        lastCoordinatesTimestamp = timestamp
        lastProvider = providers.firstOrNull()

        val satelliteBreakdown = computeSatelliteSignalBreakdown()
        val satellites = satelliteBreakdown.total
        val locationAge = 0f

        broadcastLocation(baseLocation, satellites, lastProvider, locationAge, satelliteBreakdown)
        updateNotification()
        Log.d(
            TAG,
            "Location broadcast lat=${baseLocation.latitude} lon=${baseLocation.longitude} accuracy=${baseLocation.accuracy} satellites=$satellites providers=${providers.joinToString()}"
        )
        return baseLocation
    }

    private fun scheduleIdleMovement() {
        handler.removeCallbacks(idleMovementRunnable)
        if (!alwaysMovingEnabled || !hasReceivedModuleFix) {
            return
        }
        handler.postDelayed(idleMovementRunnable, IDLE_DRIFT_DELAY_MS)
    }

    private fun emitIdleMovement() {
        if (!alwaysMovingEnabled || !hasReceivedModuleFix) {
            return
        }
        val origin = lastRealLocation ?: lastReceivedLocation ?: return
        val idleDuration = System.currentTimeMillis() - lastRealUpdateTimestamp
        if (idleDuration < IDLE_DRIFT_DELAY_MS) {
            scheduleIdleMovement()
            return
        }
        val bearing = Random.nextDouble(0.0, 360.0)
        val (targetLat, targetLon) = offsetCoordinates(origin, MIN_DRIFT_RADIUS_METERS, bearing)
        handleLocationUpdate(
            targetLat,
            targetLon,
            synthetic = true,
            speedOverride = MIN_DRIFT_SPEED_MPS,
            bearingOverride = bearing.toFloat()
        )
        handler.postDelayed(idleMovementRunnable, IDLE_DRIFT_INTERVAL_MS)
    }

    private fun offsetCoordinates(
        origin: Location,
        distanceMeters: Double,
        bearingDegrees: Double
    ): Pair<Double, Double> {
        val earthRadius = 6_371_000.0
        val distanceRatio = distanceMeters / earthRadius
        val bearingRad = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(origin.latitude)
        val lon1 = Math.toRadians(origin.longitude)
        val lat2 = Math.asin(
            Math.sin(lat1) * Math.cos(distanceRatio) +
                Math.cos(lat1) * Math.sin(distanceRatio) * Math.cos(bearingRad)
        )
        val lon2 =
            lon1 + Math.atan2(
                Math.sin(bearingRad) * Math.sin(distanceRatio) * Math.cos(lat1),
                Math.cos(distanceRatio) - Math.sin(lat1) * Math.sin(lat2)
            )
        return Pair(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun computeAccuracyMeters(): Float {
        val hdopValue =
            hdop?.takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0 } ?: DEFAULT_HDOP_FALLBACK
        val accuracy = (hdopValue * HDOP_TO_ACCURACY_METERS).toFloat()
        return accuracy.coerceIn(MIN_ACCURACY_METERS, MAX_ACCURACY_METERS)
    }

    private fun resolveAltitudeMeters(): Double? {
        val altitudeFromBle = altitudeMeters
        if (altitudeFromBle != null &&
            !altitudeFromBle.isNaN() &&
            !altitudeFromBle.isInfinite()
        ) {
            return altitudeFromBle
        }
        return lastReceivedLocation?.takeIf { it.hasAltitude() }?.altitude
    }

    private fun resolveSpeedMetersPerSecond(
        latitude: Double,
        longitude: Double,
        timestampMillis: Long
    ): Double? {
        val direct =
            speedMetersPerSecond?.takeIf {
                !it.isNaN() && !it.isInfinite() && it >= 0.0
            }
        if (direct != null) {
            return direct
        }
        val previous = lastReceivedLocation ?: return null
        val previousTimestamp = lastCoordinatesTimestamp
        if (previousTimestamp <= 0L) {
            return null
        }
        val deltaSeconds = (timestampMillis - previousTimestamp) / 1000.0
        if (deltaSeconds <= 0.0) {
            return null
        }
        val results = FloatArray(1)
        Location.distanceBetween(
            previous.latitude,
            previous.longitude,
            latitude,
            longitude,
            results
        )
        val distance = results[0].toDouble()
        if (distance < MIN_MOVEMENT_THRESHOLD_METERS) {
            return 0.0
        }
        return (distance / deltaSeconds).takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun computeSatelliteSignalBreakdown(): SatelliteSignalBreakdown {
        val rawLevels = signalLevels?.takeIf { it.isNotBlank() } ?: return SatelliteSignalBreakdown()
        var strong = 0
        var medium = 0
        var weak = 0
        // API contract: signals array contains ASCII digits '1'/'2'/'3' for weak/medium/strong buckets.
        rawLevels.split(',')
            .map { it.trim() }
            .forEach { token ->
                when (token.toIntOrNull()) {
                    3 -> strong++
                    2 -> medium++
                    1, 0 -> weak++
                    else -> Unit
                }
            }
        return SatelliteSignalBreakdown(strong, medium, weak)
    }

    private fun broadcastLocation(
        location: Location,
        satellites: Int,
        provider: String?,
        locationAge: Float,
        signalBreakdown: SatelliteSignalBreakdown
    ) {
        val intent =
            Intent(ACTION_LOCATION_UPDATE).apply {
                putExtra(EXTRA_LOCATION, location)
                putExtra(EXTRA_SATELLITES, satellites)
                putExtra(EXTRA_PROVIDER, provider ?: location.provider)
                putExtra(EXTRA_LOCATION_AGE, locationAge)
                putExtra(EXTRA_SATELLITES_STRONG, signalBreakdown.strong)
                putExtra(EXTRA_SATELLITES_MEDIUM, signalBreakdown.medium)
                putExtra(EXTRA_SATELLITES_WEAK, signalBreakdown.weak)
            }
        sendBroadcast(intent)
    }

    private fun broadcastConnectionState(connected: Boolean) {
        val intent =
            Intent(ACTION_CONNECTION_CHANGED).apply {
                putExtra(EXTRA_CONNECTED, connected)
            }
        sendBroadcast(intent)
    }

    private fun broadcastMockLocationStatus(message: String) {
        val intent =
            Intent(ACTION_MOCK_LOCATION_STATUS).apply {
                putExtra(EXTRA_MESSAGE, message)
            }
        sendBroadcast(intent)
    }

    private fun broadcastDeviceSettings() {
        val intent = Intent(ACTION_DEVICE_SETTINGS_CHANGED)
        intent.putExtra(EXTRA_AP_CONTROL_KNOWN, apControlEnabled != null)
        intent.putExtra(EXTRA_BRIDGE_MODE_KNOWN, bridgeModeEnabled != null)
        intent.putExtra(EXTRA_GPS_BAUD_KNOWN, gpsBaudRate != null)
        intent.putExtra(EXTRA_GNSS_PROFILE_KNOWN, gnssProfile != null)
        intent.putExtra(EXTRA_BASE_SETTINGS_PROFILE_KNOWN, baseSettingsProfile != null)
        intent.putExtra(EXTRA_CUSTOM_GNSS_PROFILE_KNOWN, customGnssProfileFrame != null)
        intent.putExtra(EXTRA_CUSTOM_BASE_SETTINGS_KNOWN, customBaseSettingsFrame != null)
        intent.putExtra(EXTRA_DEVICE_VERSION_KNOWN, deviceVersion != null)
        intent.putExtra(EXTRA_INPUT_VOLTAGE_KNOWN, inputVoltage != null)
        apControlEnabled?.let { intent.putExtra(EXTRA_AP_CONTROL_ENABLED, it) }
        bridgeModeEnabled?.let { intent.putExtra(EXTRA_BRIDGE_MODE_ENABLED, it) }
        gpsBaudRate?.let { intent.putExtra(EXTRA_GPS_BAUD_RATE, it) }
        gnssProfile?.let { intent.putExtra(EXTRA_GNSS_PROFILE, it) }
        baseSettingsProfile?.let { intent.putExtra(EXTRA_BASE_SETTINGS_PROFILE, it) }
        customGnssProfileFrame?.let { intent.putExtra(EXTRA_CUSTOM_GNSS_PROFILE_FRAME, it) }
        customBaseSettingsFrame?.let { intent.putExtra(EXTRA_CUSTOM_BASE_SETTINGS_FRAME, it) }
        deviceVersion?.let { intent.putExtra(EXTRA_DEVICE_VERSION, it) }
        inputVoltage?.let { intent.putExtra(EXTRA_INPUT_VOLTAGE, it) }
        intent.putExtra(EXTRA_AP_SSID_HINT, STATIC_AP_SSID)
        sendBroadcast(intent)
    }

    private fun updateNotification() {
        val ageSeconds =
            if (lastUpdateTime > 0) {
                (System.currentTimeMillis() - lastUpdateTime) / 1000.0
            } else {
                null
            }
        val notification =
            NotificationUtils.buildStatusNotification(
                this,
                isConnected,
                ageSeconds
            )
        notificationManager.notify(NotificationUtils.NOTIFICATION_ID, notification)
    }

    private fun requestDeviceSettingsRead() {
        if (!isConnected) {
            Log.d(TAG, "Skipping device settings read: BLE not connected")
            return
        }
        readDeviceSetting(BleUuids.CHAR_AP_CONTROL_UUID)
        handler.postDelayed(
            { readDeviceSetting(BleUuids.CHAR_MODE_CONTROL_UUID) },
            200
        )
        handler.postDelayed(
            { readDeviceSetting(BleUuids.CHAR_GPS_BAUD_UUID) },
            400
        )
        handler.postDelayed(
            { readDeviceSetting(BleUuids.CHAR_GNSS_PROFILE_UUID) },
            600
        )
        handler.postDelayed(
            { readDeviceSetting(BleUuids.CHAR_BASE_SETTINGS_PROFILE_UUID) },
            800
        )
        handler.postDelayed(
            { readDeviceSetting(BleUuids.CHAR_CUSTOM_GNSS_PROFILE_UUID) },
            1000
        )
        handler.postDelayed(
            { readDeviceSetting(BleUuids.CHAR_CUSTOM_BASE_SETTINGS_UUID) },
            1200
        )
        handler.postDelayed(
            { readDeviceSetting(BleUuids.CHAR_DEVICE_VERSION_UUID) },
            1400
        )
        handler.postDelayed(
            { readDeviceSetting(BleUuids.CHAR_INPUT_VOLTAGE_UUID) },
            1600
        )
    }

    private fun readDeviceSetting(uuid: UUID, attempt: Int = 0) {
        val manager = connectionManager ?: return
        if (!isConnected) {
            return
        }
        val success = manager.readCharacteristic(uuid)
        if (!success && attempt < DEVICE_SETTING_READ_RETRY_MAX) {
            handler.postDelayed(
                { readDeviceSetting(uuid, attempt + 1) },
                DEVICE_SETTING_READ_RETRY_DELAY_MS
            )
        } else if (!success) {
            Log.w(TAG, "Failed to read $uuid after ${attempt + 1} attempts")
        }
    }

    fun requestOtaPortal(enable: Boolean): Boolean {
        if (!isConnected) {
            updateOtaPortalState(message = getString(R.string.ota_error_not_connected))
            return false
        }
        val manager = connectionManager ?: return false
        otaGuardPending = true
        val payload = if (enable) "1" else "0"
        val enqueued = manager.writeCharacteristic(BleUuids.CHAR_OTA_CONTROL_UUID, payload)
        if (!enqueued) {
            otaGuardPending = false
            updateOtaPortalState(message = getString(R.string.ota_guard_write_failed))
            return false
        }
        val pendingMessage =
            if (enable) getString(R.string.ota_status_request_open) else getString(R.string.ota_status_request_close)
        updateOtaPortalState(message = pendingMessage)
        handler.postDelayed({ refreshOtaPortalState() }, 500)
        return true
    }

    fun refreshOtaPortalState() {
        val manager = connectionManager ?: return
        if (!isConnected) return
        manager.readCharacteristic(BleUuids.CHAR_OTA_CONTROL_UUID)
        handler.postDelayed(
            { manager.readCharacteristic(BleUuids.CHAR_WIFI_STATUS_UUID) },
            250
        )
    }

    fun emitCurrentOtaPortalState() {
        broadcastOtaPortalState()
    }

    private fun startWifiStatusPolling(immediate: Boolean = false) {
        handler.removeCallbacks(wifiStatusPollRunnable)
        if (!isConnected) {
            return
        }
        val delayMillis = if (immediate) 0L else WIFI_STATUS_POLL_INTERVAL_MS
        handler.postDelayed(wifiStatusPollRunnable, delayMillis)
    }

    private fun stopWifiStatusPolling() {
        handler.removeCallbacks(wifiStatusPollRunnable)
    }

    private fun updateOtaPortalState(
        enabled: Boolean? = null,
        wifiStatus: String? = null,
        ip: String? = null,
        message: String? = null
    ) {
        val updated =
            otaPortalState.copy(
                enabled = enabled ?: otaPortalState.enabled,
                wifiStatus = wifiStatus ?: otaPortalState.wifiStatus,
                ip = ip ?: otaPortalState.ip,
                message = message ?: otaPortalState.message
            )
        otaPortalState = updated
        broadcastOtaPortalState(message ?: updated.message)
    }

    private fun broadcastOtaPortalState(message: String? = otaPortalState.message) {
        val current = otaPortalState.copy(message = message ?: otaPortalState.message)
        val intent =
            Intent(ACTION_OTA_STATUS).apply {
                putExtra(EXTRA_OTA_STATE, if (current.enabled) "enabled" else "disabled")
                putExtra(EXTRA_OTA_GUARD_ENABLED, current.enabled)
                putExtra(EXTRA_OTA_WIFI_STATE, current.wifiStatus)
                current.ip?.let { putExtra(EXTRA_OTA_WIFI_IP, it) }
                current.portalUrl?.let { putExtra(EXTRA_OTA_PORTAL_URL, it) }
                putExtra(
                    EXTRA_OTA_PORTAL_FALLBACK,
                    "http://$OTA_AP_FALLBACK_HOST$OTA_PORTAL_PATH"
                )
                message?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_OTA_MESSAGE, it) }
            }
        sendBroadcast(intent)
    }

    private fun resetOtaPortalState(reasonMessage: String? = null) {
        otaGuardPending = false
        otaPortalState = OtaPortalState(message = reasonMessage)
        broadcastOtaPortalState(reasonMessage)
    }

    private fun hasMockLocationPermission(): Boolean {
        val fineGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarseGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    fun isConnectedToServer(): Boolean = isConnected

    fun getLastReceivedLocation(): Location? = lastReceivedLocation

    fun getLastUpdateTime(): Long = lastUpdateTime

    fun isMockLocationEnabled(): Boolean {
        return try {
            Settings.Secure.getString(contentResolver, "mock_location") != null
        } catch (error: Exception) {
            Log.e(TAG, "Mock location setting lookup failed", error)
            false
        }
    }

    // region BleScanListener
    override fun onDeviceFound(device: BluetoothDevice) {
        Log.d(TAG, "BLE device found: ${device.address}")
    }

    override fun onScanFailed(errorCode: Int) {
        Log.w(TAG, "BLE scan failed: $errorCode")
        if (AppPrefs.isMockEnabled(this) && !isConnected) {
            scheduleRescan()
        }
    }

    override fun onScanStopped(foundDevice: Boolean) {
        Log.d(TAG, "BLE scan stopped. Device found: $foundDevice")
        if (!foundDevice && AppPrefs.isMockEnabled(this) && !isConnected) {
            scheduleRescan()
        }
    }
    // endregion

    // region BleConnectionDataListener
    override fun onConnecting(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to BLE device ${device.address}")
        broadcastConnectionState(false)
    }

    override fun onConnected(device: BluetoothDevice) {
        Log.i(TAG, "Connected to BLE device ${device.address}")
        isConnected = true
        handler.removeCallbacks(rescanRunnable)
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire()
            }
        }
        connectionManager?.pollTelemetry()
        startReceivingLocationUpdates()
        broadcastConnectionState(true)
        broadcastDeviceSettings()
        requestDeviceSettingsRead()
        refreshOtaPortalState()
        startWifiStatusPolling(immediate = true)
        updateNotification()
    }

    override fun onDisconnected(device: BluetoothDevice) {
        Log.i(TAG, "Disconnected from BLE device ${device.address}")
        isConnected = false
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        resetOtaPortalState(getString(R.string.ota_status_closed))
        stopWifiStatusPolling()
        stopReceivingLocationUpdates()
        broadcastConnectionState(false)
        handler.removeCallbacks(idleMovementRunnable)
        hasReceivedModuleFix = false
        lastRealUpdateTimestamp = 0L
        lastRealLocation = null
        apControlEnabled = null
        bridgeModeEnabled = null
        gpsBaudRate = null
        gnssProfile = null
        baseSettingsProfile = null
        customGnssProfileFrame = null
        customBaseSettingsFrame = null
        deviceVersion = null
        inputVoltage = null
        broadcastDeviceSettings()
        updateNotification()
        if (AppPrefs.isMockEnabled(this)) {
            scheduleRescan()
        }
    }

    override fun onServicesDiscovered(device: BluetoothDevice) {
        Log.d(TAG, "Services discovered on ${device.address}")
        connectionManager?.startKeepAlive()
        requestDeviceSettingsRead()
        refreshOtaPortalState()
        startWifiStatusPolling(immediate = true)
    }

    override fun onError(message: String) {
        Log.e(TAG, "BLE error: $message")
    }

    override fun onCoordinatesReceived(latitude: Double, longitude: Double) {
        val currentMillis = System.currentTimeMillis()
        val deltaMillis =
            if (lastCoordinatesTimestamp > 0L) {
                currentMillis - lastCoordinatesTimestamp
            } else {
                null
            }
        Log.d(
            TAG,
            "BLE coordinates lat=$latitude lon=$longitude deltaMillis=${deltaMillis ?: "n/a"}"
        )
        handler.removeCallbacks(idleMovementRunnable)
        val location = handleLocationUpdate(latitude, longitude, synthetic = false)
        if (location != null) {
            hasReceivedModuleFix = true
            lastRealUpdateTimestamp = currentMillis
            lastRealLocation = Location(location)
        }
        scheduleIdleMovement()
    }

    override fun onFixStatusReceived(status: String) {
        Log.d(TAG, "Fix status: $status")
    }

    override fun onHdopReceived(hdop: Double) {
        this.hdop = hdop
    }

    override fun onSignalLevelsReceived(levels: String) {
        signalLevels = levels
    }

    override fun onAltitudeReceived(altitudeMeters: Double) {
        this.altitudeMeters = altitudeMeters
    }

    override fun onSpeedReceived(speedMetersPerSecond: Double) {
        this.speedMetersPerSecond = speedMetersPerSecond
    }

    override fun onHeadingReceived(headingDegrees: Double) {
        this.headingDegrees = headingDegrees
    }

    override fun onDeviceStatusReceived(status: String) {
        Log.d(TAG, "Device status: $status")
    }

    override fun onApControlChanged(enabled: Boolean) {
        val previous = apControlEnabled
        apControlEnabled = enabled
        if (previous != enabled) {
            Log.i(TAG, "AP control state updated to $enabled")
        }
        broadcastDeviceSettings()
    }

    override fun onBridgeModeChanged(enabled: Boolean) {
        val previous = bridgeModeEnabled
        bridgeModeEnabled = enabled
        if (previous != enabled) {
            Log.i(TAG, "Bridge mode state updated to $enabled")
        }
        broadcastDeviceSettings()
    }

    override fun onGpsBaudRateChanged(baudRate: Int) {
        val sanitized = baudRate.coerceIn(GPS_BAUD_MIN, GPS_BAUD_MAX)
        val previous = gpsBaudRate
        gpsBaudRate = sanitized
        if (previous != sanitized) {
            if (sanitized != baudRate) {
                Log.w(TAG, "GPS baud rate updated with out-of-range value $baudRate, using $sanitized")
            } else {
                Log.i(TAG, "GPS baud rate updated to $sanitized")
            }
            broadcastDeviceSettings()
        } else if (previous == null) {
            broadcastDeviceSettings()
        }
    }

    override fun onGnssProfileChanged(profile: Int) {
        val previous = gnssProfile
        val supported = GNSS_PROFILE_VALUES.contains(profile)
        gnssProfile = profile
        if (previous != gnssProfile) {
            if (!supported) {
                Log.w(TAG, "GNSS profile $profile not in supported set")
            } else {
                Log.i(TAG, "GNSS profile updated to $profile")
            }
            broadcastDeviceSettings()
        } else if (previous == null) {
            broadcastDeviceSettings()
        }
    }

    override fun onBaseSettingsProfileChanged(profile: Int) {
        val previous = baseSettingsProfile
        val supported = BASE_SETTINGS_PROFILE_VALUES.contains(profile)
        baseSettingsProfile = profile
        if (previous != baseSettingsProfile) {
            if (!supported) {
                Log.w(TAG, "Base settings profile $profile not in supported set")
            } else {
                Log.i(TAG, "Base settings profile updated to $profile")
            }
            broadcastDeviceSettings()
        } else if (previous == null) {
            broadcastDeviceSettings()
        }
    }

    override fun onCustomGnssProfileFrameChanged(frame: String) {
        customGnssProfileFrame = frame
        broadcastDeviceSettings()
    }

    override fun onCustomBaseSettingsFrameChanged(frame: String) {
        customBaseSettingsFrame = frame
        broadcastDeviceSettings()
    }

    override fun onOtaStatusReceived(status: String) {
        Log.d(TAG, "OTA status (legacy): $status")
    }

    override fun onOtaGuardStateChanged(enabled: Boolean) {
        otaGuardPending = false
        val message =
            if (enabled) getString(R.string.ota_status_open) else getString(R.string.ota_status_closed)
        updateOtaPortalState(enabled = enabled, message = message)
        if (enabled) {
            handler.postDelayed({ refreshOtaPortalState() }, 300)
        }
    }

    override fun onWifiStatusReceived(status: String, ip: String?) {
        val normalized =
            when (status.lowercase()) {
                "connected" -> "connected"
                "connecting" -> "connecting"
                "disconnected" -> "disconnected"
                else -> "unknown"
            }
        updateOtaPortalState(wifiStatus = normalized, ip = ip)
    }

    override fun onInputVoltageReceived(voltage: Double) {
        inputVoltage = voltage
        broadcastDeviceSettings()
    }

    override fun onDeviceVersionReceived(version: String) {
        deviceVersion = version
        broadcastDeviceSettings()
    }

    override fun onTtffReceived(ttffSeconds: Long) {
        this.ttffSeconds = ttffSeconds
    }
    // endregion

    companion object {
        const val ACTION_CONNECTION_CHANGED =
            "com.g992.blegpsmocker.CONNECTION_CHANGED"
        const val ACTION_LOCATION_UPDATE =
            "com.g992.blegpsmocker.LOCATION_UPDATE"
        const val ACTION_MOCK_LOCATION_STATUS =
            "com.g992.blegpsmocker.MOCK_LOCATION_STATUS"
        const val ACTION_DEVICE_SETTINGS_CHANGED =
            "com.g992.blegpsmocker.DEVICE_SETTINGS_CHANGED"

        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_SATELLITES = "satellites"
        const val EXTRA_SATELLITES_STRONG = "satellites_strong"
        const val EXTRA_SATELLITES_MEDIUM = "satellites_medium"
        const val EXTRA_SATELLITES_WEAK = "satellites_weak"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_LOCATION_AGE = "locationAge"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_AP_CONTROL_ENABLED = "ap_control_enabled"
        const val EXTRA_BRIDGE_MODE_ENABLED = "bridge_mode_enabled"
        const val EXTRA_AP_SSID_HINT = "ap_ssid_hint"
        const val EXTRA_AP_CONTROL_KNOWN = "ap_control_known"
        const val EXTRA_BRIDGE_MODE_KNOWN = "bridge_mode_known"
        const val EXTRA_GPS_BAUD_RATE = "gps_baud_rate"
        const val EXTRA_GPS_BAUD_KNOWN = "gps_baud_known"
        const val EXTRA_GNSS_PROFILE = "gnss_profile"
        const val EXTRA_GNSS_PROFILE_KNOWN = "gnss_profile_known"
        const val EXTRA_BASE_SETTINGS_PROFILE = "base_settings_profile"
        const val EXTRA_BASE_SETTINGS_PROFILE_KNOWN = "base_settings_profile_known"
        const val EXTRA_CUSTOM_GNSS_PROFILE_FRAME = "custom_gnss_profile_frame"
        const val EXTRA_CUSTOM_GNSS_PROFILE_KNOWN = "custom_gnss_profile_known"
        const val EXTRA_CUSTOM_BASE_SETTINGS_FRAME = "custom_base_settings_frame"
        const val EXTRA_CUSTOM_BASE_SETTINGS_KNOWN = "custom_base_settings_known"
        const val EXTRA_DEVICE_VERSION = "device_version"
        const val EXTRA_DEVICE_VERSION_KNOWN = "device_version_known"
        const val EXTRA_INPUT_VOLTAGE = "input_voltage"
        const val EXTRA_INPUT_VOLTAGE_KNOWN = "input_voltage_known"
        const val ACTION_OTA_STATUS = "com.g992.blegpsmocker.OTA_STATUS"
        const val EXTRA_OTA_STATE = "ota_state"
        const val EXTRA_OTA_MESSAGE = "ota_message"
        const val EXTRA_OTA_GUARD_ENABLED = "ota_guard_enabled"
        const val EXTRA_OTA_WIFI_STATE = "ota_wifi_state"
        const val EXTRA_OTA_WIFI_IP = "ota_wifi_ip"
        const val EXTRA_OTA_PORTAL_URL = "ota_portal_url"
        const val EXTRA_OTA_PORTAL_FALLBACK = "ota_portal_fallback"

        private val providerCandidates = listOf(LocationManager.GPS_PROVIDER)

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

    private data class SatelliteSignalBreakdown(
        val strong: Int = 0,
        val medium: Int = 0,
        val weak: Int = 0
    ) {
        val total: Int
            get() = strong + medium + weak
    }
}
