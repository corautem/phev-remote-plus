package io.github.anwo91.phevlink.ui.obd

import android.content.ActivityNotFoundException
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Wifi
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.obd.ObdDeviceSpec
import io.github.anwo91.phevlink.obd.ObdDevices
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Chooses the OBD adapter: recent adapters, paired Bluetooth devices, a Bluetooth LE scan, or a Wi-Fi adapter.
 * Asks for the Bluetooth permissions and to switch Bluetooth on when needed.
 */
@Composable
fun DevicePicker(vm: ObdViewModel, onDismiss: () -> Unit, onPicked: (ObdDeviceSpec) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recent by vm.recentDevices.collectAsState()
    var permissionTick by remember { mutableStateOf(0) }
    val hasPerm = remember(permissionTick) { ObdDevices.hasPermissions(ctx) }
    val btOn = remember(permissionTick) { ObdDevices.isBluetoothOn(ctx) }
    val hasBt = remember { ObdDevices.hasBluetooth(ctx) }
    val bonded = remember(permissionTick) { if (hasPerm && btOn) ObdDevices.bondedDevices(ctx) else emptyList() }
    val found = remember { mutableStateListOf<ObdDeviceSpec>() }
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
                    FilledTonalButton(onClick = { permLauncher.launch(ObdDevices.requiredPermissions()) }) {
                        Text(stringResource(R.string.obd_pick_allow))
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
                        DeviceRow(d, onClick = { pick(d) }, onForget = { vm.forget(d) })
                    }
                }
                if (bonded.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.obd_pick_paired))
                    bonded.filter { b -> recent.none { it.sameDevice(b) } }.forEach { d -> DeviceRow(d, onClick = { pick(d) }) }
                } else if (hasPerm && btOn) {
                    Text(stringResource(R.string.obd_pick_none_paired), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (hasPerm && btOn && ObdDevices.hasBluetoothLe(ctx)) {
                    SectionLabel(stringResource(R.string.obd_pick_ble))
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
                                try {
                                    ObdDevices.scanBle(ctx, 12_000, vm.log).collect { d ->
                                        if (found.none { it.sameDevice(d) } && bonded.none { it.address.equals(d.address, true) }) found += d
                                    }
                                } catch (e: Exception) {
                                    vm.toast(e.message ?: e.toString())
                                } finally {
                                    scanning = false
                                }
                            }
                        }) {
                            Icon(Icons.Outlined.BluetoothSearching, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.obd_pick_scan))
                        }
                    }
                    found.sortedByDescending { it.looksLikeObd }.forEach { d -> DeviceRow(d, onClick = { pick(d) }) }
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
                    DeviceRow(ObdDeviceSpec.defaultWifi(ctx.getString(R.string.obd_wifi_adapter_name)), onClick = { showWifi = true })
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
private fun DeviceRow(d: ObdDeviceSpec, onClick: () -> Unit, onForget: (() -> Unit)? = null) {
    val icon: ImageVector = when (d.kind) {
        ObdDeviceSpec.Kind.WIFI -> Icons.Outlined.Wifi
        else -> Icons.Outlined.Bluetooth
    }
    val kind = stringResource(
        when (d.kind) {
            ObdDeviceSpec.Kind.CLASSIC -> R.string.obd_kind_classic
            ObdDeviceSpec.Kind.BLE -> R.string.obd_kind_ble
            ObdDeviceSpec.Kind.WIFI -> R.string.obd_kind_wifi
        }
    )
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = if (d.looksLikeObd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge)
            Text("$kind · ${d.address}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onForget != null) {
            IconButton(onClick = onForget) { Icon(Icons.Outlined.Close, stringResource(R.string.obd_pick_forget)) }
        }
    }
}
