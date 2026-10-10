@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.anwo91.phevlink.ui.obd

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.obd.BatteryHealth
import io.github.anwo91.phevlink.core.obd.Confidence
import io.github.anwo91.phevlink.core.obd.ObdClient
import io.github.anwo91.phevlink.core.obd.ObdPhase
import io.github.anwo91.phevlink.core.obd.ObdState
import io.github.anwo91.phevlink.core.obd.ObdValue
import io.github.anwo91.phevlink.core.obd.livetest.LiveTestText
import io.github.anwo91.phevlink.core.obd.livetest.TestUiState
import io.github.anwo91.phevlink.ui.common.LabeledValue
import io.github.anwo91.phevlink.ui.common.Pill
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.theme.LocalStatus

/** Sub-tabs of the OBD tab. */
private enum class ObdTab(val label: Int) {
    OVERVIEW(R.string.obd_tab_overview),
    LIVE(R.string.obd_tab_live),
    CODES(R.string.obd_tab_codes),
    TESTS(R.string.obd_tab_tests),
    TOOLS(R.string.obd_tab_tools),
}

/**
 * The OBD tab: connection to the ELM327 adapter, overview with drive-battery health, all live values, fault codes
 * with explanations, guided live tests and tools.
 */
@Composable
fun ObdScreen(vm: ObdViewModel, modifier: Modifier, onOpenTip: (String) -> Unit) {
    val ctx = LocalContext.current
    val s by vm.state.collectAsState()
    val testState by vm.tests.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
    }

    val active = testState
    if (active != null) {
        Column(modifier.fillMaxSize()) {
            // The runner replaces the whole tab, so without this a dropped adapter (e.g. the phone was out of range
            // during the 25-minute charging test) could only be reconnected by cancelling the test. The runner
            // follows the new connection (ObdViewModel.startTest).
            val linkUp = s.phase == ObdPhase.READY || s.phase == ObdPhase.POLLING
            if (active !is TestUiState.Finished && !linkUp) ConnectionCard(vm, s, onPick = { picker = true })
            TestRunnerScreen(vm, active, Modifier.weight(1f), onOpenTip)
        }
    } else {
        Column(modifier.fillMaxSize()) {
            ConnectionCard(vm, s, onPick = { picker = true })
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                ObdTab.entries.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t.label)) })
                }
            }
            when (ObdTab.entries[tab]) {
                ObdTab.OVERVIEW -> OverviewTab(vm, s, onOpenCodes = { tab = ObdTab.CODES.ordinal }, onOpenTests = { tab = ObdTab.TESTS.ordinal })
                ObdTab.LIVE -> LiveTab(s)
                ObdTab.CODES -> CodesTab(vm, s, onOpenTip)
                ObdTab.TESTS -> TestsTab(vm, s)
                ObdTab.TOOLS -> ToolsTab(vm, s)
            }
        }
    }
    if (picker) {
        DevicePicker(vm, onDismiss = { picker = false }, onPicked = { picker = false; vm.connect(it) })
    }
}

@Composable
private fun ConnectionCard(vm: ObdViewModel, s: ObdState, onPick: () -> Unit) {
    val device by vm.device.collectAsState()
    val session by vm.sessionActive.collectAsState()
    val st = LocalStatus.current
    val (color, label) = when {
        s.phase == ObdPhase.ERROR -> st.bad to R.string.obd_phase_error
        s.phase == ObdPhase.CONNECTING || s.phase == ObdPhase.INITIALISING -> st.charge to R.string.obd_phase_connecting
        (s.phase == ObdPhase.READY || s.phase == ObdPhase.POLLING) && s.carReachable -> st.good to R.string.obd_phase_car_ok
        s.phase == ObdPhase.READY || s.phase == ObdPhase.POLLING -> st.warn to R.string.obd_phase_car_silent
        else -> st.idle to R.string.obd_phase_disconnected
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(stringResource(label), color)
            Spacer(Modifier.width(8.dp))
            Text(
                device?.name ?: stringResource(R.string.obd_no_adapter), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f), maxLines = 1,
            )
            s.adapterVoltage?.let { Text(LiveTestText.number(it, 1, vm.lang()) + " V", style = MaterialTheme.typography.labelLarge) }
        }
        val msg = obdMessage(s)
        if (msg != null && (s.phase != ObdPhase.POLLING || !s.carReachable)) {
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session) {
                OutlinedButton(onClick = { vm.disconnect() }) { Text(stringResource(R.string.obd_disconnect)) }
                if (s.phase == ObdPhase.ERROR) FilledTonalButton(onClick = { vm.reconnect() }) { Text(stringResource(R.string.obd_reconnect)) }
            } else {
                val d = device
                if (d != null) Button(onClick = { vm.connect(d) }) { Text(stringResource(R.string.obd_connect)) }
                OutlinedButton(onClick = onPick) { Text(stringResource(if (d == null) R.string.obd_choose_adapter else R.string.obd_change_adapter)) }
            }
        }
    }
}

