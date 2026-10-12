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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small 3D model of the car (an SUV of the Outlander's proportions, in metres: x to the right side, y up, z to the
 * nose) drawn with flat shading and the painter's algorithm, turned by [yaw] (drag sideways) and tilted by [pitch].
 * Open doors, tailgate and bonnet swing open on their hinges; lights, climate, lock, charge cable and alarm show as in
 * the flat pictures. Unit space 300 × 180. Taps are matched against the faces drawn last.
 */
class Car3DScene : CarScene {
    override val w = 300f
    override val h = 180f

    /** Degrees; 35 shows the front and the right (charge-port) side, 90 the right side with the nose to the right. */
    var yaw = 35f
    /** Degrees the camera looks down; 16 in the home screen, more in the large view. */
    var pitch = 16f

    private class V(val x: Float, val y: Float, val z: Float)

    private enum class Kind { BODY, HOOD, GLASS, WINDSCREEN, ROOF, TYRE, RIM, ARCH, HEADLAMP, TAILLAMP, TURN, GRILLE, PLATE, PILLAR, RAIL, PORT, HANDLE, OPENING, LEAF }

    private class Face(
        var pts: List<V>,
        val kind: Kind,
        val part: CarPart,
        val twoSided: Boolean = false,
        val decals: MutableList<Face> = ArrayList(),
        val tag: String = "",
    )

    private class Drawn(val xs: FloatArray, val ys: FloatArray, val part: CarPart)

    @Volatile private var lastDrawn: List<Drawn> = emptyList()
    @Volatile private var lastPort: FloatArray? = null
    @Volatile private var lastCable: List<FloatArray> = emptyList()

    // ------------------------------------------------------------------------------------------ projection

    private val cam = 9f
    /** Zoomed out a little as the camera looks further down, so the car still fits. */
    private val focal get() = 400f * (1f - 0.4f * ((pitch - 16f) / 54f).coerceIn(0f, 1f))
    private val cy = 0.83f

    private inner class View {
        val cyaw = cos(yaw * PI / 180).toFloat()
        val syaw = sin(yaw * PI / 180).toFloat()
        val cp = cos(pitch * PI / 180).toFloat()
        val sp = sin(pitch * PI / 180).toFloat()

        /** View-space coordinates (camera at z = [cam] looking towards −z). */
        fun xf(v: V): FloatArray {
            // The model is x = the car's right; seen from the front that is the viewer's left, so mirror x.
            val x = -v.x
            val y = v.y - cy
            val z = v.z
            val x1 = x * cyaw + z * syaw
            val z1 = -x * syaw + z * cyaw
            return floatArrayOf(x1, y * cp - z1 * sp, y * sp + z1 * cp)
        }

        fun px(p: FloatArray): Float = w / 2f + p[0] * focal / (cam - p[2])
        fun py(p: FloatArray): Float = h * 0.54f - p[1] * focal / (cam - p[2])
        fun project(v: V): FloatArray = xf(v).let { floatArrayOf(px(it), py(it), it[2]) }

        /** Whether a model-space direction (outward normal) faces the camera at model point [at]. */
        fun facing(nx: Float, ny: Float, nz: Float, at: V): Boolean {
            val n = xf(V(nx, ny + cy, nz)) // direction: rotate without translation
            val p = xf(at)
            return n[0] * (0f - p[0]) + n[1] * (0f - p[1]) + n[2] * (cam - p[2]) > 0f
        }
    }

    // ------------------------------------------------------------------------------------------ the model

    private class Station(val z: Float, val a: Float, val b: Float, val c: Float, val y0: Float, val y1: Float, val y2: Float)

    private val stations = listOf(
        Station(-2.33f, .72f, .82f, .72f, .42f, .62f, .98f),
        Station(-2.20f, .80f, .89f, .82f, .30f, .62f, 1.00f),
        Station(-1.80f, .82f, .90f, .83f, .30f, .62f, 1.00f),
        Station(-1.62f, .82f, .90f, .83f, .58f, .66f, 1.00f),
        Station(-1.38f, .82f, .90f, .83f, .75f, .81f, 1.00f),
        Station(-1.14f, .82f, .90f, .83f, .58f, .66f, 1.00f),
        Station(-0.96f, .82f, .90f, .83f, .30f, .62f, 1.00f),
        Station(-0.05f, .82f, .90f, .83f, .30f, .62f, 1.00f),
        Station(0.96f, .82f, .90f, .83f, .30f, .62f, 1.00f),
        Station(1.14f, .82f, .90f, .83f, .58f, .66f, .99f),
        Station(1.38f, .81f, .90f, .83f, .75f, .81f, .98f),
        Station(1.62f, .80f, .89f, .82f, .58f, .66f, .955f),
        Station(1.80f, .79f, .89f, .81f, .30f, .62f, .935f),
        Station(2.10f, .76f, .87f, .77f, .32f, .60f, .88f),
        Station(2.33f, .66f, .77f, .64f, .40f, .56f, .76f),
    )

