package io.github.anwo91.phevlink.core.model

/**
 * One of the car's own customisable functions ("Vehicle settings" in the
 * official app: lights, locks, wipers, A/C behaviour…), as reported in
 * register 0x16.
 *
 * [mask]: bit k set = option k is available on this car.
 * [value]: index of the currently selected option (7 = car reports "unknown").
 */
data class CarSetting(val mask: Int, val value: Int) {
    fun hasOption(option: Int): Boolean = option in 0..6 && ((mask shr option) and 1) == 1
}

/** Catalogue entry: name and option labels exactly as the official app shows them (English). */
data class SettingDef(val id: Int, val name: String, val options: List<String>, val group: String)

/** A setting the UI should show, with the labels already resolved. */
data class VisibleSetting(
    val def: SettingDef,
    /** Currently selected option index. */
    val value: Int,
    val currentLabel: String,
    /** Selectable options (index to UI label), in option order. */
    val options: List<Pair<Int, String>>,
) {
    val id: Int get() = def.id
    val name: String get() = def.name
}

data class VisibleGroup(val title: String, val items: List<VisibleSetting>)

/**
 * The vehicle settings catalogue and the 0x16 wire format, reverse-engineered
 * from the official app (com.inventec.iMobile2 v3.1.6).
 *
 * Wire format of a 0x16 notification: `[S][A1][B1][A2][B2][A3][B3][00]`.
 * S is a status byte (the official app refuses to open its settings screen
 * when S is 1 or 3; normally 2). Each (A, B) pair describes one setting:
 *   id    = A and 63
 *   mask  = (B shr 1) and 127
 *   value = ((B and 1) shl 2) or ((A shr 6) and 3)
 * The car spreads all settings over several 0x16 notifications, so they must
 * be accumulated.
 *
 * Writes: register 0x0f `[id, value]` changes one setting, `[0xfe, 0x00]`
 * resets all to factory defaults; register 0x0e `[0x00]` then saves.
 */
object VehicleSettingsCatalog {
    const val MIN_ID = 1
    const val MAX_ID = 60
    /** Id written to 0x0f to reset every setting to its factory default. */
    const val RESET_ID = 0xfe
    /** Status byte the car normally sends. */
    const val STATUS_NORMAL = 2

    /** Number of options per id (index = id), from the official app. */
    private val OPTION_COUNTS = intArrayOf(
        0, 2, 2, 7, 4, 3, 4, 5, 4, 5, // 0..9
        2, 7, 6, 4, 2, 2, 5, 2, 0, 2, // 10..19
        5, 4, 4, 2, 4, 3, 6, 2, 2, 2, // 20..29
        0, 0, 3, 2, 2, 2, 3, 0, 0, 0, // 30..39
        0, 4, 5, 3, 0, 0, 0, 0, 0, 0, // 40..49
        2, 2, 0, 2, 3, 3, 2, 2, 2, 0, // 50..59
        2,                            // 60
    )

    /** Ids whose name/options are empty in this market's build of the official app: never shown. */
    val HIDDEN_IDS: Set<Int> = setOf(23, 25, 27, 41)

    const val GROUP_KEYLESS = "Keyless entry & operation"
    const val GROUP_WIPERS = "Wipers"
    const val GROUP_LIGHTS = "Lights"
    const val GROUP_WINDOWS = "Power windows"
    const val GROUP_TURN_SIGNALS = "Turn signals"
    const val GROUP_DOOR_LOCKS = "Door locks"
    const val GROUP_AIR_CON = "Air conditioning"
    const val GROUP_OTHER = "Other"

