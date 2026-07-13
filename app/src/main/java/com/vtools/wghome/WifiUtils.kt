package com.vtools.wghome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

/** Снимок состояния текущей Wi-Fi: имя, уровень сигнала (dBm), есть ли реально интернет. */
data class WifiState(
    val ssid: String?,
    val rssi: Int,        // dBm; -127 если нет данных
    val validated: Boolean // прошла ли сеть проверку интернета
)

/** Утилиты чтения текущей Wi-Fi сети. */
object WifiUtils {

    const val NO_RSSI = -127

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Убирает кавычки и служебные значения, возвращает чистый SSID или null. */
    fun normalizeSsid(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        var s = raw
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        if (s.isEmpty() || s == WifiManager.UNKNOWN_SSID || s == "<unknown ssid>" || s == "0x") return null
        return s
    }

    fun ssidFromWifiInfo(info: WifiInfo?): String? = normalizeSsid(info?.ssid)

    /**
     * Текущий SSID «здесь и сейчас».
     * WifiManager.connectionInfo возвращает реальный SSID даже когда поверх Wi-Fi поднят VPN,
     * т.к. читает состояние Wi-Fi-подсистемы напрямую. Требует разрешение на геолокацию.
     */
    @Suppress("DEPRECATION")
    fun currentSsid(context: Context): String? {
        if (!hasLocationPermission(context)) return null
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) return null
        val info = wifiManager.connectionInfo ?: return null
        if (info.networkId == -1 && normalizeSsid(info.ssid) == null) return null
        return ssidFromWifiInfo(info)
    }

    /**
     * Полный снимок Wi-Fi: SSID + уровень сигнала + признак «реального интернета».
     * RSSI берём из WifiManager (работает и под VPN), validated — из NetworkCapabilities Wi-Fi сети.
     */
    @Suppress("DEPRECATION")
    fun wifiState(context: Context): WifiState {
        if (!hasLocationPermission(context)) return WifiState(null, NO_RSSI, false)
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) return WifiState(null, NO_RSSI, false)
        val info = wm.connectionInfo
        val ssid = ssidFromWifiInfo(info)
        val rssi = info?.rssi ?: NO_RSSI
        val validated = wifiValidated(context)
        return WifiState(ssid, rssi, validated)
    }

    /** Прошла ли активная Wi-Fi сеть проверку доступа в интернет. */
    private fun wifiValidated(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                return true
            }
        }
        return false
    }

    /** Есть ли хоть одна активная Wi-Fi сеть с интернетом (независимо от VPN поверх неё). */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return true
            }
        }
        return false
    }
}
