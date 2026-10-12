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
 * The car from its right side (the charge-port side), nose to the right. Unit space 240 × 110, ground at y 102.
 * A door shows open when the door on either side at that position is open (the dialog lists which); open doors
 * swing towards you, the tailgate and bonnet lift. Lights, hazards, climate, lock, cable and alarm as in the top view.
 */
object SideScene : CarScene {
    override val w = 240f
    override val h = 110f

    private const val GROUND = 102f
    private val REAR_WHEEL = 64f to 88f
    private val FRONT_WHEEL = 180f to 88f
    private const val PORT_X = 36f
    private const val PORT_Y = 52f

    private val body = Shape().apply {
        moveTo(27f, 84f)
        lineTo(24f, 64f)
        cubicTo(23f, 50f, 25f, 36f, 30f, 28f)
        quadTo(34f, 21f, 46f, 20f)
        lineTo(144f, 18f)
        quadTo(152f, 18f, 157f, 23f)
        lineTo(178f, 45f)
        quadTo(182f, 48f, 190f, 49f)
        lineTo(212f, 53f)
        quadTo(220f, 55f, 221f, 62f)
        lineTo(222f, 78f)
        quadTo(222f, 84f, 216f, 84f)
        lineTo(200f, 84f)
        arcTo(160f, 66f, 200f, 106f, 0f, -180f) // front wheel arch
        lineTo(84f, 84f)
        arcTo(44f, 66f, 84f, 106f, 0f, -180f) // rear wheel arch
        close()
    }

    /** Side glass (daylight opening). */
    private val glass = Shape().apply {
        moveTo(36f, 46f)
        lineTo(38f, 31f)
        quadTo(40f, 24f, 50f, 23f)
        lineTo(142f, 21.5f)
        quadTo(149f, 21.5f, 153f, 26f)
        lineTo(171f, 46f)
        close()
    }
    private val frontDoor = Shape().apply {
        moveTo(110f, 22f); lineTo(152f, 22f); lineTo(171f, 46f); lineTo(160f, 84f); lineTo(110f, 84f); close()
    }
    private val rearDoor = Shape().apply {
        moveTo(64f, 22f); lineTo(110f, 22f); lineTo(110f, 84f); lineTo(86f, 84f); quadTo(84f, 72f, 72f, 68f); lineTo(64f, 64f); close()
    }
    private val tailgate = Shape().apply {
        moveTo(46f, 20f); quadTo(34f, 21f, 30f, 28f); cubicTo(25f, 36f, 23f, 50f, 24f, 64f); lineTo(36f, 64f); lineTo(38f, 24f); close()
    }
    private val hood = Shape().apply {
        moveTo(178f, 45f); quadTo(182f, 48f, 190f, 49f); lineTo(212f, 53f); quadTo(220f, 55f, 221f, 62f); lineTo(178f, 62f); close()
    }

    override fun partAt(x: Float, y: Float, car: CarState): CarPart? {
        val plugged = car.plug == PlugState.PLUGGED || car.isCharging
        return when {
            x < 0f || x > w || y < 4f || y > h -> null
            (x - PORT_X) * (x - PORT_X) + (y - PORT_Y) * (y - PORT_Y) < 13f * 13f -> CarPart.CHARGE
            plugged && x < 40f && y > 60f -> CarPart.CHARGE
            x > 176f && y < 86f -> CarPart.LIGHTS
            y < 46f && x in 34f..174f -> CarPart.CABIN
            x in 18f..226f && y > 12f -> CarPart.BODY
            else -> null
        }
    }

    override fun hints(car: CarState) = listOf(
        Hint(CarPart.LIGHTS, 206f, 58f, 214f, 36f),
        Hint(CarPart.CABIN, 104f, 34f, 104f, 8f),
        Hint(CarPart.BODY, 134f, 64f, 122f, 96f),
        Hint(CarPart.CHARGE, PORT_X, PORT_Y, 18f, 10f),
    )

