package com.vtools.wghome

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Профиль, который пришёл извне: ссылка vless://… или .conf через «Открыть с помощью»/«Поделиться». */
object PendingImport {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text

    fun offer(context: Context, intent: Intent?) {
        val t = when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                if (uri.scheme == "content" || uri.scheme == "file") {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                } else {
                    uri.toString()
                }
            }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!t.isNullOrBlank()) _text.value = t
    }

    fun take(): String? = _text.value.also { _text.value = null }
}
