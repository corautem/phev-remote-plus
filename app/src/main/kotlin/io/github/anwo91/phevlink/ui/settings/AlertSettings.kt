package io.github.anwo91.phevlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.alerts.AlertPrefs
import io.github.anwo91.phevlink.data.AppSettings
import io.github.anwo91.phevlink.link.BridgeSetupApi
import io.github.anwo91.phevlink.ui.MainViewModel
import kotlinx.coroutines.launch

/** Which alerts are on, as a list (for the summary line). */
fun alertSwitches(a: AlertPrefs): List<Boolean> =
    listOf(a.chargeComplete, a.chargeStopped, a.climateFinished, a.climateCancelled, a.leftUnlocked, a.doorOpen, a.lightsOn, a.theftAlarm, a.chargeTimerFailed, a.plugInReminder, a.weeklySummary)

/**
 * Settings → Alerts: on/off, where they come from (the bridge or this phone), each kind, and the background watch.
 * With a bridge the evening plug-in reminder (shared by all phones, kept on the bridge) is set here too.
 */
@Composable
fun AlertsContent(vm: MainViewModel, s: AppSettings) {
    fun set(t: (AppSettings) -> AppSettings) = vm.updateSettings(t)
    val a = s.alerts
    ToggleRow(stringResource(R.string.settings_alerts), s.phoneAlerts) { on -> set { it.copy(phoneAlerts = on) } }
    if (!s.phoneAlerts) return
    if (s.hasBridge) {
        ToggleRow(stringResource(R.string.settings_alerts_from_bridge), s.alertsFromBridge) { on ->
            vm.log.link("Settings: alerts from ${if (on) "the bridge" else "this phone"}")
            set { it.copy(alertsFromBridge = on) }
        }
        Note(stringResource(if (s.alertsFromBridge) R.string.settings_alerts_from_bridge_on else R.string.settings_alerts_from_bridge_off))
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    ToggleRow(stringResource(R.string.settings_alert_charge_complete), a.chargeComplete) { on -> set { it.copy(alerts = it.alerts.copy(chargeComplete = on)) } }
    ToggleRow(stringResource(R.string.settings_alert_charge_stopped), a.chargeStopped) { on -> set { it.copy(alerts = it.alerts.copy(chargeStopped = on)) } }
    ToggleRow(stringResource(R.string.settings_alert_charge_timer_failed), a.chargeTimerFailed) { on -> set { it.copy(alerts = it.alerts.copy(chargeTimerFailed = on)) } }
    ToggleRow(stringResource(R.string.settings_alert_climate_finished), a.climateFinished) { on -> set { it.copy(alerts = it.alerts.copy(climateFinished = on)) } }
    ToggleRow(stringResource(R.string.settings_alert_climate_cancelled), a.climateCancelled) { on -> set { it.copy(alerts = it.alerts.copy(climateCancelled = on)) } }
    ToggleRow(stringResource(R.string.settings_alert_left_unlocked), a.leftUnlocked) { on -> set { it.copy(alerts = it.alerts.copy(leftUnlocked = on)) } }
    ToggleRow(stringResource(R.string.settings_alert_door_open), a.doorOpen) { on -> set { it.copy(alerts = it.alerts.copy(doorOpen = on)) } }
    ToggleRow(stringResource(R.string.settings_alert_lights_on), a.lightsOn) { on -> set { it.copy(alerts = it.alerts.copy(lightsOn = on)) } }
    ToggleRow(stringResource(R.string.settings_alert_theft), a.theftAlarm) { on -> set { it.copy(alerts = it.alerts.copy(theftAlarm = on)) } }
    if (s.hasBridge) {
        ToggleRow(stringResource(R.string.settings_alert_plug_in), a.plugInReminder) { on -> set { it.copy(alerts = it.alerts.copy(plugInReminder = on)) } }
        if (a.plugInReminder) PlugReminderSettings(vm, s)
        ToggleRow(stringResource(R.string.settings_alert_weekly), a.weeklySummary) { on -> set { it.copy(alerts = it.alerts.copy(weeklySummary = on)) } }
        Note(stringResource(R.string.settings_alert_weekly_note))
    }
}

/**
 * The evening plug-in reminder, kept on the bridge for all phones (it works it out around the clock): on/off, the
 * time and below which battery level. Read from and saved straight to the bridge.
 */
@Composable
private fun PlugReminderSettings(vm: MainViewModel, s: AppSettings) {
    val scope = rememberCoroutineScope()
    var shared by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(s.bridgeUrl) {
        val r = BridgeSetupApi.shared(s.bridgeUrl, s.bridgeToken)
        if (r?.code == 200) shared = r.body else failed = true
    }
    fun save(changes: Map<String, Any?>) {
        shared = (shared ?: emptyMap()) + changes
        scope.launch {
            val r = BridgeSetupApi.putShared(s.bridgeUrl, s.bridgeToken, changes)
            vm.log.link("Settings: evening reminder $changes → " + if (r?.code == 200) "saved on the bridge" else "not saved (${r?.code ?: "no answer"})")
            if (r?.code != 200) failed = true
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp)) {
        val m = shared
        if (m == null) {
            Note(stringResource(if (failed) R.string.rem_load_failed else R.string.sh_loading))
            return@Column
        }
        val on = m["plugReminder"] as? Boolean ?: true
        val at = m["plugReminderAt"] as? String ?: "21:00"
        val below = (m["plugReminderBelow"] as? Number)?.toInt() ?: 80
        ToggleRow(stringResource(R.string.rem_on), on) { v -> save(mapOf("plugReminder" to v)) }
        Note(stringResource(R.string.rem_explain))
        if (on) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.rem_time), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in listOf("20:00", "21:00", "22:00", "23:00")) {
                    FilterChip(selected = at == t, onClick = { save(mapOf("plugReminderAt" to t)) }, label = { Text(t) })
                }
            }
            Text(stringResource(R.string.rem_below), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in listOf(50, 80, 100)) {
                    FilterChip(
                        selected = below == p, onClick = { save(mapOf("plugReminderBelow" to p)) },
                        label = { Text(if (p == 100) stringResource(R.string.rem_always) else "$p %") },
                    )
                }
            }
        }
        if (failed) Note(stringResource(R.string.rem_load_failed))
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
