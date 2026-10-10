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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
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
import io.github.anwo91.phevlink.ui.common.quantityString
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Labels
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: MainViewModel, modifier: Modifier, onOpenSchedule: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val car by vm.car.collectAsState()
    val link by vm.link.collectAsState()
    val pending by vm.pending.collectAsState()
    val settings by vm.settings.collectAsState()
    var showLink by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
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
                        car.updatedAtMs?.let { stringResource(R.string.home_updated, Format.ago(it, now)) } ?: stringResource(R.string.home_no_data),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinkChip(link) { showLink = true }
            }
        }
        item { BatteryCard(car, settings.fullRangeKm, now) }
        item {
            val msgOff = stringResource(R.string.cmd_climate_off)
            val msgCleared = stringResource(R.string.msg_climate_warning_cleared)
            ClimateCard(
                car = car, pending = pending,
                defaultMode = settings.quickMode, defaultDuration = settings.quickDuration,
                onStart = { m, d ->
                    vm.run(CarCommand.ClimateOn(m, d), ctx.getString(R.string.msg_climate_started, ctx.getString(Labels.mode(m)), d.minutes))
                },
                onSmart = { vm.quickClimate() },
                onStop = { vm.run(CarCommand.ClimateOff, msgOff) },
                onReset = { vm.run(CarCommand.AckClimateCancelled, msgCleared) },
            )
        }
        item { QuickActions(car, pending, vm) }
        item { SecurityCard(car) }
        item { DepartureCard(vm, onOpenSchedule) }
        item {
            Text(
                listOfNotNull(
                    car.vin?.let { "VIN $it" }, car.ecuVersion?.let { "ECU $it" },
                    car.registrations?.let { quantityString(R.plurals.home_phones_registered, it, it) },
                ).joinToString(" · "),
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
                Text(stringResource(R.string.home_electric_range), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                when {
                    car.charging == true -> {
                        Pill(stringResource(R.string.home_charging), status.charge)
                        car.chargeDoneAtMs(now)?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.home_full_at, Format.clockOrDay(it, now), Format.minutes(car.chargeMinutesRemaining ?: 0)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    car.plug == PlugState.PLUGGED -> {
                        Pill(stringResource(R.string.home_plugged_in), status.good)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.home_not_charging), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    car.plug == PlugState.UNPLUGGED -> Pill(stringResource(R.string.home_unplugged), status.idle)
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
    SectionCard(title = stringResource(R.string.climate_title), trailing = {
        when (c.state) {
            PreAcState.RUNNING -> Pill(
                stringResource(
                    R.string.climate_running_pill,
                    c.mode?.let { stringResource(Labels.mode(it)) } ?: stringResource(R.string.common_on),
                    c.duration?.minutes?.toString() ?: "?",
                ),
                status.charge,
            )
            PreAcState.CANCELLED -> Pill(stringResource(R.string.climate_stopped_by_car), status.warn)
            else -> Unit
        }
    }) {
        when (c.state) {
            PreAcState.RUNNING -> {
                val end = c.startedAtMs?.let { it + (c.duration?.minutes ?: 0) * 60_000L }
                Text(
                    end?.let { stringResource(R.string.climate_running_until, Format.clock(it)) } ?: stringResource(R.string.climate_running),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                BusyButton(stringResource(R.string.common_stop), pending, Modifier.fillMaxWidth(), tonal = true, onClick = onStop)
            }
            PreAcState.CANCELLED -> {
                Text(
                    stringResource(R.string.climate_cancelled_explain),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                BusyButton(stringResource(R.string.climate_clear_warning), pending, Modifier.fillMaxWidth(), tonal = true, onClick = onReset)
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
                        ) { Text(stringResource(Labels.mode(m))) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClimateDuration.entries.forEach { d ->
                        FilterChip(selected = dur == d, onClick = { dur = d }, label = { Text(stringResource(R.string.dur_min, d.minutes)) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BusyButton(stringResource(R.string.climate_start), pending, Modifier.weight(1f)) { onStart(mode, dur) }
                    OutlinedButton(onClick = onSmart, enabled = pending == null, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.climate_smart))
                    }
                }
                Text(
                    stringResource(R.string.climate_smart_explain),
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
    SectionCard(title = stringResource(R.string.quick_title)) {
        val msgFlashed = stringResource(R.string.msg_headlights_flashed)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(R.string.quick_flash), Icons.Outlined.Flare, pending, Modifier.weight(1f)) { vm.run(CarCommand.Locate(), msgFlashed) }
            ActionButton(
                stringResource(if (car.headlights == true) R.string.quick_lights_off else R.string.quick_headlights),
                Icons.Outlined.Highlight, pending, Modifier.weight(1f),
            ) { vm.run(CarCommand.Headlights(car.headlights != true)) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                stringResource(if (car.parkingLights == true) R.string.quick_parking_off else R.string.quick_parking_lights),
                Icons.Outlined.EmojiObjects, pending, Modifier.weight(1f),
            ) { vm.run(CarCommand.ParkingLights(car.parkingLights != true)) }
            ActionButton(stringResource(R.string.common_refresh), Icons.Outlined.Refresh, pending, Modifier.weight(1f)) { vm.refresh() }
        }
        if (car.plug == PlugState.PLUGGED && car.charging != true) {
            Spacer(Modifier.height(8.dp))
            val msgCharge = stringResource(R.string.msg_charge_requested)
            ActionButton(stringResource(R.string.quick_charge_now), Icons.Outlined.EvStation, pending, Modifier.fillMaxWidth()) {
                vm.run(CarCommand.ChargeNow, msgCharge)
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
    val ctx = LocalContext.current
    SectionCard(title = stringResource(R.string.security_title), trailing = {
        when (car.locked) {
            true -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, tint = status.good, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.security_locked), color = status.good)
            }
            false -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LockOpen, null, tint = status.warn, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.security_unlocked), color = status.warn)
            }
            null -> Unit
        }
    }) {
        val open = car.doors?.let { Labels.openDoors(ctx, it) }.orEmpty()
        LabeledValue(
            stringResource(R.string.security_doors),
            if (car.doors == null) "–" else if (open.isEmpty()) stringResource(R.string.security_all_closed) else open.joinToString(),
            valueColor = if (open.isEmpty()) androidx.compose.ui.graphics.Color.Unspecified else status.warn,
        )
        val lights = listOfNotNull(
            stringResource(R.string.light_headlights).takeIf { car.headlights == true },
            stringResource(R.string.light_parking).takeIf { car.parkingLights == true },
            stringResource(R.string.light_hazards).takeIf { car.hazardLights == true },
            stringResource(R.string.light_interior).takeIf { car.interiorLights == true },
        )
        LabeledValue(
            stringResource(R.string.security_lights), if (lights.isEmpty()) stringResource(R.string.common_off) else lights.joinToString(),
            valueColor = if (lights.isEmpty()) androidx.compose.ui.graphics.Color.Unspecified else status.warn,
        )
        LabeledValue(
            stringResource(R.string.security_power),
            when (car.ignition) {
                Ignition.OFF -> stringResource(R.string.common_off)
                Ignition.ACC -> stringResource(R.string.ignition_acc)
                Ignition.ON -> stringResource(R.string.common_on)
                Ignition.UNKNOWN -> "–"
            },
        )
        car.carClock?.let { c ->
            if (c.size >= 5) LabeledValue(stringResource(R.string.security_car_clock), "%02d:%02d".format(c[3], c[4]))
        }
    }
}

@Composable
private fun DepartureCard(vm: MainViewModel, onOpenSchedule: () -> Unit) {
    val settings by vm.settings.collectAsState()
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); minute = System.currentTimeMillis() / 60_000 } }
    val next = remember(settings.departures, minute) { vm.nextDeparture() }
    SectionCard(title = stringResource(R.string.departure_next_title), trailing = {
        TextButton(onClick = onOpenSchedule) {
            Text(stringResource(if (settings.departures.isEmpty()) R.string.departure_set_up else R.string.departure_edit))
        }
    }) {
        if (next == null) {
            Text(
                stringResource(R.string.departure_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(Format.clockOrDay(next.atMs), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.departure_weather_check, Format.clockOrDay(next.planAtMs)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            link.via?.let { Text(stringResource(R.string.link_route, stringResource(it.labelRes)), style = MaterialTheme.typography.bodyMedium) }
            link.detail?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            link.sinceMs?.let { Text(stringResource(R.string.link_since, Format.clock(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.link_tips),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.link_try_again)) }
                OutlinedButton(onClick = onDiagnostics, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tab_diagnostics)) }
            }
        }
    }
}