    override fun draw(pen: Pen, car: CarState, pal: Palette, anim: Anim, highlight: CarPart?) = with(pen) { drawCar(car, pal, anim, highlight) }

    private fun Pen.drawCar(car: CarState, pal: Palette, anim: Anim, highlight: CarPart?) {
        val doors = car.doors ?: Doors()
        val warn = pal.warn
        val frontOpen = doors.driver || doors.frontPassenger
        val rearOpen = doors.rearLeft || doors.rearRight
        val plugged = car.plug == PlugState.PLUGGED || car.isCharging

        // Shadow on the ground.
        fill(Shape().oval(122f, GROUND, 104f, 4.5f), Paint.Radial(122f, GROUND, 104f, intArrayOf(withAlpha(BLACK, if (pal.dark) 0.45f else 0.28f), withAlpha(BLACK, 0f))))

        // Headlight beams ahead of the car.
        if (car.headlights == true) {
            fill(Shape().poly(219f, 57f, w, 50f, w, 76f, 219f, 63f), Paint.Linear(219f, 0f, w, 0f, intArrayOf(withAlpha(HEADLIGHT, 0.6f), withAlpha(HEADLIGHT, 0f))))
        }

        // The cable lies behind the rear wheel.
        if (plugged) {
            val cable = Shape().moveTo(PORT_X, PORT_Y + 4f).cubicTo(PORT_X - 2f, 74f, 30f, 92f, 20f, GROUND - 1f).lineTo(2f, GROUND - 1f)
            val cableColor = if (car.isCharging) pal.charge else pal.cable
            stroke(cable, solid(cableColor), 2.4f)
            if (car.isCharging) stroke(cable, solid(withAlpha(WHITE, 0.85f)), 1.1f, floatArrayOf(2.5f, 4.5f), -anim.phase * 7f)
        }

        // Body: lighter along the shoulder, darker towards the sills.
        fill(
            body,
            Paint.Linear(
                0f, 18f, 0f, 86f,
                intArrayOf(shade(pal.body, 1.03f), pal.body, shade(pal.body, 0.93f), shade(pal.body, 0.78f)),
                floatArrayOf(0f, 0.35f, 0.62f, 1f),
            ),
        )
        saved {
            clip(body)
            // Open panels: tint the gap.
            if (frontOpen) fill(frontDoor, withAlpha(warn, 0.32f))
            if (rearOpen) fill(rearDoor, withAlpha(warn, 0.32f))
            if (doors.boot) fill(tailgate, withAlpha(warn, 0.32f))
            if (doors.bonnet) fill(hood, withAlpha(warn, 0.32f))

            // Glass with the pillars over it.
            val glassTop = lerpColor(pal.glass, WHITE, if (pal.dark) 0.10f else 0.22f)
            fill(glass, Paint.Linear(0f, 22f, 0f, 46f, intArrayOf(glassTop, pal.glass)))
            fill(Shape().poly(70f, 23f, 80f, 23f, 64f, 46f, 56f, 46f), withAlpha(WHITE, 0.08f))
            fill(Shape().poly(58f, 22f, 64f, 22f, 64f, 46f, 58f, 46f), shade(pal.body, 0.9f)) // C-pillar
            fill(Shape().poly(106f, 21.5f, 111f, 21.5f, 111f, 46f, 106f, 46f), shade(pal.glass, 0.8f)) // B-pillar (black)

            // Climate shows through the glass; defrost lights the windscreen edge.
            if (car.climate.isRunning) {
                val a = 0.20f + 0.35f * anim.pulse
                when (car.climate.mode) {
                    ClimateMode.WINDSCREEN -> Unit
                    ClimateMode.HEAT -> fill(glass, withAlpha(HEAT, a))
                    else -> fill(glass, withAlpha(pal.charge, a))
                }
            }
            if (car.interiorLights == true) glow(100f, 34f, 26f, INTERIOR, 0.7f)

            // Dark plastic along the sills, the bumpers' lower edge and round the wheel arches (an SUV's cladding).
            val clad = if (pal.dark) 0xFF262B2F.toInt() else 0xFF3B4146.toInt()
            fill(Shape().rect(0f, 77f, w, h), clad)
            listOf(REAR_WHEEL, FRONT_WHEEL).forEach { (x, _) ->
                stroke(Shape().arcTo(x - 20f, 66f, x + 20f, 106f, 180f, 180f), solid(clad), 5f, roundCap = false)
            }

            // Panel lines.
            val seam = solid(pal.outline)
            stroke(Shape().moveTo(110f, 46f).lineTo(110f, 84f), seam, 0.9f)
            stroke(Shape().moveTo(159f, 46f).lineTo(160f, 66f), seam, 0.9f)
            stroke(Shape().moveTo(64f, 46f).lineTo(64f, 62f).quadTo(80f, 66f, 86f, 84f), seam, 0.9f)
            stroke(Shape().moveTo(36f, 64f).lineTo(24f, 64f), seam, 0.9f)
            stroke(Shape().moveTo(178f, 45f).lineTo(176f, 58f), withAlpha(pal.outline, 0.6f), 0.8f)
            // Shoulder crease and sill.
            stroke(Shape().moveTo(30f, 50f).lineTo(176f, 52f).lineTo(214f, 57f), withAlpha(WHITE, if (pal.dark) 0.12f else 0.55f), 1.1f)
            stroke(Shape().moveTo(86f, 78f).lineTo(160f, 78f), withAlpha(pal.outline, 0.6f), 1.2f)
            // Door handles.
            fill(Shape.rr(92f, 52f, 9f, 2.6f, 1.3f), shade(pal.body, 0.7f))
            fill(Shape.rr(140f, 52f, 9f, 2.6f, 1.3f), shade(pal.body, 0.7f))
            // Bumpers.
            fill(Shape().poly(206f, 68f, 222f, 68f, 222f, 80f, 204f, 80f), withAlpha(pal.glass, 0.35f))
            fill(Shape().poly(24f, 66f, 40f, 66f, 42f, 80f, 26f, 80f), withAlpha(pal.glass, 0.25f))

            // Lights.
            val lightsOn = car.headlights == true || car.parkingLights == true
            val front = when {
                car.headlights == true -> HEADLIGHT
                car.parkingLights == true -> AMBER
                else -> lerpColor(pal.glass, WHITE, 0.55f)
            }
            fill(Shape().poly(200f, 52f, 219f, 56.5f, 220.5f, 62f, 204f, 59f), front)
            fill(Shape().poly(24.5f, 33f, 32f, 29f, 32f, 42f, 24.5f, 45f), withAlpha(TAIL, if (lightsOn) 1f else 0.5f))
        }
        stroke(body, solid(pal.outline), 1f)
        // Defrost: the windscreen edge glows.
        if (car.climate.isRunning && car.climate.mode == ClimateMode.WINDSCREEN) {
            stroke(Shape().moveTo(157f, 23f).lineTo(178f, 45f), solid(withAlpha(DEFROST, 0.55f + 0.45f * anim.pulse)), 3.2f)
        }
        // Mirror.
        val mirror = Shape().poly(160f, 37f, 170f, 38f, 170f, 44f, 162f, 45f)
        fill(mirror, shade(pal.body, 0.92f))
        stroke(mirror, solid(pal.outline), 0.8f)

        // Wheels.
        listOf(REAR_WHEEL, FRONT_WHEEL).forEach { (x, y) ->
            fill(Shape().oval(x, y, 14f), pal.tyre)
            fill(Shape().oval(x, y, 9f), Paint.Radial(x - 2f, y - 2f, 10f, intArrayOf(0xFFC9CED2.toInt(), 0xFF7D858B.toInt())))
            for (i in 0 until 5) {
                val a = (i * 72f + 18f) * (Math.PI / 180).toFloat()
                line(x, y, x + 8f * kotlin.math.cos(a), y + 8f * kotlin.math.sin(a), shade(0xFF7D858B.toInt(), 0.7f), 1.6f)
            }
            fill(Shape().oval(x, y, 2.4f), 0xFF5F676D.toInt())
        }

        // Glow around lit lights and blinking hazards.
        if (lightsOn(car)) {
            glow(212f, 58f, 11f, if (car.headlights == true) HEADLIGHT else AMBER, 0.6f)
            glow(28f, 38f, 9f, TAIL, 0.5f)
        }
        if (car.hazardLights == true && anim.blinkOn) {
            listOf(214f to 70f, 27f to 40f).forEach { (x, y) ->
                glow(x, y, 9f, AMBER, 0.75f)
                fill(Shape().oval(x, y, 2.6f), AMBER)
            }
        }

        // Charge port (the flap) and the plug.
        if (plugged) {
            if (car.isCharging) glow(PORT_X, PORT_Y, 7f + 4f * anim.pulse, pal.charge, 0.7f)
            val plug = Shape.rr(PORT_X - 4f, PORT_Y - 3.5f, 8f, 8f, 1.8f)
            fill(plug, pal.tyre)
            stroke(plug, solid(if (car.isCharging) pal.charge else pal.cable), 0.8f)
        } else {
            stroke(Shape.rr(PORT_X - 4f, PORT_Y - 3.5f, 8f, 7f, 1.5f), solid(pal.outline), 0.8f)
        }

        if (car.climate.isRunning) climateBadge(80f, 34f, 7.5f, car.climate.mode, pal)
        car.locked?.let { lockBadge(130f, 34f, 8.5f, it, if (it) pal.good else warn) }

        // Open doors swing towards you from their front hinges; the tailgate and bonnet lift.
        fun leaf(hinge: Float, width: Float, top: Float) {
            val far = hinge - width * 0.30f
            val s = Shape().poly(hinge, top, hinge, 84f, far, 90f, far, top + 6f)
            fill(s, Paint.Linear(hinge, 0f, far, 0f, intArrayOf(withAlpha(warn, 0.95f), withAlpha(shade(warn, 0.75f), 0.95f))))
            // The door's window frame.
            stroke(Shape().poly(hinge - 1.5f, top + 3f, hinge - 1.5f, 45f, far + 1.5f, 50f, far + 1.5f, top + 8f), solid(withAlpha(WHITE, 0.35f)), 0.8f)
            stroke(s, solid(pal.outline), 0.8f)
        }
        if (rearOpen) leaf(110f, 46f, 24f)
        if (frontOpen) leaf(160f, 50f, 26f)
        if (doors.boot) {
            val lid = Shape().poly(46f, 19f, 8f, 3f, 6f, 8f, 42f, 23f)
            fill(lid, warn)
            stroke(lid, solid(pal.outline), 0.8f)
        }
        if (doors.bonnet) {
            val lid = Shape().poly(178f, 44f, 212f, 26f, 215f, 31f, 182f, 48f)
            fill(lid, warn)
            stroke(lid, solid(pal.outline), 0.8f)
        }

        if (car.theftAlarm == TheftAlarm.TRIGGERED) stroke(body, solid(withAlpha(pal.bad, 0.4f + 0.6f * anim.pulse)), 3f)

        highlight?.let { drawHighlight(it, pal) }
    }

    private fun lightsOn(car: CarState) = car.headlights == true || car.parkingLights == true

    private fun Pen.drawHighlight(part: CarPart, pal: Palette) {
        val fillC = withAlpha(pal.highlight, 0.30f)
        if (part == CarPart.CHARGE) {
            val c = Shape().oval(PORT_X, PORT_Y, 12f)
            fill(c, fillC)
            stroke(c, solid(pal.highlight), 1.6f)
            return
        }
        val region = when (part) {
            CarPart.LIGHTS -> Shape().rect(178f, 0f, w, h)
            CarPart.CABIN -> Shape().rect(36f, 0f, 176f, 46f)
            else -> Shape().rect(0f, 46f, 178f, h).rect(0f, 0f, 36f, 46f)
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
