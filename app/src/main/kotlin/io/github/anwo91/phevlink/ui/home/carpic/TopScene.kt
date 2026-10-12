package io.github.anwo91.phevlink.ui.home.carpic

import io.github.anwo91.phevlink.core.model.CarState
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.Doors
import io.github.anwo91.phevlink.core.model.PlugState
import io.github.anwo91.phevlink.core.model.TheftAlarm
import io.github.anwo91.phevlink.ui.home.carpic.Palette.Companion.AMBER
import io.github.anwo91.phevlink.ui.home.carpic.Palette.Companion.BLACK
import io.github.anwo91.phevlink.ui.home.carpic.Palette.Companion.DEFROST
import io.github.anwo91.phevlink.ui.home.carpic.Palette.Companion.HEADLIGHT
import io.github.anwo91.phevlink.ui.home.carpic.Palette.Companion.HEAT
import io.github.anwo91.phevlink.ui.home.carpic.Palette.Companion.INTERIOR
import io.github.anwo91.phevlink.ui.home.carpic.Palette.Companion.TAIL
import io.github.anwo91.phevlink.ui.home.carpic.Palette.Companion.WHITE

/**
 * The car from above, nose up, showing its state at a glance: open doors, boot and bonnet swing out in the warning
 * colour, lights glow, hazards blink, the cabin glows warm or cool while climate runs, the lock badge sits on the roof
 * and a cable hangs from the charge port when plugged in (animated while charging). Unit space 100 × 212; the body
 * spans x 18..82, y 22..196. Drivers are assumed on the left (LHD), so the driver's door is the front-left one.
 */
object TopScene : CarScene {
    override val w = 100f
    override val h = 212f

    override fun partAt(x: Float, y: Float, car: CarState): CarPart? = when {
        x < -4f || x > w + 4f || y < -4f || y > h + 4f -> null
        // The charge port sits on the right rear quarter, with the cable hanging out to the right.
        (x > 70f && y in 168f..204f) || (x > 84f && y > 160f) -> CarPart.CHARGE
        y < 66f -> CarPart.LIGHTS
        y > 164f -> CarPart.BODY
        x < 29f || x > 71f -> CarPart.BODY
        else -> CarPart.CABIN
    }

    override fun hints(car: CarState) = listOf(
        Hint(CarPart.LIGHTS, 50f, 46f),
        Hint(CarPart.CABIN, 50f, 92f),
        Hint(CarPart.BODY, 50f, 178f),
        Hint(CarPart.CHARGE, 91f, 166f, 80f, 204f),
    )

    private val body = Shape().roundRect(18f, 22f, 82f, 196f, 16f, 20f, 16f, 20f, 12f, 10f, 12f, 10f)

    override fun draw(pen: Pen, car: CarState, pal: Palette, anim: Anim, highlight: CarPart?) = with(pen) { drawCar(car, pal, anim, highlight) }

