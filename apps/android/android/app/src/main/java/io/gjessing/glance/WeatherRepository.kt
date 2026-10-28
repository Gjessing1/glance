package io.gjessing.glance

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A forecast point: a commute stop's name and position. */
data class Place(val name: String, val lat: Double, val lon: Double) {
    /** MET caches by position; two decimals (about a kilometre) is what they ask clients to send. */
    val key get() = "%.2f,%.2f".format(Locale.ROOT, lat, lon)
}

/**
 * MET Norway directly: `locationforecast/2.0/complete` for hourly rain amount and probability,
 * `nowcast/2.0/complete` for the temperature and sky right now. MET's terms are a User-Agent with
 * contact details, honouring Expires, and If-Modified-Since, so a refresh before Expires is free.
 */
object WeatherRepository {
    private const val prefsName = "glance_weather"
    private const val base = "https://api.met.no/weatherapi"
    private const val FORECAST = "locationforecast/2.0/complete"
    private const val NOWCAST = "nowcast/2.0/complete"
    private const val HOURS = 48

    private fun prefs(context: Context) = context.getSharedPreferences(prefsName, 0)
    internal fun userAgent() = "gjessing-glance/${BuildConfig.VERSION_NAME} larsgjessing@gmail.com"

    /**
     * The commute's two ends for this widget, from the coordinates the last commute refresh brought
     * back. They move with the stops, so changing the commute moves the weather too.
     */
    fun places(context: Context, widgetId: Int, network: Boolean = false): List<Place> {
        val ends = try { CommuteRepository.ends(context, widgetId, network) } catch (_: Exception) { null } ?: return emptyList()
        val names = ends.optJSONObject("names")
        val coords = ends.optJSONObject("coords") ?: return emptyList()
        return listOf("from", "to").mapNotNull { end ->
            val point = coords.optJSONObject(end) ?: return@mapNotNull null
            Place(names?.optString(end).orEmpty().ifEmpty { end }, point.optDouble("lat"), point.optDouble("lon"))
        }.distinctBy { it.key }
    }

