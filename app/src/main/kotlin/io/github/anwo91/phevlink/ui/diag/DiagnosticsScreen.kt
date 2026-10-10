package io.github.anwo91.phevlink.ui.diag

import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.core.diag.LogEntry
import io.github.anwo91.phevlink.core.diag.LogKind
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.protocol.Reg
import io.github.anwo91.phevlink.core.protocol.hexToBytes
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.LabeledValue
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.ui.theme.Mono
import io.github.anwo91.phevlink.util.Format
import java.time.LocalDateTime

private enum class Filter(val label: String, val kinds: Set<LogKind>) {
    ALL("All", LogKind.entries.toSet()),
    LINK("Connection", setOf(LogKind.LINK, LogKind.WARN, LogKind.ERROR)),
    CMDS("Commands", setOf(LogKind.CMD, LogKind.ERROR, LogKind.WARN)),
    PACKETS("Packets", setOf(LogKind.TX, LogKind.RX)),
    STATE("Car state", setOf(LogKind.STATE)),
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(vm: MainViewModel, modifier: Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Log", "Registers", "Tools").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
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
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var previous by rememberSaveable { mutableStateOf(false) }
    val entries = remember(version, filter) { vm.log.snapshot().filter { it.kind in filter.kinds } }
    val listState = rememberLazyListState()
    LaunchedEffect(version, filter) { if (entries.isNotEmpty() && !previous) listState.scrollToItem(entries.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Filter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
            IconButton(onClick = { previous = !previous }) { Icon(Icons.Outlined.History, "Previous session") }
            IconButton(onClick = {
                val text = if (previous) vm.previousSessionLog() else vm.log.dump()
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "PHEV Link log ${LocalDateTime.now()}")
                    .putExtra(Intent.EXTRA_TEXT, text)
                ctx.startActivity(Intent.createChooser(send, "Share log"))
            }) { Icon(Icons.Outlined.Share, "Share") }
            IconButton(onClick = { vm.log.clear(); vm.toast("Log cleared") }) { Icon(Icons.Outlined.DeleteSweep, "Clear") }
        }
        if (previous) {
            val text = remember { vm.previousSessionLog().ifBlank { "No saved session yet." } }
            Text(
                text, style = Mono,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                items(entries) { e -> LogLine(e) }
            }
        }
    }
}

@Composable
private fun LogLine(e: LogEntry) {
    val s = LocalStatus.current
    val color = when (e.kind) {
        LogKind.ERROR -> s.bad
        LogKind.WARN -> s.warn
        LogKind.LINK -> MaterialTheme.colorScheme.primary
        LogKind.CMD -> s.charge
        LogKind.STATE -> s.good
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.padding(vertical = 2.dp)) {
        Text("${Format.clock(e.timeMs)}:${"%02d".format((e.timeMs / 1000) % 60)} ${e.kind.tag} ${e.text}", style = Mono, color = color)
        if (e.rawHex != null) Text("    ${e.xor?.let { "x%02x ".format(it) } ?: ""}${e.rawHex}", style = Mono, color = Color.Gray)
    }
}

@Composable
private fun RegistersTab(vm: MainViewModel) {
    val car by vm.car.collectAsState()
    val regs = car.registers.values.sortedBy { it.register }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(
                "Every register the car has reported, raw. Values that change while you operate the car are clues for new features.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (regs.isEmpty()) item { Text("Nothing yet — connect to the car first.") }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "Connection") {
            LabeledValue("Status", link.text)
            link.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            LabeledValue("Protocol", car.modelYear.label)
            LabeledValue("ECU", car.ecuVersion ?: "–")
            LabeledValue("Car Wi-Fi", car.ssid ?: settings.ssid.ifBlank { "–" })
            LabeledValue("Paired access point", settings.bssid.ifBlank { "not paired" })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.reconnect() }, modifier = Modifier.weight(1f)) { Text("Reconnect") }
                FilledTonalButton(onClick = { vm.refresh() }, enabled = pending == null, modifier = Modifier.weight(1f)) { Text("Full refresh") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Log keepalive pings", modifier = Modifier.weight(1f))
                Switch(checked = settings.recordPings, onCheckedChange = { on -> vm.updateSettings { it.copy(recordPings = on) } })
            }
        }
        SectionCard(title = "Car") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.run(CarCommand.SyncClock(LocalDateTime.now()), "Car clock set") }, enabled = pending == null, modifier = Modifier.weight(1f)) { Text("Sync clock") }
                FilledTonalButton(onClick = { vm.run(CarCommand.AckClimateCancelled) }, enabled = pending == null, modifier = Modifier.weight(1f)) { Text("Clear climate warning") }
            }
        }
        SectionCard(title = "Raw register write") {
            Text(
                "For experiments. Writes go straight to the car — only use values you understand.",
                style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.warn,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(reg, { reg = it.trim() }, label = { Text("Register (hex)") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None))
                OutlinedTextField(data, { data = it.trim() }, label = { Text("Data (hex)") }, singleLine = true, modifier = Modifier.weight(2f))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val r = reg.removePrefix("0x").toIntOrNull(16)
                    val d = runCatching { data.removePrefix("0x").hexToBytes() }.getOrNull()
                    val cmd = if (r != null && d != null) runCatching { CarCommand.Raw(r, d) }.getOrNull() else null
                    if (cmd == null) vm.toast("Enter a register (00–ff) and 1–64 bytes of hex data")
                    else vm.run(cmd)
                },
                enabled = pending == null,
            ) { Text("Send") }
        }
    }
}