/** Core status messages translated where we know them; anything else as reported. */
@Composable
fun obdMessage(s: ObdState): String? {
    val m = s.message ?: return null
    return when (m) {
        ObdClient.MSG_LINK_LOST -> stringResource(R.string.obd_msg_link_lost)
        ObdClient.MSG_CAR_SILENT -> stringResource(R.string.obd_msg_car_silent)
        ObdClient.MSG_NOT_CONNECTED -> stringResource(R.string.obd_not_connected)
        ObdClient.MSG_RECONNECT -> stringResource(R.string.obd_msg_reconnect)
        else -> m
    }
}

// ====================================================================================== overview

@Composable
private fun OverviewTab(vm: ObdViewModel, s: ObdState, onOpenCodes: () -> Unit, onOpenTests: () -> Unit) {
    val lang = vm.lang()
    val busy by vm.busy.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.phase == ObdPhase.DISCONNECTED && s.values.isEmpty()) {
            SectionCard(title = stringResource(R.string.obd_getting_started)) {
                Text(stringResource(R.string.obd_getting_started_text), style = MaterialTheme.typography.bodyMedium)
            }
        }
        BatteryCard(vm, s.batteryHealth, s.batteryMessage, lang, busy != null)
        TwelveVoltCard(s, lang)
        EngineCard(s)
        SectionCard(title = stringResource(R.string.obd_codes_title)) {
            val n = s.dtcs.map { it.code }.distinct().size
            Text(
                when {
                    s.lastDtcScanMs == null -> stringResource(R.string.obd_codes_not_scanned)
                    n == 0 -> stringResource(R.string.obd_codes_none)
                    else -> stringResource(R.string.obd_codes_count, n)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpenCodes) { Text(stringResource(R.string.obd_open_codes)) }
                OutlinedButton(onClick = onOpenTests) { Text(stringResource(R.string.obd_open_tests)) }
            }
        }
    }
}

