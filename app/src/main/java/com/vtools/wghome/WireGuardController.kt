package com.vtools.wghome

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Управление официальным приложением WireGuard через broadcast-Intent.
 *
 * Проверено по исходникам wireguard-android (TunnelManager.IntentReceiver):
 *  - приёмник: com.wireguard.android/.model.TunnelManager$IntentReceiver
 *  - action:   com.wireguard.android.action.SET_TUNNEL_UP / SET_TUNNEL_DOWN
 *  - extra:    "tunnel" = имя тоннеля (String)
 *  - приёмник защищён разрешением com.wireguard.android.permission.CONTROL_TUNNELS
 *
 * ВАЖНО: в приложении WireGuard должна быть включена опция
 * «Allow remote control apps» (Настройки → Дополнительно), иначе UP/DOWN игнорируются.
 */
object WireGuardController {

    const val WG_PACKAGE = "com.wireguard.android"
    /** dangerous runtime-разрешение, объявленное самим WireGuard. Его нужно запрашивать в рантайме. */
    const val CONTROL_PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS"
    private const val WG_RECEIVER = "com.wireguard.android.model.TunnelManager\$IntentReceiver"

    private const val ACTION_UP = "com.wireguard.android.action.SET_TUNNEL_UP"
    private const val ACTION_DOWN = "com.wireguard.android.action.SET_TUNNEL_DOWN"
    private const val EXTRA_TUNNEL = "tunnel"

    private const val TAG = "WireGuardController"

    /** Включает (up=true) или выключает (up=false) заданный тоннель. */
    fun setTunnel(context: Context, up: Boolean, tunnelName: String) {
        if (tunnelName.isBlank()) {
            Log.w(TAG, "Имя тоннеля не задано — команда пропущена")
            return
        }
        val intent = Intent(if (up) ACTION_UP else ACTION_DOWN).apply {
            setClassName(WG_PACKAGE, WG_RECEIVER)
            putExtra(EXTRA_TUNNEL, tunnelName)
        }
        try {
            context.sendBroadcast(intent)
            Log.i(TAG, "WireGuard: ${if (up) "SET_TUNNEL_UP" else "SET_TUNNEL_DOWN"} '$tunnelName'")
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось отправить команду в WireGuard", e)
        }
    }

    fun isWireGuardInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(WG_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }

    /** Выдано ли нам право слать команды WireGuard. Без него система молча отбрасывает broadcast. */
    fun hasControlPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, CONTROL_PERMISSION) == PackageManager.PERMISSION_GRANTED
}
