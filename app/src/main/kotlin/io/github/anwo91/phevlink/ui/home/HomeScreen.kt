package io.github.anwo91.phevlink.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EmojiObjects
import androidx.compose.material.icons.outlined.EvStation
import androidx.compose.material.icons.outlined.Flare
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.Ignition
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.link.LinkUi
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.BatteryRing
import io.github.anwo91.phevlink.ui.common.LabeledValue
import io.github.anwo91.phevlink.ui.common.LinkChip
import io.github.anwo91.phevlink.ui.common.Pill
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.common.phaseColor
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.Format
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: MainViewModel, modifier: Modifier, onOpenSchedule: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val car by vm.car.collectAsState()
    val link by vm.link.collectAsState()
    val pending by vm.pending.collectAsState()
    val settings by vm.settings.collectAsState()
    var showLink by remember { mutableStateOf(false) }
    // Re-render "x min ago" texts.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Outlander PHEV", style = MaterialTheme.typography.titleLarge)
                    Text(
                        car.updatedAtMs?.let { "Updated ${Format.ago(it, now)}" } ?: "No data from the car yet",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinkChip(link) { showLink = true }
            }
        }
        item { BatteryCard(car, settings.fullRangeKm, now) }
        item {
            ClimateCard(
                car = car, pending = pending,
                defaultMode = settings.quickMode, defaultDuration = settings.quickDuration,
                onStart = { m, d -> vm.run(CarCommand.ClimateOn(m, d), "${m.label} started — ready in ${d.minutes} min") },
                onSmart = { vm.quickClimate() },
                onStop = { vm.run(CarCommand.ClimateOff, "Climate off") },
                onReset = { vm.run(CarCommand.AckClimateCancelled, "Climate warning cleared") },
            )
        }
        item { QuickActions(car, pending, vm) }
        item { SecurityCard(car) }
        item { DepartureCard(vm, onOpenSchedule) }
        item {
            Text(
                listOfNotNull(car.vin?.let { "VIN $it" }, car.ecuVersion?.let { "ECU $it" }, car.registrations?.let { "$it phone(s) registered" })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }

    if (showLink) LinkSheet(link, onDismiss = { showLink = false }, onRetry = { vm.reconnect(); showLink = false }, onDiagnostics = { showLink = false; onOpenDiagnostics() })
}

