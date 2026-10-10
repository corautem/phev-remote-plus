package io.github.anwo91.phevlink.ui.obd

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.obd.ObdState
import io.github.anwo91.phevlink.ui.common.LabeledValue
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.theme.Mono

@Composable
fun ToolsTab(vm: ObdViewModel, s: ObdState) {
    val ctx = LocalContext.current
    val recording by vm.recording.collectAsState()
    val traffic by vm.logTraffic.collectAsState()
    val terminal by vm.terminal.collectAsState()
    var cmd by rememberSaveable { mutableStateOf("") }
    var lastFile by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmCmd by remember { mutableStateOf<String?>(null) }
    // Anything that isn't a plain read goes through a confirmation first.
    fun send(c: String) {
        if (ObdViewModel.isReadOnlyCommand(c)) vm.sendTerminal(c) else confirmCmd = c.trim()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- recording
        SectionCard(title = stringResource(R.string.obd_rec_title)) {
            val rec = recording
            if (rec != null) {
                Text(stringResource(R.string.obd_rec_running, rec.rows), style = MaterialTheme.typography.bodyMedium)
                Text(rec.file.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { lastFile = rec.file.absolutePath; vm.toggleRecording() }, modifier = Modifier.padding(top = 6.dp)) {
                    Text(stringResource(R.string.obd_rec_stop))
                }
            } else {
                Text(stringResource(R.string.obd_rec_hint), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.toggleRecording() }) { Text(stringResource(R.string.obd_rec_start)) }
                    val f = lastFile ?: vm.obd.lastRecording?.absolutePath
                    if (f != null) {
                        OutlinedButton(onClick = {
                            val intent = vm.obd.shareIntent(java.io.File(f))
                            if (intent != null) {
                                try {
                                    ctx.startActivity(intent)
                                } catch (e: Exception) {
                                    vm.toast(f)
                                }
                            } else {
                                vm.toast(f)
                            }
                        }) { Text(stringResource(R.string.obd_rec_share)) }
                    }
                }
            }
        }

        // ---- adapter
        s.adapter?.let { a ->
            SectionCard(title = stringResource(R.string.obd_adapter_title)) {
                LabeledValue(stringResource(R.string.obd_adapter_version), a.version)
                a.description?.let { LabeledValue(stringResource(R.string.obd_adapter_description), it) }
                a.stnChip?.let { LabeledValue("STN", it) }
                LabeledValue(stringResource(R.string.obd_adapter_protocol), a.protocol)
                LabeledValue(stringResource(R.string.obd_adapter_fc), stringResource(if (a.supportsFlowControlHeader) R.string.yes else R.string.no))
                LabeledValue(stringResource(R.string.obd_adapter_filter), stringResource(if (a.supportsReceiveFilter) R.string.yes else R.string.no))
                if (a.isClone) a.cloneNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                a.batteryDataMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (s.vin != null) LabeledValue("VIN", s.vin ?: "")
                if (s.ecus.isNotEmpty()) {
                    Text(stringResource(R.string.obd_ecus), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    s.ecus.forEach { e ->
                        Text("• ${e.name} (${"%03X".format(e.txId)}→${"%03X".format(e.rxId)})" + (e.reportedName?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (s.unavailable.isNotEmpty()) {
                    Text(stringResource(R.string.obd_unavailable, s.unavailable.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { vm.obd.client?.resetUnavailable() }) { Text(stringResource(R.string.obd_retry_unavailable)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(stringResource(R.string.obd_log_traffic), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = traffic, onCheckedChange = { vm.setLogTraffic(it) })
                }
            }
        }

        // ---- raw battery data
        s.batteryHealth?.rawBmu?.let { raw ->
            SectionCard(title = stringResource(R.string.obd_raw_bmu)) {
                Text(stringResource(R.string.obd_raw_bmu_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val rows = raw.toList().chunked(8)
                Column(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                    rows.forEachIndexed { r, bytes ->
                        Text(
                            "%02d: ".format(r * 8) + bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                            style = Mono,
                        )
                    }
                }
            }
        }

        // ---- terminal
        SectionCard(title = stringResource(R.string.obd_terminal_title)) {
            Text(stringResource(R.string.obd_terminal_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ATI", "ATRV", "ATDPN", "0100", "0101", "03", "07", "0902").forEach { c ->
                    AssistChip(onClick = { send(c) }, label = { Text(c) })
                }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 260.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)
                    .horizontalScroll(rememberScrollState()),
            ) {
                terminal.forEach { line -> Text(line, style = Mono) }
            }
            OutlinedTextField(
                value = cmd, onValueChange = { cmd = it.take(60) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.obd_terminal_command)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(cmd); cmd = "" }),
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { send(cmd); cmd = "" }, enabled = cmd.isNotBlank()) { Text(stringResource(R.string.obd_terminal_send)) }
                TextButton(onClick = { vm.clearTerminal() }) { Text(stringResource(R.string.obd_terminal_clear)) }
            }
        }
    }
    confirmCmd?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmCmd = null },
            title = { Text(stringResource(R.string.obd_terminal_confirm_title)) },
            text = { Text(stringResource(R.string.obd_terminal_confirm_text, c)) },
            confirmButton = { TextButton(onClick = { confirmCmd = null; vm.sendTerminal(c) }) { Text(stringResource(R.string.obd_terminal_send)) } },
            dismissButton = { TextButton(onClick = { confirmCmd = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
