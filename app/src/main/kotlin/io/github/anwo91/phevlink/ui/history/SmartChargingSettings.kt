package io.github.anwo91.phevlink.ui.history

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.data.AppSettings
import io.github.anwo91.phevlink.link.BridgeSetupApi
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.util.AppLocale
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Money
import kotlinx.coroutines.launch

/**
 * Settings → Charging → Smart charging: on/off (kept on the bridge, off by default — it changes the car's charge
 * timer), ready by when, and what the bridge plans tonight (or why it doesn't). Logged ("Smart charging: …").
 */
@Composable
fun SmartChargingSettings(vm: MainViewModel, s: AppSettings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shared by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var smart by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var oldBridge by remember { mutableStateOf(false) }
    var loads by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.bridgeUrl, loads) {
        if (!s.hasBridge) return@LaunchedEffect
        val r = BridgeSetupApi.shared(s.bridgeUrl, s.bridgeToken)
        val h = BridgeSetupApi.history(s.bridgeUrl, s.bridgeToken, limit = 1)
        oldBridge = h?.code == 404 || (h?.code == 200 && h.body["smart"] == null)
        @Suppress("UNCHECKED_CAST")
        smart = h?.body?.get("smart") as? Map<String, Any?>
        if (r?.code == 200) {
            shared = r.body
            failed = false
        } else {
            failed = true
            vm.log.link("Smart charging: couldn't read the bridge's settings (${r?.code ?: BridgeSetupApi.lastFailure ?: "no answer"})")
        }
    }
    fun save(changes: Map<String, Any?>) {
        shared = (shared ?: emptyMap()) + changes
        scope.launch {
            val r = BridgeSetupApi.putShared(s.bridgeUrl, s.bridgeToken, changes)
            vm.log.link("Smart charging: $changes → " + if (r?.code == 200) "saved on the bridge" else "not saved (${r?.code ?: "no answer"})")
            failed = r?.code != 200
            // Not saved: show what the bridge really has. Saved: the bridge looks every minute; show what it decided.
            if (!failed) kotlinx.coroutines.delay(65_000)
            loads++
        }
    }
    if (!s.hasBridge) {
        Note(stringResource(R.string.hist_needs_bridge))
        return
    }
    val m = shared
    if (m == null) {
        Note(stringResource(if (failed) R.string.rem_load_failed else R.string.sh_loading))
        if (failed) TextButton(onClick = { failed = false; loads++ }) { Text(stringResource(R.string.reach_try_again)) }
        return
    }
    if (oldBridge) {
        Note(stringResource(R.string.smart_old_bridge))
        return
    }
    val on = m["smartCharge"] as? Boolean ?: false
    val readyAt = m["smartReadyAt"] as? String ?: "07:00"
    val deps = m["smartUseDepartures"] as? Boolean ?: true
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.smart_title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { save(mapOf("smartCharge" to it)) })
        }
        Note(stringResource(R.string.smart_explain))
        if (on) {
            Text(stringResource(R.string.smart_ready), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (at in listOf("06:00", "06:30", "07:00", "07:30", "08:00")) {
                    FilterChip(selected = readyAt == at, onClick = { save(mapOf("smartReadyAt" to at)) }, label = { Text(at) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.smart_departures), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = deps, onCheckedChange = { save(mapOf("smartUseDepartures" to it)) })
            }
        }
        // Tonight's plan — also while switched off, until the owner's timer is back on the car.
        smart?.takeIf { on || it["status"] in BUSY }?.let { sm ->
            Text(
                smartStatusText(ctx, sm), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (failed) Note(stringResource(R.string.rem_load_failed))
    }
}

/** Statuses where the car carries (or gets) the bridge's window. */
val BUSY = setOf("planned", "writing", "restoring")

/**
 * On the charge timer page: tonight's window comes from smart charging, the owner's timer comes back in the morning,
 * and a change saved there stays. Shows nothing otherwise (or without a bridge).
 */
@Composable
fun SmartTimerNote(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(s.bridgeUrl) {
        if (!s.hasBridge) return@LaunchedEffect
        val h = BridgeSetupApi.history(s.bridgeUrl, s.bridgeToken, limit = 1)
        busy = (h?.body?.get("smart") as? Map<*, *>)?.get("status") in BUSY
    }
    if (busy) Text(stringResource(R.string.ct_smart_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

/** What the bridge plans tonight, or why it doesn't — from GET /api/history's "smart". */
fun smartStatusText(ctx: Context, sm: Map<String, Any?>): String {
    val res = AppLocale.localized(ctx)
    @Suppress("UNCHECKED_CAST")
    val plan = sm["plan"] as? Map<String, Any?>
    return when (sm["status"]) {
        "planned" -> {
            val start = (plan?.get("startMs") as? Number)?.toLong()
            val end = (plan?.get("endMs") as? Number)?.toLong()
            if (start == null || end == null) res.getString(R.string.smart_status_waiting)
            else res.getString(R.string.smart_status_planned, Format.clock(start), Format.clock(end)) +
                ((sm["saving"] as? Number)?.toDouble()?.takeIf { it > 0 }?.let {
                    " · " + res.getString(R.string.smart_status_saving, Money.format(it, sm["currency"] as? String, AppLocale.uiLocale(ctx)))
                } ?: "")
        }
        "off" -> res.getString(R.string.smart_status_off)
        "waiting_prices" -> res.getString(R.string.smart_status_waiting_prices)
        "full" -> res.getString(R.string.smart_status_full)
        "no_time" -> res.getString(R.string.smart_status_no_time)
        "no_saving" -> res.getString(R.string.smart_status_no_saving)
        "charge_now" -> res.getString(R.string.smart_status_charge_now)
        "my18" -> res.getString(R.string.smart_status_my18)
        "no_timer_data" -> res.getString(R.string.smart_status_no_timer_data)
        "failed" -> res.getString(R.string.smart_status_failed)
        "changed" -> res.getString(R.string.smart_status_changed)
        "done" -> res.getString(R.string.smart_status_done)
        "flat_price" -> res.getString(R.string.smart_status_flat_price)
        "no_slot" -> res.getString(R.string.smart_status_no_slot)
        "writing" -> res.getString(R.string.smart_status_writing)
        "restoring" -> res.getString(R.string.smart_status_restoring)
        "state_failed" -> res.getString(R.string.smart_status_state_failed)
        else -> res.getString(R.string.smart_status_waiting)
    }
}
