package io.github.anwo91.phevlink.ui.bridge

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.session.LinkStatus
import io.github.anwo91.phevlink.link.BridgeAdminController
import io.github.anwo91.phevlink.link.BridgeAdminController.Action
import io.github.anwo91.phevlink.link.BridgeAdminController.Outcome
import io.github.anwo91.phevlink.link.BridgeAdminController.UpdatePhase
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.LabeledValue
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import io.github.anwo91.phevlink.ui.settings.ExpandableHelp
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.Format
import kotlinx.coroutines.delay

/**
 * The bridge page: what the bridge is doing (status, car link, car Wi-Fi, the computer, its own log) and what you can
 * do with it (connect, restart, restart the computer, update its software) — no computer or terminal needed, except
 * once for bridges from before in-app updates.
 */
@Composable
fun BridgeScreen(vm: MainViewModel, modifier: Modifier, onBack: () -> Unit, onOpenSetup: () -> Unit, onOpenActivity: () -> Unit = {}) {
    val c = vm.bridgeAdmin
    val st by c.state.collectAsState()
    val s by vm.settings.collectAsState()
    DisposableEffect(Unit) {
        c.open()
        onDispose { c.close() }
    }
    BackHandler(onBack = onBack)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = System.currentTimeMillis() } }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(PhevIcons.Back, stringResource(R.string.common_back)) }
            Text(stringResource(R.string.bp_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!s.hasBridge || s.bridgeToken.isBlank()) {
                SectionCard {
                    Text(stringResource(R.string.bp_none), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenSetup) { Text(stringResource(R.string.settings_bridge_setup)) }
                }
                return@Column
            }
            StatusCard(st, now)
            // While the bridge restarts or updates (asked for here), keep everything on screen — no "offline" scare.
            if (st.reachable == true || st.restarting || st.phase != UpdatePhase.IDLE) {
                ActionsCard(c, st)
                RecentActivityCard(vm, onOpenActivity)
                // Home Assistant (MQTT): only once the bridge has said what it can do.
                if (st.features.isNotEmpty()) {
                    MatterCard(vm, s.bridgeUrl, s.bridgeToken, supported = "matter" in st.features)
                    SmartHomeCard(vm, s.bridgeUrl, s.bridgeToken, supported = "smarthome" in st.features, carName = s.carName.ifBlank { "Outlander PHEV" })
                }
                UpdateCard(c, st)
                LogCard(st)
            }
            TextButton(onClick = onOpenSetup) { Text(stringResource(R.string.bp_setup_again)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Note(text: String, color: Color = Color.Unspecified) {
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
    )
}

@Composable
private fun StatusCard(st: BridgeAdminController.State, now: Long) {
    val status = LocalStatus.current
    SectionCard(title = stringResource(R.string.bp_status)) {
        if (st.restarting) {
            Busy(stringResource(R.string.bp_restarting))
            return@SectionCard
        }
        when (st.reachable) {
            null -> Busy(stringResource(R.string.bp_checking))
            false -> Note(stringResource(R.string.bp_unreachable, st.url), MaterialTheme.colorScheme.error)
            true -> {
                LabeledValue(stringResource(R.string.bp_name), listOfNotNull(st.name, st.url.substringAfter("://")).joinToString(" · "))
                LabeledValue(
                    stringResource(R.string.bp_software),
                    st.build ?: stringResource(R.string.bp_software_old, st.apiVersion ?: "?"),
                )
                val wifi = st.wifi
                LabeledValue(
                    stringResource(R.string.bp_car_wifi),
                    when {
                        wifi == null || wifi.ssid == null -> stringResource(R.string.bp_wifi_none)
                        wifi.connected -> wifi.signal?.let { stringResource(R.string.bp_wifi_on, it) } ?: stringResource(R.string.bp_wifi_on_nosignal)
                        else -> stringResource(R.string.bp_wifi_off)
                    },
                    valueColor = if (wifi?.connected == true) status.good else Color.Unspecified,
                )
                val link = st.link
                LabeledValue(
                    stringResource(R.string.bp_car_link),
                    when (link) {
                        is LinkStatus.Connected -> stringResource(R.string.bp_link_connected)
                        is LinkStatus.Connecting, LinkStatus.Handshaking -> stringResource(R.string.bp_link_connecting)
                        is LinkStatus.Retrying -> stringResource(R.string.bp_link_retry, link.lastError)
                        is LinkStatus.Idle -> stringResource(R.string.bp_link_idle)
                        null -> "–"
                    },
                    valueColor = when (link) {
                        is LinkStatus.Connected -> status.good
                        is LinkStatus.Retrying -> status.warn
                        else -> Color.Unspecified
                    },
                )
                st.carUpdatedAtMs?.let {
                    LabeledValue(
                        stringResource(R.string.bp_last_data),
                        Format.ago(it, now) + (st.battery?.let { b -> " · $b %" } ?: ""),
                    )
                }
                st.startedAtMs?.let { LabeledValue(stringResource(R.string.bp_started), Format.ago(it, now)) }
                val sys = st.system
                (sys["uptimeS"] as? Number)?.toLong()?.let { up ->
                    LabeledValue(stringResource(R.string.bp_computer_started), Format.ago(now - up * 1000, now))
                }
                (sys["model"] as? String)?.let { LabeledValue(stringResource(R.string.bp_computer), it) }
                (sys["cpuTempC"] as? Number)?.toDouble()?.let { t ->
                    LabeledValue(stringResource(R.string.bp_temp), "%.0f °C".format(t), valueColor = if (t >= 75) status.warn else Color.Unspecified)
                }
                (sys["memAvailableMb"] as? Number)?.toLong()?.let { LabeledValue(stringResource(R.string.bp_memory), "$it MB") }
                (sys["diskFreeMb"] as? Number)?.toLong()?.let { mb ->
                    LabeledValue(
                        stringResource(R.string.bp_disk),
                        if (mb >= 2048) "%.1f GB".format(mb / 1024.0) else "$mb MB",
                        valueColor = if (mb < 200) status.warn else Color.Unspecified,
                    )
                }
                if (st.emulator) {
                    Spacer(Modifier.height(6.dp))
                    Note(stringResource(R.string.bp_test_mode), status.warn)
                }
            }
        }
    }
}

@Composable
private fun ActionsCard(c: BridgeAdminController, st: BridgeAdminController.State) {
    var confirmReboot by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.bp_actions)) {
        val busy = st.busy != null
        FilledTonalButton(onClick = { c.connectCar() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bp_connect))
        }
        if (st.canRestart) {
            FilledTonalButton(onClick = { c.restart() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bp_restart))
            }
        }
        if (st.canPause) {
            // The car's Wi-Fi takes one device at a time: the bridge steps aside so a phone can use it (to register).
            val w = st.wifi
            val until = w?.pausedUntilMs
            if (w?.paused == true && until != null) {
                Note(stringResource(R.string.bp_paused, Format.clock(until)))
                FilledTonalButton(onClick = { c.resumeCarWifi() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bp_resume))
                }
            } else {
                FilledTonalButton(onClick = { c.pauseCarWifi() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bp_pause))
                }
            }
        }
        if (st.canInvite) {
            // Another phone pairs with the bridge without anyone unplugging it: open the door for 10 minutes.
            val until = st.inviteUntilMs
            if (st.inviteOpen && until != null) {
                Note(stringResource(R.string.bp_invite_open, Format.clock(until)), LocalStatus.current.good)
            } else {
                FilledTonalButton(onClick = { c.invitePhone() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bp_invite))
                }
            }
        }
        if (st.canUpdate) {
            OutlinedButton(onClick = { confirmReboot = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bp_reboot))
            }
        }
        st.busy?.let { Busy(stringResource(R.string.bp_checking)) }
        val last = st.lastAction
        if (last != null && st.busy == null) {
            Spacer(Modifier.height(4.dp))
            if (last.second == Outcome.OK) {
                Note(
                    stringResource(
                        when (last.first) {
                            Action.CONNECT -> R.string.bp_done_connect
                            Action.RESTART -> R.string.bp_done_restart
                            Action.REBOOT -> R.string.bp_done_reboot
                            Action.CHECK -> R.string.bp_done_check
                            Action.PAUSE -> R.string.bp_done_pause
                            Action.RESUME -> R.string.bp_done_resume
                            Action.INVITE -> R.string.bp_done_invite
                        },
                    ),
                )
            } else {
                Note(stringResource(R.string.bp_failed, st.lastActionDetail ?: "?"), MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text(stringResource(R.string.bp_reboot_confirm_title)) },
            text = { Text(stringResource(R.string.bp_reboot_confirm)) },
            confirmButton = { TextButton(onClick = { confirmReboot = false; c.reboot() }) { Text(stringResource(R.string.bp_restart_short)) } },
            dismissButton = { TextButton(onClick = { confirmReboot = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun UpdateCard(c: BridgeAdminController, st: BridgeAdminController.State) {
    val status = LocalStatus.current
    SectionCard(title = stringResource(R.string.bp_update)) {
        if (!st.canUpdate) {
            // Bridges from before in-app updates (or without the helper): one manual update, then never again.
            Note(stringResource(R.string.bp_update_manual))
            ExpandableHelp(stringResource(R.string.bs_update_help_title), stringResource(R.string.bp_update_help, st.url.substringAfter("://").substringBefore(':')))
            return@SectionCard
        }
        val u = st.update
        LabeledValue(stringResource(R.string.bp_installed), st.build ?: u?.current ?: "?")
        LabeledValue(stringResource(R.string.bp_newest), u?.latest ?: stringResource(R.string.bp_unknown))
        Spacer(Modifier.height(6.dp))
        when (st.phase) {
            UpdatePhase.STARTING -> Busy(stringResource(R.string.bp_update_starting))
            UpdatePhase.RUNNING -> Busy(stringResource(R.string.bp_update_running))
            UpdatePhase.RESTARTING -> Busy(stringResource(R.string.bp_update_restarting))
            UpdatePhase.DONE -> {
                Text(stringResource(R.string.bp_update_done, st.phaseDetail ?: "?"), color = status.good, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { c.dismissUpdateResult() }) { Text(stringResource(R.string.carview_close)) }
            }
            UpdatePhase.FAILED -> {
                val why = st.phaseDetail.let { if (it == "timeout") stringResource(R.string.bp_update_timeout) else it ?: "?" }
                Note(stringResource(R.string.bp_update_failed, why), MaterialTheme.colorScheme.error)
                TextButton(onClick = { c.dismissUpdateResult() }) { Text(stringResource(R.string.carview_close)) }
            }
            UpdatePhase.IDLE -> {
                when {
                    u == null -> Unit
                    u.latest == null -> Note(stringResource(R.string.bp_update_no_internet))
                    u.available -> Note(stringResource(R.string.bp_update_available), status.good)
                    else -> Note(stringResource(R.string.bp_up_to_date))
                }
                if (u?.result == "update-failed" && !u.running) {
                    Note(stringResource(R.string.bp_update_failed, u.detail ?: "?"), MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (u?.available == true) Button(onClick = { c.startUpdate() }, enabled = st.busy == null) { Text(stringResource(R.string.bp_update_button)) }
                    OutlinedButton(onClick = { c.checkForUpdate() }, enabled = st.busy == null) { Text(stringResource(R.string.bp_check_button)) }
                }
            }
        }
        // While an update runs, show what the installer writes.
        val lines = u?.log.orEmpty()
        if (st.phase in setOf(UpdatePhase.RUNNING, UpdatePhase.RESTARTING, UpdatePhase.FAILED) && lines.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            LogBox(lines.takeLast(12), maxHeight = 180)
        }
    }
}

@Composable
private fun LogCard(st: BridgeAdminController.State) {
    val ctx = LocalContext.current
    val shareTitle = stringResource(R.string.bp_share)
    SectionCard(title = stringResource(R.string.bp_log), trailing = {
        if (st.log.isNotEmpty()) {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "PHEV Link bridge log")
                    putExtra(Intent.EXTRA_TEXT, st.log.joinToString("\n"))
                }
                ctx.startActivity(Intent.createChooser(send, shareTitle))
            }) { Text(shareTitle) }
        }
    }) {
        Note(stringResource(R.string.bp_log_hint))
        Spacer(Modifier.height(6.dp))
        if (st.log.isEmpty()) Note(stringResource(R.string.bp_log_empty)) else LogBox(st.log, maxHeight = 360)
    }
}

/** Monospace lines in a scrollable box that follows the newest line (at the bottom). */
@Composable
private fun LogBox(lines: List<String>, maxHeight: Int) {
    val v = rememberScrollState()
    LaunchedEffect(lines.size, lines.lastOrNull()) { v.scrollTo(v.maxValue) }
    Box(
        Modifier.fillMaxWidth().height(maxHeight.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Text(
            lines.joinToString("\n"),
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
            modifier = Modifier.verticalScroll(v).horizontalScroll(rememberScrollState()),
        )
    }
}

/** The last few lines of the activity log (who did what, charging…), with a way to the whole log. */
@Composable
private fun RecentActivityCard(vm: MainViewModel, onOpenActivity: () -> Unit) {
    var recent by remember { mutableStateOf<List<io.github.anwo91.phevlink.core.activity.Activity>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val s = vm.settings.value
            val (st, list) = io.github.anwo91.phevlink.ui.activity.fetchBridgeActivity(s.bridgeUrl, s.bridgeToken, 6)
            if (st == io.github.anwo91.phevlink.ui.activity.BridgeState.OK) recent = list
            delay(30_000)
        }
    }
    val list = recent ?: return
    SectionCard(title = stringResource(R.string.act_title)) {
        list.forEach { io.github.anwo91.phevlink.ui.activity.ActivityRow(it) }
        TextButton(onClick = onOpenActivity) { Text(stringResource(R.string.bp_activity_all)) }
    }
}
