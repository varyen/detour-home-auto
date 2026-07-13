package com.vtools.wghome

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Действия, ждущие результата запроса разрешения/согласия.
private const val ACTION_NONE = 0
private const val ACTION_ENABLE = 1
private const val ACTION_TEST = 2

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors: ColorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.get(context) }
    val scope = rememberCoroutineScope()

    val automationEnabled by settings.automationEnabled.collectAsState()
    val tunnelName by settings.tunnelName.collectAsState()
    val configText by settings.configText.collectAsState()
    val homeSsids by settings.homeSsids.collectAsState()
    val strictMode by settings.strictWeakSignal.collectAsState()
    val status by AppState.status.collectAsState()

    var detectedSsid by remember { mutableStateOf(WifiUtils.currentSsid(context)) }
    var batteryOk by remember { mutableStateOf(isIgnoringBatteryOpt(context)) }
    var pendingAction by remember { mutableStateOf(ACTION_NONE) }
    val hasConfig = !configText.isNullOrBlank()

    val refresh = {
        detectedSsid = WifiUtils.currentSsid(context)
        batteryOk = isIgnoringBatteryOpt(context)
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // До-запуск сервиса при открытии, если авторежим включён, а служба не работает.
    LaunchedEffect(Unit) {
        if (settings.automationEnabled.value &&
            WifiUtils.hasLocationPermission(context) &&
            !AppState.status.value.serviceRunning
        ) {
            MonitorService.start(context)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val startMonitoring = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        settings.setAutomationEnabled(true)
        MonitorService.start(context)
        pendingAction = ACTION_NONE
        refresh()
    }

    val runTest = {
        pendingAction = ACTION_NONE
        val text = settings.configText.value
        if (text.isNullOrBlank()) {
            toast("Импортируйте конфиг WireGuard")
        } else {
            scope.launch(Dispatchers.IO) {
                try {
                    TunnelController.tunnelName = settings.tunnelName.value
                    TunnelController.up(context, TunnelController.parseConfig(text))
                    withContext(Dispatchers.Main) { toast("Туннель поднят — смотрите статус VPN") }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { toast("Ошибка: ${TunnelController.describeError(e)}") }
                }
            }
            Unit
        }
    }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val ok = TunnelController.isAuthorized(context)
        when (pendingAction) {
            ACTION_ENABLE -> if (ok) startMonitoring() else { toast("Согласие на VPN не дано"); pendingAction = ACTION_NONE }
            ACTION_TEST -> if (ok) runTest() else { toast("Согласие на VPN не дано"); pendingAction = ACTION_NONE }
        }
        refresh()
    }

    val permsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (pendingAction == ACTION_ENABLE) {
            if (WifiUtils.hasLocationPermission(context)) {
                val consent = TunnelController.prepareIntent(context)
                if (consent != null) vpnConsentLauncher.launch(consent) else startMonitoring()
            } else {
                toast("Нужен точный доступ к геолокации, чтобы определять Wi-Fi сеть")
                pendingAction = ACTION_NONE
            }
        }
        refresh()
    }

    val configImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                TunnelController.parseConfig(text) // валидация — бросит, если конфиг битый
                val name = queryDisplayName(context, uri)
                settings.setConfig(text, name)
                toast("Конфиг загружен: $name")
            } catch (e: Exception) {
                toast("Не удалось прочитать конфиг: ${e.message}")
            }
        }
        refresh()
    }

    val onToggle = { enable: Boolean ->
        if (enable) {
            pendingAction = ACTION_ENABLE
            when {
                !settings.hasConfig() -> {
                    toast("Сначала импортируйте конфиг WireGuard (.conf)")
                    pendingAction = ACTION_NONE
                }
                !WifiUtils.hasLocationPermission(context) ->
                    permsLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                else -> {
                    val consent = TunnelController.prepareIntent(context)
                    if (consent != null) vpnConsentLauncher.launch(consent) else startMonitoring()
                }
            }
        } else {
            settings.setAutomationEnabled(false)
            MonitorService.stop(context)
            pendingAction = ACTION_NONE
            scope.launch(Dispatchers.IO) { try { TunnelController.down(context) } catch (_: Exception) {} }
            Unit
        }
    }

    val onTest = {
        if (!settings.hasConfig()) {
            toast("Импортируйте конфиг WireGuard")
        } else {
            val consent = TunnelController.prepareIntent(context)
            if (consent != null) {
                pendingAction = ACTION_TEST
                vpnConsentLauncher.launch(consent)
            } else {
                runTest()
            }
        }
    }

    val onAddCurrent = {
        val ssid = WifiUtils.currentSsid(context)
        when {
            !WifiUtils.hasLocationPermission(context) -> {
                pendingAction = ACTION_NONE
                permsLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
            ssid == null -> toast("Сейчас нет подключения к Wi-Fi")
            homeSsids.contains(ssid) -> toast("Сеть \"$ssid\" уже в списке")
            else -> {
                settings.addSsid(ssid)
                toast("Добавлена домашняя сеть: $ssid")
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("WG Home Auto") }) }) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!hasConfig) {
                WarningCard("Импортируйте конфиг WireGuard (.conf) — без него включать нечего.")
            }
            if (!batteryOk) {
                WarningCard(
                    "Для надёжной работы в фоне отключите оптимизацию батареи для приложения " +
                        "(кнопка ниже)."
                )
            }

            MasterToggleCard(enabled = automationEnabled, onToggle = onToggle)

            StrictModeCard(enabled = strictMode, onChange = { settings.setStrictWeakSignal(it) })

            StatusCard(
                serviceRunning = status.serviceRunning,
                automationEnabled = automationEnabled,
                currentSsid = if (status.serviceRunning) status.currentSsid else detectedSsid,
                isHome = if (status.serviceRunning) status.isHomeNetwork
                else (detectedSsid != null && homeSsids.contains(detectedSsid)),
                desiredVpnUp = status.desiredVpnUp,
                vpnActive = status.vpnActive,
                signalDbm = status.signalDbm,
                lastAction = status.lastActionText
            )

            ConfigCard(
                hasConfig = hasConfig,
                tunnelName = tunnelName,
                onImport = { configImportLauncher.launch(arrayOf("*/*")) },
                onTest = { onTest() }
            )

            HomeNetworksCard(
                ssids = homeSsids,
                detectedSsid = detectedSsid,
                onAddCurrent = { onAddCurrent() },
                onRemove = { settings.removeSsid(it) }
            )

            if (!batteryOk) {
                Button(
                    onClick = {
                        val i = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        runCatching { context.startActivity(i) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Отключить оптимизацию батареи") }
            }

            OutlinedButton(
                onClick = {
                    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    runCatching { context.startActivity(i) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Разрешения приложения") }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MasterToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Автоматический режим", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Выключать VPN дома и включать вне дома",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun StrictModeCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Защита от утечек", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Дома включать VPN, если сигнал слабый или Wi-Fi без интернета. " +
                        "Сокращает окно незащищённого трафика.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun StatusCard(
    serviceRunning: Boolean,
    automationEnabled: Boolean,
    currentSsid: String?,
    isHome: Boolean,
    desiredVpnUp: Boolean,
    vpnActive: Boolean,
    signalDbm: Int?,
    lastAction: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Статус", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)

            StatusRow(
                icon = if (currentSsid != null) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                label = "Текущая сеть",
                value = currentSsid ?: "не подключено к Wi-Fi"
            )
            StatusRow(
                icon = if (isHome) Icons.Filled.Home else Icons.Filled.Warning,
                label = "Домашняя сеть",
                value = if (isHome) "да" else "нет"
            )
            if (signalDbm != null) {
                StatusRow(icon = Icons.Filled.Wifi, label = "Сигнал дома", value = "$signalDbm dBm")
            }
            StatusRow(
                icon = if (vpnActive) Icons.Filled.Lock else Icons.Filled.WifiOff,
                label = "VPN сейчас",
                value = if (vpnActive) "активен" else "выключен"
            )
            StatusRow(
                icon = if (desiredVpnUp) Icons.Filled.CheckCircle else Icons.Filled.WifiOff,
                label = "Целевое состояние VPN",
                value = if (desiredVpnUp) "включён" else "выключен"
            )
            StatusRow(
                icon = if (serviceRunning) Icons.Filled.CheckCircle else Icons.Filled.WifiOff,
                label = "Служба мониторинга",
                value = if (serviceRunning) "работает" else if (automationEnabled) "запускается…" else "остановлена"
            )
            if (serviceRunning) {
                Text(
                    "Последнее действие: $lastAction",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text("$label:", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConfigCard(
    hasConfig: Boolean,
    tunnelName: String,
    onImport: () -> Unit,
    onTest: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Конфигурация WireGuard", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(
                if (hasConfig) "Загружен туннель: $tunnelName"
                else "Импортируйте файл .conf вашего туннеля WireGuard. VPN поднимает само приложение — " +
                    "официальный WireGuard больше не нужен.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (hasConfig) "Заменить конфиг (.conf)" else "Импортировать конфиг (.conf)")
            }
            if (hasConfig) {
                OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Проверить (поднять туннель)")
                }
            }
        }
    }
}

@Composable
private fun HomeNetworksCard(
    ssids: Set<String>,
    detectedSsid: String?,
    onAddCurrent: () -> Unit,
    onRemove: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Домашние сети", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(
                "В этих сетях VPN будет выключен",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (ssids.isEmpty()) {
                Text(
                    "Список пуст. Подключитесь к домашней Wi-Fi и нажмите кнопку ниже.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ssids.forEach { ssid ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            ssid,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        IconButton(onClick = { onRemove(ssid) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Button(onClick = onAddCurrent, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                val label = detectedSsid?.let { "Добавить текущую: $it" } ?: "Добавить текущую сеть"
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun isIgnoringBatteryOpt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/** Имя выбранного файла (для отображения и имени интерфейса), без расширения .conf. */
private fun queryDisplayName(context: Context, uri: Uri): String {
    var name = "wghome"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
    }
    return name.removeSuffix(".conf")
}
