package io.github.anwo91.phevlink.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.ChargeKind
import io.github.anwo91.phevlink.core.model.Ignition
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.core.model.TheftAlarm
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.home.carpic.CarPart
import io.github.anwo91.phevlink.ui.common.LabeledValue
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Labels

private fun String.cap(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

/**
 * What a tapped part means right now, plus the matching actions (lights off, start charging, …). Stays open while a
 * command runs, so the lines — and the picture behind — update as the car confirms.
 */
@Composable
fun CarPartDialog(
    part: CarPart,
    car: CarState,
    pending: String?,
    fullRangeKm: Int,
    now: Long,
    vm: MainViewModel,
    onDismiss: () -> Unit,
) {
    val status = LocalStatus.current
    val title = when (part) {
        CarPart.LIGHTS -> R.string.carview_lights_title
        CarPart.BODY -> R.string.carview_body_title
        CarPart.CABIN -> R.string.carview_cabin_title
        CarPart.CHARGE -> R.string.carview_charge_title
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.carview_close)) } },
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!car.hasData) DialogNote(stringResource(R.string.carview_no_data))
                when (part) {
                    CarPart.LIGHTS -> {
                        LabeledValue(stringResource(R.string.carview_headlights), onOffText(car.headlights), valueColor = if (car.headlights == true) status.warn else Color.Unspecified)
                        LabeledValue(stringResource(R.string.carview_parking), onOffText(car.parkingLights), valueColor = if (car.parkingLights == true) status.warn else Color.Unspecified)
                        LabeledValue(stringResource(R.string.carview_hazards), onOffText(car.hazardLights), valueColor = if (car.hazardLights == true) status.warn else Color.Unspecified)
                        OpenLine(stringResource(R.string.door_bonnet).cap(), car.doors?.bonnet)
                        if (car.headlights == true || car.parkingLights == true) DialogNote(stringResource(R.string.carview_lights_on_note))
                        Spacer(Modifier.height(6.dp))
                        DialogAction(pending, stringResource(if (car.headlights == true) R.string.quick_lights_off else R.string.quick_headlights)) {
                            vm.run(CarCommand.Headlights(car.headlights != true))
                        }
                        DialogAction(pending, stringResource(if (car.parkingLights == true) R.string.quick_parking_off else R.string.quick_parking_lights)) {
                            vm.run(CarCommand.ParkingLights(car.parkingLights != true))
                        }
                        val msgFlashed = stringResource(R.string.msg_headlights_flashed)
                        DialogAction(pending, stringResource(R.string.quick_flash)) { vm.run(CarCommand.Locate(), msgFlashed) }
                    }
                    CarPart.BODY -> {
                        LabeledValue(
                            stringResource(R.string.carview_locks),
                            when (car.locked) {
                                true -> stringResource(R.string.security_locked)
                                false -> stringResource(R.string.security_unlocked)
                                null -> "–"
                            },
                            valueColor = when (car.locked) { true -> status.good; false -> status.warn; null -> Color.Unspecified },
                        )
                        val d = car.doors
                        OpenLine(stringResource(R.string.door_driver).cap(), d?.driver)
                        OpenLine(stringResource(R.string.door_passenger).cap(), d?.frontPassenger)
                        OpenLine(stringResource(R.string.door_rear_left).cap(), d?.rearLeft)
                        OpenLine(stringResource(R.string.door_rear_right).cap(), d?.rearRight)
                        OpenLine(stringResource(R.string.door_boot).cap(), d?.boot)
                        OpenLine(stringResource(R.string.door_bonnet).cap(), d?.bonnet)
                        when (car.theftAlarm) {
                            TheftAlarm.ARMED -> LabeledValue(stringResource(R.string.security_alarm), stringResource(R.string.security_alarm_armed))
                            TheftAlarm.TRIGGERED -> LabeledValue(stringResource(R.string.security_alarm), stringResource(R.string.security_alarm_triggered), valueColor = status.bad)
                            else -> Unit
                        }
                        if (d?.anyOpen == true) DialogNote(stringResource(R.string.carview_open_note), status.warn)
                        Spacer(Modifier.height(4.dp))
                        DialogNote(stringResource(R.string.carview_no_remote_lock))
                    }
                    CarPart.CABIN -> {
                        val c = car.climate
                        LabeledValue(
                            stringResource(R.string.carview_climate),
                            when {
                                c.isRunning -> listOfNotNull(
                                    c.mode?.let { stringResource(Labels.mode(it)) } ?: stringResource(R.string.common_on),
                                    c.duration?.let { stringResource(R.string.dur_min, it.minutes) },
                                ).joinToString(" · ")
                                c.state == PreAcState.CANCELLED -> stringResource(R.string.climate_stopped_by_car)
                                c.state == PreAcState.OFF -> stringResource(R.string.common_off)
                                else -> "–"
                            },
                            valueColor = when {
                                c.isRunning -> status.charge
                                c.state == PreAcState.CANCELLED -> status.warn
                                else -> Color.Unspecified
                            },
                        )
                        LabeledValue(stringResource(R.string.carview_interior), onOffText(car.interiorLights), valueColor = if (car.interiorLights == true) status.warn else Color.Unspecified)
                        LabeledValue(
                            stringResource(R.string.security_power),
                            when (car.ignition) {
                                Ignition.OFF -> stringResource(R.string.common_off)
                                Ignition.ACC -> stringResource(R.string.ignition_acc)
                                Ignition.ON -> stringResource(R.string.common_on)
                                Ignition.UNKNOWN -> "–"
                            },
                        )
                        if (car.ignition == Ignition.ACC || car.ignition == Ignition.ON) {
                            DialogNote(stringResource(R.string.climate_power_on_hint), MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(6.dp))
                        if (c.isRunning) {
                            val msgOff = stringResource(R.string.cmd_climate_off)
                            DialogAction(pending, stringResource(R.string.carview_stop_climate)) { vm.run(CarCommand.ClimateOff, msgOff) }
                        } else {
                            DialogAction(pending, stringResource(R.string.carview_start_climate)) { vm.quickClimate() }
                            DialogNote(stringResource(R.string.climate_smart_explain))
                        }
                    }
                    CarPart.CHARGE -> {
                        val pct = car.batteryPercent
                        LabeledValue(
                            stringResource(R.string.carview_battery),
                            pct?.let { "$it % · ≈ ${(it * fullRangeKm) / 100} km" } ?: "–",
                        )
                        LabeledValue(
                            stringResource(R.string.carview_cable),
                            when (car.plug) {
                                PlugState.PLUGGED -> stringResource(R.string.home_plugged_in)
                                PlugState.UNPLUGGED -> stringResource(R.string.home_unplugged)
                                PlugState.UNKNOWN -> if (car.isCharging) stringResource(R.string.home_plugged_in) else "–"
                            },
                        )
                        val (state, color) = when {
                            car.isCharging -> stringResource(
                                when (car.chargeKind) {
                                    ChargeKind.TRICKLE -> R.string.home_charge_trickle
                                    ChargeKind.QUICK -> R.string.home_charge_quick
                                    else -> R.string.home_charging
                                },
                            ) to status.charge
                            car.chargeTimerError -> stringResource(R.string.home_charge_failed) to status.warn
                            car.chargeWaiting -> stringResource(R.string.home_charge_waiting) to Color.Unspecified
                            car.plug == PlugState.PLUGGED -> stringResource(R.string.home_not_charging) to Color.Unspecified
                            else -> null to Color.Unspecified
                        }
                        state?.let { LabeledValue(stringResource(R.string.carview_charge_state), it, valueColor = color) }
                        car.chargeDoneAtMs(now)?.let {
                            DialogNote(stringResource(R.string.home_full_at, Format.clockOrDay(it, now), Format.minutes(car.chargeMinutesRemaining ?: 0)))
                        }
                        if (car.chargeTimerError) DialogNote(stringResource(R.string.home_charge_failed_note), status.warn)
                        if (car.batteryWarming) DialogNote(stringResource(R.string.home_batt_warming))
                        if (car.batteryTooCold) DialogNote(stringResource(R.string.home_batt_too_cold), status.warn)
                        // How the timer buttons work, in plain words — they only matter when the car has a charge timer.
                        val timerSet = io.github.anwo91.phevlink.core.model.ScheduleDecoder.charge(car).any { it.programmed }
                        Spacer(Modifier.height(4.dp))
                        DialogNote(stringResource(if (timerSet || car.chargeWaiting) R.string.charge_how_timer else R.string.charge_how_no_timer))
                        if (car.plug == PlugState.PLUGGED && !car.isCharging && (car.chargeWaiting || timerSet)) {
                            Spacer(Modifier.height(6.dp))
                            val msgCharge = stringResource(R.string.msg_charge_requested)
                            DialogAction(pending, stringResource(R.string.quick_charge_now)) { vm.run(CarCommand.ChargeNow, msgCharge) }
                        } else if (car.isCharging && timerSet) {
                            Spacer(Modifier.height(6.dp))
                            DialogAction(pending, stringResource(R.string.quick_resume_timer)) { vm.run(CarCommand.ResumeChargeTimer) }
                        }
                    }
                }
                if (pending != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(pending, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

@Composable
private fun onOffText(b: Boolean?): String = when (b) {
    true -> stringResource(R.string.common_on)
    false -> stringResource(R.string.common_off)
    null -> "–"
}

@Composable
private fun OpenLine(label: String, open: Boolean?) {
    LabeledValue(
        label,
        when (open) {
            true -> stringResource(R.string.carview_open)
            false -> stringResource(R.string.carview_closed)
            null -> "–"
        },
        valueColor = if (open == true) LocalStatus.current.warn else Color.Unspecified,
    )
}

@Composable
private fun DialogNote(text: String, color: Color = Color.Unspecified) {
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
    )
}

@Composable
private fun DialogAction(pending: String?, text: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = pending == null, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

/** One log line describing what the picture shows when a part is opened — helps match a screenshot to the car's data. */
fun carPictureLog(part: CarPart, car: CarState): String {
    val d = car.doors
    return "Car picture: opened ${part.name} — locked=${car.locked}, open=${d?.openList()?.joinToString()?.ifEmpty { "none" } ?: "?"}, " +
        "headlights=${car.headlights}, parking=${car.parkingLights}, hazards=${car.hazardLights}, interior=${car.interiorLights}, " +
        "climate=${car.climate.state}/${car.climate.mode}, ignition=${car.ignition}, plug=${car.plug}, charging=${car.isCharging} (${car.chargeKind}), " +
        "battery=${car.batteryPercent}%, alarm=${car.theftAlarm}"
}
