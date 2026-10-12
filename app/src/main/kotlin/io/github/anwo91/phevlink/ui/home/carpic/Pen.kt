package io.github.anwo91.phevlink.ui.home.carpic

import io.github.anwo91.phevlink.core.model.CarState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/*
 * The car pictures are drawn through this small, platform-free drawing interface (paths, solid/gradient paints,
 * transforms, clipping), so the same drawing code runs in the app (Compose canvas) and in plain JVM tools that render
 * it to images for checking how it looks.
 */

/** The parts of the car picture you can tap; each opens a dialog with what it means and what you can do. */
enum class CarPart { LIGHTS, BODY, CABIN, CHARGE }

/** A path: move/line/quad/cubic/close. Round rects, ovals and arcs are built from cubic curves. */
class Shape {
    sealed interface Op
    data class Move(val x: Float, val y: Float) : Op
    data class Line(val x: Float, val y: Float) : Op
    data class Quad(val x1: Float, val y1: Float, val x: Float, val y: Float) : Op
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Op
    data object Close : Op

    val ops = ArrayList<Op>()
    private var cx = 0f
    private var cy = 0f
    private var started = false

    fun moveTo(x: Float, y: Float) = apply { ops += Move(x, y); cx = x; cy = y; started = true }
    fun lineTo(x: Float, y: Float) = apply { if (!started) moveTo(x, y) else { ops += Line(x, y); cx = x; cy = y } }
    fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = apply { ops += Quad(x1, y1, x, y); cx = x; cy = y }
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = apply { ops += Cubic(x1, y1, x2, y2, x, y); cx = x; cy = y }
    fun close() = apply { ops += Close; started = false }

    /** Polygon through the points (x0, y0, x1, y1, …), closed. */
    fun poly(vararg xy: Float) = apply {
        moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }
        close()
    }

    /**
     * An elliptic arc of the ellipse in ([l], [t], [r], [b]) from [startDeg] sweeping [sweepDeg] (0° = 3 o'clock,
     * positive = clockwise on screen, as on Android). Connects with a line from the current point.
     */
    fun arcTo(l: Float, t: Float, r: Float, b: Float, startDeg: Float, sweepDeg: Float) = apply {
        val rx = (r - l) / 2f
        val ry = (b - t) / 2f
        val ox = l + rx
        val oy = t + ry
        val n = ceil(abs(sweepDeg) / 90f).toInt().coerceAtLeast(1)
        val step = sweepDeg / n * (PI / 180).toFloat()
        var a = startDeg * (PI / 180).toFloat()
        val sx = ox + rx * cos(a)
        val sy = oy + ry * sin(a)
        if (started) lineTo(sx, sy) else moveTo(sx, sy)
        val k = 4f / 3f * tan(step / 4f)
        repeat(n) {
            val a2 = a + step
            val x1 = ox + rx * (cos(a) - k * sin(a))
            val y1 = oy + ry * (sin(a) + k * cos(a))
            val x2 = ox + rx * (cos(a2) + k * sin(a2))
            val y2 = oy + ry * (sin(a2) - k * cos(a2))
            cubicTo(x1, y1, x2, y2, ox + rx * cos(a2), oy + ry * sin(a2))
            a = a2
        }
    }

    fun oval(cx: Float, cy: Float, rx: Float, ry: Float = rx) = apply {
        arcTo(cx - rx, cy - ry, cx + rx, cy + ry, 0f, 360f)
        close()
    }

    /** Rectangle with rounded corners (radii per corner as x/y pairs: top-left, top-right, bottom-right, bottom-left). */
    fun roundRect(
        l: Float, t: Float, r: Float, b: Float,
        tlx: Float, tly: Float = tlx, trx: Float = tlx, try_: Float = tly, brx: Float = tlx, bry: Float = tly, blx: Float = brx, bly: Float = bry,
    ) = apply {
        moveTo(l + tlx, t)
        lineTo(r - trx, t)
        if (trx > 0 || try_ > 0) arcTo(r - 2 * trx, t, r, t + 2 * try_, -90f, 90f)
        lineTo(r, b - bry)
        if (brx > 0 || bry > 0) arcTo(r - 2 * brx, b - 2 * bry, r, b, 0f, 90f)
        lineTo(l + blx, b)
        if (blx > 0 || bly > 0) arcTo(l, b - 2 * bly, l + 2 * blx, b, 90f, 90f)
        lineTo(l, t + tly)
        if (tlx > 0 || tly > 0) arcTo(l, t, l + 2 * tlx, t + 2 * tly, 180f, 90f)
        close()
    }

    fun rect(l: Float, t: Float, r: Float, b: Float) = poly(l, t, r, t, r, b, l, b)

    companion object {
        fun rr(l: Float, t: Float, w: Float, h: Float, r: Float) = Shape().roundRect(l, t, l + w, t + h, r)
    }
}