    private fun section(s: Station): List<V> = listOf(
        V(-s.a, s.y0, s.z), V(s.a, s.y0, s.z), V(s.b, s.y1, s.z), V(s.c, s.y2, s.z), V(-s.c, s.y2, s.z), V(-s.b, s.y1, s.z),
    )

    /** x of the upper side panel (between shoulder line and belt) at height [y], for decals on it. */
    private fun upperSideX(y: Float) = 0.900f - 0.072f * (y - 0.62f) / 0.38f + 0.006f

    private fun quad(a: V, b: V, c: V, d: V) = listOf(a, b, c, d)

    /** Points listed so that their normal points away from [ref]. */
    private fun outward(pts: List<V>, ref: V): List<V> {
        val n = normal(pts)
        val c = centroid(pts)
        return if (n[0] * (c.x - ref.x) + n[1] * (c.y - ref.y) + n[2] * (c.z - ref.z) < 0f) pts.reversed() else pts
    }

    private fun normal(p: List<V>): FloatArray {
        val ux = p[1].x - p[0].x; val uy = p[1].y - p[0].y; val uz = p[1].z - p[0].z
        val vx = p[2].x - p[0].x; val vy = p[2].y - p[0].y; val vz = p[2].z - p[0].z
        return floatArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
    }

    private fun centroid(p: List<V>) = V(p.map { it.x }.average().toFloat(), p.map { it.y }.average().toFloat(), p.map { it.z }.average().toFloat())

    private fun rotY(p: V, hx: Float, hz: Float, deg: Float): V {
        val a = deg * PI.toFloat() / 180f
        val dx = p.x - hx; val dz = p.z - hz
        return V(hx + dx * cos(a) + dz * sin(a), p.y, hz - dx * sin(a) + dz * cos(a))
    }

    private fun rotX(p: V, hy: Float, hz: Float, deg: Float): V {
        val a = deg * PI.toFloat() / 180f
        val dy = p.y - hy; val dz = p.z - hz
        return V(p.x, hy + dy * cos(a) - dz * sin(a), hz + dy * sin(a) + dz * cos(a))
    }

    private val ROOF = 1.70f
    private val portZ = -1.98f
    private val portY = 0.85f
    private val port = V(upperSideX(portY), portY, portZ)

