package io.gjessing.glance

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** One forecast hour at one point: [time, time + 1h). */
data class WeatherHour(val time: Instant, val mm: Double, val probability: Double, val temp: Double? = null, val symbol: String = "")

/** A place the commute passes, with its hourly forecast. */
data class WeatherPoint(val name: String, val hours: List<WeatherHour>)

data class Thresholds(val probability: Double = 50.0, val amount: Double = 0.3, val heavy: Double = 2.0)

data class Advice(val kind: Kind, val title: String, val reason: String, val symbol: String = "", val wetSoon: Boolean = false) {
    /** DRY, RAIN and HEAVY are the commute rain check; FORECAST is the plain forecast the rest of the time. */
    enum class Kind { DRY, RAIN, HEAVY, FORECAST, UNKNOWN }
    /** Dry advice is a calm line; rain or heavy rain is something to act on. */
    val actionable get() = kind == Kind.RAIN || kind == Kind.HEAVY
}

/**
 * The rain check for the commute: each window still ahead is checked at both ends of the commute,
 * and the wetter end decides. Pure, so every rule is a unit test.
 */
object WeatherAdvisor {
    private data class Slot(val name: String, val start: ZonedDateTime, val end: ZonedDateTime)
    private data class Wet(val point: String, val start: Instant, val end: Instant, val heavy: Boolean)

    fun advise(points: List<WeatherPoint>, now: Instant, zone: ZoneId, rules: TimeRules, thresholds: Thresholds = Thresholds()): Advice {
        val windows = windowsAhead(now.atZone(zone), rules)
        if (points.isEmpty() || points.all { it.hours.isEmpty() }) return Advice(Advice.Kind.UNKNOWN, "No forecast yet", "")
        // A window past the forecast's reach is unknown, not dry.
        val covered = windows.filter { window ->
            points.any { p -> p.hours.any { it.time.isBefore(window.end.toInstant()) && it.time.plus(HOUR).isAfter(window.start.toInstant()) } }
        }
        if (covered.isEmpty()) return Advice(Advice.Kind.UNKNOWN, "No forecast yet", "")
        val verdicts = covered.map { window -> window to wetHours(points, window, now, thresholds) }
        val wet = verdicts.filter { it.second.isNotEmpty() }
        if (wet.isEmpty()) {
            val names = covered.joinToString(" and ") { it.name }
            return Advice(Advice.Kind.DRY, "Dry commute", "No rain expected · $names")
        }
        val (window, hours) = wet.firstOrNull { verdict -> verdict.second.any { it.heavy } } ?: wet.first()
        val heavy = hours.any { it.heavy }
        val title = if (heavy) "Heavy rain expected" else "Bring an umbrella"
        val atPoints = hours.map { it.point }.distinct()
        val start = hours.minOf { it.start }.coerceAtLeast(window.start.toInstant()).coerceAtLeast(now)
        val end = hours.maxOf { it.end }.coerceAtMost(window.end.toInstant())
        val reason = when {
            wet.size > 1 && !heavy -> "Rain likely during both commutes · ${span(start, end, zone)}"
            atPoints.size == 1 && points.size > 1 -> "Rain at ${atPoints.first()} from ${clock(start, zone)} · ${window.name}"
            else -> "Rain likely during ${window.name} · ${span(start, end, zone)}"
        }
        return Advice(if (heavy) Advice.Kind.HEAVY else Advice.Kind.RAIN, title, reason)
    }

    private val HOUR: Duration = Duration.ofHours(1)
    private const val FORECAST_HOURS = 12L

