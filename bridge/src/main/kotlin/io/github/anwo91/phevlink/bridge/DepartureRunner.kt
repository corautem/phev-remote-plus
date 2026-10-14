package io.github.anwo91.phevlink.bridge

import io.github.anwo91.phevlink.core.activity.Activity
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.core.json.Json
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.CommandResult
import io.github.anwo91.phevlink.core.planner.ClimatePlan
import io.github.anwo91.phevlink.core.planner.ClimatePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Runs the shared departures from the bridge, so the cabin is ready even when every phone is off:
 * 35 minutes before leaving it checks the weather (MET Norway) and decides mode and duration (the same
 * [ClimatePlanner] the app uses), then starts the climate so it finishes on time — but only while the bridge is on
 * the car's Wi-Fi (the car is home). When the car is away, a phone near it does the job, as before.
 * Every decision is logged.
 */
class DepartureRunner(
    private val store: SharedStore,
    private val log: DiagLog,
    private val carHome: () -> Boolean,
    private val execute: suspend (CarCommand) -> CommandResult,
    private val temperature: (Double, Double, ZonedDateTime) -> Double? = ::metNoTemperature,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(store.zone()) },
    /** Each departure's outcome, for the activity log. */
    private val onActivity: (Activity) -> Unit = {},
) {
    private val plans = HashMap<String, ClimatePlan?>()
    private val done = HashSet<String>()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            log.link("Departures: the bridge runs shared departures while the car is home (${store.departures().size} saved)")
            while (isActive) {
                runCatching { tick() }.onFailure { log.warn("Departures: ${it.message}") }
                delay(20_000)
            }
        }
    }

    /** One look at the clock: plan what's due within 35 minutes, start what's due now. */
    suspend fun tick() {
        val t = now()
        val upcoming = store.departures().mapNotNull { d -> d.nextAfter(t.minusMinutes(1))?.let { d to it } }
        for ((dep, at) in upcoming) {
            val key = dep.id + "@" + at.toEpochSecond()
            if (key in done || t.isBefore(at.minusMinutes(ClimatePlanner.LEAD_MINUTES))) continue
            val label = at.format(CLOCK)
            if (key !in plans) {
                val temp = if (dep.smart) store.location()?.let { (lat, lon) -> runCatching { temperature(lat, lon, at) }.getOrNull() } else null
                val plan = ClimatePlanner.decide(dep, temp)
                plans[key] = plan
                log.link("Departures: $label — " + (plan?.let { "${it.reason}; ${it.mode.label} ${it.duration.minutes} min" } ?: "mild (${temp?.toInt()}°C), nothing to do"))
                if (plan == null) {
                    done += key
                    store.recordDeparture(result(dep.id, at, "skipped", "mild weather"))
                    continue
                }
            }
            val plan = plans[key] ?: continue
            val startAt = ClimatePlanner.startTime(at, plan).minusMinutes(1)
            if (t.isBefore(startAt) || !t.isBefore(at)) continue
            done += key
            if (!carHome()) {
                log.link("Departures: $label — the car isn't within reach (the bridge isn't on its Wi-Fi); a phone near the car can start it")
                store.recordDeparture(result(dep.id, at, "away", "car not within reach", plan))
                continue
            }
            log.link("Departures: $label — starting ${plan.mode.label} for ${plan.duration.minutes} min")
            when (val r = execute(CarCommand.ClimateOn(plan.mode, plan.duration))) {
                is CommandResult.Success -> {
                    log.link("Departures: $label — climate started (${r.tookMs} ms)")
                    store.recordDeparture(result(dep.id, at, "started", "${plan.mode.label} ${plan.duration.minutes} min", plan))
                }
                is CommandResult.Failure -> {
                    log.warn("Departures: $label — the car didn't start the climate: ${r.reason}")
                    store.recordDeparture(result(dep.id, at, "failed", r.reason, plan))
                }
            }
        }
        // Forget what's long past.
        val cutoff = t.minusDays(1).toEpochSecond()
        done.removeAll { it.substringAfter('@').toLongOrNull()?.let { s -> s < cutoff } ?: true }
        plans.keys.removeAll { it.substringAfter('@').toLongOrNull()?.let { s -> s < cutoff } ?: true }
    }

    private fun result(id: String, at: ZonedDateTime, outcome: String, detail: String, plan: ClimatePlan? = null): Map<String, Any?> {
        onActivity(
            Activity(
                System.currentTimeMillis(), Activity.BRIDGE, "departure.$outcome",
                linkedMapOf("at" to at.format(HHMM), "mode" to plan?.mode?.name, "minutes" to plan?.duration?.minutes, "reason" to detail),
                ok = outcome != "failed", detail = detail.takeIf { outcome == "failed" },
            ),
        )
        return linkedMapOf(
            "id" to id, "atMs" to at.toInstant().toEpochMilli(), "outcome" to outcome, "detail" to detail,
            "doneAtMs" to System.currentTimeMillis(),
        )
    }

    companion object {
        private val CLOCK = DateTimeFormatter.ofPattern("EEE HH:mm")
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
        @Volatile private var cache: Triple<String, Long, List<Pair<Long, Double>>>? = null

        /** Outside temperature (°C) forecast nearest [at] from MET Norway (cached 30 min per place, as MET asks). */
        fun metNoTemperature(lat: Double, lon: Double, at: ZonedDateTime): Double? {
            val la = Math.round(lat * 10_000) / 10_000.0
            val lo = Math.round(lon * 10_000) / 10_000.0
            val place = "$la,$lo"
            val c = cache
            val series = if (c != null && c.first == place && System.currentTimeMillis() - c.second < 30 * 60_000) c.third else {
                val conn = URI("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$la&lon=$lo").toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "PHEVLink-bridge/${BridgeService.BUILD} github.com/Anwo91/phev-remote-plus")
                try {
                    if (conn.responseCode != 200) return null
                    val m = Json.parseObject(conn.inputStream.bufferedReader().use { it.readText() })
                    @Suppress("UNCHECKED_CAST")
                    val ts = ((m["properties"] as? Map<String, Any?>)?.get("timeseries") as? List<Map<String, Any?>>).orEmpty()
                    val list = ts.mapNotNull { e ->
                        val time = (e["time"] as? String)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return@mapNotNull null
                        @Suppress("UNCHECKED_CAST")
                        val details = ((((e["data"] as? Map<String, Any?>)?.get("instant") as? Map<String, Any?>)?.get("details")) as? Map<String, Any?>)
                        val temp = (details?.get("air_temperature") as? Number)?.toDouble() ?: return@mapNotNull null
                        time to temp
                    }
                    cache = Triple(place, System.currentTimeMillis(), list)
                    list
                } finally {
                    conn.disconnect()
                }
            }
            val target = at.toInstant().toEpochMilli()
            return series.minByOrNull { abs(it.first - target) }?.second
        }
    }
}