    private fun Pen.drawCar(car: CarState, pal: Palette, anim: Anim, highlight: CarPart?) {
        val doors = car.doors ?: Doors()
        val warn = pal.warn

        // Soft shadow on the ground.
        for (i in 3 downTo 1) {
            fill(Shape().roundRect(18f - i * 1.6f, 24f - i * 1.2f, 82f + i * 1.6f, 198f + i * 1.6f, 16f + i), withAlpha(BLACK, if (pal.dark) 0.10f else 0.06f))
        }

        // Headlight beams reach out ahead of the car.
        if (car.headlights == true) {
            listOf(floatArrayOf(22f, 37f, 46f, 6f), floatArrayOf(63f, 78f, 94f, 54f)).forEach { (a, b, c, d) ->
                fill(Shape().poly(a, 26f, b, 26f, c, 0f, d, 0f), Paint.Linear(0f, 26f, 0f, 0f, intArrayOf(withAlpha(HEADLIGHT, 0.55f), withAlpha(HEADLIGHT, 0f))))
            }
        }

        // Wheels and mirrors stick out from under the body.
        listOf(14f to 38f, 80f to 38f, 14f to 148f, 80f to 148f).forEach { (x, y) ->
            fill(Shape.rr(x, y, 6f, 24f, 2.5f), pal.tyre)
            line(x + (if (x < 50f) 1.2f else 4.8f), y + 4f, x + (if (x < 50f) 1.2f else 4.8f), y + 20f, withAlpha(WHITE, 0.18f), 0.7f)
        }
        listOf(true, false).forEach { left ->
            val m = if (left) Shape().poly(19f, 74f, 11f, 76f, 10.5f, 80f, 19f, 80f) else Shape().poly(81f, 74f, 89f, 76f, 89.5f, 80f, 81f, 80f)
            fill(m, pal.body)
            stroke(m, solid(pal.outline), 0.8f)
        }

        // Body with a gentle left-to-right shading, like light from the upper left.
        fill(
            body,
            Paint.Linear(
                18f, 0f, 82f, 0f,
                intArrayOf(shade(pal.body, 0.86f), shade(pal.body, 1.05f), pal.body, shade(pal.body, 0.80f)),
                floatArrayOf(0f, 0.32f, 0.62f, 1f),
            ),
        )
        saved {
            clip(body)
            // Open panels: tint the gap.
            if (doors.bonnet) fill(Shape().rect(0f, 22f, w, 66f), withAlpha(warn, 0.30f))
            if (doors.boot) fill(Shape().rect(0f, 164f, w, 196f), withAlpha(warn, 0.30f))
            if (doors.driver) fill(Shape().rect(18f, 86f, 29f, 120f), withAlpha(warn, 0.35f))
            if (doors.rearLeft) fill(Shape().rect(18f, 120f, 29f, 154f), withAlpha(warn, 0.35f))
            if (doors.frontPassenger) fill(Shape().rect(71f, 86f, 82f, 120f), withAlpha(warn, 0.35f))
            if (doors.rearRight) fill(Shape().rect(71f, 120f, 82f, 154f), withAlpha(warn, 0.35f))

            // Glass and roof.
            val roofColor = lerpColor(pal.body, pal.glass, 0.12f)
            val windscreen = Shape().poly(25f, 68f, 75f, 68f, 71f, 86f, 29f, 86f)
            val rearWindow = Shape().poly(29f, 152f, 71f, 152f, 73f, 164f, 27f, 164f)
            val leftGlass = Shape().poly(23f, 88f, 29f, 88f, 29f, 150f, 24f, 148f)
            val rightGlass = Shape().poly(77f, 88f, 71f, 88f, 71f, 150f, 76f, 148f)
            val glassTop = lerpColor(pal.glass, WHITE, if (pal.dark) 0.10f else 0.22f)
            fill(windscreen, Paint.Linear(0f, 68f, 0f, 86f, intArrayOf(glassTop, pal.glass)))
            fill(rearWindow, Paint.Linear(0f, 164f, 0f, 152f, intArrayOf(glassTop, pal.glass)))
            fill(leftGlass, withAlpha(pal.glass, 0.88f))
            fill(rightGlass, withAlpha(pal.glass, 0.88f))
            // A faint reflection across the windscreen.
            fill(Shape().poly(36f, 68f, 44f, 68f, 36f, 86f, 31f, 86f), withAlpha(WHITE, 0.10f))
            val roof = Shape.rr(29f, 86f, 42f, 66f, 5f)
            fill(roof, Paint.Linear(29f, 0f, 71f, 0f, intArrayOf(shade(roofColor, 0.92f), shade(roofColor, 1.04f), shade(roofColor, 0.90f)), floatArrayOf(0f, 0.4f, 1f)))
            // Roof rails.
            listOf(31.5f, 68.5f).forEach { x -> line(x, 92f, x, 146f, withAlpha(pal.outline, 0.9f), 1.4f) }

            // Climate: the cabin glows warm (heat) or cool (cool); defrost lights up the windscreen.
            if (car.climate.isRunning) {
                val a = 0.20f + 0.35f * anim.pulse
                when (car.climate.mode) {
                    ClimateMode.WINDSCREEN -> fill(windscreen, withAlpha(DEFROST, a + 0.15f))
                    ClimateMode.HEAT -> {
                        fill(roof, withAlpha(HEAT, a))
                        fill(windscreen, withAlpha(HEAT, a * 0.6f))
                    }
                    else -> {
                        fill(roof, withAlpha(pal.charge, a))
                        fill(windscreen, withAlpha(pal.charge, a * 0.6f))
                    }
                }
            }
            if (car.interiorLights == true) glow(50f, 108f, 22f, INTERIOR, 0.75f)

            // Panel lines: bonnet edge and creases, door seams, boot edge.
            stroke(Shape().moveTo(19f, 66f).quadTo(50f, 60f, 81f, 66f), solid(pal.outline), 1f)
            line(37f, 30f, 35f, 62f, withAlpha(pal.outline, 0.4f), 0.8f)
            line(63f, 30f, 65f, 62f, withAlpha(pal.outline, 0.4f), 0.8f)
            listOf(86f, 120f, 154f).forEach { y ->
                line(18f, y, 24f, y, pal.outline, 0.9f)
                line(76f, y, 82f, y, pal.outline, 0.9f)
            }
            line(20f, 166f, 80f, 166f, pal.outline, 0.9f)

            // Front lights: bright when the headlights are on, amber when only the parking lights are.
            val lightsOn = car.headlights == true || car.parkingLights == true
            val front = when {
                car.headlights == true -> HEADLIGHT
                car.parkingLights == true -> AMBER
                else -> withAlpha(pal.outline, 0.35f)
            }
            fill(Shape().poly(21f, 27f, 37f, 24.5f, 37f, 30f, 23f, 32.5f), front)
            fill(Shape().poly(79f, 27f, 63f, 24.5f, 63f, 30f, 77f, 32.5f), front)
            listOf(22f, 63f).forEach { x ->
                fill(Shape.rr(x, 189f, 15f, 5f, 2f), withAlpha(TAIL, if (lightsOn) 1f else 0.45f))
            }
        }
        stroke(body, solid(pal.outline), 1f)

        // Glow around lit lights.
        if (car.headlights == true || car.parkingLights == true) {
            val g = if (car.headlights == true) HEADLIGHT else AMBER
            listOf(29.5f, 70.5f).forEach { x ->
                glow(x, 28f, 11f, g, 0.6f)
                glow(x, 192f, 9f, TAIL, 0.5f)
            }
        }
        // Hazards blink amber at all four corners.
        if (car.hazardLights == true && anim.blinkOn) {
            listOf(21f to 34f, 79f to 34f, 21f to 186f, 79f to 186f).forEach { (x, y) ->
                glow(x, y, 9f, AMBER, 0.7f)
                fill(Shape().oval(x, y, 3f), AMBER)
            }
        }

        if (car.climate.isRunning) climateBadge(50f, 110f, 7.5f, car.climate.mode, pal)
        car.locked?.let { lockBadge(50f, 134f, 8.5f, it, if (it) pal.good else warn) }

        // Open doors swing out from their front hinges; the boot lid lifts out past the rear.
        fun door(hx: Float, hy: Float, left: Boolean) = saved {
            rotate(if (left) 28f else -28f, hx, hy)
            val x = if (left) hx - 4f else hx
            val d = Shape.rr(x, hy, 4f, 33f, 1.5f)
            fill(d, warn)
            stroke(d, solid(pal.outline), 0.8f)
        }
        if (doors.driver) door(18f, 86f, left = true)
        if (doors.rearLeft) door(18f, 120f, left = true)
        if (doors.frontPassenger) door(82f, 86f, left = false)
        if (doors.rearRight) door(82f, 120f, left = false)
        if (doors.boot) {
            val lid = Shape.rr(23f, 194f, 54f, 12f, 4f)
            fill(lid, warn)
            stroke(lid, solid(pal.outline), 0.8f)
        }
        if (doors.bonnet) stroke(Shape().moveTo(24f, 20f).quadTo(50f, 10f, 76f, 20f), solid(warn), 3f)

        chargePort(car, pal, anim)

        // Alarm going off: a pulsing red outline.
        if (car.theftAlarm == TheftAlarm.TRIGGERED) stroke(body, solid(withAlpha(pal.bad, 0.4f + 0.6f * anim.pulse)), 3f)

        highlight?.let { drawHighlight(it, pal) }
    }

