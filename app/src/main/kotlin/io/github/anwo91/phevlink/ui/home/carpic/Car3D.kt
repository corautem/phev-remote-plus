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
import kotlin.math.pow
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

    private enum class Kind {
        BODY, HOOD, GLASS, WINDSCREEN, ROOF, TYRE, RIM, RIMHOLE, HUB, ARCH, HEADLAMP, TAILLAMP, TURN, GRILLE, CHROME, PLATE,
        PILLAR, APILLAR, CLAD, RAIL, PORT, HANDLE, OPENING, LEAF,
    }

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
    private val cy = 0.86f

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
        fun py(p: FloatArray): Float = h * 0.49f - p[1] * focal / (cam - p[2])
        fun project(v: V): FloatArray = xf(v).let { floatArrayOf(px(it), py(it), it[2]) }

        /** Whether a model-space direction (outward normal) faces the camera at model point [at]. */
        fun facing(nx: Float, ny: Float, nz: Float, at: V): Boolean {
            val n = xf(V(nx, ny + cy, nz)) // direction: rotate without translation
            val p = xf(at)
            return n[0] * (0f - p[0]) + n[1] * (0f - p[1]) + n[2] * (cam - p[2]) > 0f
        }
    }

    // ------------------------------------------------------------------------------------------ the model

    /**
     * A cross-section of the lower body at [z]: bottom half-width [a] at [y0] (raised over the wheels: the arches),
     * the top of the dark lower cladding ([bl], [yc]), the widest point ([b], [y1]) and the belt or bonnet edge
     * ([c], [y2]). Eight points: bottom, cladding, shoulder and top on each side.
     */
    private class Station(
        val z: Float, val a: Float, val y0: Float, val bl: Float, val yc: Float, val b: Float, val y1: Float, val c: Float, val y2: Float,
    )

    private val stations = listOf(
        Station(-2.33f, .70f, .42f, .78f, .56f, .82f, .84f, .72f, 1.04f),
        Station(-2.24f, .78f, .34f, .86f, .52f, .89f, .84f, .80f, 1.08f),
        Station(-1.80f, .80f, .30f, .88f, .48f, .91f, .84f, .83f, 1.08f),
        Station(-1.62f, .80f, .60f, .88f, .65f, .91f, .84f, .83f, 1.08f),
        Station(-1.38f, .80f, .76f, .88f, .80f, .91f, .88f, .83f, 1.08f),
        Station(-1.14f, .80f, .60f, .88f, .65f, .91f, .84f, .83f, 1.08f),
        Station(-0.96f, .80f, .30f, .88f, .48f, .91f, .84f, .83f, 1.08f),
        Station(-0.05f, .80f, .30f, .88f, .48f, .91f, .84f, .83f, 1.08f),
        Station(0.96f, .80f, .30f, .88f, .48f, .91f, .84f, .83f, 1.08f),
        Station(1.14f, .80f, .60f, .88f, .65f, .91f, .84f, .83f, 1.07f),
        Station(1.38f, .80f, .76f, .88f, .80f, .91f, .88f, .83f, 1.055f),
        Station(1.62f, .79f, .60f, .87f, .65f, .90f, .84f, .82f, 1.04f),
        Station(1.80f, .78f, .32f, .86f, .48f, .89f, .83f, .80f, 1.02f),
        Station(2.10f, .74f, .34f, .83f, .48f, .87f, .80f, .78f, .98f),
        Station(2.33f, .66f, .38f, .74f, .50f, .78f, .76f, .66f, .90f),
    )

    private fun section(s: Station): List<V> = listOf(
        V(-s.a, s.y0, s.z), V(s.a, s.y0, s.z), V(s.bl, s.yc, s.z), V(s.b, s.y1, s.z),
        V(s.c, s.y2, s.z), V(-s.c, s.y2, s.z), V(-s.b, s.y1, s.z), V(-s.bl, s.yc, s.z),
    )

    /** x of the upper side panel (shoulder to belt) at height [y] in the middle of the car, for decals on it. */
    private fun upperSideX(y: Float) = 0.91f - 0.08f * (y - 0.84f) / 0.24f + 0.006f

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

    /** The cabin as four rings from the belt line up to the roof crown: (height, half-width, front z, rear z, corner). */
    private class Ring(val y: Float, val w: Float, val zf: Float, val zr: Float, val k: Float) {
        /** Eight corners, starting front-right, going round. */
        fun points(): List<V> = listOf(
            V(w - k, y, zf), V(w, y, zf - k), V(w, y, zr + k), V(w - k, y, zr),
            V(-w + k, y, zr), V(-w, y, zr + k), V(-w, y, zf - k), V(-w + k, y, zf),
        )
    }

    private val ROOF = 1.70f

    private val rings = listOf(
        Ring(1.08f, .83f, 1.05f, -2.24f, .10f),
        Ring(1.60f, .74f, 0.36f, -2.02f, .14f),
        Ring(1.67f, .64f, 0.20f, -1.96f, .16f),
        Ring(ROOF, .42f, 0.08f, -1.88f, .12f),
    )

    private val portZ = -1.98f
    private val portY = 0.94f
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
            for (e in 1..7) {
                val k = (e + 1) % 8
                val top = e == 4
                // Under the cabin the body top is hidden; skip it.
                if (top && z0 >= -2.24f && z1 <= 0.96f) continue
                val pts = outward(quad(s0[e], s0[k], s1[k], s1[e]), V(0f, 0.62f, zc))
                val right = e <= 3
                val clad = e == 1 || e == 7
                val doorKey = when {
                    clad || top -> ""
                    z0 == frontDoor.first && z1 == frontDoor.second -> if (right) "fr" else "fl"
                    z0 == rearDoor.first && z1 == rearDoor.second -> if (right) "rr" else "rl"
                    else -> ""
                }
                val hood = top && z0 >= 0.96f
                val kind = when {
                    clad -> Kind.CLAD
                    hood -> Kind.HOOD
                    else -> Kind.BODY
                }
                val f = Face(pts, kind, if (hood) CarPart.LIGHTS else CarPart.BODY, tag = doorKey.ifEmpty { if (hood) "hood" else "" })
                // Door handles and the charge-port flap sit on the upper side panels.
                if (e == 3 || e == 5) {
                    val sx = if (e == 3) 1f else -1f
                    if (doorKey.isNotEmpty()) {
                        val hz = if (doorKey[0] == 'f') 0.30f else -0.66f
                        f.decals += Face(quad(V(sx * upperSideX(0.94f), 0.94f, hz), V(sx * upperSideX(0.94f), 0.94f, hz + 0.20f), V(sx * upperSideX(0.98f), 0.98f, hz + 0.20f), V(sx * upperSideX(0.98f), 0.98f, hz)), Kind.HANDLE, CarPart.BODY)
                    }
                    if (e == 3 && z0 == -2.24f) {
                        f.decals += Face(quad(V(upperSideX(0.88f), 0.88f, portZ - 0.10f), V(upperSideX(0.88f), 0.88f, portZ + 0.10f), V(upperSideX(1.0f), 1.0f, portZ + 0.10f), V(upperSideX(1.0f), 1.0f, portZ - 0.10f)), Kind.PORT, CarPart.CHARGE)
                    }
                }
                faces += f
            }
        }

        // Front: bumper cladding, grille with its chrome bar, headlights, intake, turn lights.
        val front = Face(outward(secs.last(), V(0f, 0.62f, 0f)), Kind.BODY, CarPart.LIGHTS, tag = "front")
        val fz = 2.336f
        front.decals += Face(quad(V(-0.66f, 0.38f, fz), V(0.66f, 0.38f, fz), V(0.74f, 0.50f, fz), V(-0.74f, 0.50f, fz)), Kind.CLAD, CarPart.LIGHTS)
        // A slim upper grille between the headlights and a big lower one, each with a chrome bar.
        front.decals += Face(quad(V(-0.30f, 0.76f, fz), V(0.30f, 0.76f, fz), V(0.28f, 0.84f, fz), V(-0.28f, 0.84f, fz)), Kind.GRILLE, CarPart.LIGHTS)
        front.decals += Face(quad(V(-0.31f, 0.79f, fz), V(0.31f, 0.79f, fz), V(0.30f, 0.81f, fz), V(-0.30f, 0.81f, fz)), Kind.CHROME, CarPart.LIGHTS)
        front.decals += Face(quad(V(-0.38f, 0.50f, fz), V(0.38f, 0.50f, fz), V(0.42f, 0.69f, fz), V(-0.42f, 0.69f, fz)), Kind.GRILLE, CarPart.LIGHTS)
        front.decals += Face(quad(V(-0.40f, 0.59f, fz), V(0.40f, 0.59f, fz), V(0.405f, 0.61f, fz), V(-0.405f, 0.61f, fz)), Kind.CHROME, CarPart.LIGHTS)
        for (sx in listOf(-1f, 1f)) {
            front.decals += Face(quad(V(sx * 0.77f, 0.74f, fz), V(sx * 0.32f, 0.76f, fz), V(sx * 0.31f, 0.85f, fz), V(sx * 0.66f, 0.865f, fz)), Kind.HEADLAMP, CarPart.LIGHTS, tag = "head")
            front.decals += Face(quad(V(sx * 0.71f, 0.54f, fz), V(sx * 0.55f, 0.54f, fz), V(sx * 0.55f, 0.60f, fz), V(sx * 0.73f, 0.60f, fz)), Kind.TURN, CarPart.LIGHTS)
        }
        faces += front
        // Rear: bumper cladding, tail lights, plate with a chrome strip.
        val rear = Face(outward(secs.first(), V(0f, 0.62f, 0f)), Kind.BODY, CarPart.BODY, tag = "rear")
        val rz = -2.336f
        rear.decals += Face(quad(V(-0.70f, 0.42f, rz), V(0.70f, 0.42f, rz), V(0.78f, 0.56f, rz), V(-0.78f, 0.56f, rz)), Kind.CLAD, CarPart.BODY)
        for (sx in listOf(-1f, 1f)) {
            rear.decals += Face(quad(V(sx * 0.81f, 0.80f, rz), V(sx * 0.50f, 0.82f, rz), V(sx * 0.50f, 0.92f, rz), V(sx * 0.76f, 0.95f, rz)), Kind.TAILLAMP, CarPart.BODY)
            rear.decals += Face(quad(V(sx * 0.80f, 0.755f, rz), V(sx * 0.62f, 0.755f, rz), V(sx * 0.62f, 0.79f, rz), V(sx * 0.81f, 0.79f, rz)), Kind.TURN, CarPart.BODY)
        }
        rear.decals += Face(quad(V(-0.22f, 0.58f, rz), V(0.22f, 0.58f, rz), V(0.22f, 0.70f, rz), V(-0.22f, 0.70f, rz)), Kind.PLATE, CarPart.BODY)
        rear.decals += Face(quad(V(-0.32f, 0.72f, rz), V(0.32f, 0.72f, rz), V(0.32f, 0.74f, rz), V(-0.32f, 0.74f, rz)), Kind.CHROME, CarPart.BODY)
        faces += rear

        // The cabin: rings from the belt up to the roof crown. Glass between the first two, black A-pillars, body
        // colour above; side windows with a black B-pillar; roof rails and a small spoiler over the tailgate.
        val ref = V(0f, 1.3f, -0.6f)
        val rp = rings.map { it.points() }
        for (band in 0 until rings.size - 1) {
            val lo = rp[band]; val hi = rp[band + 1]
            for (j in 0 until 8) {
                val k = (j + 1) % 8
                val pts = outward(quad(lo[j], lo[k], hi[k], hi[j]), ref)
                val face = when {
                    band > 0 -> Face(pts, Kind.ROOF, CarPart.CABIN)
                    j == 7 -> Face(pts, Kind.WINDSCREEN, CarPart.CABIN, tag = "windscreen")
                    j == 3 -> Face(pts, Kind.GLASS, CarPart.CABIN, tag = "rearwindow")
                    j == 0 || j == 6 -> Face(pts, Kind.APILLAR, CarPart.CABIN)
                    else -> Face(pts, Kind.PILLAR, CarPart.CABIN)
                }
                if (band == 0 && (j == 1 || j == 5)) {
                    val sx = if (j == 1) 1f else -1f
                    val r0 = rings[0]; val r1 = rings[1]
                    fun on(z: Float, y: Float): V {
                        val t = (y - r0.y) / (r1.y - r0.y)
                        return V(sx * (r0.w + (r1.w - r0.w) * t + 0.004f), y, z)
                    }
                    face.decals += Face(quad(on(-2.06f, 1.12f), on(-1.10f, 1.12f), on(-1.14f, 1.56f), on(-1.84f, 1.56f)), Kind.GLASS, CarPart.CABIN)
                    face.decals += Face(quad(on(-0.97f, 1.12f), on(-0.10f, 1.12f), on(-0.16f, 1.56f), on(-1.02f, 1.56f)), Kind.GLASS, CarPart.CABIN)
                    face.decals += Face(quad(on(-0.10f, 1.12f), on(0.03f, 1.12f), on(-0.08f, 1.56f), on(-0.16f, 1.56f)), Kind.APILLAR, CarPart.CABIN)
                    face.decals += Face(quad(on(0.03f, 1.12f), on(0.85f, 1.12f), on(0.24f, 1.56f), on(-0.08f, 1.56f)), Kind.GLASS, CarPart.CABIN)
                }
                faces += face
            }
        }
        val roof = Face(outward(rp.last(), ref), Kind.ROOF, CarPart.CABIN)
        for (sx in listOf(-0.34f, 0.34f)) {
            roof.decals += Face(quad(V(sx - 0.025f, ROOF + 0.004f, -1.74f), V(sx + 0.025f, ROOF + 0.004f, -1.74f), V(sx + 0.025f, ROOF + 0.004f, -0.04f), V(sx - 0.025f, ROOF + 0.004f, -0.04f)), Kind.RAIL, CarPart.CABIN)
        }
        faces += roof
        faces += Face(quad(V(-0.58f, 1.67f, -1.96f), V(0.58f, 1.67f, -1.96f), V(0.55f, 1.635f, -2.10f), V(-0.55f, 1.635f, -2.10f)), Kind.ROOF, CarPart.CABIN, twoSided = true, tag = "spoiler")

        // Mirrors.
        for (sx in listOf(-1f, 1f)) {
            val x0 = sx * 0.84f; val x1 = sx * 1.00f
            val y0 = 1.10f; val y1 = 1.20f
            val z0 = 0.86f; val z1 = 0.98f
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
        fun isOpen(f: Face) = open[f.tag] == true || (f.tag == "hood" && doors.bonnet) || ((f.tag == "rearwindow" || f.tag == "spoiler") && doors.boot)
        val extra = ArrayList<Face>()
        for (f in faces) {
            if (!isOpen(f)) continue
            val leaf = when (f.tag) {
                "hood" -> f.pts.map { rotX(it, 1.08f, 0.96f, -38f) }
                "rearwindow", "spoiler" -> f.pts.map { rotX(it, 1.67f, -1.96f, 70f) }
                else -> {
                    val right = f.tag[1] == 'r'
                    val hingeZ = if (f.tag[0] == 'f') 0.96f else -0.05f
                    f.pts.map { rotY(it, if (right) 0.91f else -0.91f, hingeZ, if (right) -50f else 50f) }
                }
            }
            extra += Face(leaf, Kind.LEAF, f.part, twoSided = true, tag = if (f.tag.length == 2) "door" else "")
        }
        if (doors.boot) {
            // The tailgate's lower panel lifts with its glass.
            val panel = quad(V(-0.72f, 0.76f, -2.33f), V(0.72f, 0.76f, -2.33f), V(0.80f, 1.08f, -2.24f), V(-0.80f, 1.08f, -2.24f))
            extra += Face(panel.map { rotX(it, 1.67f, -1.96f, 70f) }, Kind.LEAF, CarPart.BODY, twoSided = true)
        }
        for (i in faces.indices) {
            val f = faces[i]
            if (isOpen(f)) {
                faces[i] = if (f.tag == "spoiler") Face(emptyList(), Kind.OPENING, f.part) else Face(f.pts, Kind.OPENING, f.part, tag = f.tag)
            }
        }
        faces.removeAll { it.pts.isEmpty() }
        faces += extra

        // Wheels: dark arch discs, tyres and five-spoke rims.
        val wheels = ArrayList<Face>()
        for (sx in listOf(-1f, 1f)) for (wz in listOf(-1.38f, 1.38f)) {
            val n = 16
            val r = 0.36f; val yc = 0.36f
            val xo = sx * 0.92f; val xi = sx * 0.68f
            val c = V(sx * 0.80f, yc, wz)
            wheels += Face((0 until 16).map { i -> val a = i * 2 * PI.toFloat() / 16; V(sx * 0.84f, 0.42f + 0.42f * sin(a), wz + 0.42f * cos(a)) }, Kind.ARCH, CarPart.BODY, twoSided = true)
            fun at(x: Float, rr: Float, deg: Float): V {
                val a = deg * PI.toFloat() / 180f
                return V(x, yc + rr * sin(a), wz + rr * cos(a))
            }
            val ring = { x: Float, rr: Float -> (0 until n).map { i -> at(x, rr, i * 360f / n) } }
            val outer = ring(xo, r); val inner = ring(xi, r)
            for (i in 0 until n) {
                val j = (i + 1) % n
                wheels += Face(outward(quad(outer[i], outer[j], inner[j], inner[i]), c), Kind.TYRE, CarPart.BODY)
            }
            val cap = Face(outward(outer, c), Kind.TYRE, CarPart.BODY)
            val xr = xo + sx * 0.004f
            cap.decals += Face(ring(xr, 0.235f), Kind.RIM, CarPart.BODY)
            for (s in 0 until 5) {
                val m = s * 72f + 18f
                cap.decals += Face(listOf(at(xr, 0.085f, m - 13f), at(xr, 0.205f, m - 24f), at(xr, 0.215f, m), at(xr, 0.205f, m + 24f), at(xr, 0.085f, m + 13f)), Kind.RIMHOLE, CarPart.BODY)
            }
            cap.decals += Face(ring(xr, 0.05f), Kind.HUB, CarPart.BODY)
            wheels += cap
        }
        return faces to wheels
    }

    // ------------------------------------------------------------------------------------------ drawing

    private class Out(val face: Face, val xs: FloatArray, val ys: FloatArray, val depth: Float, val shade: Float, val visible: Boolean, val spec: Float)

    override fun draw(pen: Pen, car: CarState, pal: Palette, anim: Anim, highlight: CarPart?) {
        val v = View()
        val doors = car.doors ?: Doors()
        val (faces, wheels) = build(doors)
        val lightDir = run { val l = floatArrayOf(-0.35f, 0.80f, 0.50f); val n = sqrt(l.sumOf { (it * it).toDouble() }).toFloat(); l.map { it / n } }
        val half = run {
            val l = floatArrayOf(lightDir[0], lightDir[1], lightDir[2] + 1f)
            val n = sqrt(l.sumOf { (it * it).toDouble() }).toFloat()
            l.map { it / n }
        }

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
            // A glint where the light reflects towards the viewer (glossy paint).
            val h = max(0f, nx * half[0] + ny * half[1] + nz * half[2])
            val spec = h.toDouble().pow(28.0).toFloat()
            return Out(f, xs, ys, czv, lam, facing, spec)
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
                val q = listOf(V(sx * 0.70f, 0.80f, 2.36f), V(sx * 0.34f, 0.80f, 2.36f), V(sx * 0.05f, 0f, 5.4f), V(sx * 1.45f, 0f, 5.4f)).map(v::project)
                val a = v.project(V(sx * 0.52f, 0.80f, 2.36f)); val b = v.project(V(sx * 0.75f, 0f, 5.4f))
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
            val p = v.project(V(sx * 0.52f, 0.80f, 2.34f))
            pen.glow(p[0], p[1], 12f, if (car.headlights == true) HEADLIGHT else AMBER, 0.6f)
        }
        if (lit && rearVisible) for (sx in listOf(-1f, 1f)) {
            val p = v.project(V(sx * 0.64f, 0.87f, -2.34f))
            pen.glow(p[0], p[1], 9f, TAIL, 0.5f)
        }
        if (car.hazardLights == true && anim.blinkOn) {
            for (sx in listOf(-1f, 1f)) {
                if (frontVisible) v.project(V(sx * 0.63f, 0.57f, 2.34f)).let { pen.glow(it[0], it[1], 9f, AMBER, 0.75f) }
                if (rearVisible) v.project(V(sx * 0.70f, 0.77f, -2.34f)).let { pen.glow(it[0], it[1], 9f, AMBER, 0.75f) }
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
        if (f.kind == Kind.BODY || f.kind == Kind.HOOD || f.kind == Kind.ROOF || f.kind == Kind.PILLAR || f.kind == Kind.LEAF) c = lerpColor(c, WHITE, 0.45f * o.spec)
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
            Kind.RIM -> 0xFFC2C8CC.toInt()
            Kind.RIMHOLE -> lerpColor(pal.tyre, BLACK, 0.3f)
            Kind.HUB -> 0xFF8E969C.toInt()
            Kind.CLAD -> if (pal.dark) 0xFF262B2F.toInt() else 0xFF3B4146.toInt()
            Kind.CHROME -> 0xFFD8DDE0.toInt()
            Kind.APILLAR -> lerpColor(pal.glass, BLACK, 0.45f)
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
            hint(CarPart.LIGHTS, V(0f, 0.97f, 1.80f), 0f, visible = frontish),
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
