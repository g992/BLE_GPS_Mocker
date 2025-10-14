package com.g992.blegpsmocker

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.g992.blegpsmocker.ui.theme.BLEGPSMockerTheme
import com.g992.blegpsmocker.ui.theme.SuccessColor
import com.g992.blegpsmocker.ui.theme.WarningColor
import java.util.Locale

class MainActivity : ComponentActivity() {

    @Suppress("InvalidFragmentVersionForActivityResult")
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionsState()
        }

    @Suppress("InvalidFragmentVersionForActivityResult")
    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val bleTag = "MainActivity"
    private val bleConnected = mutableStateOf(false)
    private val bleScanning = mutableStateOf(false)
    private val permissionsGranted = mutableStateOf(false)

    private val bleStatusReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != AutoBleService.ACTION_BLE_STATUS) return

                val connected = intent.getBooleanExtra(AutoBleService.EXTRA_BLE_CONNECTED, false)
                val scanning = intent.getBooleanExtra(AutoBleService.EXTRA_BLE_SCANNING, false)
                bleConnected.value = connected
                bleScanning.value = scanning
                Log.d(bleTag, "BLE status: connected=$connected, scanning=$scanning")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissionsState()

        val bleFilter = IntentFilter(AutoBleService.ACTION_BLE_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bleStatusReceiver, bleFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bleStatusReceiver, bleFilter)
        }

        try {
            val bleStatusIntent = Intent(this, AutoBleService::class.java).apply {
                action = AutoBleService.ACTION_BLE_STATUS_REQUEST
            }
            ContextCompat.startForegroundService(this, bleStatusIntent)
        } catch (_: Exception) {
        }

        try {
            if (AppPrefs.isMockEnabled(this)) {
                val intent = Intent(this, AutoBleService::class.java).apply {
                    action = AutoBleService.ACTION_APPLY_PREFS
                }
                ContextCompat.startForegroundService(this, intent)
            }
        } catch (_: Exception) {
        }

        setContent {
            BLEGPSMockerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onStartService = { startMockService() },
                        onStopService = { stopMockService() },
                        onRequestPermissions = { requestRequiredPermissions() },
                        hasPermissions = { permissionsGranted.value },
                        isMockProviderEnabled = { canMockLocations() },
                        isBleConnected = { bleConnected.value },
                        isBleScanning = { bleScanning.value },
                        ensureBleReady = { ensureBleReady() }
                    )
                }
            }
        }
    }

    private fun startMockService() {
        AppPrefs.setMockEnabled(this, true)
        val intent = Intent(this, AutoBleService::class.java).apply {
            action = AutoBleService.ACTION_START_MOCK
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopMockService() {
        AppPrefs.setMockEnabled(this, false)
        val intent = Intent(this, AutoBleService::class.java).apply {
            action = AutoBleService.ACTION_STOP_MOCK
        }
        stopService(intent)
    }

    private fun requestRequiredPermissions() {
        val permissions =
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun refreshPermissionsState() {
        permissionsGranted.value = hasLocationPermissions()
    }

    private fun hasLocationPermissions(): Boolean {
        val fine =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarse =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        return adapter?.isEnabled == true
    }

    private fun requestEnableBluetooth() {
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(intent)
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionsState()
    }

    private fun ensureBleReady(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasBlePermissions =
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                    .all { perm ->
                        ContextCompat.checkSelfPermission(this, perm) ==
                            PackageManager.PERMISSION_GRANTED
                    }
            if (!hasBlePermissions) {
                requestRequiredPermissions()
                return false
            }
        }
        if (!isBluetoothEnabled()) {
            requestEnableBluetooth()
            return false
        }
        return true
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(bleStatusReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun canMockLocations(): Boolean {
        return try {
            val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            manager.addTestProvider(
                LocationManager.GPS_PROVIDER,
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
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            true
        } catch (_: Exception) {
            false
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestPermissions: () -> Unit,
    hasPermissions: () -> Boolean,
    isMockProviderEnabled: () -> Boolean,
    isBleConnected: () -> Boolean,
    isBleScanning: () -> Boolean,
    ensureBleReady: () -> Boolean
) {
    var statusText by remember { mutableStateOf("Остановлен") }
    var mockStatus by remember { mutableStateOf("Нет данных") }
    var lastPoint by remember { mutableStateOf("—") }
    var isRunning by remember { mutableStateOf(false) }
    var signalPercent by remember { mutableStateOf("—") }
    var satellites by remember { mutableStateOf("—") }
    var fixTypeText by remember { mutableStateOf("—") }
    var ttffText by remember { mutableStateOf("—") }
    var q1Count by remember { mutableStateOf(0) }
    var q2Count by remember { mutableStateOf(0) }
    var q3Count by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val configuration = LocalConfiguration.current
    val isLargeScreen =
        configuration.screenWidthDp >= 600 || configuration.screenHeightDp >= 600

    val basePadding = if (isLargeScreen) 32.dp else 16.dp
    val spacerSmall = if (isLargeScreen) 12.dp else 8.dp
    val spacerMedium = if (isLargeScreen) 24.dp else 16.dp
    val buttonHeight = if (isLargeScreen) 64.dp else 48.dp
    val textSizeNormal = if (isLargeScreen) 20.sp else 16.sp
    val textSizeSmall = if (isLargeScreen) 18.sp else 14.sp

    LaunchedEffect(Unit) {
        isRunning = AppPrefs.isMockEnabled(context)
        try {
            val intent = Intent(context, AutoBleService::class.java).apply {
                action = AutoBleService.ACTION_APPLY_PREFS
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
        }
    }

    DisposableEffect(Unit) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != AutoBleService.ACTION_STATUS) return

                    if (intent.hasExtra(AutoBleService.EXTRA_RUNNING)) {
                        val running = intent.getBooleanExtra(AutoBleService.EXTRA_RUNNING, false)
                        isRunning = running
                        statusText = if (running) "Активен" else "Остановлен"
                    }
                    val lat = intent.getDoubleExtra(AutoBleService.EXTRA_LAT, Double.NaN)
                    val lon = intent.getDoubleExtra(AutoBleService.EXTRA_LON, Double.NaN)
                    val error = intent.getStringExtra(AutoBleService.EXTRA_ERROR)
                    if (!error.isNullOrEmpty()) {
                        mockStatus = error
                    } else if (!lat.isNaN() && !lon.isNaN()) {
                        lastPoint = "${lat.format(6)}, ${lon.format(6)}"
                        mockStatus = if (isRunning) "Работает" else "Нет данных"
                    }

                    val percent = intent.getIntExtra(AutoBleService.EXTRA_SIGNAL_PERCENT, -1)
                    if (percent >= 0) {
                        signalPercent = "Сигнал: ${percent}%"
                    }
                    val sat = intent.getIntExtra(AutoBleService.EXTRA_SAT_COUNT, -1)
                    if (sat >= 0) {
                        satellites = "Спутников: ${sat}"
                    }
                    intent.getStringExtra(AutoBleService.EXTRA_SIGNAL_LEVELS)?.let { raw ->
                        val parts =
                            raw.split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                        q1Count = parts.count { it == "1" }
                        q2Count = parts.count { it == "2" }
                        q3Count = parts.count { it == "3" }
                    }
                    val fixType = intent.getIntExtra(AutoBleService.EXTRA_FIX_TYPE, -1)
                    if (fixType >= 0) {
                        val fixLabel =
                            when (fixType) {
                                0 -> "нет"
                                1 -> "2D"
                                2 -> "3D"
                                else -> fixType.toString()
                            }
                        fixTypeText = "Тип фиксации: $fixLabel"
                    }
                    if (intent.hasExtra(AutoBleService.EXTRA_TTFF)) {
                        val ttff = intent.getLongExtra(AutoBleService.EXTRA_TTFF, -1L)
                        if (ttff >= 0L) {
                            ttffText = "Время до первой фиксации: ${ttff} сек"
                        }
                    }
                }
            }
        val filter = IntentFilter(AutoBleService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    val permissionsOk = hasPermissions()
    val mockOk = isMockProviderEnabled()

    Column(
        modifier = modifier.fillMaxSize().padding(basePadding),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        val okGreen = SuccessColor
        val warnOrange = WarningColor
        val badRed = MaterialTheme.colorScheme.error
        val serviceColor = if (isRunning) okGreen else badRed
        val bleIsConnected = isBleConnected()
        val bleIsScanning = isBleScanning()
        val bleColor =
            when {
                bleIsConnected -> okGreen
                bleIsScanning -> warnOrange
                else -> badRed
            }
        val mockColor = if (isRunning) okGreen else badRed

        Column(
            verticalArrangement = Arrangement.spacedBy(spacerSmall),
            horizontalAlignment = Alignment.Start
        ) {
            StatusChip(
                title = "Сервис",
                value = statusText,
                color = serviceColor,
                pulsing = false,
                textSize = textSizeNormal
            )
            val bleValue =
                when {
                    bleIsConnected -> "Подключено"
                    bleIsScanning -> "Сканирую"
                    else -> "Не подключено"
                }
            StatusChip(
                title = "BLE",
                value = bleValue,
                color = bleColor,
                pulsing = bleIsScanning && !bleIsConnected,
                textSize = textSizeNormal
            )
            StatusChip(
                title = "Мок",
                value = mockStatus,
                color = mockColor,
                pulsing = false,
                textSize = textSizeNormal
            )
        }
        Spacer(Modifier.height(spacerSmall))
        Text(text = "Последняя точка: $lastPoint", fontSize = textSizeNormal)
        Spacer(Modifier.height(spacerSmall))

        if (signalPercent != "—") {
            Text(text = signalPercent, fontSize = textSizeSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = satellites, fontSize = textSizeSmall)
            Spacer(Modifier.width(8.dp))
            SignalBadge(count = q1Count, color = badRed, textSize = textSizeSmall)
            Spacer(Modifier.width(4.dp))
            SignalBadge(count = q2Count, color = warnOrange, textSize = textSizeSmall)
            Spacer(Modifier.width(4.dp))
            SignalBadge(count = q3Count, color = okGreen, textSize = textSizeSmall)
        }
        Text(text = fixTypeText, fontSize = textSizeSmall)
        if (ttffText != "—") {
            Spacer(Modifier.height(4.dp))
            Text(text = ttffText, fontSize = textSizeSmall)
        }
        Spacer(Modifier.height(spacerMedium))

        val serviceButtonLabel = if (isRunning) "Стоп сервиса" else "Старт сервиса"
        Button(
            onClick = {
                if (!isRunning) {
                    when {
                        !permissionsOk -> {
                            onRequestPermissions()
                            mockStatus = "Нет разрешений на локацию"
                        }

                        !mockOk -> {
                            mockStatus = "Приложение не назначено как mock-провайдер"
                        }

                        else -> {
                            if (ensureBleReady()) {
                                onStartService()
                                isRunning = true
                                statusText = "Активен"
                                mockStatus = "Ожидаю координаты"
                            } else {
                                mockStatus = "Включите Bluetooth или выдайте BLE-разрешения"
                            }
                        }
                    }
                } else {
                    onStopService()
                    isRunning = false
                    statusText = "Остановлен"
                    mockStatus = "Нет данных"
                }
            },
            modifier = Modifier.fillMaxWidth().height(buttonHeight)
        ) {
            Text(serviceButtonLabel, fontSize = textSizeNormal)
        }

        if (!permissionsOk) {
            Spacer(Modifier.height(spacerSmall))
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth().height(buttonHeight)
            ) {
                Text("Запросить разрешения", fontSize = textSizeNormal)
            }
        }
    }
}

private fun Double.format(digits: Int): String =
    String.format(Locale.US, "% .${digits}f", this).trimStart()

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    BLEGPSMockerTheme {
        MainScreen(
            onStartService = {},
            onStopService = {},
            onRequestPermissions = {},
            hasPermissions = { true },
            isMockProviderEnabled = { true },
            isBleConnected = { false },
            isBleScanning = { false },
            ensureBleReady = { true }
        )
    }
}

@Composable
private fun StatusChip(
    title: String,
    value: String,
    color: Color,
    pulsing: Boolean,
    textSize: TextUnit = 16.sp
) {
    val backgroundColor by
        animateColorAsState(targetValue = color, animationSpec = tween(400), label = "bg")
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by
        if (pulsing) {
            transition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1.0f,
                animationSpec =
                    infiniteRepeatable(
                        tween(700, easing = FastOutSlowInEasing),
                        RepeatMode.Reverse
                    ),
                label = "alpha"
            )
        } else {
            remember { mutableStateOf(1f) }
        }
    Surface(color = backgroundColor.copy(alpha = alpha), shape = RoundedCornerShape(16.dp)) {
        Text(
            text = "$title: $value",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = textSize
        )
    }
}

@Composable
private fun SignalBadge(count: Int, color: Color, textSize: TextUnit = 14.sp) {
    if (count <= 0) return
    Surface(color = color, shape = RoundedCornerShape(12.dp)) {
        Text(
            text = "$count",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = textSize
        )
    }
}
/*
2025-10-14 15:50:52.290  2764-4053  GoogleApiManager        com.g992.blegpsmocker                W  The service for com.google.android.gms.internal.location.zzdz is not available: ConnectionResult{statusCode=SERVICE_INVALID, resolution=null, message=null}
2025-10-14 15:50:52.554  2764-4053  GooglePlayServicesUtil  com.g992.blegpsmocker                W  com.g992.blegpsmocker requires the Google Play Store, but it is missing.
2025-10-14 15:50:52.555  2764-4053  GoogleApiManager        com.g992.blegpsmocker                W  The service for com.google.android.gms.internal.location.zzdz is not available: ConnectionResult{statusCode=SERVICE_INVALID, resolution=null, message=null}
2025-10-14 15:50:52.822  2764-4053  GooglePlayServicesUtil  com.g992.blegpsmocker                W  com.g992.blegpsmocker requires the Google Play Store, but it is missing.
2025-10-14 15:50:52.823  2764-4053  GoogleApiManager        com.g992.blegpsmocker                W  The service for com.google.android.gms.internal.location.zzdz is not available: ConnectionResult{statusCode=SERVICE_INVALID, resolution=null, message=null}
2025-10-14 15:50:54.312  2764-4053  GooglePlayServicesUtil  com.g992.blegpsmocker                W  com.g992.blegpsmocker requires the Google Play Store, but it is missing.
2025-10-14 15:50:54.312  2764-4053  GoogleApiManager        com.g992.blegpsmocker                W  The service for com.google.android.gms.internal.location.zzdz is not available: ConnectionResult{statusCode=SERVICE_INVALID, resolution=null, message=null}
2025-10-14 15:50:54.539  2764-4053  GooglePlayServicesUtil  com.g992.blegpsmocker                W  com.g992.blegpsmocker requires the Google Play Store, but it is missing.
2025-10-14 15:50:54.539  2764-4053  GoogleApiManager        com.g992.blegpsmocker                W  The service for com.google.android.gms.internal.location.zzdz is not available: ConnectionResult{statusCode=SERVICE_INVALID, resolution=null, message=null}
2025-10-14 15:50:54.848  2764-4053  GooglePlayServicesUtil  com.g992.blegpsmocker                W  com.g992.blegpsmocker requires the Google Play Store, but it is missing.
2025-10-14 15:50:54.848  2764-4053  GoogleApiManager        com.g992.blegpsmocker                W  The service for com.google.android.gms.internal.location.zzdz is not available: ConnectionResult{statusCode=SERVICE_INVALID, resolution=null, message=null}
2025-10-14 15:50:56.838  2764-4053  GooglePlayServicesUtil  com.g992.blegpsmocker                W  com.g992.blegpsmocker requires the Google Play Store, but it is missing.
2025-10-14 15:50:56.838  2764-4053  GoogleApiManager        com.g992.blegpsmocker                W  The service for com.google.android.gms.internal.location.zzdz is not available: ConnectionResult{statusCode=SERVICE_INVALID, resolution=null, message=null}
2025-10-14 15:50:58.320  2764-4053  GooglePlayServicesUtil  com.g992.blegpsmocker                W  com.g992.blegpsmocker requires the Google Play Store, but it is missing.
2025-10-14 15:50:58.321  2764-4053  GoogleApiManager        com.g992.blegpsmocker                W  The service for com.google.android.gms.internal.location.zzdz is not available: ConnectionResult{statusCode=SERVICE_INVALID, resolution=null, message=null}
2025-10-14 15:50:58.817  2764-4053  GooglePlayServicesUtil  com.g992.blegpsmocker                W  com.g992.blegpsmocker requires the Google Play Store, but it is missing.
2025-10-14 15:50:58.818  2764-4053  GoogleApiManager        com.g992.blegpsmocker                W  The service for com.google.android.gms.internal.location.zzdz is not available: ConnectionResult{statusCode=SERVICE_INVALID, resolution=null, message=null}
2025-10-14 15:50:59.899  2764-4053  GooglePlayServicesUtil  com.g992.blegpsmocker                W  com.g992.blegpsmocker requires the Google Play Store, but it is missing.
2025-10-14 15:50:59.900  2764-4053  GoogleApiManager        com.g992.blegpsmocker                W  The service for com.google.android.gms.internal.location.zzdz is not available: ConnectionResult{statusCode=SERVICE_INVALID, resolution=null, message=null}

 */