    /**
     * Display groups, in order. The official app shows either a "Keyless Entry
     * System" group [3,20,28,26,19] or a "Keyless Operation System" group
     * [3,20,28,26,21,32,19] depending on equipment; we show the union and let
     * availability hide what the car does not have.
     */
    val groups: List<Pair<String, List<Int>>> = listOf(
        GROUP_KEYLESS to listOf(3, 20, 28, 26, 21, 32, 19),
        GROUP_WIPERS to listOf(4, 5, 15, 6, 29),
        GROUP_LIGHTS to listOf(12, 9, 10, 42, 43, 13, 11, 7),
        GROUP_WINDOWS to listOf(24, 34, 35),
        GROUP_TURN_SIGNALS to listOf(1, 2, 14),
        GROUP_DOOR_LOCKS to listOf(22, 17, 16),
        GROUP_AIR_CON to listOf(50, 51, 53, 54, 55, 56, 57, 58, 60),
        GROUP_OTHER to listOf(8, 33, 36),
    )

    private val names: Map<Int, Pair<String, List<String>>> = mapOf(
        1 to ("Operation in Key Position" to listOf("Ignition Switch On or Accessory", "Ignition Switch On")),
        2 to ("Lane-change Signals (Flash Three Times with 1 Touch)" to listOf("Off", "On")),
        3 to ("Turn Signal Lights Answerback" to listOf(
            "Lock: once, unlock: twice", "Lock: once, unlock: off", "Lock: off, unlock: twice",
            "Lock: twice, unlock: once", "Lock: off, unlock: once", "Lock: twice, unlock: off", "Off",
        )),
        4 to ("Windshield Wipers Intermittent Operation" to listOf(
            "Fixed (4 seconds)", "Adjustable", "Adjustable & Speed Sensitive", "Adjustable & Rain Sensitive",
        )),
        5 to ("Wipers Linked to Washer" to listOf("Off", "On", "On with Delayed Finishing Wipe")),
        6 to ("Rear Wiper Intermittent Interval" to listOf("0 seconds", "4 seconds", "8 seconds", "16 seconds")),
        7 to ("Charging Lid Light Auto cut-out" to listOf("Off", "1 minute", "3 minutes", "5 minutes", "10 minutes")),
        8 to ("Auto Fold Mirrors" to listOf(
            "Off", "Open by Vehicle Speed", "Open/Close by IG On/Off and Driver Door Open/Close",
            "Open/Close by Keyless Entry/Operation Lock/Unlock",
        )),
        9 to ("Sensitivity for Auto Light" to listOf("Early", "Somewhat Early", "Normal", "Somewhat Late", "Late")),
        10 to ("Auto Light Linked to Wiper" to listOf("Off", "On")),
        11 to ("Duration Dome Light Remains On after Door is Closed" to listOf(
            "0 seconds (no timer function)", "7.5 seconds", "15 seconds", "30 seconds", "1 minute", "2 minutes", "3 minutes",
        )),
        // The official strings really do repeat "On" (regional variants); optionLabel() disambiguates.
        12 to ("Headlight Auto-cutout Function" to listOf(
            "Off", "Tail Lamp/Head Lamp: Cutout always", "On", "On",
            "Tail Lamp: Cutout once / Head Lamp: Cutout always", "On",
        )),
        13 to ("Interior Light Auto-cutout Time" to listOf("Off", "3 minutes", "30 minutes", "60 minutes")),
        14 to ("Activation Time for Lane-change Signals" to listOf("Short", "Long")),
        15 to ("Comfort Washer" to listOf("Off", "On")),
        16 to ("Automatic Unlock" to listOf(
            "Off", "When Shifted to Park", "When Shifted to Park with Windows Unlocked",
            "When Ignition turned to Lock", "When Ignition turned to Lock with Windows Unlocked",
        )),
        17 to ("Unlock Operation" to listOf("All doors", "Only Driver's door")),
        19 to ("The Number of Lock Button Pushes for Dead Lock" to listOf("2 times", "1 time")),
        20 to ("Horn Answerback Sounds at Keyless Entry Lock" to listOf(
            "Off", "One Button Push", "One Button Push at Daytime", "Two Button Pushes Sounds", "Two Button Pushes during Daytime",
        )),
        21 to ("Keyless Operation Answerback Beep Sounds" to listOf(
            "All system off", "Sound at Keyless Operation", "Sound at Keyless Entry", "All system on",
        )),
        22 to ("Automatic Relocking after Unlocked by Remote" to listOf("30 seconds", "1 minute", "2 minutes", "3 minutes")),
        24 to ("Timer Function after Ignition Switch Off" to listOf("0 seconds", "30 seconds", "3 minutes", "10 minutes")),
        26 to ("Multi Mode Keyless Entry System" to listOf(
            "Off", "Power Window & Door Mirror: Open & Close", "Power Window: Open & Close",
            "Power Window: Close / Door Mirror: Open & Close", "Door Mirror: Open & Close", "Power Window: Close",
        )),
        28 to ("Duration of Horn Answerback Sounds" to listOf("Short", "Long")),
        29 to ("Rear Wiper Activated when in Reverse" to listOf("When Rear Wiper is ON", "When Front or Rear Wiper is ON")),
        32 to ("Time for Remote Unlock Inactivation after Locking" to listOf("Off", "3 seconds", "5 seconds")),
        33 to ("Horn Answerback Sounds at Remote Engine Start" to listOf("Off", "On")),
        34 to ("Open Operation after Ignition Switch Off" to listOf("On", "Off")),
        35 to ("Power Window Position Prohibited by Lock-switch" to listOf("All doors", "Only Driver's door")),
        36 to ("Auto Cut of ACC Power" to listOf("Off", "30 minutes", "60 minutes")),
        42 to ("Headlights ON when exiting Vehicle" to listOf("Off", "15 seconds", "30 seconds", "1 minute", "3 minutes")),
        43 to ("Exterior Lights ON with Remote Unlock" to listOf("Off", "Parking lights", "Head lights")),
        50 to ("Recirculation Control" to listOf("Manual", "Automatic")),
        51 to ("A/C Switch Control" to listOf("Manual", "Automatic")),
        53 to ("Eco Mode" to listOf("Eco", "Comfort")),
        54 to ("Foot / Defroster Airflow Ratio" to listOf("Normal", "More Airflow to Foot", "More Airflow to Defroster")),
        55 to ("Face / Foot Airflow Ratio" to listOf("Normal", "More Airflow to Face", "More Airflow to Foot")),
        56 to ("Automatic Rear Defrost at Start" to listOf("Off", "On")),
        57 to ("Start A/C or Heater at Door Unlock" to listOf("Off", "On")),
        58 to ("In Foot Mode, Slight Airflow through Defroster" to listOf("Off", "On")),
        60 to ("Fast Idle for Higher Heater Performance when Stopped" to listOf("Off", "On")),
    )