    /** The charge port on the right rear quarter; when plugged a cable hangs out, with energy flowing in while charging. */
    private fun Pen.chargePort(car: CarState, pal: Palette, anim: Anim) {
        val plugged = car.plug == PlugState.PLUGGED || car.isCharging
        if (!plugged) {
            stroke(Shape.rr(79.5f, 175f, 2.5f, 10f, 1f), solid(pal.outline), 0.8f)
            return
        }
        if (car.isCharging) glow(82f, 180f, 7f + 4f * anim.pulse, pal.charge, 0.7f)
        val cable = Shape().moveTo(87f, 180f).cubicTo(94f, 180f, 96f, 186f, 96f, 196f).lineTo(96f, 212f)
        val cableColor = if (car.isCharging) pal.charge else pal.cable
        stroke(cable, solid(cableColor), 2.4f)
        // Dashes flowing towards the car.
        if (car.isCharging) stroke(cable, solid(withAlpha(WHITE, 0.85f)), 1.1f, floatArrayOf(2.5f, 4.5f), anim.phase * 7f)
        val plug = Shape.rr(81f, 176.5f, 7f, 7f, 1.6f)
        fill(plug, pal.tyre)
        stroke(plug, solid(cableColor), 0.8f)
    }

    private fun Pen.drawHighlight(part: CarPart, pal: Palette) {
        val fillC = withAlpha(pal.highlight, 0.30f)
        if (part == CarPart.CHARGE) {
            val c = Shape().oval(86f, 184f, 13f)
            fill(c, fillC)
            stroke(c, solid(pal.highlight), 1.6f)
            return
        }
        val region = when (part) {
            CarPart.LIGHTS -> Shape().rect(0f, 0f, w, 66f)
            CarPart.CABIN -> Shape().rect(29f, 66f, 71f, 164f)
            else -> Shape().rect(0f, 66f, 29f, 164f).rect(71f, 66f, w, 164f).rect(0f, 164f, w, h)
        }
        saved {
            clip(body)
            fill(region, fillC)
        }
        saved {
            clip(region)
            stroke(body, solid(pal.highlight), 2f)
        }
    }
}
