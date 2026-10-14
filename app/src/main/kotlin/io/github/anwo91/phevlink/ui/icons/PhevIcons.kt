package io.github.anwo91.phevlink.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * PHEV Link's own icon set: thin-line glyphs on a 24 x 24 grid, drawn as round-capped strokes.
 *
 * Use like any Material icon: `Icon(PhevIcons.Car, contentDescription = null)`. The glyphs are
 * drawn in black and take their colour from the `tint` of `Icon` (or `LocalContentColor`).
 *
 * To change the weight of the whole set, change [STROKE]. Each icon is built on first use.
 */
object PhevIcons {
    /** Line width in grid units (the grid is 24 wide). 1.4 is the "thin line" style. */
    private const val STROKE = 1.4f

    /** Radius of the solid dots (pins, lamps, the dot of an "i"). */
    private const val DOT = STROKE * 0.75f

    private val ink = SolidColor(Color.Black)

    /** [lines] are SVG path strings stroked with [STROKE]; [dots] are x, y pairs of solid dots. */
    private fun icon(name: String, lines: List<String>, dots: List<Float> = emptyList()): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            for (line in lines) {
                addPath(
                    pathData = addPathNodes(line),
                    stroke = ink,
                    strokeLineWidth = STROKE,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            for (i in dots.indices step 2) {
                val x = dots[i]
                val y = dots[i + 1]
                val d = DOT * 2
                addPath(
                    pathData = addPathNodes("M ${x - DOT} $y a $DOT $DOT 0 1 0 $d 0 a $DOT $DOT 0 1 0 ${-d} 0 Z"),
                    fill = ink,
                )
            }
        }.build()

    // ---- Navigation ----

    /** Car tab. */
    val Car: ImageVector by lazy {
        icon(
            "Car",
            listOf(
                "M 4 11 h 16 a 1 1 0 0 1 1 1 v 5 H 3 v -5 a 1 1 0 0 1 1 -1 z",
                "M 5 11 l 1.5 -4.2 A 2 2 0 0 1 8.4 5.5 h 7.2 a 2 2 0 0 1 1.9 1.3 L 19 11 M 5.5 17 v 1.5 M 18.5 17 v 1.5",
            ),
            listOf(7f, 14f, 17f, 14f),
        )
    }

    /** Schedule tab, departures, charge timer. */
    val Clock: ImageVector by lazy {
        icon(
            "Clock",
            listOf(
                "M 3.5 12 a 8.5 8.5 0 1 0 17 0 a 8.5 8.5 0 1 0 -17 0 Z",
                "M 12 7.5 V 12 l 3 2",
            ),
        )
    }

    /** OBD tab: the 16-pin diagnostic connector. */
    val Obd: ImageVector by lazy {
        icon(
            "Obd",
            listOf(
                "M 4 7.5 h 16 l -1.6 8.2 a 1 1 0 0 1 -1 0.8 H 6.6 a 1 1 0 0 1 -1 -0.8 z",
            ),
            listOf(8.5f, 10.5f, 12f, 10.5f, 15.5f, 10.5f, 10.2f, 13.5f, 13.8f, 13.5f),
        )
    }

    /** Log tab. */
    val Log: ImageVector by lazy {
        icon(
            "Log",
            listOf(
                "M 7 3.5 h 10 a 2 2 0 0 1 2 2 v 13 a 2 2 0 0 1 -2 2 h -10 a 2 2 0 0 1 -2 -2 v -13 a 2 2 0 0 1 2 -2 Z",
                "M 8.5 8.5 h 7 M 8.5 12 h 7 M 8.5 15.5 h 4",
            ),
        )
    }

    /** Settings tab. */
    val Settings: ImageVector by lazy {
        icon(
            "Settings",
            listOf(
                "M 4.5 7 H 13 M 19 7 h 0.5 M 4.5 12 H 5 M 11 12 h 8.5 M 4.5 17 H 11 M 17 17 h 2.5",
                "M 13.8 7 a 2.2 2.2 0 1 0 4.4 0 a 2.2 2.2 0 1 0 -4.4 0 Z",
                "M 5.8 12 a 2.2 2.2 0 1 0 4.4 0 a 2.2 2.2 0 1 0 -4.4 0 Z",
                "M 11.8 17 a 2.2 2.2 0 1 0 4.4 0 a 2.2 2.2 0 1 0 -4.4 0 Z",
            ),
        )
    }

    /** Back arrow in top bars. */
    val Back: ImageVector by lazy {
        icon(
            "Back",
            listOf(
                "M 19 12 H 5 M 11 6 l -6 6 6 6",
            ),
        )
    }


    // ---- Climate ----

    /** Cooling. */
    val Cool: ImageVector by lazy {
        icon(
            "Cool",
            listOf(
                "M 12 3 v 18 M 4.2 7.5 l 15.6 9 M 19.8 7.5 l -15.6 9 M 9.5 4.5 L 12 7 l 2.5 -2.5 M 9.5 19.5 L 12 17 l 2.5 2.5",
            ),
        )
    }

    /** Heating. */
    val Heat: ImageVector by lazy {
        icon(
            "Heat",
            listOf(
                "M 12 3 c 0.5 3 4.5 5 4.5 9.5 a 4.5 4.5 0 0 1 -9 0 c 0 -1.8 0.8 -3 1.8 -4 0.3 1.3 1 2 1.7 2.2 C 11 8.5 10.5 5.5 12 3 z",
            ),
        )
    }

    /** Windscreen defrost. */
    val Defrost: ImageVector by lazy {
        icon(
            "Defrost",
            listOf(
                "M 3.5 8.5 c 5.5 -3 11.5 -3 17 0 l -2 9 c -4.3 -1.6 -8.7 -1.6 -13 0 z",
                "M 8.5 14.5 c -0.9 -1.2 0.9 -2.3 0 -3.5 M 12 14.5 c -0.9 -1.2 0.9 -2.3 0 -3.5 M 15.5 14.5 c -0.9 -1.2 0.9 -2.3 0 -3.5",
            ),
        )
    }

    /** Smart climate. */
    val Smart: ImageVector by lazy {
        icon(
            "Smart",
            listOf(
                "M 11 4 l 1.6 4.4 L 17 10 l -4.4 1.6 L 11 16 l -1.6 -4.4 L 5 10 l 4.4 -1.6 z",
                "M 18 15 l 0.7 1.8 1.8 0.7 -1.8 0.7 L 18 20 l -0.7 -1.8 -1.8 -0.7 1.8 -0.7 z",
            ),
        )
    }

    /** Outside temperature. */
    val Temperature: ImageVector by lazy {
        icon(
            "Temperature",
            listOf(
                "M 10 14.5 V 5.5 a 2 2 0 0 1 4 0 v 9 a 4 4 0 1 1 -4 0 z",
                "M 12 9 v 7.5",
            ),
        )
    }

    /** Weather forecast. */
    val Forecast: ImageVector by lazy {
        icon(
            "Forecast",
            listOf(
                "M 7.5 18.5 a 4 4 0 0 1 -0.5 -7.95 5.5 5.5 0 0 1 10.6 1.45 3.25 3.25 0 0 1 -0.6 6.5 z",
            ),
        )
    }

    /** Duration. */
    val Timer: ImageVector by lazy {
        icon(
            "Timer",
            listOf(
                "M 5 13.5 a 7 7 0 1 0 14 0 a 7 7 0 1 0 -14 0 Z",
                "M 12 10 v 3.5 M 10 3.5 h 4 M 12 3.5 v 3",
            ),
        )
    }

    /** Weekdays. */
    val Calendar: ImageVector by lazy {
        icon(
            "Calendar",
            listOf(
                "M 6 5.5 h 12 a 2 2 0 0 1 2 2 v 10.5 a 2 2 0 0 1 -2 2 h -12 a 2 2 0 0 1 -2 -2 v -10.5 a 2 2 0 0 1 2 -2 Z",
                "M 4 10 h 16 M 8 3.5 V 7 M 16 3.5 V 7",
            ),
        )
    }


    // ---- Lights and doors ----

    /** Flash the lights. */
    val Flash: ImageVector by lazy {
        icon(
            "Flash",
            listOf(
                "M 8.8 12 a 3.2 3.2 0 1 0 6.4 0 a 3.2 3.2 0 1 0 -6.4 0 Z",
                "M 12 3 v 2.5 M 12 18.5 V 21 M 3 12 h 2.5 M 18.5 12 H 21 M 5.6 5.6 l 1.8 1.8 M 16.6 16.6 l 1.8 1.8 M 18.4 5.6 l -1.8 1.8 M 7.4 16.6 l -1.8 1.8",
            ),
        )
    }

    /** Headlights. */
    val Headlights: ImageVector by lazy {
        icon(
            "Headlights",
            listOf(
                "M 11.5 6.5 v 11 c -4 0 -7 -2.2 -7 -5.5 s 3 -5.5 7 -5.5 z",
                "M 14.5 8 H 20 M 14.5 12 H 20 M 14.5 16 H 20",
            ),
        )
    }

    /** Parking lights. */
    val ParkingLights: ImageVector by lazy {
        icon(
            "ParkingLights",
            listOf(
                "M 9.5 8 v 8 C 6.8 16 5 14.3 5 12 s 1.8 -4 4.5 -4 z",
                "M 14.5 8 v 8 c 2.7 0 4.5 -1.7 4.5 -4 s -1.8 -4 -4.5 -4 z",
                "M 2.9 9.2 l -1.4 -0.6 M 2.6 12 H 1.2 M 2.9 14.8 l -1.4 0.6 M 21.1 9.2 l 1.4 -0.6 M 21.4 12 h 1.4 M 21.1 14.8 l 1.4 0.6",
            ),
        )
    }

    /** Locked. */
    val Lock: ImageVector by lazy {
        icon(
            "Lock",
            listOf(
                "M 7.5 10.5 h 9 a 2 2 0 0 1 2 2 v 5.5 a 2 2 0 0 1 -2 2 h -9 a 2 2 0 0 1 -2 -2 v -5.5 a 2 2 0 0 1 2 -2 Z",
                "M 8.5 10.5 V 8 a 3.5 3.5 0 0 1 7 0 v 2.5 M 12 14.2 v 2",
            ),
        )
    }

    /** Unlocked. */
    val Unlock: ImageVector by lazy {
        icon(
            "Unlock",
            listOf(
                "M 7.5 10.5 h 9 a 2 2 0 0 1 2 2 v 5.5 a 2 2 0 0 1 -2 2 h -9 a 2 2 0 0 1 -2 -2 v -5.5 a 2 2 0 0 1 2 -2 Z",
                "M 8.5 10.5 V 8 a 3.5 3.5 0 0 1 6.8 -1.2 M 12 14.2 v 2",
            ),
        )
    }

    /** Door open. */
    val Door: ImageVector by lazy {
        icon(
            "Door",
            listOf(
                "M 6 20 V 10.5 l 5 -6.5 h 7 v 16 z",
                "M 6.5 10.5 H 18 M 14 14 h 2",
            ),
        )
    }

    /** Power switch. */
    val Power: ImageVector by lazy {
        icon(
            "Power",
            listOf(
                "M 12 3.5 v 7 M 7.3 6.3 a 7.5 7.5 0 1 0 9.4 0",
            ),
        )
    }

    /** Key. */
    val Key: ImageVector by lazy {
        icon(
            "Key",
            listOf(
                "M 4.5 15.5 a 3.5 3.5 0 1 0 7 0 a 3.5 3.5 0 1 0 -7 0 Z",
                "M 10.5 13 L 19 4.5 M 16 7.5 l 2.5 2.5 M 13.5 10 l 2 2",
            ),
        )
    }


    // ---- Charging ----

    /** Charging. */
    val Bolt: ImageVector by lazy {
        icon(
            "Bolt",
            listOf(
                "M 13 3 L 6.5 13 H 11 l -1 8 6.5 -10 H 12 z",
            ),
        )
    }

    /** Charge now. */
    val Plug: ImageVector by lazy {
        icon(
            "Plug",
            listOf(
                "M 6.5 8 h 11 v 3 a 5.5 5.5 0 0 1 -11 0 z",
                "M 9 3 v 5 M 15 3 v 5 M 12 16.5 V 21",
            ),
        )
    }

    /** Battery. */
    val Battery: ImageVector by lazy {
        icon(
            "Battery",
            listOf(
                "M 5 7.5 h 12 a 2 2 0 0 1 2 2 v 5 a 2 2 0 0 1 -2 2 h -12 a 2 2 0 0 1 -2 -2 v -5 a 2 2 0 0 1 2 -2 Z",
                "M 21.5 10.5 v 3 M 6.5 10.5 v 3 M 9.5 10.5 v 3 M 12.5 10.5 v 3",
            ),
        )
    }

    /** Electric range. */
    val Range: ImageVector by lazy {
        icon(
            "Range",
            listOf(
                "M 4 18.5 a 2 2 0 1 0 4 0 a 2 2 0 1 0 -4 0 Z",
                "M 16 5.5 a 2 2 0 1 0 4 0 a 2 2 0 1 0 -4 0 Z",
                "M 8 18.5 h 6.5 a 3 3 0 0 0 0 -6 h -5 a 3 3 0 0 1 0 -6 H 16",
            ),
        )
    }

    /** EV mode. */
    val EvMode: ImageVector by lazy {
        icon(
            "EvMode",
            listOf(
                "M 5 19 c 0 -9 5 -14 14 -14 0 9 -5 14 -14 14 z",
                "M 5 19 l 8 -8",
            ),
        )
    }

    /** Fuel. */
    val Fuel: ImageVector by lazy {
        icon(
            "Fuel",
            listOf(
                "M 6.5 4 h 6 a 1.5 1.5 0 0 1 1.5 1.5 v 13 a 1.5 1.5 0 0 1 -1.5 1.5 h -6 a 1.5 1.5 0 0 1 -1.5 -1.5 v -13 a 1.5 1.5 0 0 1 1.5 -1.5 Z",
                "M 7.5 8 h 4 M 4 20 h 11 M 14 11 h 1.5 a 1.5 1.5 0 0 1 1.5 1.5 V 16 a 1.5 1.5 0 0 0 3 0 V 8.5 L 17.5 6",
            ),
        )
    }


    // ---- Connection ----

    /** Wi-Fi. */
    val Wifi: ImageVector by lazy {
        icon(
            "Wifi",
            listOf(
                "M 4.5 9.5 a 11 11 0 0 1 15 0 M 7.5 13 a 6.8 6.8 0 0 1 9 0",
            ),
            listOf(12f, 17f),
        )
    }

    /** No link. */
    val WifiOff: ImageVector by lazy {
        icon(
            "WifiOff",
            listOf(
                "M 4.5 9.5 a 11 11 0 0 1 15 0 M 7.5 13 a 6.8 6.8 0 0 1 9 0",
                "M 4.5 4 l 15 16",
            ),
            listOf(12f, 17f),
        )
    }

    /** Home bridge. */
    val Bridge: ImageVector by lazy {
        icon(
            "Bridge",
            listOf(
                "M 6 10 v 9.5 h 12 V 10 l -6 -5 z",
                "M 3.5 11.5 L 12 4.5 l 8.5 7 M 9.8 14.2 a 3.2 3.2 0 0 1 4.4 0",
            ),
            listOf(12f, 16.8f),
        )
    }

    /** Bluetooth. */
    val Bluetooth: ImageVector by lazy {
        icon(
            "Bluetooth",
            listOf(
                "M 7 7.5 l 10 9 -5 4.5 V 3 l 5 4.5 -10 9",
            ),
        )
    }

    /** Scanning for Bluetooth devices. */
    val BluetoothSearching: ImageVector by lazy {
        icon(
            "BluetoothSearching",
            listOf(
                "M 5 7.5 l 10 9 -5 4.5 V 3 l 5 4.5 -10 9",
                "M 18.3 9.8 a 3.2 3.2 0 0 1 0 4.4 M 20.5 7.8 a 6.2 6.2 0 0 1 0 8.4",
            ),
        )
    }

    /** OBD dongle. */
    val Dongle: ImageVector by lazy {
        icon(
            "Dongle",
            listOf(
                "M 9 3.5 h 6 a 2 2 0 0 1 2 2 v 7 a 2 2 0 0 1 -2 2 h -6 a 2 2 0 0 1 -2 -2 v -7 a 2 2 0 0 1 2 -2 Z",
                "M 9 14.5 v 4 h 6 v -4 M 10.5 21 v -2.5 M 13.5 21 v -2.5",
            ),
            listOf(12f, 7.5f),
        )
    }

    /** Phone. */
    val Phone: ImageVector by lazy {
        icon(
            "Phone",
            listOf(
                "M 9.5 3 h 5 a 2.5 2.5 0 0 1 2.5 2.5 v 13 a 2.5 2.5 0 0 1 -2.5 2.5 h -5 a 2.5 2.5 0 0 1 -2.5 -2.5 v -13 a 2.5 2.5 0 0 1 2.5 -2.5 Z",
                "M 11 18 h 2",
            ),
        )
    }


    // ---- Diagnostics ----

    /** Engine. */
    val Engine: ImageVector by lazy {
        icon(
            "Engine",
            listOf(
                "M 7 9.5 h 8 l 2 2 h 2.5 v 5 H 17 l -1.5 2 H 9 l -2 -2.5 H 4.5 v -4.5 H 7 z",
                "M 10 9.5 V 7 M 8 7 h 4",
            ),
        )
    }

    /** Warning, fault code. */
    val Warning: ImageVector by lazy {
        icon(
            "Warning",
            listOf(
                "M 12 4.5 l 8.5 14.5 h -17 z",
                "M 12 10 v 4",
            ),
            listOf(12f, 16.5f),
        )
    }

    /** Live data. */
    val Gauge: ImageVector by lazy {
        icon(
            "Gauge",
            listOf(
                "M 4 16.5 a 8 8 0 0 1 16 0 z",
                "M 12 16.5 l 3.5 -5",
            ),
        )
    }

    /** Chart. */
    val Chart: ImageVector by lazy {
        icon(
            "Chart",
            listOf(
                "M 4.5 4.5 v 15 h 15 M 8 15 l 3.5 -4 3 2.5 4 -5.5",
            ),
        )
    }

    /** Previous session. */
    val History: ImageVector by lazy {
        icon(
            "History",
            listOf(
                "M 4.5 12 a 7.5 7.5 0 1 0 1.9 -5 M 5.5 3.5 v 4 h 4 M 12 8 v 4 l 2.5 1.5",
            ),
        )
    }

    /** Delete, clear. */
    val Delete: ImageVector by lazy {
        icon(
            "Delete",
            listOf(
                "M 7 7 h 10 l -0.8 12 a 1 1 0 0 1 -1 0.9 H 8.8 a 1 1 0 0 1 -1 -0.9 z",
                "M 5 7 h 14 M 9.5 7 V 4.5 h 5 V 7 M 10.5 10.5 v 6 M 13.5 10.5 v 6",
            ),
        )
    }

    /** Share, export. */
    val Share: ImageVector by lazy {
        icon(
            "Share",
            listOf(
                "M 12 15 V 4 M 8 8 l 4 -4 4 4 M 5 13 v 6 h 14 v -6",
            ),
        )
    }

    /** Test passed. */
    val CheckCircle: ImageVector by lazy {
        icon(
            "CheckCircle",
            listOf(
                "M 3.5 12 a 8.5 8.5 0 1 0 17 0 a 8.5 8.5 0 1 0 -17 0 Z",
                "M 8.3 12.3 l 2.6 2.6 4.8 -5.2",
            ),
        )
    }

    /** Test failed. */
    val ErrorCircle: ImageVector by lazy {
        icon(
            "ErrorCircle",
            listOf(
                "M 3.5 12 a 8.5 8.5 0 1 0 17 0 a 8.5 8.5 0 1 0 -17 0 Z",
                "M 12 7.8 v 4.7",
            ),
            listOf(12f, 16f),
        )
    }


    // ---- Settings and general ----

    /** Language. */
    val Language: ImageVector by lazy {
        icon(
            "Language",
            listOf(
                "M 3.5 12 a 8.5 8.5 0 1 0 17 0 a 8.5 8.5 0 1 0 -17 0 Z",
                "M 3.5 12 h 17 M 12 3.5 c 3 3 3 14 0 17 M 12 3.5 c -3 3 -3 14 0 17",
            ),
        )
    }

    /** Alerts. */
    val Bell: ImageVector by lazy {
        icon(
            "Bell",
            listOf(
                "M 6.5 16.5 V 11 a 5.5 5.5 0 0 1 11 0 v 5.5 l 1.5 2 h -14 z",
                "M 10.5 20.8 a 1.8 1.8 0 0 0 3 0",
            ),
        )
    }

    /** Warning beep volume. */
    val Volume: ImageVector by lazy {
        icon(
            "Volume",
            listOf(
                "M 4.5 9.5 H 8 l 4.5 -3.5 v 12 L 8 14.5 H 4.5 z",
                "M 15.5 9.5 a 3.5 3.5 0 0 1 0 5 M 18 7 a 7 7 0 0 1 0 10",
            ),
        )
    }

    /** Appearance. */
    val Theme: ImageVector by lazy {
        icon(
            "Theme",
            listOf(
                "M 3.5 12 a 8.5 8.5 0 1 0 17 0 a 8.5 8.5 0 1 0 -17 0 Z",
                "M 12 3.5 v 17",
            ),
        )
    }

    /** Info, about. */
    val Info: ImageVector by lazy {
        icon(
            "Info",
            listOf(
                "M 3.5 12 a 8.5 8.5 0 1 0 17 0 a 8.5 8.5 0 1 0 -17 0 Z",
                "M 12 11 v 5.5",
            ),
            listOf(12f, 7.8f),
        )
    }

    /** Help, inconclusive. */
    val Help: ImageVector by lazy {
        icon(
            "Help",
            listOf(
                "M 3.5 12 a 8.5 8.5 0 1 0 17 0 a 8.5 8.5 0 1 0 -17 0 Z",
                "M 9.7 9.5 a 2.4 2.4 0 1 1 3.3 2.3 c -0.7 0.4 -1 0.9 -1 1.7",
            ),
            listOf(12f, 16.5f),
        )
    }

    /** Demo mode. */
    val Demo: ImageVector by lazy {
        icon(
            "Demo",
            listOf(
                "M 3.5 12 a 8.5 8.5 0 1 0 17 0 a 8.5 8.5 0 1 0 -17 0 Z",
                "M 10 8.5 l 5.5 3.5 -5.5 3.5 z",
            ),
        )
    }

    /** Show password. */
    val Eye: ImageVector by lazy {
        icon(
            "Eye",
            listOf(
                "M 2.5 12 c 2.3 -4.3 5.5 -6.5 9.5 -6.5 s 7.2 2.2 9.5 6.5 c -2.3 4.3 -5.5 6.5 -9.5 6.5 S 4.8 16.3 2.5 12 z",
                "M 9.2 12 a 2.8 2.8 0 1 0 5.6 0 a 2.8 2.8 0 1 0 -5.6 0 Z",
            ),
        )
    }

    /** Hide password. */
    val EyeOff: ImageVector by lazy {
        icon(
            "EyeOff",
            listOf(
                "M 2.5 12 c 2.3 -4.3 5.5 -6.5 9.5 -6.5 s 7.2 2.2 9.5 6.5 c -2.3 4.3 -5.5 6.5 -9.5 6.5 S 4.8 16.3 2.5 12 z",
                "M 9.2 12 a 2.8 2.8 0 1 0 5.6 0 a 2.8 2.8 0 1 0 -5.6 0 Z",
                "M 4.5 4 l 15 16",
            ),
        )
    }

    /** Refresh. */
    val Refresh: ImageVector by lazy {
        icon(
            "Refresh",
            listOf(
                "M 19.5 12 a 7.5 7.5 0 1 1 -1.9 -5 M 18.5 3.5 v 4 h -4",
            ),
        )
    }

    /** Add. */
    val Add: ImageVector by lazy {
        icon(
            "Add",
            listOf(
                "M 12 5 v 14 M 5 12 h 14",
            ),
        )
    }

    /** Done. */
    val Check: ImageVector by lazy {
        icon(
            "Check",
            listOf(
                "M 5 12.5 l 4.5 4.5 L 19 7.5",
            ),
        )
    }

    /** Close, forget. */
    val Close: ImageVector by lazy {
        icon(
            "Close",
            listOf(
                "M 6 6 l 12 12 M 18 6 L 6 18",
            ),
        )
    }

    /** Next. */
    val ChevronRight: ImageVector by lazy {
        icon(
            "ChevronRight",
            listOf(
                "M 9.5 5.5 L 16 12 l -6.5 6.5",
            ),
        )
    }

    /** Collapse. */
    val ChevronUp: ImageVector by lazy {
        icon(
            "ChevronUp",
            listOf(
                "M 5.5 15 L 12 8.5 l 6.5 6.5",
            ),
        )
    }

    /** Expand. */
    val ChevronDown: ImageVector by lazy {
        icon(
            "ChevronDown",
            listOf(
                "M 5.5 9 L 12 15.5 18.5 9",
            ),
        )
    }

    /** Edit. */
    val Edit: ImageVector by lazy {
        icon(
            "Edit",
            listOf(
                "M 5 19 l 0.8 -3.8 L 16.5 4.5 l 3 3 L 8.8 18.2 z",
                "M 14.5 6.5 l 3 3",
            ),
        )
    }

    /** Location. */
    val Location: ImageVector by lazy {
        icon(
            "Location",
            listOf(
                "M 12 21 s -6.5 -6 -6.5 -11 a 6.5 6.5 0 0 1 13 0 c 0 5 -6.5 11 -6.5 11 z",
                "M 9.7 10 a 2.3 2.3 0 1 0 4.6 0 a 2.3 2.3 0 1 0 -4.6 0 Z",
            ),
        )
    }

    /** Smart home (Home Assistant): a house. */
    val Home: ImageVector by lazy {
        icon(
            "Home",
            listOf(
                "M 3.5 11 L 12 4 L 20.5 11",
                "M 6 9.2 V 20 h 12 V 9.2",
                "M 10 20 v -5 h 4 v 5",
            ),
        )
    }

    /** Google Home: a smart speaker. */
    val Speaker: ImageVector by lazy {
        icon(
            "Speaker",
            listOf(
                "M 8.5 3 h 7 a 2 2 0 0 1 2 2 v 14 a 2 2 0 0 1 -2 2 h -7 a 2 2 0 0 1 -2 -2 v -14 a 2 2 0 0 1 2 -2 z",
                "M 9.2 14.5 a 2.8 2.8 0 1 0 5.6 0 a 2.8 2.8 0 1 0 -5.6 0 Z",
            ),
            listOf(12f, 7f),
        )
    }

    /** How the app reaches the car (the route): a path that turns. */
    val Route: ImageVector by lazy {
        icon(
            "Route",
            listOf(
                "M 6 19 V 13 a 5 5 0 0 1 5 -5 h 7",
                "M 15.5 5.5 L 18 8 L 15.5 10.5",
            ),
            listOf(6f, 19f),
        )
    }
}