    /** Builds the faces for [doors]; wheels separately (drawn first, under everything). */
    private fun build(doors: Doors): Pair<List<Face>, List<Face>> {
        val faces = ArrayList<Face>()
        val secs = stations.map(::section)
        val frontDoor = -0.05f to 0.96f
        val rearDoor = -0.96f to -0.05f
        for (i in 0 until stations.size - 1) {
            val s0 = secs[i]; val s1 = secs[i + 1]
            val z0 = stations[i].z; val z1 = stations[i + 1].z
            val zc = (z0 + z1) / 2f
            for (e in 1..5) {
                val k = (e + 1) % 6
                // Under the cabin the body top is hidden; skip it.
                if (e == 3 && z0 >= -2.12f && z1 <= 0.96f) continue
                val pts = outward(quad(s0[e], s0[k], s1[k], s1[e]), V(0f, 0.62f, zc))
                val right = e == 1 || e == 2
                val side = e != 3
                val doorKey = when {
                    side && z0 == frontDoor.first && z1 == frontDoor.second -> if (right) "fr" else "fl"
                    side && z0 == rearDoor.first && z1 == rearDoor.second -> if (right) "rr" else "rl"
                    else -> ""
                }
                val hood = e == 3 && z0 >= 0.96f
                val f = Face(pts, if (hood) Kind.HOOD else Kind.BODY, if (hood) CarPart.LIGHTS else CarPart.BODY, tag = doorKey.ifEmpty { if (hood) "hood" else "" })
                // Door handles and the charge-port flap sit on the upper side panels.
                if (e == 2 || e == 4) {
                    val sx = if (e == 2) 1f else -1f
                    if (doorKey.isNotEmpty()) {
                        val hz = if (doorKey[0] == 'f') 0.30f else -0.66f
                        f.decals += Face(quad(V(sx * upperSideX(0.86f), 0.86f, hz), V(sx * upperSideX(0.86f), 0.86f, hz + 0.20f), V(sx * upperSideX(0.90f), 0.90f, hz + 0.20f), V(sx * upperSideX(0.90f), 0.90f, hz)), Kind.HANDLE, CarPart.BODY)
                    }
                    if (e == 2 && z0 == -2.20f) {
                        f.decals += Face(quad(V(upperSideX(0.78f), 0.78f, portZ - 0.10f), V(upperSideX(0.78f), 0.78f, portZ + 0.10f), V(upperSideX(0.92f), 0.92f, portZ + 0.10f), V(upperSideX(0.92f), 0.92f, portZ - 0.10f)), Kind.PORT, CarPart.CHARGE)
                    }
                }
                faces += f
            }
        }
        // Front: grille, headlights, intake, turn lights.
        val front = Face(outward(secs.last(), V(0f, 0.62f, 0f)), Kind.BODY, CarPart.LIGHTS, tag = "front")
        val fz = 2.336f
        front.decals += Face(quad(V(-0.26f, 0.58f, fz), V(0.26f, 0.58f, fz), V(0.24f, 0.73f, fz), V(-0.24f, 0.73f, fz)), Kind.GRILLE, CarPart.LIGHTS)
        front.decals += Face(quad(V(-0.40f, 0.45f, fz), V(0.40f, 0.45f, fz), V(0.44f, 0.55f, fz), V(-0.44f, 0.55f, fz)), Kind.GRILLE, CarPart.LIGHTS)
        for (sx in listOf(-1f, 1f)) {
            front.decals += Face(quad(V(sx * 0.68f, 0.63f, fz), V(sx * 0.29f, 0.66f, fz), V(sx * 0.29f, 0.72f, fz), V(sx * 0.62f, 0.72f, fz)), Kind.HEADLAMP, CarPart.LIGHTS, tag = "head")
            front.decals += Face(quad(V(sx * 0.66f, 0.47f, fz), V(sx * 0.50f, 0.47f, fz), V(sx * 0.50f, 0.53f, fz), V(sx * 0.70f, 0.53f, fz)), Kind.TURN, CarPart.LIGHTS)
        }
        faces += front
        // Rear: tail lights and plate.
        val rear = Face(outward(secs.first(), V(0f, 0.62f, 0f)), Kind.BODY, CarPart.BODY, tag = "rear")
        val rz = -2.336f
        for (sx in listOf(-1f, 1f)) {
            rear.decals += Face(quad(V(sx * 0.79f, 0.66f, rz), V(sx * 0.50f, 0.69f, rz), V(sx * 0.50f, 0.79f, rz), V(sx * 0.74f, 0.86f, rz)), Kind.TAILLAMP, CarPart.BODY)
            rear.decals += Face(quad(V(sx * 0.78f, 0.62f, rz), V(sx * 0.62f, 0.62f, rz), V(sx * 0.62f, 0.655f, rz), V(sx * 0.79f, 0.655f, rz)), Kind.TURN, CarPart.BODY)
        }
        rear.decals += Face(quad(V(-0.24f, 0.48f, rz), V(0.24f, 0.48f, rz), V(0.24f, 0.60f, rz), V(-0.24f, 0.60f, rz)), Kind.PLATE, CarPart.BODY)
        faces += rear

        // The cabin: a frustum of glass and roof on the belt line, with pillars and roof rails.
        val ref = V(0f, 1.0f, -0.95f)
        val b0 = V(-0.83f, 1.0f, -2.12f); val b1 = V(0.83f, 1.0f, -2.12f); val b2 = V(0.83f, 1.0f, 1.05f); val b3 = V(-0.83f, 1.0f, 1.05f)
        val t0 = V(-0.70f, ROOF, -1.95f); val t1 = V(0.70f, ROOF, -1.95f); val t2 = V(0.70f, ROOF, 0.25f); val t3 = V(-0.70f, ROOF, 0.25f)
        val roof = Face(outward(quad(t0, t1, t2, t3), ref), Kind.ROOF, CarPart.CABIN)
        for (sx in listOf(-0.56f, 0.56f)) {
            roof.decals += Face(quad(V(sx - 0.03f, ROOF + 0.005f, -1.82f), V(sx + 0.03f, ROOF + 0.005f, -1.82f), V(sx + 0.03f, ROOF + 0.005f, 0.10f), V(sx - 0.03f, ROOF + 0.005f, 0.10f)), Kind.RAIL, CarPart.CABIN)
        }
        faces += roof
        faces += Face(outward(quad(b3, b2, t2, t3), ref), Kind.WINDSCREEN, CarPart.CABIN, tag = "windscreen")
        faces += Face(outward(quad(b0, b1, t1, t0), ref), Kind.GLASS, CarPart.CABIN, tag = "rearwindow")
        for (sx in listOf(-1f, 1f)) {
            val sideFace = Face(outward(if (sx > 0) quad(b1, b2, t2, t1) else quad(b0, b3, t3, t0), ref), Kind.PILLAR, CarPart.CABIN)
            fun on(z: Float, y: Float) = V(sx * (0.83f - 0.13f * (y - 1f) / (ROOF - 1f) + 0.004f), y, z)
            sideFace.decals += Face(quad(on(-2.02f, 1.05f), on(-1.08f, 1.05f), on(-1.16f, 1.64f), on(-1.88f, 1.64f)), Kind.GLASS, CarPart.CABIN)
            sideFace.decals += Face(quad(on(-0.98f, 1.05f), on(-0.10f, 1.05f), on(-0.18f, 1.64f), on(-1.06f, 1.64f)), Kind.GLASS, CarPart.CABIN)
            sideFace.decals += Face(quad(on(0.03f, 1.05f), on(0.90f, 1.05f), on(0.31f, 1.62f), on(-0.06f, 1.64f)), Kind.GLASS, CarPart.CABIN)
            faces += sideFace
        }

        // Mirrors.
        for (sx in listOf(-1f, 1f)) {
            val x0 = sx * 0.86f; val x1 = sx * 1.00f
            val y0 = 1.00f; val y1 = 1.10f
            val z0 = 0.82f; val z1 = 0.96f
            val c = V((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
            val box = listOf(
                quad(V(x0, y0, z0), V(x1, y0, z0), V(x1, y1, z0), V(x0, y1, z0)),
                quad(V(x0, y0, z1), V(x1, y0, z1), V(x1, y1, z1), V(x0, y1, z1)),
                quad(V(x1, y0, z0), V(x1, y0, z1), V(x1, y1, z1), V(x1, y1, z0)),
                quad(V(x0, y1, z0), V(x1, y1, z0), V(x1, y1, z1), V(x0, y1, z1)),
                quad(V(x0, y0, z0), V(x1, y0, z0), V(x1, y0, z1), V(x0, y0, z1)),
            )
            box.forEach { faces += Face(outward(it, c), Kind.BODY, CarPart.BODY) }
        }

        // Open doors swing out on their front hinges (as leaves); the opening shows dark.
        val open = mapOf("fl" to doors.driver, "fr" to doors.frontPassenger, "rl" to doors.rearLeft, "rr" to doors.rearRight)
        val extra = ArrayList<Face>()
        for (f in faces) {
            val isOpen = open[f.tag] == true || (f.tag == "hood" && doors.bonnet) || (f.tag == "rearwindow" && doors.boot)
            if (!isOpen) continue
            val leaf = when {
                f.tag == "hood" -> f.pts.map { rotX(it, 1.0f, 0.96f, -38f) }
                f.tag == "rearwindow" -> f.pts.map { rotX(it, ROOF, -1.95f, 70f) }
                else -> {
                    val right = f.tag[1] == 'r'
                    val hingeZ = if (f.tag[0] == 'f') 0.96f else -0.05f
                    f.pts.map { rotY(it, if (right) 0.90f else -0.90f, hingeZ, if (right) -50f else 50f) }
                }
            }
            extra += Face(leaf, Kind.LEAF, f.part, twoSided = true, tag = if (f.tag.length == 2) "door" else "")
        }
        if (doors.boot) {
            // The tailgate's lower panel lifts with its glass.
            val panel = quad(V(-0.72f, 0.66f, -2.33f), V(0.72f, 0.66f, -2.33f), V(0.80f, 1.0f, -2.14f), V(-0.80f, 1.0f, -2.14f))
            extra += Face(panel.map { rotX(it, ROOF, -1.95f, 70f) }, Kind.LEAF, CarPart.BODY, twoSided = true)
        }
        faces.forEach { f ->
            if (open[f.tag] == true || (f.tag == "hood" && doors.bonnet) || (f.tag == "rearwindow" && doors.boot)) {
                f.decals.clear()
                faces[faces.indexOf(f)] = Face(f.pts, Kind.OPENING, f.part, decals = f.decals, tag = f.tag)
            }
        }
        faces += extra

        // Wheels: dark arch discs, tyres and rims.
        val wheels = ArrayList<Face>()
        for (sx in listOf(-1f, 1f)) for (wz in listOf(-1.38f, 1.38f)) {
            val n = 14
            val r = 0.36f; val yc = 0.36f
            val xo = sx * 0.92f; val xi = sx * 0.68f
            val c = V(sx * 0.80f, yc, wz)
            wheels += Face((0 until 16).map { i -> val a = i * 2 * PI.toFloat() / 16; V(sx * 0.84f, 0.42f + 0.42f * sin(a), wz + 0.42f * cos(a)) }, Kind.ARCH, CarPart.BODY, twoSided = true)
            val ring = { x: Float, rr: Float -> (0 until n).map { i -> val a = i * 2 * PI.toFloat() / n; V(x, yc + rr * sin(a), wz + rr * cos(a)) } }
            val outer = ring(xo, r); val inner = ring(xi, r)
            for (i in 0 until n) {
                val j = (i + 1) % n
                wheels += Face(outward(quad(outer[i], outer[j], inner[j], inner[i]), c), Kind.TYRE, CarPart.BODY)
            }
            val cap = Face(outward(outer, c), Kind.TYRE, CarPart.BODY)
            cap.decals += Face(ring(xo + sx * 0.004f, 0.22f), Kind.RIM, CarPart.BODY)
            wheels += cap
        }
        return faces to wheels
    }

    // ------------------------------------------------------------------------------------------ drawing

    private class Out(val face: Face, val xs: FloatArray, val ys: FloatArray, val depth: Float, val shade: Float, val visible: Boolean)

    override fun draw(pen: Pen, car: CarState, pal: Palette, anim: Anim, highlight: CarPart?) {
        val v = View()
        val doors = car.doors ?: Doors()
        val (faces, wheels) = build(doors)
        val lightDir = run { val l = floatArrayOf(-0.35f, 0.80f, 0.50f); val n = sqrt(l.sumOf { (it * it).toDouble() }).toFloat(); l.map { it / n } }

        fun out(f: Face): Out {
            val p = f.pts.map(v::xf)
            val xs = FloatArray(p.size) { v.px(p[it]) }
            val ys = FloatArray(p.size) { v.py(p[it]) }
            val ux = p[1][0] - p[0][0]; val uy = p[1][1] - p[0][1]; val uz = p[1][2] - p[0][2]
            val wx = p[2][0] - p[0][0]; val wy = p[2][1] - p[0][1]; val wz = p[2][2] - p[0][2]
            // Negated: mirroring x (see [View.xf]) flips the winding.
            var nx = -(uy * wz - uz * wy); var ny = -(uz * wx - ux * wz); var nz = -(ux * wy - uy * wx)
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            nx /= len; ny /= len; nz /= len
            val cxv = p.map { it[0] }.average().toFloat(); val cyv = p.map { it[1] }.average().toFloat(); val czv = p.map { it[2] }.average().toFloat()
            var facing = nx * (0f - cxv) + ny * (0f - cyv) + nz * (cam - czv) > 0f
            if (f.twoSided && !facing) { nx = -nx; ny = -ny; nz = -nz; facing = true }
            val lam = max(0f, nx * lightDir[0] + ny * lightDir[1] + nz * lightDir[2])
            return Out(f, xs, ys, czv, lam, facing)
        }

        // Ground shadow.
        for ((k, a) in listOf(1.18f to 0.07f, 1.06f to 0.09f, 0.94f to 0.12f, 0.80f to 0.14f)) {
            val ring = (0 until 28).map { i ->
                val t = i * 2 * PI.toFloat() / 28
                // A rounded rectangle-ish footprint: superellipse.
                val cx = cos(t); val sz = sin(t)
                val ex = Math.signum(cx) * Math.pow(kotlin.math.abs(cx).toDouble(), 0.5).toFloat()
                val ez = Math.signum(sz) * Math.pow(kotlin.math.abs(sz).toDouble(), 0.5).toFloat()
                v.project(V(ex * 1.0f * k, 0f, ez * 2.4f * (0.5f + k / 2f)))
            }
            pen.fill(Shape().poly(*ring.flatMap { listOf(it[0], it[1]) }.toFloatArray()), withAlpha(BLACK, if (pal.dark) a * 1.6f else a))
        }

        // Headlight beams on the ground ahead.
        if (car.headlights == true) {
            for (sx in listOf(-1f, 1f)) {
                // A cone of light from the lamp down to the road a few metres ahead.
                val q = listOf(V(sx * 0.62f, 0.68f, 2.36f), V(sx * 0.30f, 0.68f, 2.36f), V(sx * 0.05f, 0f, 5.4f), V(sx * 1.45f, 0f, 5.4f)).map(v::project)
                val a = v.project(V(sx * 0.45f, 0.68f, 2.36f)); val b = v.project(V(sx * 0.75f, 0f, 5.4f))
                pen.fill(Shape().poly(*q.flatMap { listOf(it[0], it[1]) }.toFloatArray()), Paint.Linear(a[0], a[1], b[0], b[1], intArrayOf(withAlpha(HEADLIGHT, 0.55f), withAlpha(HEADLIGHT, 0f))))
            }
        }

        val rightVisible = v.facing(1f, 0f, 0f, V(0.9f, 0.7f, -1.9f))
        val plugged = car.plug == PlugState.PLUGGED || car.isCharging
        val cable = if (plugged) listOf(
            port, V(1.05f, 0.80f, portZ - 0.04f), V(1.22f, 0.45f, portZ - 0.12f), V(1.30f, 0.03f, portZ - 0.22f), V(1.55f, 0f, portZ - 0.6f), V(1.9f, 0f, portZ - 0.9f),
        ).map(v::project) else emptyList()
        lastCable = cable
        if (plugged && !rightVisible) drawCable(pen, cable, car, pal, anim)

        val drawn = ArrayList<Drawn>()
        // Wheels first: the body covers what the arches hide.
        val wheelOut = wheels.map(::out).filter { it.visible }.sortedBy { it.depth }
        for (o in wheelOut) drawFace(pen, o, car, pal, anim, highlight, drawn, v)
        val bodyOut = faces.map(::out).filter { it.visible }.sortedBy { it.depth }
        for (o in bodyOut) drawFace(pen, o, car, pal, anim, highlight, drawn, v)
        lastDrawn = drawn

        if (plugged && rightVisible) drawCable(pen, cable, car, pal, anim)
        val portP = v.project(port)
        lastPort = if (rightVisible) portP else null
        if (rightVisible && car.isCharging) pen.glow(portP[0], portP[1], 7f + 4f * anim.pulse, pal.charge, 0.7f)

        // Light glows where the lights face us.
        val frontVisible = v.facing(0f, 0f, 1f, V(0f, 0.6f, 2.33f))
        val rearVisible = v.facing(0f, 0f, -1f, V(0f, 0.6f, -2.33f))
        val lit = car.headlights == true || car.parkingLights == true
        if (lit && frontVisible) for (sx in listOf(-1f, 1f)) {
            val p = v.project(V(sx * 0.48f, 0.72f, 2.34f))
            pen.glow(p[0], p[1], 12f, if (car.headlights == true) HEADLIGHT else AMBER, 0.6f)
        }
        if (lit && rearVisible) for (sx in listOf(-1f, 1f)) {
            val p = v.project(V(sx * 0.64f, 0.76f, -2.34f))
            pen.glow(p[0], p[1], 9f, TAIL, 0.5f)
        }
        if (car.hazardLights == true && anim.blinkOn) {
            for (sx in listOf(-1f, 1f)) {
                if (frontVisible) v.project(V(sx * 0.60f, 0.50f, 2.34f)).let { pen.glow(it[0], it[1], 9f, AMBER, 0.75f) }
                if (rearVisible) v.project(V(sx * 0.70f, 0.64f, -2.34f)).let { pen.glow(it[0], it[1], 9f, AMBER, 0.75f) }
            }
        }
        if (car.interiorLights == true) v.project(V(0f, 1.3f, -0.9f)).let { pen.glow(it[0], it[1], 26f, INTERIOR, 0.55f) }

        // Status badges float above the roof.
        val top = v.project(V(0f, ROOF, -0.85f))
        val by = top[1] - 20f
        val badges = listOfNotNull(car.locked?.let { "lock" }, if (car.climate.isRunning) "climate" else null)
        badges.forEachIndexed { i, b ->
            val bx = top[0] + (i - (badges.size - 1) / 2f) * 22f
            if (b == "lock") pen.lockBadge(bx, by, 8.5f, car.locked == true, if (car.locked == true) pal.good else pal.warn)
            else pen.climateBadge(bx, by, 8f, car.climate.mode, pal)
        }
    }

    private fun drawCable(pen: Pen, pts: List<FloatArray>, car: CarState, pal: Palette, anim: Anim) {
        if (pts.size < 2) return
        val s = Shape().moveTo(pts[0][0], pts[0][1])
        for (i in 1 until pts.size - 1) {
            val mx = (pts[i][0] + pts[i + 1][0]) / 2f; val my = (pts[i][1] + pts[i + 1][1]) / 2f
            s.quadTo(pts[i][0], pts[i][1], mx, my)
        }
        s.lineTo(pts.last()[0], pts.last()[1])
        val color = if (car.isCharging) pal.charge else pal.cable
        pen.stroke(s, solid(color), 2.6f)
        if (car.isCharging) pen.stroke(s, solid(withAlpha(WHITE, 0.85f)), 1.2f, floatArrayOf(2.5f, 4.5f), -anim.phase * 7f)
    }

    private fun drawFace(pen: Pen, o: Out, car: CarState, pal: Palette, anim: Anim, highlight: CarPart?, drawn: MutableList<Drawn>, v: View) {
        val f = o.face
        val shape = Shape().poly(*FloatArray(o.xs.size * 2) { if (it % 2 == 0) o.xs[it / 2] else o.ys[it / 2] })
        var c = baseColor(f, car, pal, anim)
        val lit = f.kind == Kind.HEADLAMP || f.kind == Kind.TAILLAMP || f.kind == Kind.TURN
        if (!lit) c = shade(c, (0.66f + 0.42f * o.shade).coerceAtMost(1.06f))
        if (f.kind == Kind.GLASS || f.kind == Kind.WINDSCREEN) c = lerpColor(c, WHITE, 0.18f * o.shade * o.shade)
        if (car.theftAlarm == TheftAlarm.TRIGGERED && (f.kind == Kind.BODY || f.kind == Kind.HOOD || f.kind == Kind.LEAF)) c = lerpColor(c, pal.bad, 0.15f + 0.35f * anim.pulse)
        if (highlight != null && f.part == highlight) c = lerpColor(c, pal.highlight, 0.42f)
        // Same-colour hairline over the edges hides seams between neighbouring faces.
        pen.stroke(shape, solid(c), 0.7f)
        pen.fill(shape, glassPaint(f.kind, c, o.ys))
        val edge = when {
            f.kind == Kind.LEAF && f.tag == "door" -> pal.outline
            f.kind == Kind.LEAF || f.kind == Kind.OPENING -> 0
            f.tag.length == 2 -> withAlpha(shade(c, 0.78f), 0.9f) // door seams
            else -> 0
        }
        if (edge != 0) pen.stroke(shape, solid(edge), 0.6f)
        if (highlight != null && f.part == highlight && f.kind != Kind.TYRE) pen.stroke(shape, solid(withAlpha(pal.highlight, 0.8f)), 0.9f)
        drawn += Drawn(o.xs, o.ys, f.part)
        for (d in f.decals) {
            val p = d.pts.map(v::xf)
            val xs = FloatArray(p.size) { v.px(p[it]) }
            val ys = FloatArray(p.size) { v.py(p[it]) }
            val ds = Shape().poly(*FloatArray(xs.size * 2) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] })
            var dc = baseColor(d, car, pal, anim)
            val dlit = d.kind == Kind.HEADLAMP || d.kind == Kind.TAILLAMP || d.kind == Kind.TURN
            if (!dlit) dc = shade(dc, (0.66f + 0.42f * o.shade).coerceAtMost(1.06f))
            if (d.kind == Kind.GLASS) dc = lerpColor(dc, WHITE, 0.18f * o.shade * o.shade)
            if (highlight != null && d.part == highlight) dc = lerpColor(dc, pal.highlight, 0.42f)
            pen.fill(ds, glassPaint(d.kind, dc, ys))
            if (d.kind == Kind.PORT) drawn += Drawn(xs, ys, d.part)
        }
    }