/** What fills or strokes a shape. Colours are ARGB ints. */
sealed interface Paint {
    data class Solid(val argb: Int) : Paint
    data class Linear(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val colors: IntArray, val stops: FloatArray? = null) : Paint
    data class Radial(val cx: Float, val cy: Float, val r: Float, val colors: IntArray, val stops: FloatArray? = null) : Paint
}

fun solid(argb: Int): Paint = Paint.Solid(argb)

interface Pen {
    fun save()
    fun restore()
    fun translate(dx: Float, dy: Float)
    fun scale(sx: Float, sy: Float)
    /** Rotates by [deg] clockwise around ([px], [py]). */
    fun rotate(deg: Float, px: Float, py: Float)
    fun clip(shape: Shape)
    fun fill(shape: Shape, paint: Paint)
    fun stroke(shape: Shape, paint: Paint, width: Float, dash: FloatArray? = null, dashPhase: Float = 0f, roundCap: Boolean = true)
}

inline fun Pen.saved(block: Pen.() -> Unit) {
    save()
    try { block() } finally { restore() }
}

fun Pen.fill(shape: Shape, argb: Int) = fill(shape, solid(argb))
fun Pen.stroke(shape: Shape, argb: Int, width: Float) = stroke(shape, solid(argb), width)
fun Pen.line(x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, width: Float) =
    stroke(Shape().moveTo(x0, y0).lineTo(x1, y1), solid(argb), width)

/** A soft round glow (light, hazard blink, charge port). */
fun Pen.glow(cx: Float, cy: Float, r: Float, argb: Int, alpha: Float) =
    fill(Shape().oval(cx, cy, r), Paint.Radial(cx, cy, r, intArrayOf(withAlpha(argb, alpha), withAlpha(argb, 0f))))

// ----------------------------------------------------------------------------------------------- colours

fun withAlpha(argb: Int, a: Float): Int = (((argb ushr 24) * a.coerceIn(0f, 1f)).toInt() shl 24) or (argb and 0xFFFFFF)

fun lerpColor(a: Int, b: Int, t: Float): Int {
    val f = t.coerceIn(0f, 1f)
    fun ch(s: Int) = ((((a ushr s) and 0xFF) * (1 - f) + ((b ushr s) and 0xFF) * f) + 0.5f).toInt() shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}

/** [argb] made darker (f < 1) or lighter (f > 1), alpha kept. */
fun shade(argb: Int, f: Float): Int =
    if (f <= 1f) lerpColor(argb, argb and 0xFF000000.toInt(), 1f - f)
    else lerpColor(argb, argb or 0xFFFFFF, (f - 1f).coerceAtMost(1f))

