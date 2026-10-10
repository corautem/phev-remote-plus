package io.github.anwo91.phevlink.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import io.github.anwo91.phevlink.core.diag.DiagLog
import io.github.anwo91.phevlink.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Outside temperature from MET Norway's free Locationforecast API
 * (https://api.met.no/weatherapi/locationforecast/2.0/documentation).
 * Results are cached for 30 minutes per location, as MET asks.
 */
object MetNo {
    private const val UA = "PHEVLink/1.0 github.com/Anwo91/phev-remote-plus"
    private data class Cached(val lat: Double, val lon: Double, val atMs: Long, val series: List<Pair<Long, Double>>)
    @Volatile private var cache: Cached? = null

    /** Temperature (°C) forecast closest to [at], or null if unknown/unavailable. */
    suspend fun temperatureNear(context: Context, s: AppSettings, at: ZonedDateTime, log: DiagLog): Double? {
        val (lat, lon) = location(context, s) ?: run {
            log.add(io.github.anwo91.phevlink.core.diag.LogKind.WARN, "Weather: no location set (Settings → Weather location)")
            return null
        }
        val series = forecast(lat, lon, log) ?: return null
        val target = at.toInstant().toEpochMilli()
        return series.minByOrNull { abs(it.first - target) }?.second
    }

    private fun location(context: Context, s: AppSettings): Pair<Double, Double>? {
        if (s.homeLat != null && s.homeLon != null) return s.homeLat to s.homeLon
        return lastKnownLocation(context)
    }

    @SuppressLint("MissingPermission")
    fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return loc.latitude to loc.longitude
    }

    private suspend fun forecast(lat: Double, lon: Double, log: DiagLog): List<Pair<Long, Double>>? {
        // MET wants at most 4 decimals (privacy + caching).
        val la = Math.round(lat * 10_000) / 10_000.0
        val lo = Math.round(lon * 10_000) / 10_000.0
        cache?.let { c ->
            if (c.lat == la && c.lon == lo && System.currentTimeMillis() - c.atMs < 30 * 60_000) return c.series
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$la&lon=$lo"
                val c = URI(url).toURL().openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", UA)
                c.connectTimeout = 8_000
                c.readTimeout = 8_000
                val code = c.responseCode
                if (code != 200) throw IllegalStateException("HTTP $code")
                val text = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val ts = JSONObject(text).getJSONObject("properties").getJSONArray("timeseries")
                val out = ArrayList<Pair<Long, Double>>(ts.length())
                for (i in 0 until minOf(ts.length(), 48)) {
                    val o = ts.getJSONObject(i)
                    val t = Instant.parse(o.getString("time")).toEpochMilli()
                    val temp = o.getJSONObject("data").getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
                    out += t to temp
                }
                cache = Cached(la, lo, System.currentTimeMillis(), out)
                out.toList()
            }.onFailure { log.add(io.github.anwo91.phevlink.core.diag.LogKind.WARN, "Weather lookup failed: ${it.message}") }
                .getOrNull()
        }
    }
}
