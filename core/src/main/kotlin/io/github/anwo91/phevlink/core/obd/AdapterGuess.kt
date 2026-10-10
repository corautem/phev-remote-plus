package io.github.anwo91.phevlink.core.obd

import java.util.Locale

/**
 * Guesses from what a Bluetooth device tells about itself before connecting (name, Bluetooth class, BLE services
 * and manufacturer ids) whether it is an ELM327-style OBD adapter, so the device list can put adapters first and
 * hide phones, headphones and watches.
 *
 * Only "certainly not an adapter" evidence hides a device: an adapter's name is often generic ("OBDII", "BT04-A",
 * "HC-05", "SPP-CA"), so anything unclear stays visible.
 */
object AdapterGuess {

    enum class Rank {
        /** Name of a known adapter brand/type ("OBDII", "OBDLink", "Vgate", "V-LINK"…). */
        ADAPTER,
        /** Offers a BLE UART service that OBD adapters use, or a generic serial-module name. */
        LIKELY,
        /** Nothing either way. */
        UNKNOWN,
        /** A scan tool that only works with its maker's app (Autel "Maxi…", Launch…): it won't answer ELM327. */
        PROPRIETARY,
        /** A phone, computer, headphones, speaker, watch, keyboard, TV… — hidden unless the user asks for all. */
        NOT_ADAPTER,
    }

    data class Guess(val rank: Rank, val brand: String? = null)

    /** Bluetooth Classic major device classes (android.bluetooth.BluetoothClass.Device.Major). */
    object Major {
        const val MISC = 0x0000
        const val COMPUTER = 0x0100
        const val PHONE = 0x0200
        const val NETWORKING = 0x0300
        const val AUDIO_VIDEO = 0x0400
        const val PERIPHERAL = 0x0500
        const val IMAGING = 0x0600
        const val WEARABLE = 0x0700
        const val TOY = 0x0800
        const val HEALTH = 0x0900
        const val UNCATEGORIZED = 0x1F00
    }

    /** OBD adapters report "uncategorized" or "misc"; these classes are never one. */
    private val NOT_ADAPTER_MAJORS = setOf(
        Major.COMPUTER, Major.PHONE, Major.AUDIO_VIDEO, Major.PERIPHERAL, Major.IMAGING,
        Major.WEARABLE, Major.TOY, Major.HEALTH,
    )

    private val ADAPTER_NAMES = listOf(
        "OBD", "ELM", "VGATE", "ICAR", "V-LINK", "VLINK", "KONNWEI", "KW90", "KW9", "VEEPEAK", "VIECAR",
        "CARISTA", "LELINK", "SCAN TOOL", "SCANTOOL", "CAN OBD", "IOS-VLINK", "ANDROID-VLINK", "FIXD", "CARLY",
        "OBDLINK", "STN11", "STN22", "MX+", "KIWI", "AUTOPHIX", "ZURICH", "BAFX", "PANLONG", "NEXAS",
    )

    /** Names of the serial Bluetooth modules inside many no-name ELM327 clones. */
    private val SERIAL_MODULE_NAMES = listOf("HC-05", "HC-06", "HC05", "HC06", "BT04", "SPP", "LINVOR", "JDY-")

    /** Brands of scan tools with their own protocol (they connect, then stay silent to ELM327 commands). */
    private val PROPRIETARY = listOf(
        "AUTEL" to "Autel", "MAXI" to "Autel", "X431" to "Launch", "X-431" to "Launch", "DBSCAR" to "Launch",
        "GOLO" to "Launch", "LAUNCH" to "Launch", "THINKCAR" to "ThinkCar", "THINKDIAG" to "ThinkCar",
        "TOPDON" to "Topdon", "FOXWELL" to "Foxwell", "BLUEDRIVER" to "BlueDriver", "OBDELEVEN" to "OBDeleven",
    )

