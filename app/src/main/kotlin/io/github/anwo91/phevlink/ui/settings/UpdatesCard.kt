package io.github.anwo91.phevlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.link.BridgeAdminController.UpdatePhase
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.update.AppUpdater.Phase
import kotlinx.coroutines.delay

/**
 * Settings → Updates, at the top: the app's and the bridge's version, whether there is a newer one, and one button
 * each to update. Progress stays right here (no jumping to another screen, no alarming "offline" while the bridge
 * restarts on its new version).
 */
@Composable
fun UpdatesCard(vm: MainViewModel, hasBridge: Boolean, onOpenBridge: () -> Unit) {
    val app by vm.appUpdater.state.collectAsState()
    val bridge by vm.bridgeAdmin.state.collectAsState()
    val ctx = LocalContext.current
    val good = LocalStatus.current.good
    LaunchedEffect(hasBridge) {
        vm.appUpdater.checkIfDue()
        if (hasBridge) {
            // While an update runs here, keep following the bridge (Settings doesn't run the bridge page's polling).
            while (true) {
                vm.bridgeAdmin.refreshForSettings()
                delay(if (vm.bridgeAdmin.state.value.phase != UpdatePhase.IDLE) 4_000 else 60_000)
            }
        }
    }
    SectionCard(title = stringResource(R.string.upd_title)) {
        // ---- the app
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.upd_app, app.current), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        app.phase == Phase.CHECKING -> stringResource(R.string.upd_checking)
                        app.available -> stringResource(R.string.upd_available, app.latest ?: "?")
                        app.checkFailed -> stringResource(R.string.upd_no_internet)
                        app.latest != null -> stringResource(R.string.upd_up_to_date)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (app.available) good else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (app.phase) {
            Phase.DOWNLOADING -> {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.upd_downloading), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { app.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
            }
            Phase.NEEDS_PERMISSION -> {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.upd_permission), style = MaterialTheme.typography.bodySmall)
            }
            Phase.FAILED -> {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.upd_failed, app.error ?: "?"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            else -> Unit
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (app.available && app.phase != Phase.DOWNLOADING) {
                Button(onClick = { vm.appUpdater.downloadAndInstall(ctx) }) {
                    Text(stringResource(if (app.phase == Phase.READY || app.phase == Phase.NEEDS_PERMISSION) R.string.upd_install else R.string.upd_app_button))
                }
            }
            OutlinedButton(onClick = { vm.appUpdater.check() }, enabled = app.phase != Phase.CHECKING && app.phase != Phase.DOWNLOADING) {
                Text(stringResource(R.string.bp_check_button))
            }
        }

        if (!hasBridge) return@SectionCard
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        // ---- the bridge
        val u = bridge.update
        Text(
            stringResource(R.string.upd_bridge, bridge.build ?: u?.current ?: "?"),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        )
        when (bridge.phase) {
            UpdatePhase.STARTING -> BusyLine(stringResource(R.string.bp_update_starting))
            UpdatePhase.RUNNING -> BusyLine(stringResource(R.string.bp_update_running))
            UpdatePhase.RESTARTING -> BusyLine(stringResource(R.string.bp_update_restarting))
            UpdatePhase.DONE -> {
                Text(stringResource(R.string.bp_update_done, bridge.phaseDetail ?: "?"), style = MaterialTheme.typography.bodySmall, color = good)
                TextButton(onClick = { vm.bridgeAdmin.dismissUpdateResult() }) { Text(stringResource(R.string.carview_close)) }
            }
            UpdatePhase.FAILED -> {
                val why = bridge.phaseDetail.let { if (it == "timeout") stringResource(R.string.bp_update_timeout) else it ?: "?" }
                Text(stringResource(R.string.bp_update_failed, why), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.bridgeAdmin.dismissUpdateResult() }) { Text(stringResource(R.string.carview_close)) }
            }
            UpdatePhase.IDLE -> {
                Text(
                    when {
                        bridge.restarting -> stringResource(R.string.bp_restarting)
                        bridge.reachable == false -> stringResource(R.string.upd_bridge_away)
                        !bridge.canUpdate && bridge.reachable == true -> stringResource(R.string.bp_update_manual)
                        u == null -> stringResource(R.string.upd_checking)
                        u.latest == null -> stringResource(R.string.bp_update_no_internet)
                        u.available -> stringResource(R.string.upd_available, u.latest)
                        else -> stringResource(R.string.upd_up_to_date)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (u?.available == true && bridge.canUpdate) good else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (u?.available == true && bridge.canUpdate) {
                        Button(onClick = { vm.bridgeAdmin.startUpdate() }) { Text(stringResource(R.string.bp_update_button)) }
                    }
                    OutlinedButton(onClick = onOpenBridge) { Text(stringResource(R.string.bp_title)) }
                }
            }
        }
        // While it updates, show what the installer writes (same as on the bridge page).
        val lines = u?.log.orEmpty()
        if ((bridge.phase == UpdatePhase.RUNNING || bridge.phase == UpdatePhase.RESTARTING) && lines.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(lines.takeLast(4).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
private fun BusyLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
