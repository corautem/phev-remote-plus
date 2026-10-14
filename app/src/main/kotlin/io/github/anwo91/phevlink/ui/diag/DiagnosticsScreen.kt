package io.github.anwo91.phevlink.ui.diag

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.diag.LogEntry
import io.github.anwo91.phevlink.core.diag.LogKind
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.protocol.Reg
import io.github.anwo91.phevlink.core.protocol.hexToBytes
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.LabeledValue
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.ui.theme.Mono
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Labels
import java.time.LocalDateTime

private enum class Filter(@StringRes val label: Int, val kinds: Set<LogKind>) {
    /** Everything except the raw packets (the default: what happened, without the wire noise). */
    EVENTS(R.string.diag_filter_events, LogKind.entries.toSet() - setOf(LogKind.TX, LogKind.RX)),
    LINK(R.string.diag_connection, setOf(LogKind.LINK, LogKind.WARN, LogKind.ERROR)),
    CMDS(R.string.diag_filter_commands, setOf(LogKind.CMD, LogKind.ERROR, LogKind.WARN)),
    STATE(R.string.diag_filter_state, setOf(LogKind.STATE)),
    PACKETS(R.string.diag_filter_packets, setOf(LogKind.TX, LogKind.RX)),
    ALL(R.string.diag_filter_all, LogKind.entries.toSet()),
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(vm: MainViewModel, modifier: Modifier, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(PhevIcons.Back, stringResource(R.string.common_back)) }
            Text(stringResource(R.string.tab_diagnostics), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf(R.string.diag_tab_log, R.string.diag_tab_registers, R.string.diag_tab_tools).forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) })
            }
        }
        when (tab) {
            0 -> LogTab(vm)
            1 -> RegistersTab(vm)
            else -> ToolsTab(vm)
        }
    }
}

@Composable
private fun LogTab(vm: MainViewModel) {
    val ctx = LocalContext.current
    val version by vm.logVersion.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Filter.EVENTS) }
    var previous by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val entries = remember(version, filter) { vm.log.snapshot().filter { it.kind in filter.kinds } }
    val listState = rememberLazyListState()
    LaunchedEffect(version, filter) { if (entries.isNotEmpty() && !previous) listState.scrollToItem(entries.size - 1) }

    Column(Modifier.fillMaxSize()) {
        // One tidy toolbar: what to show on the left, previous session / share / clear on the right.
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { menu = true }, enabled = !previous) {
                    Text(stringResource(R.string.diag_show_filter, stringResource(filter.label)))
                    Spacer(Modifier.width(4.dp))
                    Icon(PhevIcons.ChevronDown, null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Filter.entries.forEach { f ->
                        DropdownMenuItem(
                            text = { Text(stringResource(f.label)) },
                            onClick = { filter = f; menu = false },
                            trailingIcon = if (f == filter) { { Icon(PhevIcons.Check, null) } } else null,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { previous = !previous }) {
                Icon(
                    PhevIcons.History, stringResource(R.string.diag_previous_session),
                    tint = if (previous) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                val text = if (previous) vm.previousSessionLog() else vm.log.dump()
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.diag_share_subject, LocalDateTime.now().toString()))
                    .putExtra(Intent.EXTRA_TEXT, text)
                ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.diag_share_log)))
            }) { Icon(PhevIcons.Share, stringResource(R.string.diag_share), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            val msgCleared = stringResource(R.string.msg_log_cleared)
            IconButton(onClick = { vm.log.clear(); vm.toast(msgCleared) }, enabled = !previous) {
                Icon(PhevIcons.Delete, stringResource(R.string.diag_clear), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        if (previous) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.diag_previous_session), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { previous = false }) { Text(stringResource(R.string.diag_back_to_now)) }
            }
            val noSession = stringResource(R.string.diag_no_saved_session)
            val text = remember { vm.previousSessionLog().ifBlank { noSession } }
            Text(
                text, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.diag_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                items(entries) { e -> LogLine(e) }
            }
        }
    }
}