    /** Glass gets a sky reflection: lighter towards the top. */
    private fun glassPaint(kind: Kind, c: Int, ys: FloatArray): Paint {
        if (kind != Kind.GLASS && kind != Kind.WINDSCREEN) return solid(c)
        val top = ys.min(); val bottom = ys.max()
        if (bottom - top < 1f) return solid(c)
        return Paint.Linear(0f, top, 0f, bottom, intArrayOf(lerpColor(c, WHITE, 0.28f), c, shade(c, 0.9f)), floatArrayOf(0f, 0.55f, 1f))
    }

    private fun baseColor(f: Face, car: CarState, pal: Palette, anim: Anim): Int {
        val climate = car.climate.isRunning
        val a = 0.25f + 0.35f * anim.pulse
        val glassBase = if (car.interiorLights == true) lerpColor(pal.glass, INTERIOR, 0.35f) else pal.glass
        return when (f.kind) {
            Kind.BODY, Kind.HOOD, Kind.ROOF -> pal.body
            Kind.PILLAR -> pal.body
            Kind.LEAF -> pal.warn
            Kind.OPENING -> lerpColor(pal.warn, BLACK, 0.55f)
            Kind.GLASS -> when {
                climate && car.climate.mode == ClimateMode.HEAT -> lerpColor(glassBase, HEAT, a)
                climate && car.climate.mode == ClimateMode.COOL -> lerpColor(glassBase, pal.charge, a)
                else -> glassBase
            }
            Kind.WINDSCREEN -> when {
                climate && car.climate.mode == ClimateMode.WINDSCREEN -> lerpColor(glassBase, DEFROST, a + 0.15f)
                climate && car.climate.mode == ClimateMode.HEAT -> lerpColor(glassBase, HEAT, a * 0.7f)
                climate && car.climate.mode == ClimateMode.COOL -> lerpColor(glassBase, pal.charge, a * 0.7f)
                else -> glassBase
            }
            Kind.TYRE -> pal.tyre
            Kind.RIM -> 0xFFB9C0C5.toInt()
            Kind.ARCH -> lerpColor(pal.tyre, BLACK, 0.5f)
            Kind.HEADLAMP -> when {
                car.headlights == true -> HEADLIGHT
                car.parkingLights == true -> AMBER
                else -> lerpColor(pal.glass, WHITE, 0.55f)
            }
            Kind.TAILLAMP -> if (car.headlights == true || car.parkingLights == true) TAIL else lerpColor(TAIL, BLACK, 0.45f)
            Kind.TURN -> if (car.hazardLights == true && anim.blinkOn) AMBER else lerpColor(AMBER, BLACK, 0.55f)
            Kind.GRILLE -> lerpColor(pal.glass, BLACK, 0.3f)
            Kind.PLATE -> 0xFFE9ECEE.toInt()
            Kind.RAIL -> lerpColor(pal.glass, BLACK, 0.2f)
            Kind.PORT -> if (car.plug == PlugState.PLUGGED || car.isCharging) pal.tyre else shade(pal.body, 0.85f)
            Kind.HANDLE -> shade(pal.body, 0.7f)
        }
    }

