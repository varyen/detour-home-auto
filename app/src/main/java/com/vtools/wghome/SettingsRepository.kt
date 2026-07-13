package com.vtools.wghome

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Простое хранилище настроек на SharedPreferences.
 * Значения дублируются в StateFlow, чтобы UI на Compose реагировал на изменения.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _automationEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val automationEnabled: StateFlow<Boolean> = _automationEnabled

    private val _tunnelName = MutableStateFlow(prefs.getString(KEY_TUNNEL, "") ?: "")
    val tunnelName: StateFlow<String> = _tunnelName

    private val _homeSsids = MutableStateFlow(readSsids())
    val homeSsids: StateFlow<Set<String>> = _homeSsids

    // Строгий режим: включать VPN, если дома сигнал слабый или Wi-Fi без интернета.
    private val _strictWeakSignal = MutableStateFlow(prefs.getBoolean(KEY_STRICT, true))
    val strictWeakSignal: StateFlow<Boolean> = _strictWeakSignal

    fun setAutomationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _automationEnabled.value = enabled
    }

    fun setTunnelName(name: String) {
        val trimmed = name.trim()
        prefs.edit().putString(KEY_TUNNEL, trimmed).apply()
        _tunnelName.value = trimmed
    }

    fun setStrictWeakSignal(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STRICT, enabled).apply()
        _strictWeakSignal.value = enabled
    }

    fun addSsid(ssid: String) {
        val next = readSsids().toMutableSet().apply { add(ssid) }
        persistSsids(next)
    }

    fun removeSsid(ssid: String) {
        val next = readSsids().toMutableSet().apply { remove(ssid) }
        persistSsids(next)
    }

    fun isHome(ssid: String?): Boolean = ssid != null && readSsids().contains(ssid)

    private fun persistSsids(set: Set<String>) {
        // SharedPreferences хранит ссылку на Set — сохраняем копию.
        prefs.edit().putStringSet(KEY_SSIDS, HashSet(set)).apply()
        _homeSsids.value = set
    }

    private fun readSsids(): Set<String> =
        prefs.getStringSet(KEY_SSIDS, emptySet())?.toSortedSet() ?: emptySet()

    companion object {
        private const val PREFS = "wg_home_prefs"
        private const val KEY_ENABLED = "automation_enabled"
        private const val KEY_TUNNEL = "tunnel_name"
        private const val KEY_SSIDS = "home_ssids"
        private const val KEY_STRICT = "strict_weak_signal"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
