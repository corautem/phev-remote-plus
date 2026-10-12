package io.github.anwo91.phevlink.core.protocol

/**
 * Register numbers. "Read" registers are notified by the car (0x6f), "write"
 * registers are set by us (0xf6). The same number can mean different things
 * in each direction, and some differ between model years.
 *
 * Sources: phev2mqtt protocol notes (MY18 sniffing, MY14 contributions) and
 * phevcore register names (KO_WF_*), cross-checked where both exist.
 */
object Reg {
    // --- car -> client
    const val READ_CHARGE_GUN = 0x02          // MY14: KO_WF_CHG_GUN_STATUS_EVR ([1]==1 drive-battery heater on), MY18: "battery warning"
    const val READ_REMOTE_SECURITY = 0x03
    const val READ_CHARGE_TIMER = 0x04
    const val READ_CLIMATE_TIMER = 0x05
    const val READ_QUICK_CHARGE_C = 0x0c      // quick-charge phase; ==5 quick charging (with 0x0d)
    const val READ_QUICK_CHARGE_D = 0x0d      // ==3 quick charging (CHAdeMO)
    const val READ_FULL_CHARGE_DAYS = 0x0e    // weekday bitmask: "charge to full" days (bit d = day d, Sunday 0)
    const val READ_THEFT_EVENT = 0x14         // [yy mm dd hh mi ss][type]: security-alarm event
    const val READ_CHARGE_TIMER_PROG = 0x18   // 16 bytes: per-day charge window (start/end hour+min), see ScheduleDecoder
    const val READ_CLIMATE_TIMER_PROG = 0x19  // 9 bytes: [state][per-day start hour][per-day start min]
    const val READ_CHARGE_TIMER_STATE_B = 0x1b // low nibble: 1 = suspended (charge now), >=2 = timer on
    const val READ_CHARGE_PRESETS = 0x20      // MY14, 10 bytes: per-day timer choice + Timer 1–3 windows, see ChargeTimerCodec
    const val READ_CLIMATE_PRESETS = 0x22     // MY14, 6 bytes: climate timer presets
    /** MY18+ only (the official app's register table for 2014–2017 cars has no entry): "Always Communicate" 1/0. */
    const val READ_WIFI_ALWAYS_ON_MY18 = 0x0b
    const val READ_PRE_AC_STATE = 0x10        // 0=off 2=running 3=cancelled (door/low battery)
    const val READ_CAR_CLOCK = 0x12           // [yy mm dd hh mi ss dow]
    const val READ_VIN = 0x15                 // [?][VIN x17][?][registrations]
    const val READ_SETTINGS = 0x16            // [status][A1][B1][A2][B2][A3][B3][00], see VehicleSettingsCatalog
    const val READ_CHARGE_TIMER_STATE = 0x17
    const val READ_IGNITION_AC = 0x1a         // [ignition][ac operating]...
    const val READ_AC_MODE = 0x1c             // low nibble mode, high nibble duration
    const val READ_BATTERY = 0x1d             // [level %][?][parking lights][?]
    const val READ_CHARGE_PLUG = 0x1e
    const val READ_CHARGE_STATUS = 0x1f       // [charging][minutes lo][minutes hi]
    const val READ_LIGHTS = 0x23              // [..][..][..][hazard][interior]
    const val READ_DOORS = 0x24               // [lock][..][..][driver][passenger][rearR][rearL][boot][bonnet][headlights]
    const val READ_SSID = 0x28
    const val READ_STEERING_SIDE = 0x29       // [keyless group][1 = left-hand drive, 2 = right-hand drive]
    const val READ_REGISTRATION_EVENT = 0x2a
    const val READ_NAV_IN_USE = 0x26          // ==1 while the car's own screen is being used
    const val READ_EV_ECU_ERROR = 0x2c        // ==1 EV-ECU communication error
    const val READ_START_AA = 0xaa
    const val READ_ECU_VERSION = 0xc0

