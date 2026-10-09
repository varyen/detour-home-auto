package com.vtools.wghome

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** После перезагрузки восстанавливает мониторинг, если автоматизация была включена. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsRepository.get(context)
        if (!settings.automationEnabled.value) return
        if (!WifiUtils.hasLocationPermission(context)) return

        // Android 14+: FGS типа location из фона разрешён только с «геолокация всегда».
        // Без неё старт гарантированно отклоняется — не пытаемся, а подсказываем пользователю.
        if (!WifiUtils.hasBackgroundLocationPermission(context)) {
            notifyNeedsBackgroundLocation(context)
            return
        }
        MonitorService.start(context)
    }

    private fun notifyNeedsBackgroundLocation(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Автозапуск", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Проблемы с запуском мониторинга после перезагрузки" }
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name) + " не запустился")
            .setContentText("Нужна геолокация «Разрешать всегда» — откройте приложение")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "После перезагрузки Android запрещает запуск службы мониторинга, пока " +
                        "приложению не выдана геолокация «Разрешать всегда». Откройте приложение " +
                        "и выдайте разрешение."
                )
            )
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }   // POST_NOTIFICATIONS может быть не выдано
    }

    private companion object {
        const val CHANNEL_ID = "boot_autostart"
        const val NOTIF_ID = 1002
    }
}