@Composable
private fun BatteryCard(car: CarState, fullRangeKm: Int, now: Long) {
    val status = LocalStatus.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BatteryRing(car.batteryPercent, car.charging == true) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(car.batteryPercent?.let { "$it" } ?: "–", style = MaterialTheme.typography.displayMedium)
                    Text("%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                val range = car.batteryPercent?.let { (it * fullRangeKm) / 100 }
                Text(range?.let { "≈ $it km" } ?: "–", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("electric range", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                when {
                    car.charging == true -> {
                        Pill("Charging", status.charge)
                        car.chargeDoneAtMs(now)?.let {
                            Spacer(Modifier.height(6.dp))
                            Text("Full ≈ ${Format.clockOrDay(it, now)} (${Format.minutes(car.chargeMinutesRemaining ?: 0)})", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    car.plug == PlugState.PLUGGED -> {
                        Pill("Plugged in", status.good)
                        Spacer(Modifier.height(6.dp))
                        Text("Not charging (timer or full)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    car.plug == PlugState.UNPLUGGED -> Pill("Unplugged", status.idle)
                    else -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClimateCard(
    car: CarState,
    pending: String?,
    defaultMode: ClimateMode,
    defaultDuration: ClimateDuration,
    onStart: (ClimateMode, ClimateDuration) -> Unit,
    onSmart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
) {
    val status = LocalStatus.current
    var mode by rememberSaveable { mutableStateOf(defaultMode) }
    var dur by rememberSaveable { mutableStateOf(defaultDuration) }
    val c = car.climate
    SectionCard(title = "Climate", trailing = {
        when (c.state) {
            PreAcState.RUNNING -> Pill("${c.mode?.label ?: "On"} · ${c.duration?.minutes ?: "?"} min", status.charge)
            PreAcState.CANCELLED -> Pill("Stopped by car", status.warn)
            else -> Unit
        }
    }) {
        when (c.state) {
            PreAcState.RUNNING -> {
                val end = c.startedAtMs?.let { it + (c.duration?.minutes ?: 0) * 60_000L }
                Text(
                    end?.let { "Running — the cabin will be ready around ${Format.clock(it)}." } ?: "Running.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                BusyButton("Stop", pending, Modifier.fillMaxWidth(), tonal = true, onClick = onStop)
            }
            PreAcState.CANCELLED -> {
                Text(
                    "The car switched preconditioning off — usually a door was opened or the battery is too low. " +
                        "Clear the warning before starting again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                BusyButton("Clear warning", pending, Modifier.fillMaxWidth(), tonal = true, onClick = onReset)
            }
            else -> {
                val modes = ClimateMode.entries
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            icon = { Icon(modeIcon(m), null, Modifier.size(18.dp)) },
                        ) { Text(m.label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClimateDuration.entries.forEach { d ->
                        FilterChip(selected = dur == d, onClick = { dur = d }, label = { Text("${d.minutes} min") })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BusyButton("Start", pending, Modifier.weight(1f)) { onStart(mode, dur) }
                    OutlinedButton(onClick = onSmart, enabled = pending == null, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Smart")
                    }
                }
                Text(
                    "Smart picks the mode and duration from the outside temperature.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

fun modeIcon(m: ClimateMode): ImageVector = when (m) {
    ClimateMode.HEAT -> Icons.Outlined.Whatshot
    ClimateMode.COOL -> Icons.Outlined.AcUnit
    ClimateMode.WINDSCREEN -> Icons.Outlined.Air
}

@Composable
private fun BusyButton(text: String, pending: String?, modifier: Modifier = Modifier, tonal: Boolean = false, onClick: () -> Unit) {
    val busy = pending != null
    val content: @Composable () -> Unit = {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(pending ?: "")
        } else Text(text)
    }
    if (tonal) FilledTonalButton(onClick = onClick, enabled = !busy, modifier = modifier) { content() }
    else Button(onClick = onClick, enabled = !busy, modifier = modifier) { content() }
}

@Composable
private fun QuickActions(car: CarState, pending: String?, vm: MainViewModel) {
    SectionCard(title = "Quick actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Flash", Icons.Outlined.Flare, pending, Modifier.weight(1f)) { vm.run(CarCommand.Locate(), "Headlights flashed") }
            ActionButton(
                if (car.headlights == true) "Lights off" else "Headlights", Icons.Outlined.Highlight, pending, Modifier.weight(1f),
            ) { vm.run(CarCommand.Headlights(car.headlights != true)) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                if (car.parkingLights == true) "Parking off" else "Parking lights", Icons.Outlined.EmojiObjects, pending, Modifier.weight(1f),
            ) { vm.run(CarCommand.ParkingLights(car.parkingLights != true)) }
            ActionButton("Refresh", Icons.Outlined.Refresh, pending, Modifier.weight(1f)) { vm.refresh() }
        }
        if (car.plug == PlugState.PLUGGED && car.charging != true) {
            Spacer(Modifier.height(8.dp))
            ActionButton("Charge now (skip timer)", Icons.Outlined.EvStation, pending, Modifier.fillMaxWidth()) {
                vm.run(CarCommand.ChargeNow, "Asked the car to start charging")
            }
        }
    }
}

@Composable
private fun ActionButton(text: String, icon: ImageVector, pending: String?, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = pending == null, modifier = modifier.height(52.dp)) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1)
    }
}

@Composable
private fun SecurityCard(car: CarState) {
    val status = LocalStatus.current
    SectionCard(title = "Doors & lights", trailing = {
        when (car.locked) {
            true -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, tint = status.good, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Locked", color = status.good)
            }
            false -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LockOpen, null, tint = status.warn, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Unlocked", color = status.warn)
            }
            null -> Unit
        }
    }) {
        val open = car.doors?.openList().orEmpty()
        LabeledValue("Doors, boot, bonnet", if (car.doors == null) "–" else if (open.isEmpty()) "All closed" else open.joinToString(),
            valueColor = if (open.isEmpty()) androidx.compose.ui.graphics.Color.Unspecified else status.warn)
        val lights = listOfNotNull(
            "headlights".takeIf { car.headlights == true }, "parking".takeIf { car.parkingLights == true },
            "hazards".takeIf { car.hazardLights == true }, "interior".takeIf { car.interiorLights == true },
        )
        LabeledValue("Lights", if (lights.isEmpty()) "Off" else lights.joinToString(), valueColor = if (lights.isEmpty()) androidx.compose.ui.graphics.Color.Unspecified else status.warn)
        LabeledValue("Power switch", when (car.ignition) { Ignition.OFF -> "Off"; Ignition.ACC -> "Accessory"; Ignition.ON -> "On"; Ignition.UNKNOWN -> "–" })
        car.carClock?.let { c ->
            if (c.size >= 5) LabeledValue("Car clock", "%02d:%02d".format(c[3], c[4]))
        }
    }
}

@Composable
private fun DepartureCard(vm: MainViewModel, onOpenSchedule: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val next = remember(settings.departures) { vm.nextDeparture() }
    SectionCard(title = "Next departure", trailing = {
        TextButton(onClick = onOpenSchedule) { Text(if (settings.departures.isEmpty()) "Set up" else "Edit") }
    }) {
        if (next == null) {
            Text(
                "Tell the app when you usually leave, and it will warm or defrost the car just in time — choosing the mode from the weather forecast.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(Format.clockOrDay(next.atMs), style = MaterialTheme.typography.titleMedium)
                    Text("Weather check at ${Format.clockOrDay(next.planAtMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkSheet(link: LinkUi, onDismiss: () -> Unit, onRetry: () -> Unit, onDiagnostics: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(link.text, style = MaterialTheme.typography.titleLarge, color = phaseColor(link.phase))
            link.via?.let { Text("Route: ${it.label}", style = MaterialTheme.typography.bodyMedium) }
            link.detail?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            link.sinceMs?.let { Text("Since ${Format.clock(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "Tips: the car's Wi-Fi reaches about 10–20 m. After several days parked it goes to sleep — " +
                    "pressing a button on the key, or unlocking, usually wakes it. If it says the phone isn't registered, " +
                    "register it under Settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Try again now") }
                OutlinedButton(onClick = onDiagnostics, modifier = Modifier.weight(1f)) { Text("Diagnostics") }
            }
        }
    }
}