    /**
     * The forecast where the user is, for the next twelve hours, as what it means: how long the
     * weather now lasts, then what to do about it. The hourly strip below carries the temperatures.
     * "Heavy rain, easing after 20:00" · "Umbrella recommended", "Rain until 23:00", "Dry until 16:00" · "Umbrella for later".
     */
    fun forecast(hours: List<WeatherHour>, now: Instant, zone: ZoneId, t: Thresholds = Thresholds()): Advice {
        val ahead = hours.filter { it.time.plus(HOUR).isAfter(now) && it.time.isBefore(now.plus(Duration.ofHours(FORECAST_HOURS))) }
        val current = ahead.firstOrNull() ?: return Advice(Advice.Kind.UNKNOWN, "No forecast yet", "")
        fun wet(hour: WeatherHour) = hour.probability >= t.probability && hour.mm >= t.amount
        val sky = describe(current.symbol)
        val (title, advice) = if (wet(current)) {
            // The symbol can lag the amounts; a wet hour under a dry-looking symbol is still rain.
            val falling = sky.takeIf { WET_WORDS.any { word -> it.contains(word, ignoreCase = true) } } ?: "Rain"
            val gear = if (falling == "Snow") "Dress for snow" else "Umbrella recommended"
            val spell = ahead.takeWhile { wet(it) }
            val dryAt = ahead.firstOrNull { !wet(it) }
            // Heavy rain that turns lighter before it stops says when it eases, not when the last drizzle ends.
            val eases = if (current.mm >= t.heavy) spell.lastOrNull { it.mm >= t.heavy }?.time?.plus(HOUR)?.takeIf { it.isBefore(spell.last().time.plus(HOUR)) } else null
            val title = when {
                eases != null -> "$falling, easing after ${clock(eases, zone)}"
                dryAt != null -> "$falling until ${clock(dryAt.time, zone)}"
                else -> "$falling for the next 12 hours"
            }
            title to gear
        } else {
            ahead.firstOrNull { wet(it) }?.let { "Dry until ${clock(it.time, zone)}" to "Umbrella for later" } ?: (sky to "No rain expected")
        }
        return Advice(Advice.Kind.FORECAST, title, advice, current.symbol, ahead.any { wet(it) })
    }

    private val WET_WORDS = listOf("rain", "showers", "sleet", "snow", "thunder")

    /** MET's symbol code in words: "partlycloudy_day" → "Partly cloudy". */
    internal fun describe(symbol: String): String {
        val code = symbol.substringBefore('_')
        return when {
            code.isEmpty() -> "Forecast"
            code.contains("thunder") -> "Thunder"
            code.startsWith("heavy") && code.contains("rain") -> "Heavy rain"
            code.startsWith("light") && code.contains("rain") -> "Light rain"
            code.contains("rainshowers") -> "Rain showers"
            code.contains("rain") -> "Rain"
            code.contains("sleet") -> "Sleet"
            code.contains("snow") -> "Snow"
            code == "fog" -> "Fog"
            code == "cloudy" -> "Cloudy"
            code == "partlycloudy" -> "Partly cloudy"
            code == "fair" -> "Fair"
            code == "clearsky" -> "Clear sky"
            else -> code.replaceFirstChar { it.uppercase() }
        }
    }

    /** Hours inside the window, not yet over, wet enough at a point. */
    private fun wetHours(points: List<WeatherPoint>, window: Slot, now: Instant, t: Thresholds): List<Wet> =
        points.flatMap { point ->
            point.hours.filter { hour ->
                val end = hour.time.plus(HOUR)
                end.isAfter(window.start.toInstant()) && hour.time.isBefore(window.end.toInstant()) && end.isAfter(now) &&
                    hour.probability >= t.probability && hour.mm >= t.amount
            }.map { Wet(point.name, it.time, it.time.plus(HOUR), it.mm >= t.heavy) }
        }

    /**
     * The commute windows still ahead: what is left of today's on a workday (after the morning window just
     * the return), otherwise the next workday's morning.
     */
    private fun windowsAhead(now: ZonedDateTime, rules: TimeRules): List<Slot> {
        val today = now.toLocalDate()
        if (GlanceTime.isWorkday(now, rules)) {
            val left = listOf(slot("morning commute", today, rules.morning, now.zone), slot("return commute", today, rules.evening, now.zone))
                .filter { it.end.isAfter(now) }
            if (left.isNotEmpty()) return left
        }
        var day = today.plusDays(1)
        while (day.dayOfWeek !in rules.workDays && day.isBefore(today.plusDays(8))) day = day.plusDays(1)
        val name = if (day == today.plusDays(1)) "tomorrow morning" else day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " morning"
        return listOf(slot(name, day, rules.morning, now.zone))
    }

    private fun slot(name: String, day: LocalDate, window: Window, zone: ZoneId) =
        Slot(name, ZonedDateTime.of(day, window.start, zone), ZonedDateTime.of(day, window.end, zone))

    /** "15–18", or with minutes where they are not whole hours: "15:30–18". */
    internal fun span(start: Instant, end: Instant, zone: ZoneId) = "${hour(start, zone)}–${hour(end, zone)}"

    private fun hour(at: Instant, zone: ZoneId): String {
        val t = at.atZone(zone)
        return if (t.minute == 0) "%02d".format(t.hour) else "%02d:%02d".format(t.hour, t.minute)
    }

    private fun clock(at: Instant, zone: ZoneId) = at.atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
}