/** One line: the time, a coloured dot for the kind, the text (warnings and errors in their colour); packets in mono with their bytes. */
@Composable
private fun LogLine(e: LogEntry) {
    val s = LocalStatus.current
    val kindColor = when (e.kind) {
        LogKind.ERROR -> s.bad
        LogKind.WARN -> s.warn
        LogKind.LINK -> MaterialTheme.colorScheme.primary
        LogKind.CMD -> s.charge
        LogKind.STATE -> s.good
        else -> MaterialTheme.colorScheme.outline
    }
    val packet = e.kind == LogKind.TX || e.kind == LogKind.RX
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            "${Format.clock(e.timeMs)}:${"%02d".format((e.timeMs / 1000) % 60)}",
            style = Mono.copy(fontSize = 11.sp), color = muted, modifier = Modifier.width(58.dp).padding(top = 1.dp),
        )
        Box(Modifier.padding(top = 5.dp).size(7.dp).background(kindColor, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (packet) e.kind.tag + " " else "") + e.text,
                style = if (packet) Mono.copy(fontSize = 11.sp) else MaterialTheme.typography.bodySmall,
                color = when (e.kind) {
                    LogKind.ERROR, LogKind.WARN -> kindColor
                    LogKind.TX, LogKind.RX -> muted
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (e.rawHex != null) {
                Text("${e.xor?.let { "x%02x ".format(it) } ?: ""}${e.rawHex}", style = Mono.copy(fontSize = 10.sp), color = Color.Gray)
            }
        }
    }
}

@Composable
private fun RegistersTab(vm: MainViewModel) {
    val car by vm.car.collectAsState()
    val regs = car.registers.values.sortedBy { it.register }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(
                stringResource(R.string.diag_registers_explain),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (regs.isEmpty()) item { Text(stringResource(R.string.diag_registers_empty)) }
        items(regs, key = { it.register }) { r ->
            Column(Modifier.fillMaxWidth()) {
                Row {
                    Text("0x%02x".format(r.register), style = Mono, modifier = Modifier.width(44.dp))
                    Text(Reg.readName(r.register), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text("${r.changes}× · ${Format.ago(r.updatedAtMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(r.hex.chunked(2).joinToString(" "), style = Mono, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ToolsTab(vm: MainViewModel) {
    val car by vm.car.collectAsState()
    val link by vm.link.collectAsState()
    val settings by vm.settings.collectAsState()
    val pending by vm.pending.collectAsState()
    var reg by rememberSaveable { mutableStateOf("") }
    var data by rememberSaveable { mutableStateOf("") }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = stringResource(R.string.diag_connection)) {
            LabeledValue(stringResource(R.string.diag_status), link.text)
            link.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            LabeledValue(stringResource(R.string.settings_protocol), Labels.modelYear(ctx, car.modelYear))
            LabeledValue("ECU", car.ecuVersion ?: "–")
            LabeledValue(stringResource(R.string.settings_car_wifi), car.ssid ?: settings.ssid.ifBlank { "–" })
            val notPaired = stringResource(R.string.diag_not_paired)
            LabeledValue(stringResource(R.string.diag_paired_ap), settings.bssid.ifBlank { notPaired })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.reconnect() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.diag_reconnect)) }
                FilledTonalButton(onClick = { vm.refresh() }, enabled = pending == null, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.diag_full_refresh))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diag_log_pings), modifier = Modifier.weight(1f))
                Switch(checked = settings.recordPings, onCheckedChange = { on -> vm.updateSettings { it.copy(recordPings = on) } })
            }
        }
        SectionCard(title = stringResource(R.string.diag_car)) {
            val msgClockSet = stringResource(R.string.msg_car_clock_set)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.run(CarCommand.SyncClock(LocalDateTime.now()), msgClockSet) }, enabled = pending == null, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.diag_sync_clock))
                }
                FilledTonalButton(onClick = { vm.run(CarCommand.AckClimateCancelled) }, enabled = pending == null, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.diag_clear_climate_warning))
                }
            }
        }
        SectionCard(title = stringResource(R.string.diag_raw_title)) {
            Text(
                stringResource(R.string.diag_raw_warning),
                style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.warn,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(reg, { reg = it.trim() }, label = { Text(stringResource(R.string.diag_register_hex)) }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None))
                OutlinedTextField(data, { data = it.trim() }, label = { Text(stringResource(R.string.diag_data_hex)) }, singleLine = true, modifier = Modifier.weight(2f))
            }
            Spacer(Modifier.height(8.dp))
            val msgInvalid = stringResource(R.string.msg_raw_invalid)
            Button(
                onClick = {
                    val r = reg.removePrefix("0x").toIntOrNull(16)
                    val d = runCatching { data.removePrefix("0x").hexToBytes() }.getOrNull()
                    val cmd = if (r != null && d != null) runCatching { CarCommand.Raw(r, d) }.getOrNull() else null
                    if (cmd == null) vm.toast(msgInvalid)
                    else vm.run(cmd)
                },
                enabled = pending == null,
            ) { Text(stringResource(R.string.diag_send)) }
        }
    }
}
