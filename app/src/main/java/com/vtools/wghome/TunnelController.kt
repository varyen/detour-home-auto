package com.vtools.wghome

import android.content.Context
import android.content.Intent
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.varyen.dha.dhcore.Dhcore
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Встроенный VPN: свой VpnService + движок mihomo (dhcore). Один движок на все
 * протоколы — WireGuard, AmneziaWG, VLESS и прочие ссылки.
 *
 * ВНИМАНИЕ: up()/down() блокирующие — вызывать НЕ в главном потоке.
 */
object TunnelController {

    private const val TAG = "TunnelController"

    @Volatile private var serviceFuture = CompletableFuture<DhaVpnService>()

    internal fun attach(s: DhaVpnService) {
        if (serviceFuture.getNow(null) === s) return
        if (serviceFuture.isDone) serviceFuture = CompletableFuture()
        serviceFuture.complete(s)
    }

    internal fun detach(s: DhaVpnService) {
        if (serviceFuture.getNow(null) === s) serviceFuture = CompletableFuture()
    }

    /** null-Intent = согласие на VPN уже дано; иначе этот Intent надо запустить в Activity. */
    fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

    fun isAuthorized(context: Context): Boolean = prepareIntent(context) == null

    /** Поднять туннель с профилем (или переключить работающий на другой). */
    fun up(context: Context, profile: VpnProfile) {
        val svc = serviceFuture.getNow(null) ?: run {
            context.startService(Intent(context, DhaVpnService::class.java))
            serviceFuture.get(5, TimeUnit.SECONDS)
        }
        svc.startTunnel(profile)
    }

    fun down(context: Context) {
        serviceFuture.getNow(null)?.stopTunnel()
    }

    fun isUp(): Boolean = serviceFuture.getNow(null)?.isUp() == true

    /** Задержка через профиль в мс, без поднятия туннеля. Блокирующий. */
    fun probe(profile: VpnProfile): Int = Dhcore.probe(profile.core, "", 10_000)

    fun describeError(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        return root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
    }
}

class DhaVpnService : VpnService() {

    // Свой экземпляр дескриптора: интерфейс VPN живёт, пока открыт хоть один, поэтому
    // при остановке закрываем его сами, не полагаясь на то, когда движок отпустит свой.
    private var tunPfd: ParcelFileDescriptor? = null
    @Volatile private var current: VpnProfile? = null

    override fun onCreate() {
        super.onCreate()
        TunnelController.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Служба могла получить stopSelf() и тут же новый старт — onCreate тогда не повторяется.
        TunnelController.attach(this)
        // Система стартует службу сама при «Постоянном VPN»; туннель поднимает только приложение.
        if (intent?.action == SERVICE_INTERFACE && current == null &&
            !SettingsRepository.get(this).automationEnabled.value
        ) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopTunnel()
        TunnelController.detach(this)
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN отозван системой")
        stopTunnel()
        AppState.update { it.copy(lastActionText = "VPN отключён системой (другой VPN или отзыв разрешения)") }
        super.onRevoke()
    }

    fun isUp(): Boolean = current != null

    @Synchronized
    fun startTunnel(profile: VpnProfile) {
        val b = Builder()
            .setSession("Detour Home Auto · ${profile.name}")
            .setMtu(profile.mtu())
            .addAddress(Dhcore.TunAddress4, Dhcore.TunPrefix4.toInt())
            .addAddress(Dhcore.TunAddress6, Dhcore.TunPrefix6.toInt())
            .addDisallowedApplication(packageName)
        val routes = profile.routes()
        if (routes.isEmpty() || routes.contains("0.0.0.0/0")) {
            // Весь IPv4 в туннель; IPv6 тоже заводим, чтобы он не шёл мимо — движок его отбрасывает.
            b.addRoute("0.0.0.0", 0).addRoute("::", 0).addDnsServer(Dhcore.TunDNS4)
        } else {
            for (r in routes) addRoute(b, r)
            profile.dns().forEach { runCatching { b.addDnsServer(it) } }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        // Неблокирующий fd: иначе Go откладывает его закрытие до первого прочитанного пакета,
        // и после «выключить» интерфейс VPN висит, пока не придёт трафик.
        b.setBlocking(false)

        val pfd = b.establish() ?: throw IllegalStateException("нет согласия на VPN")
        // Движку — копия: её он закроет сам (при остановке или при переходе на новый fd).
        val fd = pfd.dup().detachFd()
        try {
            Dhcore.start(profile.core, fd, filesDir.resolve("core").absolutePath)
        } catch (e: Exception) {
            closeFd(fd)
            runCatching { pfd.close() }
            throw e
        }
        val old = tunPfd
        tunPfd = pfd
        runCatching { old?.close() }
        current = profile
        AppState.update { it.copy(vpnActive = true, activeProfileName = profile.name) }
        Log.i(TAG, "туннель поднят: ${profile.name} (${profile.kind})")
    }

    @Synchronized
    fun stopTunnel() {
        if (current == null && tunPfd == null) return
        runCatching { Dhcore.stop() }
        runCatching { tunPfd?.close() }
        tunPfd = null
        current = null
        AppState.update { it.copy(vpnActive = false) }
        Log.i(TAG, "туннель опущен")
        // Как GoBackend в 2.x: служба уходит вместе с туннелем, ключ VPN в строке состояния гаснет.
        // Отвязываемся сразу, чтобы следующий up() стартовал службу заново, а не застал её на выходе.
        TunnelController.detach(this)
        stopSelf()
    }

    private fun addRoute(b: Builder, cidr: String) {
        runCatching {
            val (addr, len) = cidr.split("/").let { it[0] to (it.getOrNull(1)?.toInt() ?: -1) }
            val ia = InetAddress.getByName(addr)
            val bits = if (len >= 0) len else if (ia.address.size == 4) 32 else 128
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                b.addRoute(IpPrefix(ia, bits))
            } else {
                b.addRoute(ia, bits)
            }
        }.onFailure { Log.w(TAG, "маршрут $cidr пропущен: ${it.message}") }
    }

    private fun closeFd(fd: Int) {
        runCatching { android.os.ParcelFileDescriptor.adoptFd(fd).close() }
    }

    private companion object {
        const val TAG = "DhaVpnService"
    }
}
