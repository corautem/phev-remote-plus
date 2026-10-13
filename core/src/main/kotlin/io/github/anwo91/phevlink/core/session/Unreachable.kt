package io.github.anwo91.phevlink.core.session

/** Why the bridge didn't even try the car (said at once instead of timing out); the app shows them in its own language. */
object Unreachable {
    const val OFF_CAR_WIFI = "The bridge isn't on the car's Wi-Fi — the car is away, or its Wi-Fi is asleep"
    const val STEPPED_ASIDE = "The bridge has stepped off the car's Wi-Fi for a phone"
}
