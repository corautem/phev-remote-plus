package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.alerts.Alert
import io.github.anwo91.phevlink.core.alerts.AlertType
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.PlugState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Evening reminders the bridge works out on its own (it watches the car around the clock; phones pick them up with
 * the bridge's alerts): at the shared time (21:00 by default), if the car is home, below the shared battery level and
 * not plugged in → "Battery 18 %, not plugged in." Once a day; every decision is logged.
 */
class Reminders(
    private val store: SharedStore,
    private val car: () -> CarState,
    /** True when the car is within the bridge's reach (on its Wi-Fi), false when not, null when unknown. */
    private val carHome: () -> Boolean?,
    private val raise: (Alert) -> Unit,
    private val log: DiagLog,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(store.zone()) },
) {
    private var lastDay: LocalDate? = null

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            runCatching { tick() }.onFailure { log.warn("Reminders: ${it.message}") }
            delay(30_000)
        }
    }

    /** One look at the clock; the plug-in reminder when it's due. */
    fun tick() {
        val cfg = store.plugReminder()
        if (!cfg.enabled) return
        val t = now()
        val today = t.toLocalDate()
        if (lastDay == today) return
        val due = today.atTime(cfg.at).atZone(t.zone)
        // Within half an hour after the time (a bridge restarted at 21:10 still reminds); never before.
        if (t.isBefore(due) || !t.isBefore(due.plusMinutes(30))) return
        lastDay = today
        val c = car()
        val pct = c.batteryPercent
        val label = cfg.at.format(HHMM)
        val why = when {
            carHome() != true -> "the car isn't within reach — nothing to remind"
            c.plug == PlugState.PLUGGED -> "already plugged in (${pct ?: "?"} %)"
            pct == null -> "battery level unknown"
            pct >= cfg.belowPercent -> "$pct % — not below ${cfg.belowPercent} %"
            else -> null
        }
        if (why != null) {
            log.link("Reminders $label: plug-in — $why")
            return
        }
        log.link("Reminders $label: plug-in — car home, $pct %, not plugged in → reminding the phones")
        raise(Alert(AlertType.PLUG_IN_REMINDER, "Battery $pct %, not plugged in.", t.toInstant().toEpochMilli()))
    }

    companion object {
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    }
}