    private val defs: Map<Int, SettingDef> = run {
        val groupOf = HashMap<Int, String>()
        for ((title, ids) in groups) for (id in ids) groupOf[id] = title
        names.mapValues { (id, n) -> SettingDef(id, n.first, n.second, groupOf[id] ?: GROUP_OTHER) }
    }

    /** All named (showable) settings, by id. */
    val all: List<SettingDef> get() = defs.values.sortedBy { it.id }

    fun def(id: Int): SettingDef? = defs[id]

    /** Number of options the official app knows for [id] (0 = unused id). */
    fun optionCount(id: Int): Int = OPTION_COUNTS.getOrElse(id) { 0 }

    /** The official app refuses to change settings while the car reports status 1 or 3. */
    fun statusAllowsChanges(status: Int?): Boolean = status != 1 && status != 3

    /**
     * Whether the official app would show this setting: the id is named and not
     * hidden, the car offers at least one option, the current value is a known
     * option and the mask fits in the option count.
     */
    fun available(id: Int, setting: CarSetting?): Boolean {
        if (setting == null || id in HIDDEN_IDS) return false
        val def = defs[id] ?: return false
        if (def.name.isEmpty()) return false
        val n = optionCount(id)
        if (n <= 0) return false
        return setting.mask != 0 && setting.value in 0 until n && setting.mask <= (1 shl n) - 1
    }