    // ------------------------------------------------------------------------------------------ taps and hints

    override fun partAt(x: Float, y: Float, car: CarState): CarPart? {
        lastPort?.let { if (hypot(x - it[0], y - it[1]) < 14f) return CarPart.CHARGE }
        val cable = lastCable
        for (i in 0 until cable.size - 1) {
            if (segDist(x, y, cable[i][0], cable[i][1], cable[i + 1][0], cable[i + 1][1]) < 7f) return CarPart.CHARGE
        }
        for (d in lastDrawn.asReversed()) if (inside(x, y, d.xs, d.ys)) return d.part
        return null
    }

    override fun hints(car: CarState): List<Hint> {
        val v = View()
        val rightVisible = v.facing(1f, 0f, 0f, V(0.9f, 0.7f, -1.9f))
        val side = if (rightVisible) 1f else -1f
        val c = v.project(V(0f, 0.8f, 0f))
        // Labels sit beside their part, outwards from the middle of the car.
        fun hint(part: CarPart, p: V, dy: Float, visible: Boolean = true): Hint {
            val t = v.project(p)
            val sx = if (t[0] >= c[0]) 1f else -1f
            return Hint(part, t[0], t[1], (t[0] + sx * 42f).coerceIn(28f, w - 28f), (t[1] + dy).coerceIn(8f, h - 8f), visible)
        }
        // The bonnet hides behind the cabin when seen from the back.
        val nose = v.xf(V(0f, cy, 1f))
        val frontish = nose[2] > -0.45f
        return listOf(
            hint(CarPart.LIGHTS, V(0f, 0.86f, 1.95f), 0f, visible = frontish),
            hint(CarPart.CABIN, V(side * 0.77f, 1.33f, -0.55f), -12f),
            hint(CarPart.BODY, V(side * 0.92f, 0.62f, 0.45f), 22f),
            hint(CarPart.CHARGE, port, 0f, visible = rightVisible),
        )
    }

    private fun inside(x: Float, y: Float, xs: FloatArray, ys: FloatArray): Boolean {
        var c = false
        var j = xs.size - 1
        for (i in xs.indices) {
            if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) c = !c
            j = i
        }
        return c
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
