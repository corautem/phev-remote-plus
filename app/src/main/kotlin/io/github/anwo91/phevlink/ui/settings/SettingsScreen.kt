package io.github.anwo91.phevlink.ui.settings

import io.github.anwo91.phevlink.ui.home.carpic.CarPictureOptions
import io.github.anwo91.phevlink.core.diag.LogKind
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.anwo91.phevlink.ui.common.quantityString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.BuildConfig
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.protocol.ModelYear
import io.github.anwo91.phevlink.data.AppSettings
import io.github.anwo91.phevlink.data.Route
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.LabeledValue
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.common.rememberCarPairing
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import io.github.anwo91.phevlink.util.AppLocale
import io.github.anwo91.phevlink.util.Labels
import io.github.anwo91.phevlink.weather.MetNo
import io.github.anwo91.phevlink.link.BridgeClient
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * With a bridge, the phone can only reach the car's Wi-Fi (to register) while the bridge is off it: the car takes one
 * device at a time. Lets the bridge step aside for 10 minutes, and shows until when.
 */
@Composable
private fun BridgeStepAside(vm: MainViewModel) {
    val c = vm.bridgeAdmin
    val st by c.state.collectAsState()
    LaunchedEffect(Unit) { c.refreshWifi() }
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.settings_reg_bridge_note), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(6.dp))
    val until = st.wifi?.pausedUntilMs
    when {
        st.wifi?.paused == true && until != null -> {
            Text(
                stringResource(R.string.settings_reg_bridge_paused, io.github.anwo91.phevlink.util.Format.clock(until)),
                style = MaterialTheme.typography.bodyMedium, color = io.github.anwo91.phevlink.ui.theme.LocalStatus.current.good,
            )
            TextButton(onClick = { c.resumeCarWifi() }, enabled = st.busy == null) { Text(stringResource(R.string.settings_reg_bridge_resume)) }
        }
        st.reachable == true && !st.canPause -> Text(
            stringResource(R.string.settings_reg_bridge_old), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
        else -> FilledTonalButton(onClick = { c.pauseCarWifi() }, enabled = st.busy == null && st.canPause, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_reg_bridge_pause))
        }
    }
    Spacer(Modifier.height(6.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    modifier: Modifier,
    onOpenBridgeSetup: () -> Unit = {},
    onOpenBridge: () -> Unit = {},
    onOpenSmartHome: (String) -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
) {
    val s by vm.settings.collectAsState()
    val car by vm.car.collectAsState()
    val pending by vm.pending.collectAsState()
    val demo by io.github.anwo91.phevlink.link.DemoMode.active.collectAsState()
    fun set(t: (AppSettings) -> AppSettings) = vm.updateSettings(t)
    val notSet = stringResource(R.string.set_sum_not_set)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))

        // Updates (app + bridge) stay at the top, easy to find.
        UpdatesCard(vm, s.hasBridge, onOpenBridge)

        // ------------------------------------------------------------ car connection
        SettingsGroup(stringResource(R.string.set_group_connection)) {
            // The bridge has a page of its own (status, log, updates, smart home); without one this sets it up.
            SettingsRow(
                PhevIcons.Bridge, stringResource(R.string.bp_title),
                if (s.hasBridge) stringResource(R.string.set_sum_bridge_page, s.bridgeUrl.substringAfter("://").substringBefore('/'))
                else stringResource(R.string.set_sum_bridge_none),
                first = true, onClick = if (s.hasBridge) onOpenBridge else onOpenBridgeSetup,
            )
            SettingsRow(PhevIcons.Route, stringResource(R.string.settings_route_title), stringResource(s.route.labelRes)) {
                RouteContent(vm, s)
            }
            SettingsRow(PhevIcons.Wifi, stringResource(R.string.settings_car_wifi), s.ssid.ifBlank { notSet }) {
                CarWifiFields(vm, s)
            }
            SettingsRow(
                PhevIcons.Key, stringResource(R.string.settings_registration_title),
                car.registrations?.let { quantityString(R.plurals.home_phones_registered, it, it) },
            ) {
                Text(stringResource(R.string.settings_registration_intro), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.registration_steps), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                LabeledValue(stringResource(R.string.settings_registered_phones), car.registrations?.toString() ?: "–")
                if (s.hasBridge) BridgeStepAside(vm)
                val msgRegistered = stringResource(R.string.msg_phone_registered)
                val msgUnregistered = stringResource(R.string.msg_phone_unregistered)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.run(CarCommand.RegisterPhone, msgRegistered) }, enabled = pending == null, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_register))
                    }
                    OutlinedButton(onClick = { vm.run(CarCommand.UnregisterPhone, msgUnregistered) }, enabled = pending == null, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_unregister))
                    }
                }
                Spacer(Modifier.height(4.dp))
                ExpandableHelp(stringResource(R.string.registration_full_title), stringResource(R.string.registration_full))
                ExpandableHelp(stringResource(R.string.registration_wifi_stopped_title), stringResource(R.string.registration_wifi_stopped))
            }
            SettingsRow(PhevIcons.Power, stringResource(R.string.settings_stay_title), stringResource(R.string.set_sum_stay)) {
                StayConnectedContent(vm)
            }
        }

        // ------------------------------------------------------------ climate and alerts
        SettingsGroup(stringResource(R.string.set_group_climate)) {
            val quickSummary = stringResource(
                if (s.quickSmart) R.string.set_sum_quick_smart else R.string.set_sum_quick,
                stringResource(Labels.mode(s.quickMode)), s.quickDuration.minutes,
            )
            SettingsRow(PhevIcons.Smart, stringResource(R.string.settings_quick_title), quickSummary, first = true) {
                Toggle(stringResource(R.string.settings_quick_smart), s.quickSmart) { on -> set { it.copy(quickSmart = on) } }
                Text(
                    stringResource(if (s.quickSmart) R.string.settings_quick_fallback else R.string.settings_quick_always),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ClimateMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(selected = s.quickMode == m, onClick = { set { it.copy(quickMode = m) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) {
                            Text(stringResource(Labels.mode(m)))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ClimateDuration.entries.forEachIndexed { i, d ->
                        SegmentedButton(selected = s.quickDuration == d, onClick = { set { it.copy(quickDuration = d) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) {
                            Text(stringResource(R.string.dur_min, d.minutes))
                        }
                    }
                }
            }
            val alertList = alertSwitches(s.alerts)
            val alertsSummary = if (s.phoneAlerts) stringResource(R.string.set_sum_on_of, alertList.count { it }, alertList.size) else stringResource(R.string.set_sum_off)
            SettingsRow(PhevIcons.Bell, stringResource(R.string.settings_alerts_title), alertsSummary) {
                AlertsContent(vm, s)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Toggle(stringResource(R.string.settings_watch), s.watchWhileBusy) { on -> set { it.copy(watchWhileBusy = on) } }
                Text(
                    stringResource(R.string.settings_watch_explain, s.watchIntervalMinutes),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val weatherSummary = if (s.homeLat != null && s.homeLon != null) "%.2f, %.2f".format(java.util.Locale.ROOT, s.homeLat, s.homeLon) else notSet
            SettingsRow(PhevIcons.Forecast, stringResource(R.string.settings_weather_title), weatherSummary) {
                WeatherContent(vm, s)
            }
        }

        // ------------------------------------------------------------ smart home (through the bridge)
        SettingsGroup(stringResource(R.string.set_group_smarthome)) {
            val needsBridge = stringResource(R.string.set_sum_needs_bridge)
            SettingsRow(
                PhevIcons.Speaker, stringResource(R.string.mt_title),
                if (s.hasBridge) stringResource(R.string.set_sum_google) else needsBridge, first = true, onClick = { onOpenSmartHome("matter") },
            )
            SettingsRow(
                PhevIcons.Home, stringResource(R.string.sh_title),
                if (s.hasBridge) stringResource(R.string.set_sum_ha) else needsBridge, onClick = { onOpenSmartHome("ha") },
            )
        }

        // ------------------------------------------------------------ look and language
        SettingsGroup(stringResource(R.string.set_group_look)) {
            LanguageRow()
            SettingsRow(PhevIcons.Theme, stringResource(R.string.settings_appearance), listOf(s.carName, s.deviceName).filter { it.isNotBlank() }.joinToString(" · ")) {
                var carName by rememberSaveable(s.carName) { mutableStateOf(s.carName) }
                OutlinedTextField(
                    carName, { v -> carName = v; set { it.copy(carName = v) } },
                    label = { Text(stringResource(R.string.settings_car_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // This phone's name in the activity log (per phone, not shared).
                var phoneName by rememberSaveable(s.phoneName) { mutableStateOf(s.phoneName) }
                OutlinedTextField(
                    phoneName, { v -> phoneName = v.take(30); set { it.copy(phoneName = v.take(30)) } },
                    label = { Text(stringResource(R.string.set_phone_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.set_phone_name_hint, android.os.Build.MODEL ?: "")) },
                )
                Spacer(Modifier.height(4.dp))
                Toggle(stringResource(R.string.settings_dynamic_color), s.dynamicColor) { on -> set { it.copy(dynamicColor = on) } }
                Text(
                    stringResource(R.string.settings_dynamic_color_explain),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val styleLabel = stringResource(
                when (s.carStyle) {
                    io.github.anwo91.phevlink.data.CarStyle.TOP -> R.string.set_carpic_top
                    io.github.anwo91.phevlink.data.CarStyle.SIDE -> R.string.set_carpic_side
                    io.github.anwo91.phevlink.data.CarStyle.THREE_D -> R.string.set_carpic_3d
                }
            )
            SettingsRow(PhevIcons.Car, stringResource(R.string.set_carpic_title), styleLabel) {
                CarPictureOptions(
                    s.carStyle, s.tapHints,
                    onStyle = { st -> vm.log.add(LogKind.CMD, "Car picture: style $st"); set { it.copy(carStyle = st) } },
                    onHints = { h -> vm.log.add(LogKind.CMD, "Car picture: hints $h"); set { it.copy(tapHints = h) } },
                    colour = s.carColour,
                    onColour = { c -> vm.log.add(LogKind.CMD, "Car picture: colour $c"); set { it.copy(carColour = c) } },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.set_carpic_explain),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ------------------------------------------------------------ help and support
        SettingsGroup(stringResource(R.string.set_group_help)) {
            SettingsRow(PhevIcons.Help, stringResource(R.string.help_title), stringResource(R.string.set_sum_help), first = true, onClick = onOpenHelp)
            SettingsRow(PhevIcons.Log, stringResource(R.string.tab_diagnostics), stringResource(R.string.set_sum_diagnostics), onClick = onOpenDiagnostics)
            SettingsRow(
                PhevIcons.Demo, stringResource(R.string.settings_demo_title),
                stringResource(if (demo) R.string.settings_demo_running else R.string.set_sum_demo),
            ) {
                Text(
                    stringResource(R.string.settings_demo_explain),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (demo) {
                    Text(stringResource(R.string.settings_demo_running), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { vm.setDemo(false) }) { Text(stringResource(R.string.settings_demo_stop)) }
                } else {
                    FilledTonalButton(onClick = { vm.setDemo(true) }) { Text(stringResource(R.string.settings_demo_start)) }
                }
            }
            SettingsRow(PhevIcons.Gauge, stringResource(R.string.settings_advanced), stringResource(R.string.set_sum_advanced)) {
                Toggle(stringResource(R.string.settings_sync_clock), s.syncClock) { on -> set { it.copy(syncClock = on) } }
                Spacer(Modifier.height(4.dp))
                var range by rememberSaveable(s.fullRangeKm) { mutableStateOf(s.fullRangeKm.toString()) }
                OutlinedTextField(range, { v -> range = v; v.toIntOrNull()?.takeIf { it in 10..150 }?.let { km -> set { it.copy(fullRangeKm = km) } } },
                    label = { Text(stringResource(R.string.settings_full_range)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.settings_protocol), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(
                        ModelYear.UNKNOWN to stringResource(R.string.settings_protocol_auto), ModelYear.MY14 to "2014–17", ModelYear.MY18 to "2018+",
                    ).forEachIndexed { i, (my, label) ->
                        SegmentedButton(selected = s.modelYearOverride == my, onClick = { set { it.copy(modelYearOverride = my) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                    }
                }
                if (s.devMode) {
                    Spacer(Modifier.height(8.dp))
                    var host by rememberSaveable(s.hostOverride) { mutableStateOf(s.hostOverride) }
                    OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.settings_test_address)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), supportingText = { Text(stringResource(R.string.settings_test_address_hint)) })
                    FilledTonalButton(onClick = { set { it.copy(hostOverride = host) } }) { Text(stringResource(R.string.settings_apply)) }
                }
            }
            SettingsRow(PhevIcons.Info, stringResource(R.string.settings_about), BuildConfig.VERSION_NAME) {
                var devTaps by remember { mutableStateOf(0) }
                val devOnMsg = stringResource(R.string.settings_dev_on)
                LabeledValue(
                    stringResource(R.string.settings_version), BuildConfig.VERSION_NAME,
                    modifier = if (s.devMode) Modifier else Modifier.clickable {
                        devTaps++
                        if (devTaps >= 7) { set { it.copy(devMode = true) }; vm.toast(devOnMsg) }
                    },
                )
                // The car as it reports itself (was the footer of the Car tab).
                car.vin?.let { LabeledValue("VIN", it) }
                car.ecuVersion?.let { LabeledValue("ECU", it) }
                car.registrations?.let { LabeledValue(stringResource(R.string.settings_registered_phones), it.toString()) }
                Text(
                    stringResource(R.string.settings_about_text),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { vm.runSetupAgain() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.settings_run_setup))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** How the app reaches the car: Automatic / car Wi-Fi only / bridge only, and — tucked away — the bridge address and key by hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteContent(vm: MainViewModel, s: AppSettings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Route.entries.forEachIndexed { i, r ->
            SegmentedButton(selected = s.route == r, onClick = { vm.updateSettings { it.copy(route = r) } }, shape = SegmentedButtonDefaults.itemShape(i, Route.entries.size)) {
                Text(stringResource(r.labelRes), maxLines = 1)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.settings_route_explain),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    var manual by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { manual = !manual }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.set_bridge_manual), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Icon(if (manual) PhevIcons.ChevronUp else PhevIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
    if (manual) {
        var url by rememberSaveable(s.bridgeUrl) { mutableStateOf(s.bridgeUrl) }
        var token by rememberSaveable(s.bridgeToken) { mutableStateOf(s.bridgeToken) }
        var testingBridge by remember { mutableStateOf(false) }
        val msgSaved = stringResource(R.string.msg_saved)
        OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.settings_bridge_address)) }, placeholder = { Text("http://100.x.y.z:8099") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.settings_bridge_token)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            FilledTonalButton(onClick = {
                vm.updateSettings { it.copy(bridgeUrl = url, bridgeToken = token) }
                vm.toast(msgSaved)
            }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_save))
            }
            OutlinedButton(
                onClick = {
                    testingBridge = true
                    scope.launch {
                        try {
                            val snap = BridgeClient(url.trim().trimEnd('/'), token.trim(), vm.log, ctx).fetchStatus()
                            val msg = if (snap != null) {
                                ctx.getString(
                                    R.string.msg_bridge_ok,
                                    snap.car.updatedAtMs?.let { io.github.anwo91.phevlink.util.Format.ago(it, context = ctx) }
                                        ?: ctx.getString(R.string.msg_bridge_no_car_data),
                                )
                            } else ctx.getString(R.string.msg_bridge_unreachable)
                            vm.log.link("Bridge test: $msg")
                            vm.toast(msg)
                        } finally {
                            testingBridge = false
                        }
                    }
                },
                enabled = !testingBridge, modifier = Modifier.weight(1f),
            ) { Text(stringResource(if (testingBridge) R.string.settings_testing else R.string.common_test)) }
        }
    }
}

/** Where the weather forecast is read for (smart climate): numbers by hand, or the phone's location once. */
@Composable
private fun WeatherContent(vm: MainViewModel, s: AppSettings) {
    val ctx = LocalContext.current
    val msgLocationSaved = stringResource(R.string.msg_location_saved)
    val msgLocationNone = stringResource(R.string.msg_location_none)
    val msgLocationDenied = stringResource(R.string.msg_location_denied)
    val msgLocationInvalid = stringResource(R.string.msg_location_invalid)
    val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            val loc = MetNo.lastKnownLocation(ctx)
            if (loc != null) {
                vm.updateSettings { it.copy(homeLat = loc.first, homeLon = loc.second) }
                vm.toast(msgLocationSaved)
            } else vm.toast(msgLocationNone)
        } else vm.toast(msgLocationDenied)
    }
    Text(stringResource(R.string.settings_weather_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    var lat by rememberSaveable(s.homeLat) { mutableStateOf(s.homeLat?.toString() ?: "") }
    var lon by rememberSaveable(s.homeLon) { mutableStateOf(s.homeLon?.toString() ?: "") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(lat, { lat = it }, label = { Text(stringResource(R.string.settings_latitude)) }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(lon, { lon = it }, label = { Text(stringResource(R.string.settings_longitude)) }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        FilledTonalButton(onClick = {
            val la = lat.replace(',', '.').toDoubleOrNull(); val lo = lon.replace(',', '.').toDoubleOrNull()
            if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                vm.updateSettings { it.copy(homeLat = la, homeLon = lo) }; vm.toast(msgLocationSaved)
            } else vm.toast(msgLocationInvalid)
        }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_save)) }
        OutlinedButton(onClick = { locationPerm.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_use_current))
        }
    }
}

/** "Language" row: the app language, independent of the phone's (see [AppLocale]). First row of its group. */
@Composable
private fun LanguageRow() {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf(AppLocale.current(ctx)) }
    var picking by remember { mutableStateOf(false) }
    val systemName = stringResource(R.string.settings_language_system)
    fun nameOf(tag: String) = AppLocale.displayName(tag) ?: systemName

    SettingsRow(PhevIcons.Language, stringResource(R.string.settings_language), nameOf(current), first = true, onClick = { picking = true })

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.common_cancel)) } },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    (listOf(AppLocale.SYSTEM) + AppLocale.SUPPORTED).forEach { tag ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = tag == current,
                                    role = Role.RadioButton,
                                    onClick = {
                                        picking = false
                                        if (tag != current) {
                                            current = tag
                                            AppLocale.set(ctx, tag)
                                            // Android 13+ recreates the activity by itself.
                                            if (Build.VERSION.SDK_INT < 33) ctx.findActivity()?.recreate()
                                        }
                                    },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == current, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(nameOf(tag), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
        )
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** A heading that opens a longer help text below it. */
@Composable
internal fun ExpandableHelp(title: String, text: String) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Icon(if (open) PhevIcons.ChevronUp else PhevIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
    if (open) Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
}

/**
 * Why the link drops while the car is switched on, and (2018+ cars) the official "Always Communicate" switch
 * (register 0x18 write, 0x0b read). 2014–2017 cars have no such setting: the official app doesn't offer it.
 */
@Composable
private fun StayConnectedContent(vm: MainViewModel) {
    val car by vm.car.collectAsState()
    val link by vm.link.collectAsState()
    val pending by vm.pending.collectAsState()
    run {
        when (car.modelYear) {
            ModelYear.MY18, ModelYear.MY24 -> {
                val on = car.wifiAlwaysOn
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_wifi_always_on), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = on == true,
                        onCheckedChange = { vm.run(CarCommand.WifiAlwaysOn(it)) },
                        enabled = link.connected && pending == null && on != null,
                    )
                }
                Text(
                    stringResource(if (on == null) R.string.settings_wifi_always_unknown else R.string.settings_wifi_always_on_explain),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ModelYear.MY14 -> Text(stringResource(R.string.settings_wifi_power_on_my14), style = MaterialTheme.typography.bodySmall)
            ModelYear.UNKNOWN -> Text(stringResource(R.string.settings_wifi_power_on_unknown), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.phone_wifi_tips_title), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.phone_wifi_tips), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
fun CarWifiCard(vm: MainViewModel, s: AppSettings, showTest: Boolean = true, showSilent: Boolean = true) {
    SectionCard(title = stringResource(R.string.settings_car_wifi)) { CarWifiFields(vm, s, showTest, showSilent) }
}

/** The car's Wi-Fi name and password, test, and (with [showSilent]) pairing with the car's access point. */
@Composable
fun CarWifiFields(vm: MainViewModel, s: AppSettings, showTest: Boolean = true, showSilent: Boolean = true) {
    var ssid by rememberSaveable(s.ssid) { mutableStateOf(s.ssid) }
    var pw by rememberSaveable(s.password) { mutableStateOf(s.password) }
    var showPw by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val pwError = io.github.anwo91.phevlink.data.passwordProblemRes(pw).takeIf { pw.isNotEmpty() }?.let { stringResource(it) }
    run {
        Text(
            stringResource(R.string.settings_car_wifi_explain),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pw, { pw = it }, label = { Text(stringResource(R.string.settings_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPw = !showPw }) {
                    Icon(
                        if (showPw) PhevIcons.EyeOff else PhevIcons.Eye,
                        stringResource(if (showPw) R.string.settings_hide else R.string.settings_show),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = pwError != null,
            supportingText = if (pwError != null) { { Text(pwError) } } else null,
        )
        val msgSaved = stringResource(R.string.msg_saved)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            FilledTonalButton(onClick = {
                vm.updateSettings { it.copy(ssid = ssid.trim(), password = pw) }
                vm.toast(msgSaved)
            }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_save)) }
            if (showTest) Button(
                onClick = {
                    vm.updateSettings { it.copy(ssid = ssid.trim(), password = pw) }
                    testing = true
                    vm.testConnection { ok, msg -> testing = false; vm.toast(msg) }
                },
                enabled = !testing, modifier = Modifier.weight(1f),
            ) { Text(stringResource(if (testing) R.string.settings_testing else R.string.common_test)) }
        }
        if (showSilent) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(stringResource(R.string.settings_silent_title), style = MaterialTheme.typography.labelLarge)
            SilentConnections(vm, s, ssid.trim())
        }
    }
}

/**
 * "Pair with car" (Android companion pairing with the car's access point), which only matters when the phone itself
 * joins the car's Wi-Fi. Tells the user so: with a bridge set up it says they can skip it.
 */
@Composable
fun SilentConnections(vm: MainViewModel, s: AppSettings, ssid: String = s.ssid) {
    val ctx = LocalContext.current
    val msgPairingCancelled = stringResource(R.string.msg_pairing_cancelled)
    val pairing = rememberCarPairing { bssid ->
        if (bssid != null) {
            vm.updateSettings { it.copy(bssid = bssid) }
            vm.toast(ctx.getString(R.string.msg_paired, bssid))
        } else vm.toast(msgPairingCancelled)
    }
    val usesBridge = s.hasBridge
    if (s.bssid.isNotBlank()) {
        Text(stringResource(R.string.settings_paired_with, s.bssid), style = MaterialTheme.typography.bodySmall)
    }
    if (usesBridge) {
        Text(
            stringResource(R.string.settings_silent_bridge_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (s.bssid.isBlank()) {
        Text(stringResource(R.string.settings_silent_no_bridge), style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlinedButton(onClick = { pairing.start(ssid) { err -> vm.toast(err) } }, modifier = Modifier.weight(1f)) {
            Text(stringResource(if (s.bssid.isNotBlank()) R.string.settings_pair_again else R.string.settings_pair))
        }
        if (s.bssid.isNotBlank()) OutlinedButton(onClick = { vm.updateSettings { it.copy(bssid = "") } }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_forget))
        }
    }
}