    /** Option indices the car offers for [id] (bits set in the mask), or empty if not [available]. */
    fun availableOptions(id: Int, setting: CarSetting?): List<Int> {
        if (setting == null || !available(id, setting)) return emptyList()
        return (0 until optionCount(id)).filter { setting.hasOption(it) }
    }

    /**
     * UI label for [option] of [id]. When several of the options in play share
     * the same official label (id 12 repeats "On"), a stable suffix is added:
     * "On (type 2)" is always option 3 of id 12, whatever the car offers. With a
     * [setting], only the options this car offers are considered for collisions;
     * without one, all of the catalogue's options are.
     */
    fun optionLabel(id: Int, option: Int, setting: CarSetting? = null): String {
        val def = defs[id]
        val base = def?.options?.getOrNull(option)?.takeIf { it.isNotBlank() } ?: return "Option ${option + 1}"
        val inPlay = if (setting != null && available(id, setting)) availableOptions(id, setting)
        else def.options.indices.toList()
        val collides = inPlay.count { it != option && def.options.getOrNull(it) == base } > 0
        if (!collides) return base
        val ordinal = def.options.withIndex().filter { it.value == base }.indexOfFirst { it.index == option } + 1
        return "$base (type $ordinal)"
    }

    /** "Name: label" for logs, falling back to raw numbers for unknown ids. */
    fun describe(id: Int, value: Int): String {
        val def = defs[id] ?: return "setting #$id = $value"
        return "${def.name}: ${optionLabel(id, value)}"
    }

    /** Settings to show, grouped and in display order; groups with nothing available are left out. */
    fun visibleGroups(settings: Map<Int, CarSetting>): List<VisibleGroup> = groups.mapNotNull { (title, ids) ->
        val items = ids.mapNotNull { id ->
            val s = settings[id]
            if (s == null || !available(id, s)) return@mapNotNull null
            val def = defs.getValue(id)
            VisibleSetting(
                def = def,
                value = s.value,
                currentLabel = optionLabel(id, s.value, s),
                options = availableOptions(id, s).map { it to optionLabel(id, it, s) },
            )
        }
        if (items.isEmpty()) null else VisibleGroup(title, items)
    }

    // ------------------------------------------------------------- wire format

    /**
     * Decode one (A, B) pair of a 0x16 notification. Returns null for padding
     * (id 0), out-of-range ids and values the mask does not allow (the official
     * app ignores those too).
     */
    fun decodePair(a: Int, b: Int): Pair<Int, CarSetting>? {
        val id = a and 63
        val mask = (b shr 1) and 127
        val value = ((b and 1) shl 2) or ((a shr 6) and 3)
        if (id !in MIN_ID..MAX_ID) return null
        if (!(mask == 0 || value == 7 || ((mask shr value) and 1) == 1)) return null
        return id to CarSetting(mask, value)
    }

    /** Inverse of [decodePair]: the (A, B) bytes for a setting (used by the emulator and tests). */
    fun encodePair(id: Int, setting: CarSetting): Pair<Int, Int> {
        val a = (id and 63) or ((setting.value and 3) shl 6)
        val b = ((setting.mask and 127) shl 1) or ((setting.value shr 2) and 1)
        return a to b
    }

    /**
     * Build 0x16 payloads the way the car sends them: 3 pairs per notification,
     * `[status][A1][B1][A2][B2][A3][B3][00]`, unused pairs padded with 00 00.
     */
    fun encodeNotifications(settings: List<Pair<Int, CarSetting>>, status: Int = STATUS_NORMAL): List<ByteArray> =
        settings.chunked(3).map { chunk ->
            val out = ByteArray(8)
            out[0] = status.toByte()
            chunk.forEachIndexed { i, (id, s) ->
                val (a, b) = encodePair(id, s)
                out[1 + 2 * i] = a.toByte()
                out[2 + 2 * i] = b.toByte()
            }
            out
        }
}
