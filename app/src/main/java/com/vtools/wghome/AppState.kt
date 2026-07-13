package com.vtools.wghome

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Живой статус мониторинга. Сервис публикует, UI наблюдает.
 * Работает как in-memory шина, т.к. Activity и Service — в одном процессе.
 */
data class MonitorStatus(
    val serviceRunning: Boolean = false,
    val currentSsid: String? = null,
    val onWifi: Boolean = false,
    val isHomeNetwork: Boolean = false,
    val desiredVpnUp: Boolean = false,
    val signalDbm: Int? = null,       // уровень сигнала домашней сети, dBm (null если не на домашней)
    val lastActionText: String = "—"
)

object AppState {
    private val _status = MutableStateFlow(MonitorStatus())
    val status: StateFlow<MonitorStatus> = _status

    fun update(transform: (MonitorStatus) -> MonitorStatus) {
        _status.value = transform(_status.value)
    }
}