@Composable
private fun BatteryCard(vm: ObdViewModel, b: BatteryHealth?, message: String?, lang: String, busy: Boolean) {
    val st = LocalStatus.current
    SectionCard(
        title = stringResource(R.string.obd_battery_title),
        trailing = {
            TextButton(onClick = { vm.readBattery() }, enabled = !busy) { Text(stringResource(R.string.obd_read_now)) }
        },
    ) {
        if (b == null) {
            Text(message ?: stringResource(R.string.obd_battery_none), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        fun num(v: Double?, d: Int) = v?.let { LiveTestText.number(it, d, lang) } ?: "—"
        val soh = b.sohPercent
        val sohColor = when {
            soh == null -> Color.Unspecified
            soh >= 80 -> st.good
            soh >= 70 -> st.warn
            else -> st.bad
        }
        LabeledValue(stringResource(R.string.obd_soc), num(b.soc, 1) + " %")
        LabeledValue(stringResource(R.string.obd_capacity), num(b.capacityAh, 1) + " Ah")
        LabeledValue(stringResource(R.string.obd_soh, LiveTestText.number(b.referenceAh, 0, lang)), num(soh, 0) + " %", valueColor = sohColor)
        LabeledValue(stringResource(R.string.obd_remaining), num(b.remainingAh, 1) + " Ah · " + num(b.energyKwh, 1) + " kWh")
        LabeledValue(stringResource(R.string.obd_pack_voltage), num(b.packVoltage, 1) + " V")
        if (b.maxCellV != null && b.minCellV != null) {
            LabeledValue(stringResource(R.string.obd_cell_high), num(b.maxCellV, 3) + " V" + (b.maxCellNo?.let { " (#$it)" } ?: ""))
            LabeledValue(stringResource(R.string.obd_cell_low), num(b.minCellV, 3) + " V" + (b.minCellNo?.let { " (#$it)" } ?: ""))
            val spread = b.spreadMv
            LabeledValue(
                stringResource(R.string.obd_cell_spread), num(spread, 0) + " mV",
                valueColor = when {
                    spread == null -> Color.Unspecified
                    spread <= 20 -> st.good
                    spread <= 100 -> st.warn
                    else -> st.bad
                },
            )
        }
        if (b.charges200V != null || b.charges100V != null) {
            LabeledValue(stringResource(R.string.obd_charge_counts), "${b.charges200V ?: "—"} / ${b.charges100V ?: "—"}")
        }
        Text(
            stringResource(R.string.obd_battery_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TwelveVoltCard(s: ObdState, lang: String) {
    val v = s.adapterVoltage ?: return
    val st = LocalStatus.current
    SectionCard(title = stringResource(R.string.obd_12v_title)) {
        val color = when {
            v >= 13.2 -> st.charge
            v >= 12.4 -> st.good
            v >= 12.0 -> st.warn
            else -> st.bad
        }
        LabeledValue(stringResource(R.string.obd_12v_now), LiveTestText.number(v, 2, lang) + " V", valueColor = color)
        Text(
            stringResource(if (v >= 13.2) R.string.obd_12v_charging else R.string.obd_12v_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EngineCard(s: ObdState) {
    val ids = listOf("obd.05", "obd.0C", "obd.0D", "obd.2F", "cluster.fuel_level", "obd.46", "obd.42", "obd.0F", "obd.04")
    val values = ids.mapNotNull { s.values[it] }
    if (values.isEmpty()) return
    SectionCard(title = stringResource(R.string.obd_engine_title)) {
        values.forEach { v -> LabeledValue(v.name, v.formatted(1)) }
    }
}

// ====================================================================================== live

@Composable
private fun LiveTab(s: ObdState) {
    var info by remember { mutableStateOf<ObdValue?>(null) }
    val groups = s.valuesByGroup()
    if (groups.isEmpty()) {
        Text(stringResource(R.string.obd_live_empty), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            Text(stringResource(R.string.obd_live_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for ((group, list) in groups) {
            item(key = "g:$group") {
                Text(
                    group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                )
            }
            items(list.sortedBy { it.name }, key = { it.id }) { v ->
                Row(
                    Modifier.fillMaxWidth().clickable { info = v }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(v.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    ConfidenceMark(v.confidence)
                    Spacer(Modifier.width(8.dp))
                    Text(v.formatted(2), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
    info?.let { v ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(v.name) },
            text = {
                Column {
                    LabeledValue(stringResource(R.string.obd_value), v.formatted(4))
                    LabeledValue(stringResource(R.string.obd_value_id), v.id)
                    LabeledValue(stringResource(R.string.obd_confidence), stringResource(confidenceLabel(v.confidence)))
                    if (v.note.isNotBlank()) Text(v.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { info = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

fun confidenceLabel(c: Confidence): Int = when (c) {
    Confidence.VERIFIED -> R.string.obd_conf_verified
    Confidence.COMMUNITY -> R.string.obd_conf_community
    Confidence.EXPERIMENTAL -> R.string.obd_conf_experimental
}

@Composable
private fun ConfidenceMark(c: Confidence) {
    val st = LocalStatus.current
    when (c) {
        Confidence.VERIFIED -> Unit
        Confidence.COMMUNITY -> Pill(stringResource(R.string.obd_conf_community_short), st.charge)
        Confidence.EXPERIMENTAL -> Pill(stringResource(R.string.obd_conf_experimental_short), st.warn)
    }
}

/** Small busy indicator with a label (used by several tabs). */
@Composable
fun BusyRow(label: Int?) {
    if (label == null) return
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(4.dp))
}
