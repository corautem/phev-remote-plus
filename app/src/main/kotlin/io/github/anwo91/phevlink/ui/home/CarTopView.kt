package io.github.anwo91.phevlink.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.ChargeKind
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.Doors
import io.github.anwo91.phevlink.core.model.Ignition
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.PreAcState
import io.github.anwo91.phevlink.core.model.TheftAlarm
import io.github.anwo91.phevlink.ui.MainViewModel
import io.github.anwo91.phevlink.ui.common.LabeledValue
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.ui.theme.StatusColors
import io.github.anwo91.phevlink.util.Format
import io.github.anwo91.phevlink.util.Labels
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/** The parts of the car picture you can tap; each opens a dialog with what it means and what you can do. */
enum class CarPart { LIGHTS, BODY, CABIN, CHARGE }

// The drawing works in a fixed "unit" space, nose up, scaled to fit: the body spans x 18..82, y 22..196.
private const val W = 100f
private const val H = 212f

/** Which part a tap at unit coordinates ([ux], [uy]) means. */
internal fun carPartAt(ux: Float, uy: Float): CarPart = when {
    // The charge port sits on the right rear quarter, with the cable hanging out to the right.
    (ux > 70f && uy in 168f..204f) || (ux > 84f && uy > 160f) -> CarPart.CHARGE
    uy < 66f -> CarPart.LIGHTS
    uy > 164f -> CarPart.BODY
    ux < 29f || ux > 71f -> CarPart.BODY
    else -> CarPart.CABIN
}

/**
 * A top-down picture of the car showing its state at a glance: open doors, boot and bonnet swing out in the warning
 * colour, lights glow, hazards blink, the cabin glows warm or cool while climate runs, the lock badge sits on the roof
 * and a cable hangs from the charge port when plugged in (animated while charging). Tapping a part calls [onPart].
 * Drivers are assumed on the left (LHD), so the driver's door is the front-left one.
 */
@Composable
fun CarTopView(car: CarState, modifier: Modifier = Modifier, onPart: (CarPart) -> Unit) {
    val status = LocalStatus.current
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val palette = CarPalette(
        body = if (dark) cs.surfaceContainerHighest else cs.surface,
        outline = cs.outline.copy(alpha = 0.75f),
        glass = if (dark) Color(0xFF10171B) else Color(0xFF3E4A52),
        tyre = if (dark) Color(0xFF0A0D0F) else Color(0xFF2F363A),
        cable = if (dark) Color(0xFFB0B8BE) else Color(0xFF2F363A),
        status = status,
    )
    val doors = car.doors ?: Doors()
    val climateOn = car.climate.isRunning
    // Animate only while something moves, so a parked, quiet car costs no redraws.
    val moving = car.isCharging || climateOn || car.hazardLights == true || car.theftAlarm == TheftAlarm.TRIGGERED
    val phase: State<Float>? = if (moving) {
        rememberInfiniteTransition(label = "car").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "phase",
        )
    } else null
    val tap by rememberUpdatedState(onPart)

    val desc = describe(car, doors)
    val actions = listOf(
        stringResource(R.string.carview_lights_title) to CarPart.LIGHTS,
        stringResource(R.string.carview_body_title) to CarPart.BODY,
        stringResource(R.string.carview_cabin_title) to CarPart.CABIN,
        stringResource(R.string.carview_charge_title) to CarPart.CHARGE,
    ).map { (label, part) -> CustomAccessibilityAction(label) { tap(part); true } }

    Canvas(
        modifier
            .alpha(if (car.hasData) 1f else 0.55f)
            .semantics { contentDescription = desc; customActions = actions }
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val s = min(size.width / W, size.height / H)
                    val ox = (size.width - W * s) / 2f
                    val oy = (size.height - H * s) / 2f
                    tap(carPartAt((p.x - ox) / s, (p.y - oy) / s))
                }
            },
    ) {
        val s = min(size.width / W, size.height / H)
        val ox = (size.width - W * s) / 2f
        val oy = (size.height - H * s) / 2f
        val t = phase?.value ?: 0f
        val pulse = 0.5f - 0.5f * cos(2f * PI.toFloat() * t) // 0 → 1 → 0, smooth
        val blinkOn = t < 0.5f
        withTransform({ translate(ox, oy); scale(s, s, Offset.Zero) }) {
            drawCar(car, doors, palette, pulse, blinkOn, t)
        }
    }
}

