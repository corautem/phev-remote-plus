package io.github.anwo91.phevlink.core.obd.dtc

/**
 * Reusable plain-English sentences for DTC explanations. Kept factual and general:
 * no part numbers, prices, bulletins or recall claims.
 */
internal object DtcText {

    // ---- safety -------------------------------------------------------------------------

    const val HV_SAFETY =
        "Safety first: the orange cables and the high-voltage parts (drive battery, inverters, motors, " +
            "on-board charger, DC/DC converter) carry dangerous voltage, even with the car switched off. " +
            "Do not open, unplug or measure them yourself; this work is only for technicians trained in high-voltage systems."

    const val SRS_SAFETY =
        "Safety first: never probe airbag or seatbelt-pretensioner connectors with a test lamp or multimeter, " +
            "and never unplug them with the 12 V battery connected. An accidental deployment can cause serious injury."

    // ---- 12 V battery (the most common hidden cause on the Outlander PHEV) ------------------

    const val CAUSE_12V =
        "Weak or ageing 12 V auxiliary battery. On the Outlander PHEV it is charged only by the DC/DC converter " +
            "(mainly while the car is in READY or charging), and low voltage makes control modules behave oddly or drop off the network"

    const val CHECK_12V =
        "Test the 12 V auxiliary battery: after the car has stood for a few hours it should read about 12.6 V or more; " +
            "with the car in READY the voltage should rise to roughly 13–14.5 V. A battery tester at a workshop or parts " +
            "shop gives a more reliable answer than voltage alone. Also check that the terminals and ground connections are clean and tight."

    const val CHECK_MANY_CODES =
        "If several unrelated codes appeared at the same time, test the 12 V auxiliary battery before anything else"

    const val CHECK_DONGLE =
        "Unplug the OBD dongle (and any other aftermarket device on the diagnostic socket) when you are not using it: " +
            "some adapters keep the network awake or drain the 12 V battery while the car is parked"

    // ---- tools and follow-up ------------------------------------------------------------

    const val CHECK_DEALER_TOOL =
        "Have a Mitsubishi dealer or a workshop with a Mitsubishi-compatible diagnostic tool (MUT-III or equivalent) " +
            "read all control units; a generic OBD reader only sees part of the system"

    const val CHECK_CLEAR_RETEST =
        "After a repair, clear the code and drive normally for a few days; if it does not return, the fix worked"

    const val CHECK_CLEAR_SEE =
        "Clear the code and see whether it comes back: a code that returns quickly points to a real, current fault"

    const val CHECK_FREEZE_FRAME =
        "Note when the code appeared and, if your scan tool shows it, the freeze-frame data (speed, temperatures, load) " +
            "saved with the code: it shows the conditions when the fault happened"

    const val CHECK_OIL =
        "Check the engine oil level and condition on the dipstick; top up or change it if it is low, old or sludgy " +
            "(on a plug-in hybrid the engine runs less, but oil still ages with time)"

    const val CHECK_CHARGE_MODE =
        "To test the engine for longer, keep it running with the Charge (or Save) function so it reaches full working temperature"

    const val CHECK_FRESH_FUEL =
        "If the engine rarely runs, the petrol may be months old: fill up with fresh petrol from a busy station and let the engine run regularly"

    const val CHECK_PHEV_CONVENTIONAL_GEARBOX =
        "The Outlander PHEV has no conventional multi-speed gearbox (it uses a fixed-ratio transaxle, with a clutch that " +
            "lets the engine drive the front wheels directly at higher speeds), so many gearbox codes do not apply. " +
            "If this code appears, have it confirmed with a Mitsubishi-compatible tool."

    // ---- drive advice -------------------------------------------------------------------

    const val ADVICE_INFO = "Yes. This does not affect how the car drives."

    const val ADVICE_CHECK_SOON =
        "Yes, you can keep driving normally. Have it checked within the next few weeks, and sooner if more warning " +
            "lights come on or the car starts to behave differently."

    const val ADVICE_CHECK_NOW =
        "Short, gentle trips are usually possible, but get it checked within days. Avoid long trips, towing and hard " +
            "driving until it is fixed. If the car shows a message telling you to stop, or a warning light flashes, " +
            "stop as soon as it is safe."

    const val ADVICE_STOP =
        "No. Stop as soon as it is safe, switch off and do not drive on. Arrange for the car to be checked or recovered."

    const val ADVICE_MISFIRE =
        "If the check-engine light is flashing, or the engine shakes or loses power, stop as soon as it is safe: unburnt " +
            "fuel can overheat and destroy the catalytic converter. If the light is steady and the engine runs smoothly, " +
            "short trips are possible, but get it checked within days. Gentle driving on battery power keeps the engine " +
            "off most of the time, but the car will still start the engine when it needs to."

    const val ADVICE_HV =
        "The car often still drives, sometimes with reduced power or electric range, but get it checked within days. " +
            "Follow any message on the dashboard: if it tells you to stop the vehicle, stop safely and do not restart. " +
            "Do not try to repair high-voltage parts yourself."

    const val ADVICE_NETWORK =
        "Usually yes if the car drives normally and no other warnings are shown. If warning lights or messages appear " +
            "(for example 'Check EV system'), the car may switch functions off; have it checked within days."

    const val ADVICE_UNKNOWN =
        "This app cannot tell how serious this code is. If the car drives normally and shows no warning lights or " +
            "messages, it is usually fine to keep driving while you get the code identified. If a warning message " +
            "appears (such as 'Check EV system'), the check-engine light flashes, or the car behaves differently, " +
            "follow the owner's manual and have it checked straight away."

    fun adviceFor(severity: DtcSeverity): String = when (severity) {
        DtcSeverity.INFO -> ADVICE_INFO
        DtcSeverity.CHECK_SOON -> ADVICE_CHECK_SOON
        DtcSeverity.CHECK_NOW -> ADVICE_CHECK_NOW
        DtcSeverity.STOP -> ADVICE_STOP
    }
}
