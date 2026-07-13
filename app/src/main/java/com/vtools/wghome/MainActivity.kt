package com.vtools.wghome

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.OutlinedTextField
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

    val automationEnabled by settings.automationEnabled.collectAsState()
    val tunnelName by settings.tunnelName.collectAsState()
    val homeSsids by settings.homeSsids.collectAsState()
    val strictMode by settings.strictWeakSignal.collectAsState()
    val status by AppState.status.collectAsState()

    var detectedSsid by remember { mutableStateOf(WifiUtils.currentSsid(context)) }
    var controlGranted by remember { mutableStateOf(WireGuardController.hasControlPermission(context)) }
    var pendingEnable by remember { mutableStateOf(false) }
    val wgInstalled = remember { WireGuardController.isWireGuardInstalled(context) }

    val refresh = {
        detectedSsid = WifiUtils.currentSsid(context)
        controlGranted = WireGuardController.hasControlPermission(context)
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // При открытии приложения до-запускаем сервис, если авторежим включён, но служба не работает
    // (после переустановки/закрытия/гибели процесса BootReceiver не срабатывает).
    LaunchedEffect(Unit) {
        if (settings.automationEnabled.value &&
            WifiUtils.hasLocationPermission(context) &&
            !AppState.status.value.serviceRunning
        ) {
            MonitorService.start(context)
        }
    }

    // Обновляем текущий SSID при возврате в приложение
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
    ) { /* даже при отказе сервис работает, просто без уведомления */ }

    val requestNotifIfNeeded = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val startMonitoring = {
        requestNotifIfNeeded()
        settings.setAutomationEnabled(true)
        MonitorService.start(context)
        refresh()
    }

    val permsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        controlGranted = WireGuardController.hasControlPermission(context)
        if (pendingEnable) {
            pendingEnable = false
            if (WifiUtils.hasLocationPermission(context)) {
                startMonitoring()
                if (wgInstalled && !controlGranted) {
                    toast("Внимание: не выдано управление WireGuard — VPN переключаться не будет")
                }
            } else {
                toast("Нужен точный доступ к геолокации, чтобы определять Wi-Fi сеть")
            }
        }
        refresh()
    }

    // Что нужно запросить при включении режима: геолокация (для SSID) и управление WireGuard.
    val missingPermsForEnable = {
        val list = mutableListOf<String>()
        if (!WifiUtils.hasLocationPermission(context)) {
            list += Manifest.permission.ACCESS_FINE_LOCATION
            list += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (wgInstalled && !WireGuardController.hasControlPermission(context)) {
            list += WireGuardController.CONTROL_PERMISSION
        }
        list
    }

    val onToggle = { enable: Boolean ->
        if (enable) {
            val missing = missingPermsForEnable()
            if (missing.isEmpty()) {
                startMonitoring()
            } else {
                pendingEnable = true
                permsLauncher.launch(missing.toTypedArray())
            }
        } else {
            settings.setAutomationEnabled(false)
            MonitorService.stop(context)
        }
    }

    val onAddCurrent = {
        val ssid = WifiUtils.currentSsid(context)
        when {
            !WifiUtils.hasLocationPermission(context) -> {
                pendingEnable = false
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("WG Home Auto") }) }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!wgInstalled) {
                WarningCard(
                    "Приложение WireGuard не найдено. Установите официальный WireGuard " +
                        "и включите в нём «Allow remote control apps»."
                )
            }

            if (wgInstalled && !controlGranted) {
                WarningCard(
                    "Не выдано разрешение на управление WireGuard. Включите автоматический режим " +
                        "и подтвердите запрос (или выдайте разрешение в настройках приложения)."
                )
            }

            MasterToggleCard(enabled = automationEnabled, onToggle = onToggle)

            StrictModeCard(
                enabled = strictMode,
                onChange = { settings.setStrictWeakSignal(it) }
            )

            StatusCard(
                serviceRunning = status.serviceRunning,
                automationEnabled = automationEnabled,
                currentSsid = if (status.serviceRunning) status.currentSsid else detectedSsid,
                isHome = if (status.serviceRunning) status.isHomeNetwork
                else (detectedSsid != null && homeSsids.contains(detectedSsid)),
                desiredVpnUp = status.desiredVpnUp,
                signalDbm = status.signalDbm,
                lastAction = status.lastActionText
            )

            TunnelCard(
                tunnelName = tunnelName,
                wgInstalled = wgInstalled,
                onTunnelChange = { settings.setTunnelName(it) },
                onTest = {
                    when {
                        !isTunnelNameValid(tunnelName) ->
                            toast("Сначала введите корректное имя тоннеля")
                        wgInstalled && !WireGuardController.hasControlPermission(context) -> {
                            pendingEnable = false
                            permsLauncher.launch(arrayOf(WireGuardController.CONTROL_PERMISSION))
                        }
                        else -> {
                            WireGuardController.setTunnel(context, up = true, tunnelName = tunnelName)
                            toast("Команда «включить $tunnelName» отправлена — проверьте WireGuard")
                        }
                    }
                }
            )

            HomeNetworksCard(
                ssids = homeSsids,
                detectedSsid = detectedSsid,
                onAddCurrent = { onAddCurrent() },
                onRemove = { settings.removeSsid(it) }
            )

            OutlinedButton(
                onClick = {
                    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(i)
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Защита от утечек",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
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
                StatusRow(
                    icon = Icons.Filled.Wifi,
                    label = "Сигнал дома",
                    value = "$signalDbm dBm"
                )
            }
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
private fun TunnelCard(
    tunnelName: String,
    wgInstalled: Boolean,
    onTunnelChange: (String) -> Unit,
    onTest: () -> Unit
) {
    val invalid = tunnelName.isNotEmpty() && !isTunnelNameValid(tunnelName)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Тоннель WireGuard", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(
                "Имя тоннеля точно как в приложении WireGuard. Список тоннелей WireGuard не отдаёт " +
                    "сторонним приложениям, поэтому имя вводится вручную и проверяется кнопкой ниже.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = tunnelName,
                onValueChange = onTunnelChange,
                singleLine = true,
                isError = invalid,
                placeholder = { Text("например, my-vpn") },
                supportingText = if (invalid) {
                    { Text("Допустимо 1–15 символов: латиница, цифры, _ = + . -") }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = onTest,
                enabled = wgInstalled && tunnelName.isNotEmpty() && !invalid,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Проверить (включить тоннель)")
            }
        }
    }
}

/** Те же правила имени, что и внутри WireGuard (Tunnel.isNameInvalid). */
private val TUNNEL_NAME_REGEX = Regex("[a-zA-Z0-9_=+.\\-]{1,15}")

private fun isTunnelNameValid(name: String): Boolean = TUNNEL_NAME_REGEX.matches(name)

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
