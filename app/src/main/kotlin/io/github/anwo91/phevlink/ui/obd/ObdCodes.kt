package io.github.anwo91.phevlink.ui.obd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.knowledge.Tips
import io.github.anwo91.phevlink.core.obd.DtcKind
import io.github.anwo91.phevlink.core.obd.DtcRecord
import io.github.anwo91.phevlink.core.obd.ObdPhase
import io.github.anwo91.phevlink.core.obd.ObdState
import io.github.anwo91.phevlink.core.obd.dtc.DtcDatabase
import io.github.anwo91.phevlink.core.obd.dtc.DtcSeverity
import io.github.anwo91.phevlink.ui.common.Pill
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.ui.theme.Mono

@Composable
fun severityColor(s: DtcSeverity): Color {
    val st = LocalStatus.current
    return when (s) {
        DtcSeverity.INFO -> st.idle
        DtcSeverity.CHECK_SOON -> st.charge
        DtcSeverity.CHECK_NOW -> st.warn
        DtcSeverity.STOP -> st.bad
    }
}

private fun kindLabel(k: DtcKind): Int = when (k) {
    DtcKind.STORED -> R.string.obd_dtc_stored
    DtcKind.PENDING -> R.string.obd_dtc_pending
    DtcKind.PERMANENT -> R.string.obd_dtc_permanent
}

@Composable
fun CodesTab(vm: ObdViewModel, s: ObdState, onOpenTip: (String) -> Unit) {
    val lang = vm.lang()
    val busy by vm.busy.collectAsState()
    val lastScan by vm.lastScan.collectAsState()
    var extended by rememberSaveable { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val canTalk = (s.phase == ObdPhase.READY || s.phase == ObdPhase.POLLING) && s.carReachable

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard(title = stringResource(R.string.obd_codes_title)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.obd_scan_all_units), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = extended, onCheckedChange = { extended = it })
                }
                Text(stringResource(R.string.obd_scan_all_units_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.scan(extended) }, enabled = canTalk && busy == null) { Text(stringResource(R.string.obd_scan)) }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = canTalk && busy == null) { Text(stringResource(R.string.obd_clear)) }
                }
                BusyRow(busy)
                if (!canTalk) Text(stringResource(R.string.obd_need_car), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                lastScan?.let { r ->
                    Text(
                        if (r.success) stringResource(R.string.obd_scan_result, r.dtcs.map { it.code }.distinct().size, r.respondingEcus.size) else r.message,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        val byEcu = s.dtcs.groupBy { it.ecuName }
        if (s.lastDtcScanMs != null && s.dtcs.isEmpty()) {
            item { Text(stringResource(R.string.obd_codes_none), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(8.dp)) }
        }
        for ((ecu, list) in byEcu) {
            item(key = "ecu:$ecu") {
                Text(ecu, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
            items(list.distinctBy { it.code to it.kind }, key = { "dtc:$ecu:${it.code}:${it.kind}" }) { d ->
                DtcRow(d, lang) { detail = d.code }
            }
        }
        item {
            SectionCard(title = stringResource(R.string.obd_lookup_title)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(40) }, singleLine = true,
                    label = { Text(stringResource(R.string.obd_lookup_hint)) }, modifier = Modifier.fillMaxWidth(),
                )
                val q = query.trim()
                if (q.length >= 2) {
                    val hits = remember(q, lang) { DtcDatabase.search(q, 25, lang) }
                    if (hits.isEmpty() && DtcDatabase.isValid(q)) {
                        TextButton(onClick = { detail = DtcDatabase.normalize(q) }) { Text(stringResource(R.string.obd_lookup_explain, q.uppercase())) }
                    }
                    hits.forEach { (code, title) ->
                        Row(Modifier.fillMaxWidth().clickable { detail = code }.padding(vertical = 6.dp)) {
                            Text(code, style = Mono, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                            Text(title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.obd_clear_title)) },
            text = { Text(stringResource(R.string.obd_clear_text)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clear() }) { Text(stringResource(R.string.obd_clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    detail?.let { code ->
        DtcDetailDialog(code, lang, s.dtcs.filter { it.code == code }, onDismiss = { detail = null }, onOpenTip = { detail = null; onOpenTip(it) })
    }
}

@Composable
private fun DtcRow(d: DtcRecord, lang: String, onClick: () -> Unit) {
    val e = remember(d.code, lang) { DtcDatabase.explain(d.code, lang) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(72.dp)) {
            Text(d.code, style = Mono, fontWeight = FontWeight.Bold)
            Text(stringResource(kindLabel(d.kind)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(e.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Pill(e.severity.label(lang), severityColor(e.severity))
    }
}

/** Full explanation of one code. [records] are the reads of that code (which ECU, stored/pending). */
@Composable
fun DtcDetailDialog(code: String, lang: String, records: List<DtcRecord>, onDismiss: () -> Unit, onOpenTip: (String) -> Unit) {
    val e = remember(code, lang) { DtcDatabase.explain(code, lang) }
    val tips = remember(code, lang) { Tips.load(lang).tips.filter { code in it.relatedCodes } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$code · ${e.title}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(e.severity.label(lang), severityColor(e.severity))
                    Pill(e.system.label(lang), MaterialTheme.colorScheme.primary)
                }
                e.subsystem?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                records.forEach { r ->
                    Text(
                        stringResource(R.string.obd_dtc_reported_by, r.ecuName, stringResource(kindLabel(r.kind))),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(e.summary, style = MaterialTheme.typography.bodyMedium)
                Heading(stringResource(R.string.obd_dtc_drive))
                Text(e.driveAdvice, style = MaterialTheme.typography.bodyMedium)
                Text(e.severity.advice(lang), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Heading(stringResource(R.string.obd_dtc_causes))
                e.causes.forEach { Bullet(it) }
                Heading(stringResource(R.string.obd_dtc_checks))
                e.checks.forEachIndexed { i, c -> Bullet(c, "${i + 1}.") }
                if (e.manufacturerSpecific) {
                    Text(stringResource(R.string.obd_dtc_manufacturer_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tips.isNotEmpty()) {
                    Heading(stringResource(R.string.obd_dtc_tips))
                    tips.forEach { t ->
                        TextButton(onClick = { onOpenTip(t.id) }) { Text(t.title) }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.obd_dtc_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun Bullet(text: String, mark: String = "•") {
    Row {
        Text(mark, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
