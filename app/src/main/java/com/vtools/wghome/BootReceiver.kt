package com.vtools.wghome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** После перезагрузки восстанавливает мониторинг, если автоматизация была включена. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsRepository.get(context)
        if (settings.automationEnabled.value && WifiUtils.hasLocationPermission(context)) {
            MonitorService.start(context)
        }
    }
}
