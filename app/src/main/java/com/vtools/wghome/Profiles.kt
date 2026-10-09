package com.vtools.wghome

import io.github.varyen.dha.dhcore.Dhcore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Профиль подключения. [core] — JSON, который вернул движок (ParseProfile) и
 * который он же принимает в Start/Probe; остальные поля — для экрана.
 */
data class VpnProfile(
    val id: String,
    val name: String,
    val kind: String,
    val server: String,
    val core: String,
) {
    val kindLabel: String
        get() = when (kind) {
            "wireguard" -> "WireGuard"
            "amneziawg" -> "AmneziaWG"
            "vless" -> "VLESS"
            "vless-reality" -> "VLESS Reality"
            "vless-xhttp" -> "VLESS xhttp"
            "trojan" -> "Trojan"
            "ss" -> "Shadowsocks"
            "vmess" -> "VMess"
            "hysteria2" -> "Hysteria2"
            else -> kind
        }

    /** Маршруты в туннель из профиля; пусто — весь трафик. */
    fun routes(): List<String> = JSONObject(core).optJSONArray("routes").strings()

    fun dns(): List<String> = JSONObject(core).optJSONArray("dns").strings()

    fun mtu(): Int = JSONObject(core).optInt("mtu", 0).takeIf { it > 0 } ?: Dhcore.DefaultMTU.toInt()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind).put("server", server).put("core", core)

    companion object {
        fun fromJson(o: JSONObject) = VpnProfile(
            id = o.getString("id"),
            name = o.optString("name"),
            kind = o.optString("kind"),
            server = o.optString("server"),
            core = o.getString("core"),
        )

        /**
         * Разбор того, что дал пользователь: .conf WireGuard/AmneziaWG или ссылка
         * (vless://, trojan://, ss://, hysteria2://…). Бросает с понятным текстом.
         */
        fun parse(text: String, fallbackName: String): VpnProfile {
            val core = Dhcore.parseProfile(text, fallbackName)
            val o = JSONObject(core)
            return VpnProfile(
                id = UUID.randomUUID().toString(),
                name = o.optString("name").ifBlank { fallbackName },
                kind = o.optString("kind"),
                server = o.optString("server"),
                core = core,
            )
        }

        fun listToJson(list: List<VpnProfile>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): List<VpnProfile> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(s)
                (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }
