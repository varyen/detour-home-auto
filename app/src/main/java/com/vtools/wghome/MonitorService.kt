package com.vtools.wghome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Постоянно отслеживает состояние Wi-Fi через ConnectivityManager.NetworkCallback.
 *
 * Логика:
 *  - подключены к «домашней» сети (SSID в списке) → выключаем VPN (SET_TUNNEL_DOWN);
 *  - любая другая сеть / нет Wi-Fi → включаем VPN (SET_TUNNEL_UP).
 *
 * Работает как foreground-сервис типа "location" (чтение SSID = доступ к геолокации).
 */
class MonitorService : Service() {

    private lateinit var settings: SettingsRepository
    private lateinit var cm: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var foregroundOk = false
    private var pending: Runnable? = null
    @Volatile private var lastAppliedVpnUp: Boolean? = null
    private var homeSafeState = false   // память гистерезиса: считали ли дом «надёжным»
    private val vpnExecutor = Executors.newSingleThreadExecutor()
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository.get(this)
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createChannel()
        if (!goForeground("Запуск мониторинга…")) {
            // Android 14+ запрещает старт FGS типа location из фона без «геолокация всегда».
            // Раньше это валило процесс при загрузке телефона — теперь просто не стартуем.
            AppState.update {
                it.copy(serviceRunning = false, lastActionText = FG_DENIED_TEXT)
            }
            stopSelf()
            return
        }
        foregroundOk = true
        registerCallback()
        AppState.update { it.copy(serviceRunning = true) }
        evaluateSoon()
        // Сеть не менялась, а список домашних сетей или строгий режим — да: пересчитать сразу.
        scope.launch {
            combine(settings.homeSsids, settings.strictWeakSignal) { a, b -> a to b }
                .drop(1)
                .collect { evaluateSoon() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundOk) {
            // onCreate не смог уйти в foreground — не даём системе перезапускать нас по кругу.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** @return true, если сервис действительно перешёл в foreground. */
    private fun goForeground(text: String): Boolean = try {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground отклонён системой", e)
        false
    }

    private fun registerCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = evaluateSoon()
            override fun onLost(network: Network) = evaluateSoon()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = evaluateSoon()
        }
        networkCallback = cb
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback failed", e)
        }
    }

    /** Решение о желаемом состоянии VPN. */
    private data class Decision(
        val desiredUp: Boolean,
        val ssid: String?,
        val isHome: Boolean,
        val rssi: Int,
        val statusText: String
    )

    /**
     * Асимметричный дебаунс: включаем VPN быстро (безопасность), выключаем медленно
     * (чтобы не мигал при кратких переподключениях дома). Направление выбираем по «мгновенной»
     * оценке, а окончательное решение принимаем уже в момент срабатывания.
     */
    private fun evaluateSoon() {
        val provisional = decide(commit = false).desiredUp
        val delay = if (provisional) TURN_ON_MS else TURN_OFF_MS
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { applyNow() }
        pending = r
        handler.postDelayed(r, delay)
    }

    /** Считает желаемое состояние. commit=true фиксирует гистерезис. */
    private fun decide(commit: Boolean): Decision {
        val st = WifiUtils.wifiState(this)
        val strict = settings.strictWeakSignal.value
        val onHome = settings.isHome(st.ssid)

        val homeSafe: Boolean = when {
            !onHome -> false
            !strict -> true // простой режим: дома = безопасно, качество не учитываем
            else -> {
                // гистерезис по RSSI + требование реального интернета
                val rssiOk = if (homeSafeState) st.rssi > RSSI_EXIT_SAFE else st.rssi >= RSSI_ENTER_SAFE
                st.validated && rssiOk
            }
        }
        if (commit) homeSafeState = homeSafe

        val desiredUp = !homeSafe
        val statusText = when {
            onHome && !homeSafe && strict ->
                "Дома, но связь ненадёжна (${st.rssi} dBm${if (!st.validated) ", нет интернета" else ""}) → VPN включён"
            onHome -> "Дома (${st.ssid}, ${st.rssi} dBm) → VPN выключен"
            st.ssid != null -> "Сеть \"${st.ssid}\" → VPN включён"
            else -> "Нет домашней Wi-Fi → VPN включён"
        }
        return Decision(desiredUp, st.ssid, onHome, st.rssi, statusText)
    }

    private fun applyNow() {
        val d = decide(commit = true)
        val onWifi = d.ssid != null || WifiUtils.isWifiConnected(this)
        AppState.update {
            it.copy(
                currentSsid = d.ssid,
                onWifi = onWifi,
                isHomeNetwork = d.isHome,
                desiredVpnUp = d.desiredUp,
                signalDbm = if (d.isHome) d.rssi else null
            )
        }
        updateNotification(d.statusText)

        if (lastAppliedVpnUp == d.desiredUp) {
            AppState.update { it.copy(lastActionText = "${d.statusText} (без изменений)") }
            return
        }
        applyVpn(d.desiredUp, d.statusText)
    }

    /** Управление встроенным туннелем в фоновом потоке (up/down блокирующие). */
    private fun applyVpn(up: Boolean, statusText: String) {
        vpnExecutor.execute {
            try {
                if (up) {
                    val profile = settings.activeProfile()
                    if (profile == null) {
                        AppState.update { it.copy(lastActionText = "Нет профиля — добавьте .conf или ссылку") }
                        return@execute
                    }
                    if (!TunnelController.isAuthorized(this)) {
                        AppState.update { it.copy(lastActionText = "Нет согласия на VPN — откройте приложение") }
                        return@execute
                    }
                    TunnelController.up(this, profile)
                } else {
                    TunnelController.down(this)
                }
                lastAppliedVpnUp = up   // фиксируем только при успехе → при ошибке повторим на след. событии
                AppState.update { it.copy(lastActionText = statusText) }
                Log.i(TAG, statusText)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка управления туннелем", e)
                AppState.update { it.copy(lastActionText = "Ошибка VPN: ${TunnelController.describeError(e)}") }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pending?.let { handler.removeCallbacks(it) }
        scope.cancel()
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        vpnExecutor.shutdown()
        AppState.update { it.copy(serviceRunning = false) }
    }

    // ------- Уведомление -------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Мониторинг Wi-Fi",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Отслеживание домашней сети для управления VPN" }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "wifi_monitor"
        private const val NOTIF_ID = 1001

        // Асимметричный дебаунс: включаем быстро, выключаем неспешно.
        private const val TURN_ON_MS = 700L
        private const val TURN_OFF_MS = 4000L

        // Гистерезис по уровню сигнала (dBm): чтобы считать дом «надёжным» — нужно >= -70,
        // чтобы перестать считать надёжным — упасть ниже -80.
        private const val RSSI_ENTER_SAFE = -70
        private const val RSSI_EXIT_SAFE = -80

        /** Текст для UI, когда система не дала уйти в foreground. */
        const val FG_DENIED_TEXT =
            "Android не дал запустить службу в фоне — нужна геолокация «Разрешать всегда»"

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException и подобное — не роняем приложение.
                Log.e(TAG, "Не удалось запустить MonitorService", e)
                AppState.update { it.copy(serviceRunning = false, lastActionText = FG_DENIED_TEXT) }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