private class CarPalette(val body: Color, val outline: Color, val glass: Color, val tyre: Color, val cable: Color, val status: StatusColors)

private val HEADLIGHT = Color(0xFFFFF3B0)
private val AMBER = Color(0xFFFFB300)
private val TAIL = Color(0xFFE53935)
private val HEAT = Color(0xFFFF7043)
private val DEFROST = Color(0xFF26C6DA)
private val INTERIOR = Color(0xFFFFE082)

private fun DrawScope.drawCar(car: CarState, doors: Doors, p: CarPalette, pulse: Float, blinkOn: Boolean, phase: Float) {
    val warn = p.status.warn
    val bodyPath = Path().apply {
        addRoundRect(
            RoundRect(
                18f, 22f, 82f, 196f,
                topLeftCornerRadius = CornerRadius(16f, 20f), topRightCornerRadius = CornerRadius(16f, 20f),
                bottomRightCornerRadius = CornerRadius(12f, 10f), bottomLeftCornerRadius = CornerRadius(12f, 10f),
            ),
        )
    }
    val thin = Stroke(width = 1f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // Headlight beams reach out ahead of the car.
    if (car.headlights == true) {
        listOf(listOf(22f, 37f, 46f, 6f), listOf(63f, 78f, 94f, 54f)).forEach { (a, b, c, d) ->
            val beam = Path().apply { moveTo(a, 26f); lineTo(b, 26f); lineTo(c, 0f); lineTo(d, 0f); close() }
            drawPath(beam, Brush.verticalGradient(listOf(HEADLIGHT.copy(alpha = 0.55f), HEADLIGHT.copy(alpha = 0f)), startY = 26f, endY = 0f))
        }
    }

    // Wheels and mirrors stick out from under the body.
    listOf(14f to 38f, 80f to 38f, 14f to 148f, 80f to 148f).forEach { (x, y) ->
        drawRoundRect(p.tyre, Offset(x, y), Size(6f, 24f), CornerRadius(2.5f))
    }
    listOf(11f, 81f).forEach { x ->
        drawRoundRect(p.body, Offset(x, 74f), Size(8f, 6f), CornerRadius(2.5f))
        drawRoundRect(p.outline, Offset(x, 74f), Size(8f, 6f), CornerRadius(2.5f), style = thin)
    }

    drawPath(bodyPath, p.body)
    clipPath(bodyPath) {
        // Open panels: tint the gap.
        if (doors.bonnet) drawRect(warn.copy(alpha = 0.30f), Offset(0f, 22f), Size(W, 44f))
        if (doors.boot) drawRect(warn.copy(alpha = 0.30f), Offset(0f, 164f), Size(W, 32f))
        if (doors.driver) drawRect(warn.copy(alpha = 0.35f), Offset(18f, 86f), Size(11f, 34f))
        if (doors.rearLeft) drawRect(warn.copy(alpha = 0.35f), Offset(18f, 120f), Size(11f, 34f))
        if (doors.frontPassenger) drawRect(warn.copy(alpha = 0.35f), Offset(71f, 86f), Size(11f, 34f))
        if (doors.rearRight) drawRect(warn.copy(alpha = 0.35f), Offset(71f, 120f), Size(11f, 34f))

        // Glass and roof.
        val roof = lerp(p.body, p.glass, 0.12f)
        val windscreen = Path().apply { moveTo(25f, 68f); lineTo(75f, 68f); lineTo(71f, 86f); lineTo(29f, 86f); close() }
        val rearWindow = Path().apply { moveTo(29f, 152f); lineTo(71f, 152f); lineTo(73f, 164f); lineTo(27f, 164f); close() }
        val leftGlass = Path().apply { moveTo(23f, 88f); lineTo(29f, 88f); lineTo(29f, 150f); lineTo(24f, 148f); close() }
        val rightGlass = Path().apply { moveTo(77f, 88f); lineTo(71f, 88f); lineTo(71f, 150f); lineTo(76f, 148f); close() }
        drawPath(windscreen, p.glass)
        drawPath(rearWindow, p.glass)
        drawPath(leftGlass, p.glass.copy(alpha = 0.85f))
        drawPath(rightGlass, p.glass.copy(alpha = 0.85f))
        drawRoundRect(roof, Offset(29f, 86f), Size(42f, 66f), CornerRadius(5f))

        // Climate: the cabin glows warm (heat) or cool (cool); defrost lights up the windscreen.
        if (car.climate.isRunning) {
            val a = 0.20f + 0.35f * pulse
            when (car.climate.mode) {
                ClimateMode.WINDSCREEN -> drawPath(windscreen, DEFROST.copy(alpha = a + 0.15f))
                ClimateMode.HEAT -> {
                    drawRoundRect(HEAT.copy(alpha = a), Offset(29f, 86f), Size(42f, 66f), CornerRadius(5f))
                    drawPath(windscreen, HEAT.copy(alpha = a * 0.6f))
                }
                else -> {
                    drawRoundRect(p.status.charge.copy(alpha = a), Offset(29f, 86f), Size(42f, 66f), CornerRadius(5f))
                    drawPath(windscreen, p.status.charge.copy(alpha = a * 0.6f))
                }
            }
        }
        if (car.interiorLights == true) {
            drawCircle(Brush.radialGradient(listOf(INTERIOR.copy(alpha = 0.75f), INTERIOR.copy(alpha = 0f)), Offset(50f, 108f), 22f), 22f, Offset(50f, 108f))
        }

        // Panel lines: bonnet edge, door seams, boot edge.
        val bonnet = Path().apply { moveTo(19f, 66f); quadraticTo(50f, 60f, 81f, 66f) }
        drawPath(bonnet, p.outline, style = thin)
        drawLine(p.outline.copy(alpha = 0.4f), Offset(37f, 30f), Offset(35f, 62f), 0.8f)
        drawLine(p.outline.copy(alpha = 0.4f), Offset(63f, 30f), Offset(65f, 62f), 0.8f)
        listOf(86f, 120f, 154f).forEach { y ->
            drawLine(p.outline, Offset(18f, y), Offset(24f, y), 0.9f)
            drawLine(p.outline, Offset(76f, y), Offset(82f, y), 0.9f)
        }
        drawLine(p.outline, Offset(20f, 166f), Offset(80f, 166f), 0.9f)

        // Front lights: bright when the headlights are on, amber when only the parking lights are.
        val lightsOn = car.headlights == true || car.parkingLights == true
        val front = when {
            car.headlights == true -> HEADLIGHT
            car.parkingLights == true -> AMBER
            else -> p.outline.copy(alpha = 0.35f)
        }
        listOf(22f, 63f).forEach { x ->
            drawRoundRect(front, Offset(x, 24f), Size(15f, 7f), CornerRadius(3f))
            drawRoundRect(TAIL.copy(alpha = if (lightsOn) 1f else 0.45f), Offset(x, 189f), Size(15f, 5f), CornerRadius(2f))
        }
    }
    drawPath(bodyPath, p.outline, style = thin)

    // Glow around lit lights.
    if (car.headlights == true || car.parkingLights == true) {
        val glow = if (car.headlights == true) HEADLIGHT else AMBER
        listOf(29.5f, 70.5f).forEach { x ->
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.6f), glow.copy(alpha = 0f)), Offset(x, 27f), 11f), 11f, Offset(x, 27f))
            drawCircle(Brush.radialGradient(listOf(TAIL.copy(alpha = 0.5f), TAIL.copy(alpha = 0f)), Offset(x, 192f), 9f), 9f, Offset(x, 192f))
        }
    }
    // Hazards blink amber at all four corners.
    if (car.hazardLights == true && blinkOn) {
        listOf(Offset(21f, 34f), Offset(79f, 34f), Offset(21f, 186f), Offset(79f, 186f)).forEach { c ->
            drawCircle(Brush.radialGradient(listOf(AMBER.copy(alpha = 0.7f), AMBER.copy(alpha = 0f)), c, 9f), 9f, c)
            drawCircle(AMBER, 3f, c)
        }
    }

    // Climate symbol on the roof.
    if (car.climate.isRunning) drawClimateIcon(car.climate.mode, p)

    // Lock badge on the roof.
    car.locked?.let { locked -> drawLockBadge(locked, if (locked) p.status.good else warn) }

    // Open doors swing out from their front hinges; the boot lid lifts out past the rear.
    val doorStroke = Stroke(width = 0.8f)
    fun door(hingeX: Float, hingeY: Float, left: Boolean) {
        rotate(if (left) 28f else -28f, Offset(hingeX, hingeY)) {
            val x = if (left) hingeX - 4f else hingeX
            drawRoundRect(warn, Offset(x, hingeY), Size(4f, 33f), CornerRadius(1.5f))
            drawRoundRect(p.outline, Offset(x, hingeY), Size(4f, 33f), CornerRadius(1.5f), style = doorStroke)
        }
    }
    if (doors.driver) door(18f, 86f, left = true)
    if (doors.rearLeft) door(18f, 120f, left = true)
    if (doors.frontPassenger) door(82f, 86f, left = false)
    if (doors.rearRight) door(82f, 120f, left = false)
    if (doors.boot) {
        drawRoundRect(warn, Offset(23f, 194f), Size(54f, 12f), CornerRadius(4f))
        drawRoundRect(p.outline, Offset(23f, 194f), Size(54f, 12f), CornerRadius(4f), style = doorStroke)
    }
    if (doors.bonnet) {
        val lid = Path().apply { moveTo(24f, 20f); quadraticTo(50f, 10f, 76f, 20f) }
        drawPath(lid, warn, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }

    drawChargePort(car, p, pulse, phase)

    // Alarm going off: a pulsing red outline.
    if (car.theftAlarm == TheftAlarm.TRIGGERED) {
        drawPath(bodyPath, p.status.bad.copy(alpha = 0.4f + 0.6f * pulse), style = Stroke(width = 3f))
    }
}

