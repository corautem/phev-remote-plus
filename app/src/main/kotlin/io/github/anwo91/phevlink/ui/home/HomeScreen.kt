package io.github.anwo91.phevlink.ui.home

import io.github.anwo91.phevlink.data.CarStyle
import io.github.anwo91.phevlink.data.TapHints
import io.github.anwo91.phevlink.ui.home.carpic.CarPart
import io.github.anwo91.phevlink.ui.home.carpic.CarPicture
import io.github.anwo91.phevlink.ui.home.carpic.CarPictureDialog
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import io.github.anwo91.phevlink.core.model.ChargeKind
import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.Ignition
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.core.model.ScheduleDecoder
import io.github.anwo91.phevlink.core.model.TheftAlarm
import io.github.anwo91.phevlink.core.diag.LogKind
import io.github.anwo91.phevlink.link.LinkUi
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.BatteryRing
import io.github.anwo91.phevlink.ui.common.LabeledValue
import io.github.anwo91.phevlink.ui.common.LinkChip
import io.github.anwo91.phevlink.ui.common.Pill
import io.github.anwo91.phevlink.ui.common.SectionCard
import io.github.anwo91.phevlink.ui.common.phaseColor
import io.github.anwo91.phevlink.ui.common.quantityString
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Labels
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    vm: MainViewModel,
    modifier: Modifier,
    onOpenSchedule: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenVehicleSettings: () -> Unit = {},
    onOpenTips: () -> Unit = {},
    onOpenObd: () -> Unit = {},
    onOpenBridgeSetup: () -> Unit = {},
    onOpenChargeTimer: () -> Unit = {},
    onOpenActivity: () -> Unit = {},
) {
    val car by vm.car.collectAsState()
    val link by vm.link.collectAsState()
    val pending by vm.pending.collectAsState()
    val pendingCmd by vm.pendingCommand.collectAsState()
    val settings by vm.settings.collectAsState()
    var showLink by remember { mutableStateOf(false) }
    // The part of the car picture the user tapped; its dialog explains it and offers the matching actions.
    var part by remember { mutableStateOf<CarPart?>(null) }
    // Holding the car picture shows it large (and the style choices).
    var bigPicture by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    // Re-render "x min ago" texts.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (settings.bridgeSetupPending) item { BridgePendingCard(vm, onOpenBridgeSetup) }
        item { AttentionCard(car) }
        item {
            HeroHeader(
                car, link, settings.fullRangeKm, settings.carName, now, settings.carStyle, settings.tapHints,
                onLinkClick = { showLink = true },
                onPart = { p -> vm.log.add(LogKind.CMD, carPictureLog(p, car)); part = p },
                onHold = {
                    vm.log.add(LogKind.CMD, "Car picture: large view (${settings.carStyle}, hints ${settings.tapHints})")
                    bigPicture = true
                },
            )
        }
        item {
            val msgOff = stringResource(R.string.cmd_climate_off)
            val msgCleared = stringResource(R.string.msg_climate_warning_cleared)
            val busyText = when (val p = pendingCmd) {
                is CarCommand.ClimateOn -> stringResource(R.string.climate_starting, stringResource(Labels.mode(p.mode)))
                CarCommand.ClimateOff -> stringResource(R.string.climate_stopping)
                CarCommand.AckClimateCancelled -> stringResource(R.string.climate_clearing)
                else -> null
            }
            ClimateCard(
                car = car, pending = pending, busyText = busyText,
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
        item { ScheduleCard(car, onOpenChargeTimer) }
        item { DepartureCard(vm, onOpenSchedule) }
        item { MoreCard(onOpenVehicleSettings, onOpenTips, onOpenObd, onOpenChargeTimer, onOpenActivity) }
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

    if (bigPicture) {
        CarPictureDialog(
            car, settings.carName.ifBlank { "Outlander PHEV" }, settings.carStyle, settings.tapHints,
            onStyle = { st -> vm.log.add(LogKind.CMD, "Car picture: style $st"); vm.updateSettings { it.copy(carStyle = st) } },
            onHints = { h -> vm.log.add(LogKind.CMD, "Car picture: hints $h"); vm.updateSettings { it.copy(tapHints = h) } },
            onPart = { p -> vm.log.add(LogKind.CMD, carPictureLog(p, car)); part = p },
            onDismiss = { bigPicture = false },
        )
    }
    // After the large view, so a part's dialog opens on top of it.
    part?.let { p ->
        CarPartDialog(
            p, car, pending, settings.fullRangeKm, now, vm, onDismiss = { part = null },
            onOpenChargeTimer = { part = null; bigPicture = false; onOpenChargeTimer() },
        )
    }
    if (showLink) LinkSheet(link, car, onDismiss = { showLink = false }, onRetry = { vm.reconnect(); showLink = false }, onDiagnostics = { showLink = false; onOpenDiagnostics() })
}

/** A single banner for things that need attention now — renders nothing when all is well. */
@Composable
private fun AttentionCard(car: CarState) {
    val messages = listOfNotNull(
        stringResource(R.string.home_attention_alarm).takeIf { car.theftAlarm == TheftAlarm.TRIGGERED },
        stringResource(R.string.home_attention_charge_failed).takeIf { car.chargeTimerError },
        stringResource(R.string.home_attention_ev_error).takeIf { car.evEcuError },
    )
    if (messages.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            messages.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PhevIcons.Warning, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(m, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * The top-of-screen hero: title, connection, and the battery at a glance on a branded gradient. It is
 * the one block that looks intentional even before any car data arrives, so the app never looks empty.
 */
@Composable
private fun HeroHeader(
    car: CarState,
    link: LinkUi,
    fullRangeKm: Int,
    carName: String,
    now: Long,
    carStyle: CarStyle,
    tapHints: TapHints,
    onLinkClick: () -> Unit,
    onPart: (CarPart) -> Unit,
    onHold: () -> Unit,
) {
    val status = LocalStatus.current
    val brush = Brush.verticalGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainer),
    )
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(brush).padding(20.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(carName.ifBlank { "Outlander PHEV" }, style = MaterialTheme.typography.titleLarge)
                    Text(
                        car.updatedAtMs?.let { stringResource(R.string.home_updated, Format.ago(it, now)) } ?: stringResource(R.string.home_no_data),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinkChip(link, onLinkClick)
            }
            Spacer(Modifier.height(16.dp))
            // Battery and charging on the left, the car picture on the right: tap a part of the car for details.
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    BatteryRing(car.batteryPercent, car.isCharging, size = 132.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(car.batteryPercent?.let { "$it" } ?: "–", style = MaterialTheme.typography.displayMedium)
                            Text("%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    val range = car.batteryPercent?.let { (it * fullRangeKm) / 100 }
                    Text(range?.let { "≈ $it km" } ?: "–", style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.home_electric_range), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    when {
                        car.isCharging -> {
                            val label = when (car.chargeKind) {
                                ChargeKind.TRICKLE -> R.string.home_charge_trickle
                                ChargeKind.QUICK -> R.string.home_charge_quick
                                else -> R.string.home_charging
                            }
                            Pill(stringResource(label), status.charge)
                            car.chargeDoneAtMs(now)?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.home_full_at, Format.clockOrDay(it, now), Format.minutes(car.chargeMinutesRemaining ?: 0)),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        car.chargeTimerError -> {
                            Pill(stringResource(R.string.home_charge_failed), status.warn)
                            ChargeNote(stringResource(R.string.home_charge_failed_note))
                        }
                        car.chargeWaiting -> {
                            Pill(stringResource(R.string.home_charge_waiting), status.good)
                            ChargeNote(stringResource(R.string.home_charge_waiting_note))
                        }
                        car.plug == PlugState.PLUGGED -> {
                            Pill(stringResource(R.string.home_plugged_in), status.good)
                            ChargeNote(stringResource(R.string.home_not_charging))
                        }
                        car.plug == PlugState.UNPLUGGED -> Pill(stringResource(R.string.home_unplugged), status.idle)
                        else -> Unit
                    }
                    // Winter: the drive battery's own thermal state.
                    if (car.batteryWarming) BatteryNote(PhevIcons.Heat, stringResource(R.string.home_batt_warming), status.charge)
                    if (car.batteryTooCold) BatteryNote(PhevIcons.Cool, stringResource(R.string.home_batt_too_cold), status.warn)
                }
                if (carStyle == CarStyle.TOP) {
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CarPicture(car, carStyle, tapHints, Modifier.width(112.dp).height(224.dp), onPart = onPart, onHold = onHold)
                        Text(
                            stringResource(R.string.carview_hint_top),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            // From the side and in 3D the car is wide: it gets the full width below.
            if (carStyle != CarStyle.TOP) {
                Spacer(Modifier.height(12.dp))
                CarPicture(
                    car, carStyle, tapHints,
                    Modifier.fillMaxWidth().height(if (carStyle == CarStyle.SIDE) 136.dp else 190.dp),
                    onPart = onPart, onHold = onHold,
                )
                Text(
                    stringResource(if (carStyle == CarStyle.THREE_D) R.string.carview_hint_3d else R.string.carview_hint_wide),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ChargeNote(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BatteryNote(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClimateCard(
    car: CarState,
    pending: String?,
    busyText: String?,
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
        when {
            busyText != null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            c.state == PreAcState.RUNNING -> Pill(
                stringResource(
                    R.string.climate_running_pill,
                    c.mode?.let { stringResource(Labels.mode(it)) } ?: stringResource(R.string.common_on),
                    c.duration?.minutes?.toString() ?: "?",
                ),
                status.charge,
            )
            c.state == PreAcState.CANCELLED -> Pill(stringResource(R.string.climate_stopped_by_car), status.warn)
            else -> Unit
        }
    }) {
        // While a climate command is in flight, say so clearly — the car can take up to 30 s to confirm.
        if (busyText != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = status.charge)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(busyText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.climate_working_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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
                // Mitsubishi: remote climate "cannot operate when the power switch is not OFF".
                if (car.ignition == Ignition.ACC || car.ignition == Ignition.ON) {
                    Text(
                        stringResource(R.string.climate_power_on_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
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
                        Icon(PhevIcons.Smart, null, Modifier.size(18.dp))
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
    ClimateMode.HEAT -> PhevIcons.Heat
    ClimateMode.COOL -> PhevIcons.Cool
    ClimateMode.WINDSCREEN -> PhevIcons.Defrost
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
            ActionButton(stringResource(R.string.quick_flash), PhevIcons.Flash, pending, Modifier.weight(1f)) { vm.run(CarCommand.Locate(), msgFlashed) }
            ActionButton(
                stringResource(if (car.headlights == true) R.string.quick_lights_off else R.string.quick_headlights),
                PhevIcons.Headlights, pending, Modifier.weight(1f),
            ) { vm.run(CarCommand.Headlights(car.headlights != true)) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                stringResource(if (car.parkingLights == true) R.string.quick_parking_off else R.string.quick_parking_lights),
                PhevIcons.ParkingLights, pending, Modifier.weight(1f),
            ) { vm.run(CarCommand.ParkingLights(car.parkingLights != true)) }
            ActionButton(stringResource(R.string.common_refresh), PhevIcons.Refresh, pending, Modifier.weight(1f)) { vm.refresh() }
        }
        // The timer buttons only mean something when the car has a charge timer: without one it charges as soon as
        // it's plugged in, and "Back to timer" would say "done" while nothing changes.
        val timerSet = ScheduleDecoder.charge(car).any { it.programmed }
        // Plugged in but waiting for the timer: offer "Charge now". Charging on demand with a timer set: hand it back.
        if (car.plug == PlugState.PLUGGED && !car.isCharging && (car.chargeWaiting || timerSet)) {
            Spacer(Modifier.height(8.dp))
            val msgCharge = stringResource(R.string.msg_charge_requested)
            ActionButton(stringResource(R.string.quick_charge_now), PhevIcons.Plug, pending, Modifier.fillMaxWidth()) {
                vm.run(CarCommand.ChargeNow, msgCharge)
            }
        } else if (car.isCharging && timerSet) {
            Spacer(Modifier.height(8.dp))
            ActionButton(stringResource(R.string.quick_resume_timer), PhevIcons.Clock, pending, Modifier.fillMaxWidth()) {
                vm.run(CarCommand.ResumeChargeTimer)
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
                Icon(PhevIcons.Lock, null, tint = status.good, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.security_locked), color = status.good)
            }
            false -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PhevIcons.Unlock, null, tint = status.warn, modifier = Modifier.size(18.dp))
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
        // The security alarm, only when the car actually reports it armed or going off.
        when (car.theftAlarm) {
            TheftAlarm.ARMED -> LabeledValue(stringResource(R.string.security_alarm), stringResource(R.string.security_alarm_armed))
            TheftAlarm.TRIGGERED -> LabeledValue(stringResource(R.string.security_alarm), stringResource(R.string.security_alarm_triggered), valueColor = status.warn)
            else -> Unit
        }
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
                Icon(PhevIcons.Clock, null, tint = MaterialTheme.colorScheme.primary)
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
private fun LinkSheet(link: LinkUi, car: CarState, onDismiss: () -> Unit, onRetry: () -> Unit, onDiagnostics: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(link.text, style = MaterialTheme.typography.titleLarge, color = phaseColor(link.phase))
            link.via?.let { Text(stringResource(R.string.link_route, stringResource(it.labelRes)), style = MaterialTheme.typography.bodyMedium) }
            link.detail?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            link.sinceMs?.let { Text(stringResource(R.string.link_since, Format.clock(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // The car turns its Wi-Fi off while switched on (2014–2017 always, newer cars unless "always on" is set).
            if (!link.connected && car.ignition == Ignition.ON && car.wifiAlwaysOn != true) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.link_hint_power_on), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
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

/** The car's own charge and climate timers, read-only. Shows nothing until the car reports a programmed timer. */
@Composable
private fun ScheduleCard(car: CarState, onOpenChargeTimer: () -> Unit) {
    if (!ScheduleDecoder.hasAny(car)) return
    val status = LocalStatus.current
    fun dayName(d: Int): String =
        java.time.DayOfWeek.of(if (d == 0) 7 else d).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
    fun hhmm(t: java.time.LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    SectionCard(title = stringResource(R.string.sched_card_title)) {
        val charge = ScheduleDecoder.charge(car).filter { it.programmed }
        val climate = ScheduleDecoder.climate(car).filter { it.programmed }
        if (charge.isNotEmpty() || ScheduleDecoder.chargeTimerOn(car)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sched_charge), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Pill(
                    stringResource(if (ScheduleDecoder.chargeTimerOn(car)) R.string.sched_on else R.string.sched_off),
                    if (ScheduleDecoder.chargeTimerOn(car)) status.good else status.idle,
                )
            }
            if (charge.isEmpty()) {
                Text(stringResource(R.string.sched_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else charge.forEach { d ->
                val window = listOfNotNull(d.start?.let(::hhmm), d.end?.let(::hhmm)).joinToString("–")
                val extra = if (d.fullCharge) " · " + stringResource(R.string.sched_full) else ""
                LabeledValue(dayName(d.day), window + extra)
            }
            TextButton(onClick = onOpenChargeTimer) { Text(stringResource(R.string.ct_change)) }
        }
        if (climate.isNotEmpty() || ScheduleDecoder.climateTimerOn(car)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sched_climate), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Pill(
                    stringResource(if (ScheduleDecoder.climateTimerOn(car)) R.string.sched_on else R.string.sched_off),
                    if (ScheduleDecoder.climateTimerOn(car)) status.good else status.idle,
                )
            }
            if (climate.isEmpty()) {
                Text(stringResource(R.string.sched_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else climate.forEach { d -> d.start?.let { LabeledValue(dayName(d.day), hhmm(it)) } }
        }
    }
}

/** Entry points to the car's own settings, the tips and the OBD diagnostics. */
@Composable
private fun MoreCard(onOpenVehicleSettings: () -> Unit, onOpenTips: () -> Unit, onOpenObd: () -> Unit, onOpenChargeTimer: () -> Unit, onOpenActivity: () -> Unit) {
    SectionCard(title = stringResource(R.string.home_more)) {
        MoreRow(stringResource(R.string.act_title), stringResource(R.string.act_more), onOpenActivity)
        MoreRow(stringResource(R.string.ct_title), stringResource(R.string.ct_more), onOpenChargeTimer)
        MoreRow(stringResource(R.string.vs_title), stringResource(R.string.home_more_vs), onOpenVehicleSettings)
        MoreRow(stringResource(R.string.tips_title), stringResource(R.string.home_more_tips), onOpenTips)
        MoreRow(stringResource(R.string.tab_obd), stringResource(R.string.home_more_obd), onOpenObd)
    }
}

@Composable
private fun MoreRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The bridge was set up while the car was away: one step (registration in the car) is left. */
@Composable
private fun BridgePendingCard(vm: MainViewModel, onContinue: () -> Unit) {
    // Ask the bridge while the card is on screen: the background check can be held back for hours by Android.
    var seen by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            seen = vm.bridgeSeesCar()
            delay(60_000)
        }
    }
    SectionCard(title = stringResource(R.string.home_bridge_pending_title)) {
        Text(
            stringResource(if (seen == true) R.string.home_bridge_pending_ready else R.string.home_bridge_pending_text),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (seen == true) FontWeight.Medium else null,
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = onContinue) { Text(stringResource(R.string.home_bridge_pending_button)) }
    }
}
