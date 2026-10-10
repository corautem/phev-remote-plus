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
    const val READ_CHARGE_GUN = 0x02          // MY14: KO_WF_CHG_GUN_STATUS_EVR, MY18: "battery warning"
    const val READ_REMOTE_SECURITY = 0x03
    const val READ_CHARGE_TIMER = 0x04
    const val READ_CLIMATE_TIMER = 0x05
    const val READ_PRE_AC_STATE = 0x10        // 0=off 2=running 3=cancelled (door/low battery)
    const val READ_CAR_CLOCK = 0x12           // [yy mm dd hh mi ss dow]
    const val READ_VIN = 0x15                 // [?][VIN x17][?][registrations]
    const val READ_SETTINGS = 0x16
    const val READ_CHARGE_TIMER_STATE = 0x17
    const val READ_IGNITION_AC = 0x1a         // [ignition][ac operating]...
    const val READ_AC_MODE = 0x1c             // low nibble mode, high nibble duration
    const val READ_BATTERY = 0x1d             // [level %][?][parking lights][?]
    const val READ_CHARGE_PLUG = 0x1e
    const val READ_CHARGE_STATUS = 0x1f       // [charging][minutes lo][minutes hi]
    const val READ_LIGHTS = 0x23              // [..][..][..][hazard][interior]
    const val READ_DOORS = 0x24               // [lock][..][..][driver][passenger][rearR][rearL][boot][bonnet][headlights]
    const val READ_SSID = 0x28
    const val READ_REGISTRATION_EVENT = 0x2a
    const val READ_START_AA = 0xaa
    const val READ_ECU_VERSION = 0xc0

    // --- client -> car
    const val WRITE_AC_SCHEDULE_MY14 = 0x02   // 15 bytes, byte 6 = mode | duration<<4
    const val WRITE_AC_SWITCH_MY14 = 0x04     // 0x02 = on, 0x01 = off
    const val WRITE_CLOCK_SYNC = 0x05
    const val WRITE_REQUEST_UPDATE = 0x06     // 0x03 = send all registers
    const val WRITE_HEADLIGHTS = 0x0a         // 0x01 on, 0x02 off
    const val WRITE_PARKING_LIGHTS = 0x0b     // 0x01 on, 0x02 off
    const val WRITE_REGISTER_CLIENT = 0x10    // 0x01 while car is in registration mode
    const val WRITE_ACK_AC_TERMINATION = 0x13 // 0x01
    const val WRITE_UNREGISTER_CLIENT = 0x15  // 0x01
    const val WRITE_CHARGE_TIMER_CANCEL = 0x17
    const val WRITE_AC_MY18 = 0x1b            // [state][mode][duration][delay]

    private val readNames = mapOf(
        0x01 to "connect info",
        READ_CHARGE_GUN to "charge gun / battery warning",
        READ_REMOTE_SECURITY to "remote security info",
        READ_CHARGE_TIMER to "charge timer",
        READ_CLIMATE_TIMER to "climate timer",
        0x06 to "unknown 0x06",
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
        READ_REGISTRATION_EVENT to "registration event",
        READ_START_AA to "start AA",
        READ_ECU_VERSION to "ECU version",
    )

    private val writeNames = mapOf(
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
        0x0e to "save settings",
        0x0f to "update setting",
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
