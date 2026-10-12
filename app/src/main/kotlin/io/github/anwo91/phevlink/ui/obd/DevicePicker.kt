package io.github.anwo91.phevlink.ui.obd

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.obd.AdapterGuess
import io.github.anwo91.phevlink.obd.ObdCandidate
import io.github.anwo91.phevlink.obd.ObdDeviceSpec
import io.github.anwo91.phevlink.obd.ObdDevices
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Chooses the OBD adapter: recent adapters, paired Bluetooth devices, a scan (Bluetooth LE and Classic), or a Wi-Fi
 * adapter. Likely adapters come first; phones, headphones and the like are hidden behind "show all".
 * Asks for the Bluetooth permissions and to switch Bluetooth on when needed.
 */
@Composable
fun DevicePicker(vm: ObdViewModel, onDismiss: () -> Unit, onPicked: (ObdDeviceSpec) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recent by vm.recentDevices.collectAsState()
    var permissionTick by remember { mutableStateOf(0) }
    val hasPerm = remember(permissionTick) { ObdDevices.hasPermissions(ctx) }
    // Listing paired devices only needs BLUETOOTH_CONNECT (Android 12+); on Android 10–11 hasPerm means location
    // (needed for the BLE scan only), which used to hide the paired adapters from users who declined it.
    val canConnect = remember(permissionTick) { ObdDevices.hasConnectPermission(ctx) }
    val btOn = remember(permissionTick) { ObdDevices.isBluetoothOn(ctx) }
    val hasBt = remember { ObdDevices.hasBluetooth(ctx) }
    val bonded = remember(permissionTick) { if (canConnect && btOn) ObdDevices.bondedDevices(ctx) else emptyList() }
    val found = remember { mutableStateListOf<ObdCandidate>() }
    var showAll by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var wifiAddress by remember { mutableStateOf(ObdDeviceSpec.defaultWifi().address) }
    var showWifi by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionTick++ }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionTick++ }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanning = false
    }

    fun pick(spec: ObdDeviceSpec) {
        stopScan()
        onPicked(spec)
    }

    DisposableEffect(Unit) { onDispose { scanJob?.cancel() } }
    // Back from the app's system settings (permission granted there) or from switching Bluetooth on elsewhere:
    // re-check, otherwise the dialog keeps showing the old state until it is reopened.
    LifecycleResumeEffect(Unit) {
        permissionTick++
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (hasBt && !hasPerm) permLauncher.launch(ObdDevices.requiredPermissions())
    }

    AlertDialog(
        onDismissRequest = { stopScan(); onDismiss() },
        title = { Text(stringResource(R.string.obd_pick_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.obd_pick_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (!hasBt) {
                    Text(stringResource(R.string.obd_err_no_bluetooth), color = MaterialTheme.colorScheme.error)
                } else if (!hasPerm) {
                    Text(stringResource(R.string.obd_pick_need_permission), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { permLauncher.launch(ObdDevices.requiredPermissions()) }) {
                            Text(stringResource(R.string.obd_pick_allow))
                        }
                        // After "Don't allow" twice Android no longer shows the request, so "Allow" does nothing:
                        // the app's system settings are then the only way to grant it.
                        TextButton(onClick = {
                            try {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            } catch (e: ActivityNotFoundException) {
                                vm.log.warn("OBD: no app settings screen to open: ${e.message}")
                            }
                        }) { Text(stringResource(R.string.sched_open_settings)) }
                    }
                } else if (!btOn) {
                    Text(stringResource(R.string.obd_err_bluetooth_off), style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = {
                        try {
                            enableLauncher.launch(ObdDevices.enableBluetoothIntent())
                        } catch (e: ActivityNotFoundException) {
                            vm.toast(ctx.getString(R.string.obd_err_bluetooth_off))
                        } catch (e: SecurityException) {
                            permLauncher.launch(ObdDevices.requiredPermissions())
                        }
                    }) { Text(stringResource(R.string.obd_pick_turn_on_bt)) }
                }

                if (recent.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.obd_pick_recent))
                    recent.forEach { d ->
                        DeviceRow(d, AdapterGuess.guess(d.name), onClick = { pick(d) }, onForget = { vm.forget(d) })
                    }
                }
                // Phones, headphones, watches… are hidden unless asked for (AdapterGuess); counted across both lists.
                val pairedList = bonded.filter { b -> recent.none { it.sameDevice(b.spec) } }
                val hiddenCount = (pairedList + found).count { it.hidden }
                if (pairedList.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.obd_pick_paired))
                    pairedList.filter { showAll || !it.hidden }.forEach { c -> DeviceRow(c.spec, c.guess, onClick = { pick(c.spec) }) }
                    if (pairedList.all { it.hidden } && !showAll) {
                        Text(stringResource(R.string.obd_pick_none_paired), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (canConnect && btOn) {
                    Text(stringResource(R.string.obd_pick_none_paired), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (hasPerm && btOn) {
                    SectionLabel(stringResource(R.string.obd_pick_nearby))
                    if (scanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.obd_pick_scanning), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { stopScan() }) { Text(stringResource(R.string.obd_pick_stop)) }
                        }
                    } else {
                        OutlinedButton(onClick = {
                            found.clear()
                            scanning = true
                            scanJob = scope.launch {
                                fun addFound(c: ObdCandidate) {
                                    val known = found.any { it.spec.address.equals(c.spec.address, true) } ||
                                        bonded.any { it.spec.address.equals(c.spec.address, true) }
                                    if (!known) found += c
                                }
                                try {
                                    // Both at once: newer adapters are BLE, most older ELM327 dongles are Classic-only
                                    // and only show up in a discovery (unless already paired).
                                    val jobs = buildList {
                                        if (ObdDevices.hasBluetoothLe(ctx)) add(launch { ObdDevices.scanBle(ctx, 12_000, vm.log).collect { addFound(it) } })
                                        add(launch { ObdDevices.discoverClassic(ctx, vm.log).collect { addFound(it) } })
                                    }
                                    jobs.forEach { it.join() }
                                    val c = found.groupingBy { it.guess.rank }.eachCount()
                                    vm.log.link("OBD: scan found ${found.size} new devices: " + c.entries.joinToString { "${it.value} ${it.key.name.lowercase()}" })
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    vm.toast(e.message ?: e.toString())
                                } finally {
                                    scanning = false
                                }
                            }
                        }) {
                            Icon(PhevIcons.BluetoothSearching, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.obd_pick_scan))
                        }
                    }
                    ObdDevices.sorted(found).filter { showAll || !it.hidden }.forEach { c -> DeviceRow(c.spec, c.guess, onClick = { pick(c.spec) }) }
                    Text(stringResource(R.string.obd_pick_pin_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                if (hiddenCount > 0 || showAll) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(
                            if (showAll) stringResource(R.string.obd_pick_hide_others)
                            else pluralStringResource(R.plurals.obd_pick_show_others, hiddenCount, hiddenCount),
                        )
                    }
                }

                SectionLabel(stringResource(R.string.obd_pick_wifi))
                if (showWifi) {
                    OutlinedTextField(
                        value = wifiAddress, onValueChange = { wifiAddress = it.trim() }, singleLine = true,
                        label = { Text(stringResource(R.string.obd_pick_wifi_address)) }, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.obd_pick_wifi_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { pick(ObdDeviceSpec(ObdDeviceSpec.Kind.WIFI, wifiAddress, ctx.getString(R.string.obd_wifi_adapter_name))) }) {
                        Text(stringResource(R.string.obd_connect))
                    }
                } else {
                    DeviceRow(ObdDeviceSpec.defaultWifi(ctx.getString(R.string.obd_wifi_adapter_name)), AdapterGuess.Guess(AdapterGuess.Rank.ADAPTER), onClick = { showWifi = true })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { stopScan(); onDismiss() }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable
private fun DeviceRow(d: ObdDeviceSpec, guess: AdapterGuess.Guess, onClick: () -> Unit, onForget: (() -> Unit)? = null) {
    val icon: ImageVector = when (d.kind) {
        ObdDeviceSpec.Kind.WIFI -> PhevIcons.Wifi
        else -> PhevIcons.Bluetooth
    }
    val kind = stringResource(
        when (d.kind) {
            ObdDeviceSpec.Kind.CLASSIC -> R.string.obd_kind_classic
            ObdDeviceSpec.Kind.BLE -> R.string.obd_kind_ble
            ObdDeviceSpec.Kind.WIFI -> R.string.obd_kind_wifi
            ObdDeviceSpec.Kind.DEMO -> R.string.obd_kind_demo
        }
    )
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val adapterLike = guess.rank == AdapterGuess.Rank.ADAPTER || guess.rank == AdapterGuess.Rank.LIKELY
        Icon(icon, null, tint = if (adapterLike) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge)
            Text("$kind · ${d.address}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (guess.rank) {
                AdapterGuess.Rank.LIKELY -> Text(stringResource(R.string.obd_pick_likely), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                AdapterGuess.Rank.PROPRIETARY -> Text(
                    stringResource(R.string.obd_pick_proprietary, guess.brand ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
        if (onForget != null) {
            IconButton(onClick = onForget) { Icon(PhevIcons.Close, stringResource(R.string.obd_pick_forget)) }
        }
    }
}
