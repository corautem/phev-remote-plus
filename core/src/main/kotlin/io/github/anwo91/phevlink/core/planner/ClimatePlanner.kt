package io.github.anwo91.phevlink.core.planner

import io.github.anwo91.phevlink.core.model.ClimateDuration
import io.github.anwo91.phevlink.core.model.ClimateMode
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** A recurring departure the car should be ready for. */
data class Departure(
    val id: String,
    val time: LocalTime,
    val days: Set<DayOfWeek>,
    val enabled: Boolean = true,
    /** Pick mode and duration from the weather. When false, [mode]/[duration] are always used. */
    val smart: Boolean = true,
    val mode: ClimateMode = ClimateMode.HEAT,
    val duration: ClimateDuration = ClimateDuration.MIN_20,
    val label: String = "",
) {
    /** Next departure at or after [from]. */
    fun nextAfter(from: ZonedDateTime): ZonedDateTime? {
        if (!enabled || days.isEmpty()) return null
        for (d in 0..7) {
            val day = from.toLocalDate().plusDays(d.toLong())
            if (day.dayOfWeek !in days) continue
            val at = ZonedDateTime.of(day, time, from.zone)
            if (!at.isBefore(from)) return at
        }
        return null
    }
}

data class PlannerPrefs(
    /** At or below this the windscreen likely needs defrosting. */
    val frostAtOrBelowC: Double = 2.0,
    /** Below this, warm the cabin. */
    val heatBelowC: Double = 12.0,
    /** At or above this, cool the cabin. */
    val coolAtOrAboveC: Double = 24.0,
    /** Colder than this gets 30 min, colder than [mediumBelowC] gets 20 min. */
    val longBelowC: Double = -10.0,
    val mediumBelowC: Double = -3.0,
)

data class ClimatePlan(
    val mode: ClimateMode,
    val duration: ClimateDuration,
    val reason: String,
)

object ClimatePlanner {
    /** Weather is checked this long before departure, so a 30-minute run can still start in time. */
    const val LEAD_MINUTES = 35L

    fun nextDeparture(departures: List<Departure>, now: ZonedDateTime): Pair<Departure, ZonedDateTime>? =
        departures.mapNotNull { d -> d.nextAfter(now)?.let { d to it } }.minByOrNull { it.second }

    /**
     * What to run for a departure given the outside temperature (°C, null if unknown).
     * Returns null when the weather is mild enough that preconditioning is pointless.
     */
    fun decide(departure: Departure, tempC: Double?, prefs: PlannerPrefs = PlannerPrefs()): ClimatePlan? {
        if (!departure.smart) {
            return ClimatePlan(departure.mode, departure.duration, "Fixed: ${departure.mode.label}, ${departure.duration.minutes} min")
        }
        if (tempC == null) {
            return ClimatePlan(departure.mode, departure.duration, "Temperature unknown — using your default")
        }
        val t = "${tempC.roundToInt()}°C"
        return when {
            tempC <= prefs.frostAtOrBelowC -> ClimatePlan(
                ClimateMode.WINDSCREEN, coldDuration(tempC, prefs), "$t, likely frost — defrost the windscreen"
            )
            tempC < prefs.heatBelowC -> ClimatePlan(
                ClimateMode.HEAT, if (tempC < 6) ClimateDuration.MIN_20 else ClimateDuration.MIN_10, "$t — warm the cabin"
            )
            tempC >= prefs.coolAtOrAboveC -> ClimatePlan(
                ClimateMode.COOL, if (tempC >= 28) ClimateDuration.MIN_20 else ClimateDuration.MIN_10, "$t — cool the cabin"
            )
            else -> null
        }
    }

    private fun coldDuration(t: Double, p: PlannerPrefs) = when {
        t < p.longBelowC -> ClimateDuration.MIN_30
        t < p.mediumBelowC -> ClimateDuration.MIN_20
        else -> ClimateDuration.MIN_10
    }

    /** When to switch the climate on so it finishes as the person walks out. */
    fun startTime(departureAt: ZonedDateTime, plan: ClimatePlan): ZonedDateTime =
        departureAt.minusMinutes(plan.duration.minutes.toLong())
}
