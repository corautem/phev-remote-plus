package io.github.anwo91.phevlink.ui.home.carpic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import io.github.anwo91.phevlink.data.CarColour
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.Doors
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.TheftAlarm
import io.github.anwo91.phevlink.data.CarStyle
import io.github.anwo91.phevlink.data.TapHints
import io.github.anwo91.phevlink.ui.icons.PhevIcons
import io.github.anwo91.phevlink.ui.theme.LocalStatus
import io.github.anwo91.phevlink.util.Labels
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/** Style + hints that already showed their "what you can tap" tour in this run of the app. */
private val toured = HashSet<String>()

/**
 * The car picture in the chosen [style] (from above, from the side, or a 3D model you can turn by dragging), with
 * [hints] showing what can be tapped: icons on the parts, labels beside them, or nothing until a part is touched (then
 * it lights up; the first time, the parts light up one after another). Every touch highlights the part under the
 * finger; a tap calls [onPart], holding calls [onHold] (the large view). [large]: in the large view the 3D model can
 * also be tilted.
 */
@Composable
fun CarPicture(
    car: CarState,
    style: CarStyle,
    hints: TapHints,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    colour: CarColour = CarColour.THEME,
    onPart: (CarPart) -> Unit,
    onHold: (() -> Unit)? = null,
) {
    val pal = rememberPalette(colour)
    val scene3d = remember { Car3DScene() }
    val scene: CarScene = when (style) {
        CarStyle.TOP -> TopScene
        CarStyle.SIDE -> SideScene
        CarStyle.THREE_D -> scene3d
    }
    var yaw by rememberSaveable { mutableFloatStateOf(35f) }
    var pitch by rememberSaveable(large) { mutableFloatStateOf(if (large) 24f else 16f) }
    var pressed by remember { mutableStateOf<CarPart?>(null) }
    var tour by remember { mutableStateOf<CarPart?>(null) }
    val tap by rememberUpdatedState(onPart)
    val hold by rememberUpdatedState(onHold)
    val haptics = LocalHapticFeedback.current
    val carNow by rememberUpdatedState(car)

    // "On touch" shows nothing until touched — so the first time, light the parts up one by one.
    LaunchedEffect(style, hints) {
        val key = "$style/$hints"
        if (hints == TapHints.HIGHLIGHT && key !in toured) {
            toured += key
            delay(700)
            for (p in listOf(CarPart.LIGHTS, CarPart.CABIN, CarPart.BODY, CarPart.CHARGE)) {
                tour = p
                delay(450)
            }
            tour = null
        }
    }

    // Animate only while something moves, so a parked, quiet car costs no redraws.
    val moving = car.isCharging || car.climate.isRunning || car.hazardLights == true || car.theftAlarm == TheftAlarm.TRIGGERED
    val phaseState = if (moving) {
        rememberInfiniteTransition(label = "car").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "phase",
        )
    } else null

    val cs = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = cs.onSurface)
    val measurer = rememberTextMeasurer()
    val labelTexts = mapOf(
        CarPart.LIGHTS to stringResource(R.string.carview_label_lights),
        CarPart.BODY to stringResource(R.string.carview_label_body),
        CarPart.CABIN to stringResource(R.string.carview_label_cabin),
        CarPart.CHARGE to stringResource(R.string.carview_label_charge),
    )
    val labels = remember(labelTexts, labelStyle) { labelTexts.mapValues { (_, t) -> measurer.measure(t, labelStyle) } }
    val icons = mapOf(
        CarPart.LIGHTS to rememberVectorPainter(PhevIcons.Headlights),
        CarPart.BODY to rememberVectorPainter(PhevIcons.Door),
        CarPart.CABIN to rememberVectorPainter(PhevIcons.Temperature),
        CarPart.CHARGE to rememberVectorPainter(PhevIcons.Plug),
    )
    val badgeFill = cs.surface.copy(alpha = 0.92f)
    val accent = cs.primary

    val desc = describe(car)
    val showLarge = stringResource(R.string.carview_show_large)
    val actions = listOf(
        stringResource(R.string.carview_lights_title) to CarPart.LIGHTS,
        stringResource(R.string.carview_body_title) to CarPart.BODY,
        stringResource(R.string.carview_cabin_title) to CarPart.CABIN,
        stringResource(R.string.carview_charge_title) to CarPart.CHARGE,
    ).map { (label, part) -> CustomAccessibilityAction(label) { tap(part); true } } +
        listOfNotNull(onHold?.let { CustomAccessibilityAction(showLarge) { hold?.invoke(); true } })

    fun fit(size: IntSize): Triple<Float, Float, Float> {
        val s = min(size.width / scene.w, size.height / scene.h)
        return Triple(s, (size.width - scene.w * s) / 2f, (size.height - scene.h * s) / 2f)
    }

    Canvas(
        modifier
            .alpha(if (car.hasData) 1f else 0.55f)
            .semantics { contentDescription = desc; customActions = actions }
            .pointerInput(scene) {
                fun partAt(p: Offset): CarPart? {
                    val (s, ox, oy) = fit(size)
                    return scene.partAt((p.x - ox) / s, (p.y - oy) / s, carNow)
                }
                detectTapGestures(
                    onPress = { p ->
                        pressed = partAt(p)
                        tryAwaitRelease()
                        pressed = null
                    },
                    onTap = { p -> partAt(p)?.let { tap(it) } },
                    onLongPress = if (onHold == null) null else { _ ->
                        hold?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            pressed = null
                            it()
                        }
                    },
                )
            }
            .then(
                if (style != CarStyle.THREE_D) Modifier
                else Modifier.pointerInput(large) {
                    // Sideways turns the car; in the large view up/down tilts it too.
                    if (large) {
                        detectDragGestures { change, d ->
                            change.consume()
                            pressed = null
                            yaw = (yaw + d.x * 0.45f) % 360f
                            pitch = (pitch + d.y * 0.25f).coerceIn(5f, 70f)
                        }
                    } else {
                        detectHorizontalDragGestures { change, dx ->
                            change.consume()
                            pressed = null
                            yaw = (yaw + dx * 0.45f) % 360f
                        }
                    }
                },
            ),
    ) {
        val t = phaseState?.value ?: 0f
        val anim = Anim(pulse = 0.5f - 0.5f * cos(2f * PI.toFloat() * t), blinkOn = t < 0.5f, phase = t)
        scene3d.yaw = yaw
        scene3d.pitch = pitch
        val s = min(size.width / scene.w, size.height / scene.h)
        val ox = (size.width - scene.w * s) / 2f
        val oy = (size.height - scene.h * s) / 2f
        drawIntoCanvas { canvas ->
            val pen = ComposePen(canvas)
            pen.save()
            pen.translate(ox, oy)
            pen.scale(s, s)
            scene.draw(pen, car, pal, anim, pressed ?: tour)
            pen.restore()
        }

        // Hints, in screen pixels so they stay the same size whatever the picture's scale.
        if (hints == TapHints.HIGHLIGHT) return@Canvas
        val r = 11.dp.toPx()
        val iconPx = 14.dp.toPx()
        val line = 1.dp.toPx()
        for (h in scene.hints(car)) {
            if (!h.visible) continue
            val tx = ox + h.tx * s
            val ty = oy + h.ty * s
            if (hints == TapHints.ICONS) {
                drawCircle(badgeFill, r, Offset(tx, ty))
                drawCircle(accent, r, Offset(tx, ty), style = Stroke(1.5.dp.toPx()))
                val painter = icons.getValue(h.part)
                translate(tx - iconPx / 2f, ty - iconPx / 2f) {
                    with(painter) { draw(Size(iconPx, iconPx), colorFilter = ColorFilter.tint(accent)) }
                }
            } else {
                val layout = labels.getValue(h.part)
                val padX = 6.dp.toPx()
                val padY = 2.dp.toPx()
                val bw = layout.size.width + 2 * padX
                val bh = layout.size.height + 2 * padY
                val lx = (ox + h.lx * s).coerceIn(bw / 2f, size.width - bw / 2f)
                val ly = (oy + h.ly * s).coerceIn(bh / 2f, size.height - bh / 2f)
                if (kotlin.math.hypot(lx - tx, ly - ty) > bh) {
                    drawLine(accent.copy(alpha = 0.8f), Offset(lx, ly), Offset(tx, ty), line)
                }
                drawCircle(accent, 2.5.dp.toPx(), Offset(tx, ty))
                drawRoundRect(badgeFill, Offset(lx - bw / 2f, ly - bh / 2f), Size(bw, bh), CornerRadius(bh / 2f))
                drawRoundRect(accent, Offset(lx - bw / 2f, ly - bh / 2f), Size(bw, bh), CornerRadius(bh / 2f), style = Stroke(line))
                drawText(layout, topLeft = Offset(lx - layout.size.width / 2f, ly - layout.size.height / 2f))
            }
        }
    }
}

