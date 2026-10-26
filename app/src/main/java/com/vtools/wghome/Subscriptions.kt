package com.vtools.wghome

import io.github.varyen.dha.dhcore.Dhcore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Подписка: ссылка, по которой сервер (свой Detour или чужая панель) отдаёт все
 * профили сразу. Профили подписки помечены её [id] и при обновлении заменяются.
 */
data class VpnSubscription(
    val id: String,
    val url: String,
    val title: String,
    val updatedAt: Long = 0,
    val error: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("url", url).put("title", title)
        .put("updatedAt", updatedAt).put("error", error)

    companion object {
        fun fromJson(o: JSONObject) = VpnSubscription(
            id = o.getString("id"),
            url = o.getString("url"),
            title = o.optString("title"),
            updatedAt = o.optLong("updatedAt"),
            error = o.optString("error"),
        )

        fun listToJson(list: List<VpnSubscription>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): List<VpnSubscription> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(s)
                (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

        fun isUrl(text: String): Boolean {
            val t = text.trim().lowercase()
            return (t.startsWith("https://") || t.startsWith("http://")) && !t.contains('\n')
        }

        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Результат загрузки: заголовок, профили (ещё без постоянных id) и сколько пропущено. */
class FetchedSubscription(val title: String, val profiles: List<VpnProfile>, val skipped: Int)

object SubscriptionFetcher {
    /** Сеть — только из фонового потока. Бросает с понятным текстом. */
    fun fetch(url: String, subId: String): FetchedSubscription {
        val o = JSONObject(Dhcore.fetchSubscription(url.trim()))
        val arr = o.optJSONArray("profiles") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val core = arr.getJSONObject(i)
            VpnProfile(
                id = UUID.randomUUID().toString(),
                name = core.optString("name").ifBlank { core.optString("kind") },
                kind = core.optString("kind"),
                server = core.optString("server"),
                core = core.toString(),
                subId = subId,
            )
        }
        return FetchedSubscription(o.optString("title"), list, o.optInt("skipped"))
    }
}