    private val NOT_ADAPTER_NAMES = listOf(
        "AIRPODS", "IPHONE", "IPAD", "MACBOOK", "IMAC", "APPLE WATCH", "GALAXY", "BUDS", "WATCH", " BAND", "MI BAND",
        "FITBIT", "GARMIN", "POLAR ", "AMAZFIT", "JBL", "BOSE", "SONY", "SENNHEISER", "JABRA", "BEATS", "MARSHALL",
        "SONOS", "ECHO", "[TV]", "SMART TV", "BRAVIA", "SPEAKER", "HEADSET", "HEADPHONE", "EARBUD", "KEYBOARD",
        "MOUSE", "XBOX", "PLAYSTATION", "DUALSENSE", "DUALSHOCK", "CONTROLLER", "PIXEL", "ONEPLUS", "XIAOMI",
        "REDMI", "HUAWEI", "LENOVO", "THINKPAD", "DESKTOP-", "LAPTOP-", "TILE", "CHROMECAST",
    )

    /** BLE UART services used by OBD adapters (also by other gadgets, hence only "likely"). */
    private val ADAPTER_SERVICES = setOf(
        "E7810A71-73AE-499D-8C15-FAA9AEF0C3F2", // Vgate iCar Pro / OBDLink CX family
        uuid16("FFF0"), uuid16("FFE0"), uuid16("18F0"),
        "6E400001-B5A3-F393-E0A9-E50E24DCCA9E", // Nordic UART
    )

    /** BLE services only consumer devices advertise: HID, heart rate, fitness, Fast Pair, exposure notifications, beacons. */
    private val NOT_ADAPTER_SERVICES = setOf(
        uuid16("1812"), uuid16("180D"), uuid16("1826"), uuid16("FE2C"), uuid16("FD6F"), uuid16("FEAA"), uuid16("FE9F"),
    )

    /** Bluetooth SIG company ids of phone, computer, audio and wearable makers. */
    private val NOT_ADAPTER_COMPANIES = setOf(
        0x004C, // Apple
        0x0006, // Microsoft
        0x0075, // Samsung
        0x00E0, // Google
        0x0087, // Garmin
        0x009E, // Bose
        0x012D, // Sony
        0x0057, // Harman (JBL)
        0x038F, // Xiaomi
        0x027D, // Huawei
    )

    private fun uuid16(short: String) = "0000$short-0000-1000-8000-00805F9B34FB"

    /**
     * @param majorClass Bluetooth Classic major device class, or null for BLE / unknown
     * @param serviceUuids advertised BLE service UUIDs (any case)
     * @param companyIds BLE manufacturer-data company ids
     */
    fun guess(
        name: String?,
        majorClass: Int? = null,
        serviceUuids: Collection<String> = emptyList(),
        companyIds: Collection<Int> = emptyList(),
    ): Guess {
        val n = name?.uppercase(Locale.ROOT).orEmpty()
        val services = serviceUuids.map { it.uppercase(Locale.ROOT) }.toSet()
        proprietaryBrand(n)?.let { return Guess(Rank.PROPRIETARY, it) }
        if (ADAPTER_NAMES.any { n.contains(it) }) return Guess(Rank.ADAPTER)
        if (majorClass != null && majorClass in NOT_ADAPTER_MAJORS) return Guess(Rank.NOT_ADAPTER)
        if (companyIds.any { it in NOT_ADAPTER_COMPANIES }) return Guess(Rank.NOT_ADAPTER)
        if (services.any { it in NOT_ADAPTER_SERVICES }) return Guess(Rank.NOT_ADAPTER)
        if (NOT_ADAPTER_NAMES.any { n.contains(it) }) return Guess(Rank.NOT_ADAPTER)
        if (services.any { it in ADAPTER_SERVICES } || SERIAL_MODULE_NAMES.any { n.startsWith(it) }) return Guess(Rank.LIKELY)
        return Guess(Rank.UNKNOWN)
    }

    /** Brand of a proprietary scan tool, from its Bluetooth name, or null. */
    fun proprietaryBrand(name: String?): String? {
        val n = name?.uppercase(Locale.ROOT) ?: return null
        // Autel VCIs advertise as "Maxi-<serial>"; elsewhere "MAXI" could be part of an unrelated word.
        return PROPRIETARY.firstOrNull { (key, _) -> if (key == "MAXI") n.startsWith("MAXI") else n.contains(key) }?.second
    }
}