private fun DrawScope.drawClimateIcon(mode: ClimateMode?, p: CarPalette) {
    val c = Offset(50f, 106f)
    val stroke = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (mode) {
        ClimateMode.HEAT -> listOf(45f, 50f, 55f).forEach { x ->
            val wave = Path().apply {
                moveTo(x, 113f); quadraticTo(x - 2.5f, 110f, x, 106.5f); quadraticTo(x + 2.5f, 103f, x, 99.5f)
            }
            drawPath(wave, HEAT, style = stroke)
        }
        ClimateMode.WINDSCREEN -> listOf(40f, 50f, 60f).forEach { x ->
            val wave = Path().apply {
                moveTo(x, 84f); quadraticTo(x - 2f, 81f, x, 78f); quadraticTo(x + 2f, 75f, x, 71f)
            }
            drawPath(wave, Color.White.copy(alpha = 0.9f), style = stroke)
        }
        else -> { // cool: a snowflake
            listOf(90.0, 30.0, 150.0).forEach { deg ->
                val r = 7f
                val dx = (r * cos(deg * PI / 180)).toFloat()
                val dy = (r * kotlin.math.sin(deg * PI / 180)).toFloat()
                drawLine(p.status.charge, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), 1.6f, StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.drawLockBadge(locked: Boolean, color: Color) {
    val c = Offset(50f, 134f)
    drawCircle(color, 8.5f, c)
    drawRoundRect(Color.White, Offset(c.x - 4f, c.y - 1f), Size(8f, 6f), CornerRadius(1.2f))
    val shackle = Stroke(width = 1.4f, cap = StrokeCap.Round)
    val lift = if (locked) 0f else 2.2f
    val top = c.y - 6.6f - lift
    drawArc(Color.White, 180f, 180f, false, Offset(c.x - 2.8f, top), Size(5.6f, 6f), style = shackle)
    val mid = top + 3f
    drawLine(Color.White, Offset(c.x - 2.8f, mid), Offset(c.x - 2.8f, c.y - 1f), 1.4f)
    drawLine(Color.White, Offset(c.x + 2.8f, mid), Offset(c.x + 2.8f, if (locked) c.y - 1f else mid + 0.8f), 1.4f)
}

/** The charge port on the right rear quarter; when plugged a cable hangs out, with energy flowing in while charging. */
private fun DrawScope.drawChargePort(car: CarState, p: CarPalette, pulse: Float, phase: Float) {
    val port = Offset(82f, 180f)
    val plugged = car.plug == PlugState.PLUGGED || car.isCharging
    if (!plugged) {
        drawRoundRect(p.outline, Offset(79.5f, 175f), Size(2.5f, 10f), CornerRadius(1f), style = Stroke(width = 0.8f))
        return
    }
    if (car.isCharging) {
        val r = 7f + 4f * pulse
        drawCircle(Brush.radialGradient(listOf(p.status.charge.copy(alpha = 0.7f), p.status.charge.copy(alpha = 0f)), port, r), r, port)
    }
    val cable = Path().apply {
        moveTo(87f, 180f); cubicTo(94f, 180f, 96f, 186f, 96f, 196f); lineTo(96f, 212f)
    }
    val cableColor = if (car.isCharging) p.status.charge else p.cable
    drawPath(cable, cableColor, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
    if (car.isCharging) {
        // Dashes flowing towards the car.
        drawPath(
            cable, Color.White.copy(alpha = 0.85f),
            style = Stroke(width = 1.1f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f, 4.5f), phase * 7f)),
        )
    }
    drawRoundRect(p.tyre, Offset(81f, 176.5f), Size(7f, 7f), CornerRadius(1.6f))
    drawRoundRect(cableColor, Offset(81f, 176.5f), Size(7f, 7f), CornerRadius(1.6f), style = Stroke(width = 0.8f))
}

@Composable
private fun describe(car: CarState, doors: Doors): String {
    val ctx = LocalContext.current
    val open = Labels.openDoors(ctx, doors)
    val parts = listOfNotNull(
        when (car.locked) {
            true -> stringResource(R.string.security_locked)
            false -> stringResource(R.string.security_unlocked)
            null -> null
        },
        car.doors?.let {
            if (open.isEmpty()) stringResource(R.string.carview_all_closed_short)
            else stringResource(R.string.carview_some_open, open.joinToString())
        },
        stringResource(R.string.carview_headlights).takeIf { car.headlights == true },
        stringResource(R.string.carview_parking).takeIf { car.parkingLights == true },
        stringResource(R.string.carview_hazards).takeIf { car.hazardLights == true },
        stringResource(R.string.climate_on_status).takeIf { car.climate.isRunning },
        when {
            car.isCharging -> stringResource(R.string.home_charging)
            car.plug == PlugState.PLUGGED -> stringResource(R.string.home_plugged_in)
            else -> null
        },
    )
    return stringResource(R.string.carview_desc, parts.joinToString(", ").ifEmpty { "–" })
}

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