fun luminance(argb: Int): Float {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/** The colours a car picture uses; built from the app theme (light or dark). */
data class Palette(
    val body: Int,
    val outline: Int,
    val glass: Int,
    val tyre: Int,
    val cable: Int,
    val good: Int,
    val warn: Int,
    val bad: Int,
    val charge: Int,
    val highlight: Int,
    val dark: Boolean,
) {
    companion object {
        val HEADLIGHT = 0xFFFFF3B0.toInt()
        val AMBER = 0xFFFFB300.toInt()
        val TAIL = 0xFFE53935.toInt()
        val HEAT = 0xFFFF7043.toInt()
        val DEFROST = 0xFF26C6DA.toInt()
        val INTERIOR = 0xFFFFE082.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val BLACK = 0xFF000000.toInt()

        /** The default light-theme palette (also for previews and image checks). */
        fun light() = Palette(
            body = 0xFFF4F6F7.toInt(), outline = 0xBF72787E.toInt(), glass = 0xFF3E4A52.toInt(), tyre = 0xFF2F363A.toInt(),
            cable = 0xFF2F363A.toInt(), good = 0xFF1E8E3E.toInt(), warn = 0xFFC4581C.toInt(), bad = 0xFFB3261E.toInt(),
            charge = 0xFF1E88E5.toInt(), highlight = 0xFF00677F.toInt(), dark = false,
        )

        fun dark() = Palette(
            body = 0xFF353A3E.toInt(), outline = 0xBF8C9399.toInt(), glass = 0xFF10171B.toInt(), tyre = 0xFF0A0D0F.toInt(),
            cable = 0xFFB0B8BE.toInt(), good = 0xFF6DD58C.toInt(), warn = 0xFFFFB68B.toInt(), bad = 0xFFFFB4AB.toInt(),
            charge = 0xFF64B5F6.toInt(), highlight = 0xFF5DD5FC.toInt(), dark = true,
        )
    }
}

/** Animation state: [pulse] 0→1→0 smoothly, [blinkOn] half the time, [phase] 0..1 sawtooth. */
data class Anim(val pulse: Float = 0f, val blinkOn: Boolean = true, val phase: Float = 0f)

/** Where a part's tap hint goes: an icon at [target], or a label at [label] with a line to [target]. */
data class Hint(val part: CarPart, val tx: Float, val ty: Float, val lx: Float = tx, val ly: Float = ty, val visible: Boolean = true)

/** One way of drawing the car, in its own unit space of [w] × [h] (scaled to fit the screen). */
interface CarScene {
    val w: Float
    val h: Float
    /** The part at a tap in unit coordinates, or null for empty space. */
    fun partAt(x: Float, y: Float, car: CarState): CarPart?
    fun hints(car: CarState): List<Hint>
    /** Draws the car in unit coordinates; [highlight] glows (touched part, or the hint tour). */
    fun draw(pen: Pen, car: CarState, pal: Palette, anim: Anim, highlight: CarPart?)
}

// --------------------------------------------------------------------------------- shared symbols

/** A round lock badge (closed or open shackle) centred on ([cx], [cy]) with radius [r]. */
fun Pen.lockBadge(cx: Float, cy: Float, r: Float, locked: Boolean, color: Int) {
    val k = r / 8.5f
    fill(Shape().oval(cx, cy, r), color)
    fill(Shape.rr(cx - 4f * k, cy - 1f * k, 8f * k, 6f * k, 1.2f * k), Palette.WHITE)
    val lift = if (locked) 0f else 2.2f * k
    val top = cy - 6.6f * k - lift
    val shackle = Shape().arcTo(cx - 2.8f * k, top, cx + 2.8f * k, top + 6f * k, 180f, 180f)
    val mid = top + 3f * k
    shackle.moveTo(cx - 2.8f * k, mid).lineTo(cx - 2.8f * k, cy - 1f * k)
    shackle.moveTo(cx + 2.8f * k, mid).lineTo(cx + 2.8f * k, if (locked) cy - 1f * k else mid + 0.8f * k)
    stroke(shackle, solid(Palette.WHITE), 1.4f * k)
}

/** Heat waves, a snowflake or defrost waves centred on ([cx], [cy]), about 2·[r] tall, on a soft disc. */
fun Pen.climateBadge(cx: Float, cy: Float, r: Float, mode: io.github.anwo91.phevlink.core.model.ClimateMode?, pal: Palette) {
    val k = r / 8.5f
    val color = when (mode) {
        io.github.anwo91.phevlink.core.model.ClimateMode.HEAT -> Palette.HEAT
        io.github.anwo91.phevlink.core.model.ClimateMode.WINDSCREEN -> Palette.DEFROST
        else -> pal.charge
    }
    fill(Shape().oval(cx, cy, r), color)
    val w = 1.5f * k
    when (mode) {
        io.github.anwo91.phevlink.core.model.ClimateMode.COOL, null -> listOf(90.0, 30.0, 150.0).forEach { deg ->
            val rr = 5.2f * k
            val dx = (rr * cos(deg * PI / 180)).toFloat()
            val dy = (rr * sin(deg * PI / 180)).toFloat()
            line(cx - dx, cy - dy, cx + dx, cy + dy, Palette.WHITE, w)
        }
        else -> listOf(-3.2f, 0f, 3.2f).forEach { ox ->
            val x = cx + ox * k
            val wave = Shape().moveTo(x, cy + 5f * k)
                .quadTo(x - 2f * k, cy + 2.5f * k, x, cy)
                .quadTo(x + 2f * k, cy - 2.5f * k, x, cy - 5f * k)
            stroke(wave, solid(Palette.WHITE), w)
        }
    }
}