/** The picture's colours from the app theme (light or dark, brand or wallpaper colours). */
@Composable
fun rememberPalette(colour: CarColour = CarColour.THEME): Palette {
    val status = LocalStatus.current
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    return remember(cs, status, dark, colour) {
        val body = colour.argb?.toInt() ?: (if (dark) cs.surfaceContainerHighest else cs.surface).toArgb()
        Palette(
            body = body,
            // Dark paint needs a lighter outline to keep its shape.
            outline = if (luminance(body) < 0.2f) 0xBFA3AAB0.toInt() else cs.outline.copy(alpha = 0.75f).toArgb(),
            glass = if (dark) 0xFF10171B.toInt() else 0xFF3E4A52.toInt(),
            tyre = if (dark) 0xFF0A0D0F.toInt() else 0xFF2F363A.toInt(),
            cable = if (dark) 0xFFB0B8BE.toInt() else 0xFF2F363A.toInt(),
            good = status.good.toArgb(),
            warn = status.warn.toArgb(),
            bad = status.bad.toArgb(),
            charge = status.charge.toArgb(),
            highlight = cs.primary.toArgb(),
            dark = dark,
        )
    }
}

@Composable
private fun describe(car: CarState): String {
    val ctx = LocalContext.current
    val doors = car.doors ?: Doors()
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

/** The two choices for the car picture: its style and how it shows what can be tapped. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CarPictureOptions(
    style: CarStyle, hints: TapHints, onStyle: (CarStyle) -> Unit, onHints: (TapHints) -> Unit,
    colour: CarColour? = null, onColour: (CarColour) -> Unit = {},
) {
    Text(stringResource(R.string.set_carpic_style), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val styles = listOf(CarStyle.TOP to R.string.set_carpic_top, CarStyle.SIDE to R.string.set_carpic_side, CarStyle.THREE_D to R.string.set_carpic_3d)
        styles.forEachIndexed { i, (st, label) ->
            SegmentedButton(selected = style == st, onClick = { onStyle(st) }, shape = SegmentedButtonDefaults.itemShape(i, styles.size)) {
                Text(stringResource(label))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.set_carpic_hints), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val modes = listOf(TapHints.ICONS to R.string.set_carpic_icons, TapHints.LABELS to R.string.set_carpic_labels, TapHints.HIGHLIGHT to R.string.set_carpic_touch)
        modes.forEachIndexed { i, (m, label) ->
            SegmentedButton(selected = hints == m, onClick = { onHints(m) }, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) {
                Text(stringResource(label), maxLines = 1)
            }
        }
    }
    if (colour != null) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.set_carpic_colour), style = MaterialTheme.typography.labelLarge)
        ColourSwatches(colour, onColour)
    }
}

/** Round swatches for the car's paint; the first follows the app's colours. */
@Composable
private fun ColourSwatches(selected: CarColour, onPick: (CarColour) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val names = mapOf(
        CarColour.THEME to stringResource(R.string.car_colour_theme), CarColour.WHITE to stringResource(R.string.car_colour_white),
        CarColour.SILVER to stringResource(R.string.car_colour_silver), CarColour.GREY to stringResource(R.string.car_colour_grey),
        CarColour.BLACK to stringResource(R.string.car_colour_black), CarColour.RED to stringResource(R.string.car_colour_red),
        CarColour.BLUE to stringResource(R.string.car_colour_blue), CarColour.BROWN to stringResource(R.string.car_colour_brown),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        CarColour.entries.forEach { c ->
            val fill = c.argb?.let { Color(it) } ?: cs.surfaceContainerHighest
            val chosen = c == selected
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(fill)
                    .border(if (chosen) 3.dp else 1.dp, if (chosen) cs.primary else cs.outline, CircleShape)
                    .clickable { onPick(c) }
                    .semantics { contentDescription = names.getValue(c); this.selected = chosen },
                contentAlignment = Alignment.Center,
            ) {
                if (c == CarColour.THEME) Icon(PhevIcons.Theme, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                else if (chosen) Icon(PhevIcons.Check, null, Modifier.size(18.dp), tint = if (luminance(fill.toArgb()) > 0.5f) Color.Black else Color.White)
            }
        }
    }
}

/** The car picture filling the screen (hold the picture on the home screen), with the style choices below it. */
@Composable
fun CarPictureDialog(
    car: CarState,
    title: String,
    style: CarStyle,
    hints: TapHints,
    colour: CarColour,
    onStyle: (CarStyle) -> Unit,
    onHints: (TapHints) -> Unit,
    onColour: (CarColour) -> Unit,
    onPart: (CarPart) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(PhevIcons.Close, stringResource(R.string.carview_close)) }
                }
                Text(
                    stringResource(if (style == CarStyle.THREE_D) R.string.carview_large_hint_3d else R.string.carview_large_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CarPicture(
                    car, style, hints,
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                    large = true, colour = colour, onPart = onPart,
                )
                CarPictureOptions(style, hints, onStyle, onHints, colour, onColour)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