    /** The hourly series MET gave for a place, trimmed to what the advisory reads. */
    fun hours(context: Context, place: Place): List<WeatherHour> {
        val list = cached(context, FORECAST, place)?.optJSONArray("hours") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }.mapNotNull { hour ->
            val t = CommuteRepository.millis(hour.optString("t")) ?: return@mapNotNull null
            WeatherHour(Instant.ofEpochMilli(t), hour.optDouble("mm", 0.0), hour.optDouble("p", 0.0),
                if (hour.isNull("temp")) null else hour.optDouble("temp"), hour.optString("symbol"))
        }
    }

    /** Hourly rows for the standalone widget's strip: time, symbol, temperature, rain. */
    fun hourRows(context: Context, place: Place): List<JSONObject> {
        val list = cached(context, FORECAST, place)?.optJSONArray("hours") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    /** `{temp, symbol}` now, from nowcast, or the forecast's first hour when nowcast has nothing. */
    fun now(context: Context, place: Place): JSONObject? {
        cached(context, NOWCAST, place)?.optJSONObject("now")?.let { if (it.has("temp")) return it }
        return hourRows(context, place).firstOrNull()?.let { JSONObject().put("temp", it.optDouble("temp")).put("symbol", it.optString("symbol")) }
    }

    /** The nowcast as cached: temperature, symbol, rate and wind now, or null outside its coverage. */
    fun nowcast(context: Context, place: Place): JSONObject? = cached(context, NOWCAST, place)?.optJSONObject("now")?.takeIf { it.has("temp") }

    /** Drops the cached forecast, so the next refresh fetches it whatever Expires said. */
    fun forget(context: Context, places: List<Place>) {
        val edit = prefs(context).edit()
        for (place in places) edit.remove("$FORECAST|${place.key}")
        edit.apply()
    }

    fun checkedAt(context: Context, place: Place): Long? = cached(context, FORECAST, place)?.optLong("checkedAt")?.takeIf { it > 0 }

    private fun cached(context: Context, product: String, place: Place): JSONObject? =
        prefs(context).getString("$product|${place.key}", null)?.let { JSONObject(it) }

    /** Refreshes both products for each place whose Expires has passed. Returns false if any request failed. */
    fun refreshDue(context: Context, places: List<Place>, nowMs: Long = System.currentTimeMillis()): Boolean {
        var ok = true
        for (place in places) for (product in listOf(FORECAST, NOWCAST)) {
            val entry = cached(context, product, place)
            if (entry != null && entry.optLong("expires") > nowMs) continue
            try { fetch(context, product, place, entry, nowMs) } catch (_: Exception) { ok = false }
        }
        return ok
    }

    private fun fetch(context: Context, product: String, place: Place, previous: JSONObject?, nowMs: Long) {
        val url = "%s/%s?lat=%.2f&lon=%.2f".format(Locale.ROOT, base, product, place.lat, place.lon)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", userAgent())
        connection.connectTimeout = 10000
        connection.readTimeout = 20000
        previous?.optString("lastModified")?.takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("If-Modified-Since", it) }
        try {
            val expires = header(connection.getHeaderField("Expires")) ?: (nowMs + 30 * 60_000L)
            val entry = when (val code = connection.responseCode) {
                200 -> {
                    val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val parsed = if (product == FORECAST) JSONObject().put("hours", parseForecast(body, nowMs)).put("days", parseDays(body, nowMs, GlanceTime.zone()))
                        else JSONObject().put("now", parseNowcast(body))
                    parsed.put("lastModified", connection.getHeaderField("Last-Modified") ?: "").put("checkedAt", nowMs)
                }
                // Unchanged since last time: keep the data, take the new expiry.
                304 -> previous ?: throw IllegalStateException("MET sent 304 without a cached copy")
                // Nowcast covers the Nordics only; outside it, rest until the forecast's next expiry.
                422 -> JSONObject().put("now", JSONObject()).put("checkedAt", nowMs)
                else -> throw IllegalStateException("MET answered HTTP $code")
            }
            entry.put("expires", expires)
            prefs(context).edit().putString("$product|${place.key}", entry.toString()).apply()
        } finally { connection.disconnect() }
    }

    private fun header(value: String?): Long? = try {
        if (value.isNullOrEmpty()) null else ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: Exception) { null }

    /** The next two days of hourly steps: time, temperature, symbol, rain amount and its probability, wind and cloud. */
    internal fun parseForecast(body: JSONObject, nowMs: Long): JSONArray {
        val series = body.optJSONObject("properties")?.optJSONArray("timeseries") ?: JSONArray()
        val hours = JSONArray()
        for (i in 0 until series.length()) {
            val step = series.getJSONObject(i)
            val time = CommuteRepository.millis(step.optString("time")) ?: continue
            if (time + 3_600_000L <= nowMs) continue
            val data = step.optJSONObject("data") ?: continue
            // Past ~60 hours MET switches to 6-hour steps; the advisory only reads hourly ones.
            val next = data.optJSONObject("next_1_hours") ?: break
            val details = next.optJSONObject("details")
            val instant = data.optJSONObject("instant")?.optJSONObject("details")
            hours.put(JSONObject().put("t", step.optString("time"))
                .put("temp", instant?.optDouble("air_temperature") ?: JSONObject.NULL)
                .put("symbol", next.optJSONObject("summary")?.optString("symbol_code").orEmpty())
                .put("mm", details?.optDouble("precipitation_amount", 0.0) ?: 0.0)
                .put("p", details?.optDouble("probability_of_precipitation", 0.0) ?: 0.0)
                .also { row -> instant?.let { sky(it, row) } })
            if (hours.length() >= HOURS) break
        }
        return hours
    }

    /**
     * One row per local day, up to nine: low and high, rain summed over the day, the sky around noon,
     * and the day's own steps for the app's day view (hourly, then MET's six-hour steps further out,
     * each with its span). Those six-hour steps' rain counts once.
     */
    internal fun parseDays(body: JSONObject, nowMs: Long, zone: java.time.ZoneId): JSONArray {
        class Day(val date: java.time.LocalDate) { var low = Double.MAX_VALUE; var high = -Double.MAX_VALUE; var mm = 0.0; var symbol = ""; var noonGap = 99; val steps = JSONArray() }
        val series = body.optJSONObject("properties")?.optJSONArray("timeseries") ?: JSONArray()
        val days = linkedMapOf<java.time.LocalDate, Day>()
        for (i in 0 until series.length()) {
            val step = series.getJSONObject(i)
            val time = CommuteRepository.millis(step.optString("time")) ?: continue
            if (time + 3_600_000L <= nowMs) continue
            val local = Instant.ofEpochMilli(time).atZone(zone)
            val day = days.getOrPut(local.toLocalDate()) { Day(local.toLocalDate()) }
            val data = step.optJSONObject("data") ?: continue
            data.optJSONObject("instant")?.optJSONObject("details")?.takeIf { it.has("air_temperature") }?.optDouble("air_temperature")?.let {
                day.low = minOf(day.low, it)
                day.high = maxOf(day.high, it)
            }
            val period = data.optJSONObject("next_1_hours") ?: data.optJSONObject("next_6_hours")
            val details = period?.optJSONObject("details")
            day.mm += details?.optDouble("precipitation_amount", 0.0) ?: 0.0
            if (period != null) day.steps.put(JSONObject().put("t", step.optString("time"))
                .put("temp", data.optJSONObject("instant")?.optJSONObject("details")?.takeIf { it.has("air_temperature") }?.optDouble("air_temperature") ?: JSONObject.NULL)
                .put("symbol", period.optJSONObject("summary")?.optString("symbol_code").orEmpty())
                .put("mm", details?.optDouble("precipitation_amount", 0.0) ?: 0.0)
                .put("p", details?.optDouble("probability_of_precipitation", 0.0) ?: 0.0)
                .put("span", if (data.has("next_1_hours")) 1 else 6))
            val gap = Math.abs(local.hour - 12)
            val symbol = (data.optJSONObject("next_6_hours") ?: data.optJSONObject("next_1_hours") ?: data.optJSONObject("next_12_hours"))
                ?.optJSONObject("summary")?.optString("symbol_code").orEmpty()
            if (symbol.isNotEmpty() && gap < day.noonGap) {
                day.symbol = symbol
                day.noonGap = gap
            }
        }
        val result = JSONArray()
        for (day in days.values.take(9)) {
            if (day.high == -Double.MAX_VALUE) continue
            result.put(JSONObject().put("date", day.date.toString()).put("low", day.low).put("high", day.high)
                .put("mm", Math.round(day.mm * 10) / 10.0).put("symbol", day.symbol).put("steps", day.steps))
        }
        return result
    }

    /** The day rows for a place, for the app's forecast screen. */
    fun dayRows(context: Context, place: Place): List<JSONObject> {
        val list = cached(context, FORECAST, place)?.optJSONArray("days") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    internal fun parseNowcast(body: JSONObject): JSONObject {
        val first = body.optJSONObject("properties")?.optJSONArray("timeseries")?.optJSONObject(0)?.optJSONObject("data") ?: return JSONObject()
        val details = first.optJSONObject("instant")?.optJSONObject("details")
        val result = JSONObject()
        if (details?.has("air_temperature") == true) result.put("temp", details.optDouble("air_temperature"))
        result.put("symbol", first.optJSONObject("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code").orEmpty())
        result.put("rate", details?.optDouble("precipitation_rate", 0.0) ?: 0.0)
        details?.let { sky(it, result) }
        return result
    }

    /**
     * Wind and cloud cover, for the Living Tree's branches and sky; the cloud layers say whether the
     * northern lights could be seen through it. Nowcast has no cloud cover.
     */
    private fun sky(details: JSONObject, into: JSONObject): JSONObject {
        if (details.has("cloud_area_fraction")) into.put("cloud", details.optDouble("cloud_area_fraction"))
        if (details.has("cloud_area_fraction_low")) into.put("cloudLow", details.optDouble("cloud_area_fraction_low"))
        if (details.has("cloud_area_fraction_medium")) into.put("cloudMedium", details.optDouble("cloud_area_fraction_medium"))
        if (details.has("cloud_area_fraction_high")) into.put("cloudHigh", details.optDouble("cloud_area_fraction_high"))
        if (details.has("wind_speed")) into.put("wind", details.optDouble("wind_speed"))
        if (details.has("wind_from_direction")) into.put("windFrom", details.optDouble("wind_from_direction"))
        return into
    }

    /** MET's symbol codes as one emoji each; the widget text stays in the system font. */
    fun emoji(symbol: String): String {
        val code = symbol.substringBefore('_')
        val night = symbol.endsWith("_night")
        return when {
            code.contains("thunder") -> "⛈️"
            code.contains("snow") -> "❄️"
            code.contains("sleet") -> "🌨️"
            code.contains("rain") -> "🌧️"
            code == "fog" -> "🌫️"
            code == "cloudy" -> "☁️"
            code == "partlycloudy" -> if (night) "☁️" else "⛅"
            code == "fair" -> if (night) "🌙" else "🌤️"
            code == "clearsky" -> if (night) "🌙" else "☀️"
            else -> ""
        }
    }

    fun temperature(value: Double): String = "%d°".format(Math.round(value))
}
