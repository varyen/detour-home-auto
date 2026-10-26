package com.vtools.wghome

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Обновление приложения из релизов GitHub. Ставит система (PackageInstaller) с
 * подтверждением пользователя; подпись APK обязана совпасть с установленной, так
 * что подменённый файл не встанет. Сверх того сверяется sha256 из API GitHub.
 */
object Updater {
    private const val API = "https://api.github.com/repos/varyen/detour-home-auto/releases/latest"
    private const val PREFS = "wg_home_prefs"
    private const val KEY_CHECKED = "update_checked_at"
    private const val KEY_NOTIFIED = "update_notified_version"
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 2
    const val ACTION_STATUS = "com.vtools.wghome.UPDATE_STATUS"

    data class Release(
        val version: String,
        val notes: String,
        val url: String,
        val size: Long,
        val sha256: String,
    )

    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        data class Available(val release: Release) : State()
        data class Downloading(val release: Release, val percent: Int) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** «3.10» > «3.9»: сравниваем по числам. */
    fun newer(remote: String, local: String): Boolean {
        val r = remote.trimStart('v').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.trimStart('v').split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Сеть — только из фонового потока. null — обновления нет. */
    fun fetchLatest(context: Context): Release? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DetourHomeAuto/${currentVersion(context)}")
        }
        val body = try {
            if (conn.responseCode != 200) error("GitHub ответил ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val o = JSONObject(body)
        val tag = o.optString("tag_name").trimStart('v')
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
        if (tag.isBlank() || !newer(tag, currentVersion(context))) return null
        val abis = Build.SUPPORTED_ABIS.toList()
        val assets = o.optJSONArray("assets") ?: error("в релизе нет файлов")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk") }
        // Первая подходящая из поддерживаемых устройством архитектур, в порядке предпочтения.
        val asset = abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.optString("name").contains(abi) } }
            ?: error("в релизе $tag нет APK для ${abis.joinToString()}")
        return Release(
            version = tag,
            notes = o.optString("body"),
            url = asset.getString("browser_download_url"),
            size = asset.optLong("size"),
            sha256 = asset.optString("digest").removePrefix("sha256:").lowercase(),
        )
    }

    fun shouldAutoCheck(context: Context, everyMs: Long): Boolean {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CHECKED, 0)
        return System.currentTimeMillis() - at > everyMs
    }

    /** Проверка для экрана: результат — в [state]. */
    fun check(context: Context) {
        _state.value = State.Checking
        _state.value = try {
            fetchLatest(context)?.let { State.Available(it) } ?: State.UpToDate
        } catch (e: Exception) {
            State.Failed("не удалось проверить: ${e.message}")
        }
    }

    /** Фоновая проверка из службы: о новой версии — одно уведомление на версию. */
    fun checkAndNotify(context: Context) {
        val r = runCatching { fetchLatest(context) }.getOrNull() ?: return
        if (_state.value !is State.Downloading) _state.value = State.Available(r)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NOTIFIED, null) == r.version) return
        prefs.edit().putString(KEY_NOTIFIED, r.version).apply()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Обновления", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setContentTitle("Доступна версия ${r.version}")
                .setContentText("Откройте Detour Home Auto, чтобы обновиться")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    /** Разрешено ли приложению ставить APK; иначе — экран настроек «Установка неизвестных приложений». */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Скачать и отдать системе на установку. Из фонового потока. */
    fun downloadAndInstall(context: Context, r: Release) {
        try {
            val file = File(context.cacheDir, "update.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            val conn = (URL(r.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode != 200) error("скачивание: ответ ${conn.responseCode}")
                val total = if (r.size > 0) r.size else conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (total > 0) _state.value = State.Downloading(r, (done * 100 / total).toInt())
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (r.sha256.isNotBlank() && got != r.sha256) {
                file.delete()
                error("контрольная сумма не совпала — файл повреждён или подменён")
            }
            install(context, file)
            _state.value = State.Available(r)
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "обновление не удалось")
        }
    }

    private fun install(context: Context, apk: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            s.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    internal fun fail(message: String) {
        _state.value = State.Failed(message)
    }
}

/** Ответ PackageInstaller: показать системный диалог подтверждения или сообщить об ошибке. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Updater.fail(
                "установка не удалась: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "отменена")
            )
        }
    }
}
