package com.vtools.wghome

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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

    private val _profiles = MutableStateFlow(VpnProfile.listFromJson(prefs.getString(KEY_PROFILES, null)))
    val profiles: StateFlow<List<VpnProfile>> = _profiles

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = _activeId

    private val _homeSsids = MutableStateFlow(readSsids())
    val homeSsids: StateFlow<Set<String>> = _homeSsids

    // Строгий режим: включать VPN, если дома сигнал слабый или Wi-Fi без интернета.
    private val _strictWeakSignal = MutableStateFlow(prefs.getBoolean(KEY_STRICT, true))
    val strictWeakSignal: StateFlow<Boolean> = _strictWeakSignal

    init {
        migrateLegacyConfig()
    }

    /** До 3.0 хранился один .conf WireGuard — превращаем его в первый профиль. */
    private fun migrateLegacyConfig() {
        val legacy = prefs.getString(KEY_LEGACY_CONFIG, null) ?: return
        val name = prefs.getString(KEY_LEGACY_TUNNEL, null) ?: "WireGuard"
        try {
            val p = VpnProfile.parse(legacy, name)
            saveProfiles(_profiles.value + p)
            if (_activeId.value == null) setActive(p.id)
            prefs.edit().remove(KEY_LEGACY_CONFIG).remove(KEY_LEGACY_TUNNEL).apply()
        } catch (e: Exception) {
            Log.e("Settings", "не удалось перенести старый конфиг", e)
        }
    }

    fun setAutomationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _automationEnabled.value = enabled
    }

    fun setStrictWeakSignal(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STRICT, enabled).apply()
        _strictWeakSignal.value = enabled
    }

    fun addProfile(p: VpnProfile) {
        saveProfiles(_profiles.value + p)
        if (activeProfile() == null) setActive(p.id)
    }

    fun removeProfile(id: String) {
        saveProfiles(_profiles.value.filterNot { it.id == id })
        if (_activeId.value == id) setActive(_profiles.value.firstOrNull()?.id)
    }

    fun renameProfile(id: String, name: String) {
        saveProfiles(_profiles.value.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })
    }

    fun setActive(id: String?) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        _activeId.value = id
    }

    fun activeProfile(): VpnProfile? =
        _profiles.value.firstOrNull { it.id == _activeId.value } ?: _profiles.value.firstOrNull()

    fun hasProfile(): Boolean = activeProfile() != null

    private fun saveProfiles(list: List<VpnProfile>) {
        prefs.edit().putString(KEY_PROFILES, VpnProfile.listToJson(list)).apply()
        _profiles.value = list
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
        private const val KEY_SSIDS = "home_ssids"
        private const val KEY_STRICT = "strict_weak_signal"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active_profile"
        private const val KEY_LEGACY_CONFIG = "config_text"
        private const val KEY_LEGACY_TUNNEL = "tunnel_name"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
