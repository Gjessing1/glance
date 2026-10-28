package io.gjessing.glance

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The northern lights from NOAA's Space Weather Prediction Center, for the Living Tree and Tiny
 * Living World. Two public products, no key:
 *
 * - the planetary K-index forecast (a few kB, three-hour blocks, about three days ahead), fetched
 *   every three hours: the cheap answer to "could there be aurora tonight";
 * - OVATION (155 kB gzipped, the next 30–45 minutes, aurora probability per degree), fetched only
 *   on nights that look promising (dark at home, Kp 3 or more, not fully overcast) and at most every
 *   twenty minutes, with its ETag so an unchanged grid costs nothing. Only home's chance is kept.
 */
object SpaceWeather {
    private const val prefsName = "glance_space"
    private const val KP_URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"
    private const val OVATION_URL = "https://services.swpc.noaa.gov/json/ovation_aurora_latest.json"
    private const val KP_EVERY = 3 * 3_600_000L
    private const val OVATION_EVERY = 20 * 60_000L
    private const val BLOCK = 3 * 3_600_000L

    private fun prefs(context: Context) = context.getSharedPreferences(prefsName, 0)

    /** What NOAA says for home now; OVATION only while its outlook is current. */
    fun signal(context: Context, place: Place, nowMs: Long = System.currentTimeMillis()): AuroraSignal {
        val rows = prefs(context).getString("kp", null)?.let { JSONArray(it) }
        val ovation = prefs(context).getString("ovation|${place.key}", null)?.let { JSONObject(it) }
            ?.takeIf { kotlin.math.abs(it.optLong("forecastAt") - nowMs) < 60 * 60_000L }
        return AuroraSignal(rows?.let { kpAt(it, nowMs) }, ovation?.optDouble("chance"))
    }

    /** Fetches what is due for home. Failures keep the last copy; the picture is not worth a retry storm. */
    fun refreshDue(context: Context, place: Place, nowMs: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        if (nowMs - prefs.getLong("kpAt", 0L) >= KP_EVERY) {
            try {
                get(KP_URL, null)?.second?.let { body -> prefs.edit().putString("kp", parseKp(body).toString()).putLong("kpAt", nowMs).apply() }
            } catch (_: Exception) { }
        }
        val key = "ovation|${place.key}"
        val previous = prefs.getString(key, null)?.let { JSONObject(it) }
        if (previous != null && nowMs - previous.optLong("checkedAt") < OVATION_EVERY) return
        if (!promising(context, place, nowMs)) return
        try {
            val (etag, body) = get(OVATION_URL, previous?.optString("etag")?.takeIf { it.isNotEmpty() }) ?: return
            val entry = if (body == null) previous!! else {
                val grid = parseOvation(body) ?: return
                JSONObject().put("chance", NightSky.ovationInView({ x, y -> grid.at(x, y) }, place.lat, place.lon))
                    .put("forecastAt", grid.forecastAt)
            }
            prefs.edit().putString(key, entry.put("checkedAt", nowMs).put("etag", etag ?: "").toString()).apply()
        } catch (_: Exception) { }
    }

    /** Dark at home, the K-index up (or unknown) and some sky open: worth OVATION's closer look. */
    private fun promising(context: Context, place: Place, nowMs: Long): Boolean {
        if (Sky.sun(nowMs, place.lat, place.lon).elevation > -6) return false
        val kp = prefs(context).getString("kp", null)?.let { kpAt(JSONArray(it), nowMs) }
        if (kp != null && kp < 3) return false
        val cloud = TreeWidget.weatherAt(WeatherRepository.hourRows(context, place), WeatherRepository.nowcast(context, place), nowMs, nowMs)
        return cloud == null || NightSky.cloudFactor(cloud.cloud, cloud.cloudLow, cloud.cloudMedium, cloud.cloudHigh) > 0.15
    }

    /** GET with an optional ETag: (new ETag, body), body null when unchanged; null on any other answer. */
    private fun get(url: String, etag: String?): Pair<String?, String?>? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", WeatherRepository.userAgent())
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        etag?.let { connection.setRequestProperty("If-None-Match", it) }
        try {
            return when (connection.responseCode) {
                200 -> connection.getHeaderField("ETag") to connection.inputStream.bufferedReader().use { it.readText() }
                304 -> etag to null
                else -> null
            }
        } finally { connection.disconnect() }
    }

    /**
     * The K-index forecast as [{t, kp}] by block start. NOAA has served it both as objects with
     * `time_tag` and `kp`, and as rows under a header row; either is read.
     */
    internal fun parseKp(text: String): JSONArray {
        val body = JSONArray(text)
        val out = JSONArray()
        for (i in 0 until body.length()) {
            val (tag, kp) = when (val row = body.get(i)) {
                is JSONObject -> row.optString("time_tag") to row.optDouble("kp", Double.NaN)
                is JSONArray -> row.optString(0) to (row.optString(1).toDoubleOrNull() ?: Double.NaN)
                else -> continue
            }
            if (kp.isNaN() || tag.length < 19) continue
            val at = try { LocalDateTime.parse(tag.take(19).replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { continue }
            out.put(JSONObject().put("t", at).put("kp", kp))
        }
        return out
    }

    /** Kp for the three-hour block holding `ms`. */
    internal fun kpAt(rows: JSONArray, ms: Long): Double? {
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val start = row.optLong("t")
            if (ms >= start && ms < start + BLOCK) return row.optDouble("kp")
        }
        return null
    }

    class Ovation(val forecastAt: Long, private val values: IntArray) {
        fun at(lon: Int, lat: Int): Int? = if (lat !in -90..90) null else values[(lat + 90) * 360 + ((lon % 360) + 360) % 360]
    }

    private val triple = Regex("""\[\s*(\d+)\s*,\s*(-?\d+)\s*,\s*(\d+(?:\.\d+)?)\s*]""")

    /** OVATION's `[longitude, latitude, probability]` grid, refused (as Atlas does) when it is far from complete. */
    internal fun parseOvation(text: String): Ovation? {
        val forecast = Regex("\"Forecast Time\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return null
        val forecastAt = CommuteRepository.millis(forecast) ?: return null
        val values = IntArray(360 * 181)
        var usable = 0
        for (match in triple.findAll(text)) {
            val lon = match.groupValues[1].toInt()
            val lat = match.groupValues[2].toInt()
            if (lon !in 0..359 || lat !in -90..90) continue
            values[(lat + 90) * 360 + lon] = match.groupValues[3].toDouble().toInt().coerceIn(0, 100)
            usable++
        }
        return if (usable < 60_000) null else Ovation(forecastAt, values)
    }
}