    // --- client -> car
    const val WRITE_CHARGE_TIMER_MY14 = 0x01  // 28 bytes: the whole charge timer, see ChargeTimerCodec
    const val WRITE_AC_SCHEDULE_MY14 = 0x02   // 15 bytes, byte 6 = mode | duration<<4
    const val WRITE_AC_SWITCH_MY14 = 0x04     // 0x02 = on, 0x01 = off
    const val WRITE_CLOCK_SYNC = 0x05
    const val WRITE_REQUEST_UPDATE = 0x06     // 0x03 = send all registers
    const val WRITE_HEADLIGHTS = 0x0a         // 0x01 on, 0x02 off
    const val WRITE_PARKING_LIGHTS = 0x0b     // 0x01 on, 0x02 off
    const val WRITE_SAVE_SETTINGS = 0x0e      // [0x00] after changing vehicle settings
    const val WRITE_SETTING = 0x0f            // [id, value] change one setting, [0xfe, 0x00] factory reset
    const val WRITE_REGISTER_CLIENT = 0x10    // 0x01 while car is in registration mode
    const val WRITE_ACK_AC_TERMINATION = 0x13 // 0x01
    const val WRITE_UNREGISTER_CLIENT = 0x15  // 0x01
    const val WRITE_CHARGE_TIMER_CANCEL = 0x17
    const val WRITE_AC_MY18 = 0x1b            // [state][mode][duration][delay]
    /** Official app: Settings → Wireless LAN Communication Settings → "Always Communicate" (1 = on, 0 = off). */
    const val WRITE_WIFI_ALWAYS_ON = 0x18

    private val readNames = mapOf(
        0x01 to "connect info",
        READ_CHARGE_GUN to "charge gun / battery warning",
        READ_REMOTE_SECURITY to "remote security info",
        READ_CHARGE_TIMER to "charge timer",
        READ_CLIMATE_TIMER to "climate timer",
        0x06 to "unknown 0x06",
        READ_QUICK_CHARGE_C to "quick-charge phase",
        READ_QUICK_CHARGE_D to "quick-charge state",
        READ_FULL_CHARGE_DAYS to "full-charge days",
        READ_THEFT_EVENT to "security-alarm event",
        READ_CHARGE_TIMER_PROG to "charge timer schedule",
        READ_CLIMATE_TIMER_PROG to "climate timer schedule",
        READ_CHARGE_TIMER_STATE_B to "charge timer on/off",
        READ_CHARGE_PRESETS to "charge timer presets",
        READ_CLIMATE_PRESETS to "climate timer presets",
        READ_WIFI_ALWAYS_ON_MY18 to "Wi-Fi always on (MY18)",
        READ_PRE_AC_STATE to "climate state",
        READ_CAR_CLOCK to "car clock",
        READ_VIN to "VIN / registrations",
        READ_SETTINGS to "car settings",
        READ_CHARGE_TIMER_STATE to "charge timer state",
        READ_IGNITION_AC to "ignition / AC running",
        READ_AC_MODE to "climate mode",
        READ_BATTERY to "battery / parking lights",
        READ_CHARGE_PLUG to "charge plug",
        READ_CHARGE_STATUS to "charging",
        READ_LIGHTS to "hazard / interior lights",
        READ_DOORS to "doors / lock / headlights",
        READ_SSID to "Wi-Fi SSID",
        READ_NAV_IN_USE to "car screen in use",
        READ_STEERING_SIDE to "keyless group / steering side",
        READ_REGISTRATION_EVENT to "registration event",
        READ_EV_ECU_ERROR to "EV-ECU error",
        READ_START_AA to "start AA",
        READ_ECU_VERSION to "ECU version",
    )

    private val writeNames = mapOf(
        WRITE_CHARGE_TIMER_MY14 to "charge timer (MY14)",
        WRITE_AC_SCHEDULE_MY14 to "climate mode (MY14)",
        WRITE_AC_SWITCH_MY14 to "climate on/off (MY14)",
        WRITE_CLOCK_SYNC to "clock sync",
        WRITE_REQUEST_UPDATE to "request update",
        WRITE_HEADLIGHTS to "headlights",
        WRITE_PARKING_LIGHTS to "parking lights",
        WRITE_REGISTER_CLIENT to "register phone",
        WRITE_ACK_AC_TERMINATION to "ack climate cancel",
        WRITE_UNREGISTER_CLIENT to "unregister phone",
        WRITE_CHARGE_TIMER_CANCEL to "charge timer override",
        WRITE_AC_MY18 to "climate (MY18)",
        WRITE_WIFI_ALWAYS_ON to "Wi-Fi always on",
        WRITE_SAVE_SETTINGS to "save settings",
        WRITE_SETTING to "update setting",
    )

    fun readName(reg: Int): String = readNames[reg] ?: "reg 0x%02x".format(reg)
    fun writeName(reg: Int): String = writeNames[reg] ?: "reg 0x%02x".format(reg)
}

enum class ModelYear(val label: String) {
    UNKNOWN("unknown"),
    MY14("2014–2017"),
    MY18("2018–2021"),
    MY24("2024+");

    companion object {
        fun fromStartType(type: Int): ModelYear = when (type) {
            PacketType.START_REQ_MY14 -> MY14
            PacketType.START_REQ_MY18 -> MY18
            PacketType.START_REQ_MY24 -> MY24
            else -> UNKNOWN
        }
    }
}
