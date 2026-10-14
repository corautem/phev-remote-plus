package io.github.anwo91.phevlink.ui.bridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.link.BridgeSetupApi
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.settings.ExpandableHelp
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.AppLocale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the bridge says about Matter (GET /api/matter). */
private data class MtInfo(
    val enabled: Boolean,
    val phase: String,
    val error: String?,
    val errorCode: String?,
    val manual: String?,
    val qrRows: List<String>,
    val fabrics: List<String>,
    val climate: Boolean,
    val headlights: Boolean,
    val parking: Boolean,
    val programMb: Long?,
    val restarts: Int,
    val lastExit: String?,
) {
    /** For the log: "waiting to be added (program 84 MB)", "added to Google Home", "error no-ipv6: …". */
    fun describe(): String = buildString {
        append(
            when (phase) {
                "waiting" -> "waiting to be added"
                "paired" -> "added to ${fabrics.joinToString().ifEmpty { "a controller" }}"
                "error" -> "error${errorCode?.let { " $it" } ?: ""}: ${error ?: "?"}"
                else -> phase
            },
        )
        val extra = listOfNotNull(
            programMb?.let { "program $it MB" },
            restarts.takeIf { it > 0 }?.let { "$it restart(s)" },
            lastExit?.let { "last exit $it" },
        )
        if (extra.isNotEmpty()) append(" (").append(extra.joinToString()).append(")")
    }

    companion object {
        fun from(m: Map<String, Any?>): MtInfo {
            val d = m["devices"] as? Map<*, *>
            return MtInfo(
                enabled = m["enabled"] == true,
                phase = m["phase"]?.toString() ?: "off",
                error = m["error"]?.toString(),
                errorCode = m["errorCode"]?.toString(),
                manual = m["manual"]?.toString(),
                qrRows = (m["qrRows"] as? List<*>).orEmpty().map { it.toString() },
                fabrics = (m["fabrics"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("name")?.toString() },
                climate = d?.get("climate") != false,
                headlights = d?.get("headlights") == true,
                parking = d?.get("parking") == true,
                programMb = (m["programMb"] as? Number)?.toLong(),
                restarts = (m["restarts"] as? Number)?.toInt() ?: 0,
                lastExit = m["lastExit"]?.toString(),
            )
        }
    }
}

/** "3497-011-2332", as Google Home shows it. */
private fun formatCode(c: String) = if (c.length == 11) "${c.take(4)}-${c.substring(4, 7)}-${c.drop(7)}" else c

/**
 * Bridge page → Google Home: the bridge speaks Matter, so Google Home (and Apple Home, Alexa) can add the car by
 * scanning a code — no Home Assistant, no cloud. Walks through Google's one-time developer-console step, shows the
 * code, then who it's added to and what is shared. Logged ("Matter: …").
 */
@Composable
fun MatterCard(vm: MainViewModel, url: String, token: String, supported: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val good = LocalStatus.current.good
    val bad = MaterialTheme.colorScheme.error
    var info by remember { mutableStateOf<MtInfo?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    var lastSeen by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val r = BridgeSetupApi.matter(url, token)
        if (r?.code == 200) {
            val n = MtInfo.from(r.body)
            info = n
            loadFailed = false
            // Every change of what the bridge reports goes into the log (what the card showed, and when).
            val seen = listOf(n.phase, n.errorCode, n.error, n.fabrics.joinToString(), n.restarts, n.lastExit).joinToString("|")
            if (seen != lastSeen) {
                lastSeen = seen
                vm.log.link("Matter: bridge says ${n.describe()}")
            }
        } else {
            loadFailed = true
            if (lastSeen != "failed") {
                lastSeen = "failed"
                vm.log.link("Matter: the bridge didn't answer (${r?.code?.let { "HTTP $it" } ?: BridgeSetupApi.lastFailure ?: "no answer"})")
            }
        }
    }

    fun save(changes: Map<String, Any?>, what: String, done: Int? = null) {
        busy = true
        message = null
        scope.launch {
            vm.log.link("Matter: $what")
            val r = BridgeSetupApi.putMatter(url, token, changes + ("language" to AppLocale.effectiveLanguage(ctx)))
            busy = false
            if (r?.code == 200) {
                info = MtInfo.from(r.body)
                done?.let { message = ctx.getString(it) to true }
                vm.log.link("Matter: now ${info?.describe()}")
            } else {
                message = ctx.getString(R.string.sh_err_bridge) to false
                vm.log.link("Matter: $what failed (${r?.code ?: "no answer"})")
            }
        }
    }

    LaunchedEffect(url, supported) {
        if (!supported) return@LaunchedEffect
        while (true) {
            reload()
            // While waiting to be added, look often so the card says "Added" right away.
            delay(if (info?.phase == "waiting" || info?.phase == "starting") 4_000 else 20_000)
        }
    }

    SectionCard(title = stringResource(R.string.mt_title)) {
        Text(stringResource(R.string.mt_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        val i = info
        when {
            !supported -> Text(stringResource(R.string.mt_needs_update), style = MaterialTheme.typography.bodySmall)
            i == null -> if (loadFailed) Text(stringResource(R.string.sh_err_bridge), style = MaterialTheme.typography.bodySmall) else MtBusy(stringResource(R.string.sh_loading))
            !i.enabled -> {
                Text(stringResource(R.string.mt_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { save(mapOf("enabled" to true), "switching on") }, enabled = !busy) { Text(stringResource(R.string.mt_set_up)) }
            }
            i.phase == "starting" -> MtBusy(stringResource(R.string.mt_starting))
            i.phase == "error" -> {
                Text(
                    when (i.errorCode) {
                        "missing", "no-node" -> stringResource(R.string.mt_err_missing)
                        "no-ipv6" -> stringResource(R.string.mt_err_ipv6)
                        else -> stringResource(R.string.mt_err_other, i.error ?: "?")
                    },
                    style = MaterialTheme.typography.bodyMedium, color = bad,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            BridgeSetupApi.putMatter(url, token, mapOf("enabled" to false))
                            BridgeSetupApi.putMatter(url, token, mapOf("enabled" to true, "language" to AppLocale.effectiveLanguage(ctx)))
                            reload()
                            busy = false
                        }
                    }, enabled = !busy) { Text(stringResource(R.string.mt_try_again)) }
                    TextButton(onClick = { save(mapOf("enabled" to false), "switching off", R.string.mt_off_done) }, enabled = !busy) { Text(stringResource(R.string.sh_turn_off)) }
                }
            }
            i.phase == "waiting" -> {
                // Step 1: Google's one-time developer-console registration (uncertified Matter devices).
                Text(stringResource(R.string.mt_step1_title), style = MaterialTheme.typography.labelLarge)
                ExpandableHelp(stringResource(R.string.mt_step1_show), stringResource(R.string.mt_step1))
                val uri = LocalUriHandler.current
                OutlinedButton(onClick = { runCatching { uri.openUri("https://console.home.google.com/") } }) { Text(stringResource(R.string.mt_open_console)) }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                // Step 2: scan the code in Google Home.
                Text(stringResource(R.string.mt_step2_title), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.mt_step2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                if (i.qrRows.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(i.qrRows, 220) }
                }
                i.manual?.let { code ->
                    val clipboard = LocalClipboardManager.current
                    val copied = stringResource(R.string.mt_copied)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.mt_code, formatCode(code)), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(code)); vm.toast(copied) }) { Text(stringResource(R.string.mt_copy_code)) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                MtBusy(stringResource(R.string.mt_waiting))
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                ShareChoices(i, busy) { d -> save(mapOf("devices" to d), "sharing $d") }
                TextButton(onClick = { save(mapOf("enabled" to false), "switching off", R.string.mt_off_done) }, enabled = !busy) { Text(stringResource(R.string.sh_turn_off)) }
            }
            else -> {
                Text(stringResource(R.string.mt_paired, i.fabrics.joinToString().ifEmpty { "?" }), style = MaterialTheme.typography.bodyMedium, color = good)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.mt_voice, stringResource(R.string.mt_climate_name)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.mt_away_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                ShareChoices(i, busy) { d -> save(mapOf("devices" to d), "sharing $d") }
                ExpandableHelp(stringResource(R.string.mt_other_title), stringResource(R.string.mt_other_apps))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirmRemove = true }, enabled = !busy) { Text(stringResource(R.string.mt_remove)) }
                    TextButton(onClick = { save(mapOf("enabled" to false), "switching off", R.string.mt_off_done) }, enabled = !busy) { Text(stringResource(R.string.sh_turn_off)) }
                }
            }
        }
        if (busy) {
            Spacer(Modifier.height(6.dp))
            MtBusy(stringResource(R.string.mt_starting))
        }
        message?.let { (text, ok) ->
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (ok) good else bad)
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            text = { Text(stringResource(R.string.mt_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    busy = true
                    scope.launch {
                        vm.log.link("Matter: removing every pairing")
                        val r = BridgeSetupApi.resetMatter(url, token)
                        busy = false
                        if (r?.code == 200) {
                            info = MtInfo.from(r.body)
                            message = ctx.getString(R.string.mt_removed) to true
                        } else message = ctx.getString(R.string.sh_err_bridge) to false
                    }
                }) { Text(stringResource(R.string.mt_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun ShareChoices(i: MtInfo, busy: Boolean, onChange: (Map<String, Boolean>) -> Unit) {
    Text(stringResource(R.string.mt_share_title), style = MaterialTheme.typography.labelLarge)
    @Composable
    fun choice(label: Int, checked: Boolean, key: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onChange(mapOf(key to it)) }, enabled = !busy)
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        }
    }
    choice(R.string.mt_share_climate, i.climate, "climate")
    choice(R.string.mt_share_headlights, i.headlights, "headlights")
    choice(R.string.mt_share_parking, i.parking, "parking")
    Text(stringResource(R.string.mt_share_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The QR code from the bridge (rows of 0/1), black on white with a 4-module quiet zone, readable in dark mode too. */
@Composable
private fun QrCode(rows: List<String>, sizeDp: Int) {
    val n = rows.size
    Canvas(Modifier.size(sizeDp.dp).background(Color.White, RoundedCornerShape(8.dp))) {
        val cell = size.minDimension / (n + 8)
        val o = cell * 4
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                if (ch == '1') drawRect(Color.Black, Offset(o + c * cell, o + r * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
private fun MtBusy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
