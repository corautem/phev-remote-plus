package io.github.anwo91.phevlink.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.BuildConfig
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
import io.github.anwo91.phevlink.weather.MetNo
import io.github.anwo91.phevlink.link.BridgeClient
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier) {
    val s by vm.settings.collectAsState()
    val car by vm.car.collectAsState()
    val pending by vm.pending.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun set(t: (AppSettings) -> AppSettings) = vm.updateSettings(t)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))

        // ------------------------------------------------------------ car wifi
        CarWifiCard(vm, s)

        // -------------------------------------------------------- registration
        SectionCard(title = "Phone registration") {
            Text(
                "The car only talks to phones it has registered (it remembers up to a few). To register this phone:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(REGISTRATION_STEPS, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LabeledValue("Registered phones (car says)", car.registrations?.toString() ?: "–")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.run(CarCommand.RegisterPhone, "Phone registered") }, enabled = pending == null, modifier = Modifier.weight(1f)) { Text("Register") }
                OutlinedButton(onClick = { vm.run(CarCommand.UnregisterPhone, "Phone unregistered") }, enabled = pending == null, modifier = Modifier.weight(1f)) { Text("Unregister") }
            }
        }

        // -------------------------------------------------------------- route
        SectionCard(title = "How to reach the car") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Route.entries.forEachIndexed { i, r ->
                    SegmentedButton(selected = s.route == r, onClick = { set { it.copy(route = r) } }, shape = SegmentedButtonDefaults.itemShape(i, Route.entries.size)) {
                        Text(r.label, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Automatic uses the bridge when it has fresh data about the car (car parked at home), otherwise the car's own Wi-Fi.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            var url by rememberSaveable(s.bridgeUrl) { mutableStateOf(s.bridgeUrl) }
            var token by rememberSaveable(s.bridgeToken) { mutableStateOf(s.bridgeToken) }
            OutlinedTextField(url, { url = it }, label = { Text("Bridge address") }, placeholder = { Text("http://100.x.y.z:8099") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it }, label = { Text("Bridge token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(onClick = { set { it.copy(bridgeUrl = url, bridgeToken = token) } }, modifier = Modifier.weight(1f)) { Text("Save") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val snap = BridgeClient(url.trim().trimEnd('/'), token.trim(), vm.log).fetchStatus()
                        vm.toast(if (snap != null) "Bridge OK — car data ${snap.car.updatedAtMs?.let { io.github.anwo91.phevlink.util.Format.ago(it) } ?: "not yet available"}" else "Bridge not reachable — see Diagnostics")
                    }
                }, modifier = Modifier.weight(1f)) { Text("Test") }
            }
        }

        // ---------------------------------------------------------- one tap
        SectionCard(title = "One-tap climate (widget, tile, shortcuts)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Smart (pick from weather)", modifier = Modifier.weight(1f))
                Switch(checked = s.quickSmart, onCheckedChange = { on -> set { it.copy(quickSmart = on) } })
            }
            Text(if (s.quickSmart) "Fallback when the forecast is unavailable:" else "Always:", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ClimateMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(selected = s.quickMode == m, onClick = { set { it.copy(quickMode = m) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(m.label) }
                }
            }
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ClimateDuration.entries.forEachIndexed { i, d ->
                    SegmentedButton(selected = s.quickDuration == d, onClick = { set { it.copy(quickDuration = d) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text("${d.minutes} min") }
                }
            }
        }

        // ----------------------------------------------------------- weather
        val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) {
                val loc = MetNo.lastKnownLocation(ctx)
                if (loc != null) {
                    set { it.copy(homeLat = loc.first, homeLon = loc.second) }
                    vm.toast("Location saved")
                } else vm.toast("No location available yet — open a maps app once and try again")
            } else vm.toast("Location permission denied — enter coordinates by hand")
        }
        SectionCard(title = "Weather location") {
            Text("Where the car usually is in the morning. Used only for the MET Norway forecast.", style = MaterialTheme.typography.bodySmall)
            var lat by rememberSaveable(s.homeLat) { mutableStateOf(s.homeLat?.toString() ?: "") }
            var lon by rememberSaveable(s.homeLon) { mutableStateOf(s.homeLon?.toString() ?: "") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(onClick = {
                    val la = lat.replace(',', '.').toDoubleOrNull(); val lo = lon.replace(',', '.').toDoubleOrNull()
                    if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                        set { it.copy(homeLat = la, homeLon = lo) }; vm.toast("Location saved")
                    } else vm.toast("Enter a valid latitude and longitude")
                }, modifier = Modifier.weight(1f)) { Text("Save") }
                OutlinedButton(onClick = { locationPerm.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }, modifier = Modifier.weight(1f)) { Text("Use current") }
            }
        }

        // ------------------------------------------------------------ alerts
        SectionCard(title = "Alerts on this phone") {
            Toggle("Alerts", s.phoneAlerts) { on -> set { it.copy(phoneAlerts = on) } }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val a = s.alerts
            Toggle("Charging complete", a.chargeComplete) { on -> set { it.copy(alerts = it.alerts.copy(chargeComplete = on)) } }
            Toggle("Charging stopped early", a.chargeStopped) { on -> set { it.copy(alerts = it.alerts.copy(chargeStopped = on)) } }
            Toggle("Cabin ready (climate finished)", a.climateFinished) { on -> set { it.copy(alerts = it.alerts.copy(climateFinished = on)) } }
            Toggle("Climate stopped by the car", a.climateCancelled) { on -> set { it.copy(alerts = it.alerts.copy(climateCancelled = on)) } }
            Toggle("Left unlocked (10 min)", a.leftUnlocked) { on -> set { it.copy(alerts = it.alerts.copy(leftUnlocked = on)) } }
            Toggle("Door / boot left open", a.doorOpen) { on -> set { it.copy(alerts = it.alerts.copy(doorOpen = on)) } }
            Toggle("Lights left on", a.lightsOn) { on -> set { it.copy(alerts = it.alerts.copy(lightsOn = on)) } }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Toggle("Keep watching while charging or preheating", s.watchWhileBusy) { on -> set { it.copy(watchWhileBusy = on) } }
            Text(
                "Checks the car every ${s.watchIntervalMinutes} min in the background (phone must be within Wi-Fi range, or use the bridge).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------------------------------------------------------- advanced
        SectionCard(title = "Advanced") {
            Toggle("Set the car's clock on connect", s.syncClock) { on -> set { it.copy(syncClock = on) } }
            var range by rememberSaveable(s.fullRangeKm) { mutableStateOf(s.fullRangeKm.toString()) }
            OutlinedTextField(range, { v -> range = v; v.toIntOrNull()?.takeIf { it in 10..150 }?.let { km -> set { it.copy(fullRangeKm = km) } } },
                label = { Text("Electric range at 100% (km)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(Modifier.height(8.dp))
            Text("Protocol", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(ModelYear.UNKNOWN to "Auto", ModelYear.MY14 to "2014–17", ModelYear.MY18 to "2018+").forEachIndexed { i, (my, label) ->
                    SegmentedButton(selected = s.modelYearOverride == my, onClick = { set { it.copy(modelYearOverride = my) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }
            Spacer(Modifier.height(8.dp))
            var host by rememberSaveable(s.hostOverride) { mutableStateOf(s.hostOverride) }
            OutlinedTextField(host, { host = it }, label = { Text("Test address (emulator), host:port") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), supportingText = { Text("Leave empty for the real car") })
            FilledTonalButton(onClick = { set { it.copy(hostOverride = host) } }) { Text("Apply") }
        }

        SectionCard(title = "About") {
            LabeledValue("Version", BuildConfig.VERSION_NAME)
            Text(
                "Talks the car's own Wi-Fi protocol. Built on the reverse-engineering work of phev2mqtt (Ben Buxton and contributors) and " +
                    "phev-remote (Jamie Nuttall), cross-checked against the official app. Not affiliated with Mitsubishi Motors.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { set { it.copy(setupDone = false) } }, modifier = Modifier.padding(top = 8.dp)) { Text("Run setup again") }
        }
        Spacer(Modifier.height(24.dp))
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
fun CarWifiCard(vm: MainViewModel, s: AppSettings, showTest: Boolean = true) {
    var ssid by rememberSaveable(s.ssid) { mutableStateOf(s.ssid) }
    var pw by rememberSaveable(s.password) { mutableStateOf(s.password) }
    var showPw by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val pairing = rememberCarPairing { bssid ->
        if (bssid != null) {
            vm.updateSettings { it.copy(bssid = bssid) }
            vm.toast("Paired with the car's Wi-Fi ($bssid)")
        } else vm.toast("Pairing cancelled")
    }
    val pwError = io.github.anwo91.phevlink.data.passwordProblem(pw).takeIf { pw.isNotEmpty() }
    SectionCard(title = "Car Wi-Fi") {
        Text(
            "The SSID (REMOTExxxxxx) and password are on the card that came with the car's keys.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pw, { pw = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPw = !showPw }) {
                    Icon(if (showPw) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showPw) "Hide" else "Show")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = pwError != null,
            supportingText = if (pwError != null) { { Text(pwError) } } else null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            FilledTonalButton(onClick = {
                vm.updateSettings { it.copy(ssid = ssid.trim(), password = pw) }
                vm.toast("Saved")
            }, modifier = Modifier.weight(1f)) { Text("Save") }
            if (showTest) Button(
                onClick = {
                    vm.updateSettings { it.copy(ssid = ssid.trim(), password = pw) }
                    testing = true
                    vm.testConnection { ok, msg -> testing = false; vm.toast(msg) }
                },
                enabled = !testing, modifier = Modifier.weight(1f),
            ) { Text(if (testing) "Testing…" else "Test") }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("Silent connections", style = MaterialTheme.typography.labelLarge)
        Text(
            if (s.bssid.isNotBlank()) "Paired with ${s.bssid}. Android connects without asking, even in the background."
            else "Pair once (with the car nearby) so Android stops asking \"Connect to device?\" and the widget, tile and schedules work in the background.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = { pairing.start(ssid.trim()) { err -> vm.toast(err) } }, modifier = Modifier.weight(1f)) {
                Text(if (s.bssid.isNotBlank()) "Pair again" else "Pair with car")
            }
            if (s.bssid.isNotBlank()) OutlinedButton(onClick = { vm.updateSettings { it.copy(bssid = "") } }, modifier = Modifier.weight(1f)) { Text("Forget") }
        }
    }
}

const val REGISTRATION_STEPS =
    "1. Sit in the car with the key, power switch OFF.\n" +
        "2. Press the power switch once to ACC (don't press the brake).\n" +
        "3. Within 10 seconds, press LOCK then UNLOCK on the key, alternately, 5 times each (10 presses, starting with LOCK).\n" +
        "4. The hazard lights blink: the car is in registration mode for a few minutes. Tap Register (the phone must be on the car's Wi-Fi)."
