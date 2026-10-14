package io.github.anwo91.phevlink.core.session

/** Why the bridge didn't even try the car (said at once instead of timing out); the app shows them in its own language. */
object Unreachable {
    const val OFF_CAR_WIFI = "The bridge isn't on the car's Wi-Fi — the car is away, or its Wi-Fi is asleep"
    const val STEPPED_ASIDE = "The bridge has stepped off the car's Wi-Fi for a phone"

    /** A command waited its whole time without the car answering (may be followed by " (<last error>)"). */
    const val NOT_IN_TIME = "Could not reach the car in time"

    /** This phone searched for the car's Wi-Fi and didn't find it (the car isn't nearby, or its Wi-Fi is asleep). */
    const val NOT_NEARBY = "The car's Wi-Fi isn't nearby"
}
