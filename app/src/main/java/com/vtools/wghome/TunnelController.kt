package com.vtools.wghome

import android.content.Context
import android.content.Intent
import android.util.Log
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

/**
 * Встроенный WireGuard. Туннель поднимает НАШ процесс через GoBackend (нативный движок wg-go,
 * идёт в составе библиотеки com.wireguard.android:tunnel). Никакой зависимости от стороннего
 * приложения и его фоновых ограничений.
 *
 * ВНИМАНИЕ: up()/down()/currentState() блокирующие (JNI + сеть) — вызывать НЕ в главном потоке.
 */
object TunnelController {

    private const val TAG = "TunnelController"
    const val DEFAULT_NAME = "wghome"

    @Volatile private var backendRef: GoBackend? = null

    /** Имя интерфейса; читается движком через Tunnel.getName(). */
    @Volatile var tunnelName: String = DEFAULT_NAME

    private val tunnel = object : Tunnel {
        override fun getName(): String = sanitizeName(tunnelName)
        override fun onStateChange(newState: Tunnel.State) {
            Log.i(TAG, "Состояние туннеля: $newState")
            AppState.update { it.copy(vpnActive = newState == Tunnel.State.UP) }
        }
    }

    private fun backend(context: Context): GoBackend =
        backendRef ?: synchronized(this) {
            backendRef ?: GoBackend(context.applicationContext).also { backendRef = it }
        }

    /** null-Intent = согласие на VPN уже дано; иначе этот Intent надо запустить в Activity. */
    fun prepareIntent(context: Context): Intent? = android.net.VpnService.prepare(context)

    fun isAuthorized(context: Context): Boolean = prepareIntent(context) == null

    /** Разбор текста .conf. Бросает при некорректном конфиге. */
    fun parseConfig(text: String): Config = Config.parse(BufferedReader(StringReader(text)))

    /** Поднять туннель. Блокирующий вызов. */
    fun up(context: Context, config: Config): Tunnel.State =
        backend(context).setState(tunnel, Tunnel.State.UP, config)

    /** Опустить туннель. Блокирующий вызов. */
    fun down(context: Context): Tunnel.State =
        backend(context).setState(tunnel, Tunnel.State.DOWN, null)

    fun currentState(context: Context): Tunnel.State =
        try {
            backend(context).getState(tunnel)
        } catch (e: Exception) {
            Tunnel.State.DOWN
        }

    /** Человекочитаемое описание ошибки: у BackendException нет текста, суть в reason. */
    fun describeError(e: Throwable): String = when (e) {
        is BackendException -> "WireGuard: ${e.reason}"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Имя интерфейса WireGuard: только допустимые символы, максимум 15. */
    private fun sanitizeName(raw: String): String {
        val cleaned = raw.filter { it.isLetterOrDigit() || it in "_=+.-" }
        val name = cleaned.ifEmpty { DEFAULT_NAME }
        return if (name.length > 15) name.substring(0, 15) else name
    }
}
