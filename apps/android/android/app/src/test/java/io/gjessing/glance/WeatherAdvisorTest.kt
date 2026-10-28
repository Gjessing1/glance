package io.gjessing.glance

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WeatherAdvisorTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private val rules = TimeRules()
    private fun at(text: String): Instant = ZonedDateTime.of(LocalDateTime.parse(text), oslo).toInstant()

    /** Hourly series from `from` for `count` hours; `wet` maps an hour of day to (mm, probability). */
    private fun series(from: String, count: Int, wet: Map<Int, Pair<Double, Double>> = emptyMap()): List<WeatherHour> =
        (0 until count).map { i ->
            val time = at(from).plus(Duration.ofHours(i.toLong()))
            val (mm, p) = wet[time.atZone(oslo).hour] ?: (0.0 to 5.0)
            WeatherHour(time, mm, p)
        }

    @Test fun dryWhenNothingCrossesTheThresholds() {
        val points = listOf(WeatherPoint("Engelsrud terrasse", series("2026-10-28T06:00", 30, mapOf(8 to (0.2 to 90.0), 16 to (1.0 to 40.0)))),
            WeatherPoint("Nationaltheatret", series("2026-10-28T06:00", 30)))
        val advice = WeatherAdvisor.advise(points, at("2026-10-28T06:30"), oslo, rules)
        assertEquals(Advice.Kind.DRY, advice.kind)
        assertEquals("No rain expected · morning commute and return commute", advice.reason)
    }

    @Test fun rainAtBothEndsDuringTheReturn() {
        val wet = mapOf(15 to (0.6 to 70.0), 16 to (1.2 to 80.0), 17 to (0.4 to 60.0))
        val points = listOf(WeatherPoint("Engelsrud terrasse", series("2026-10-28T10:00", 20, wet)),
            WeatherPoint("Nationaltheatret", series("2026-10-28T10:00", 20, wet)))
        val advice = WeatherAdvisor.advise(points, at("2026-10-28T11:00"), oslo, rules)
        assertEquals(Advice.Kind.RAIN, advice.kind)
        assertEquals("Bring an umbrella", advice.title)
        assertEquals("Rain likely during return commute · 15–18", advice.reason)
    }

    @Test fun oneWetEndNamesThePlace() {
        val points = listOf(WeatherPoint("Engelsrud terrasse", series("2026-10-28T10:00", 20)),
            WeatherPoint("Nationaltheatret", series("2026-10-28T10:00", 20, mapOf(16 to (0.8 to 75.0)))))
        val advice = WeatherAdvisor.advise(points, at("2026-10-28T11:00"), oslo, rules)
        assertEquals("Rain at Nationaltheatret from 16:00 · return commute", advice.reason)
    }

    @Test fun heavyRainWins() {
        val points = listOf(WeatherPoint("Engelsrud terrasse", series("2026-10-28T06:00", 30, mapOf(8 to (0.5 to 60.0), 17 to (3.5 to 90.0)))))
        val advice = WeatherAdvisor.advise(points, at("2026-10-28T06:00"), oslo, rules)
        assertEquals(Advice.Kind.HEAVY, advice.kind)
        assertEquals("Rain likely during return commute · 17–18", advice.reason)
    }

    @Test fun afterTheReturnLooksAtTomorrowMorningOrMonday() {
        val wet = mapOf(8 to (0.6 to 70.0))
        val wednesday = WeatherAdvisor.advise(listOf(WeatherPoint("A", series("2026-10-28T19:00", 20, wet))), at("2026-10-28T19:00"), oslo, rules)
        assertEquals("Rain likely during tomorrow morning · 08–09", wednesday.reason)
        val friday = WeatherAdvisor.advise(listOf(WeatherPoint("A", series("2026-10-30T19:00", 70, wet))), at("2026-10-30T19:00"), oslo, rules)
        assertEquals("Rain likely during Monday morning · 08–09", friday.reason)
    }

    @Test fun windowBeyondTheForecastIsUnknown() {
        val advice = WeatherAdvisor.advise(listOf(WeatherPoint("A", series("2026-10-30T19:00", 10))), at("2026-10-30T19:00"), oslo, rules)
        assertEquals(Advice.Kind.UNKNOWN, advice.kind)
    }

    private fun sky(from: String, count: Int, wet: Set<Int>) = (0 until count).map { i ->
        val time = at(from).plus(Duration.ofHours(i.toLong()))
        val hour = time.atZone(oslo).hour
        WeatherHour(time, if (hour in wet) 1.0 else 0.0, if (hour in wet) 80.0 else 5.0, 3.0 + i % 5, if (hour in wet) "rain" else "cloudy")
    }

    @Test fun forecastSaysWhenRainStartsAndStops() {
        val later = WeatherAdvisor.forecast(sky("2026-10-31T10:00", 24, setOf(16, 17)), at("2026-10-31T10:20"), oslo)
        assertEquals(Advice.Kind.FORECAST, later.kind)
        assertEquals("Dry until 16:00", later.title)
        assertEquals("Umbrella for later", later.reason)
        val now = WeatherAdvisor.forecast(sky("2026-10-31T10:00", 24, setOf(10, 11)), at("2026-10-31T10:20"), oslo)
        assertEquals("Rain until 12:00", now.title)
        assertEquals("Umbrella recommended", now.reason)
        val dry = WeatherAdvisor.forecast(sky("2026-10-31T10:00", 24, emptySet()), at("2026-10-31T10:20"), oslo)
        assertEquals("Cloudy", dry.title)
        assertEquals("No rain expected", dry.reason)
        val allDay = WeatherAdvisor.forecast(sky("2026-10-31T10:00", 24, (0..23).toSet()), at("2026-10-31T10:20"), oslo)
        assertEquals("Rain for the next 12 hours", allDay.title)
    }

    @Test fun heavyRainSaysWhenItEases() {
        fun rain(vararg mm: Double) = mm.mapIndexed { i, amount ->
            WeatherHour(at("2026-10-25T17:00").plus(Duration.ofHours(i.toLong())), amount, if (amount > 0) 90.0 else 5.0, 8.0, if (amount > 0) "heavyrain" else "cloudy")
        }
        val easing = WeatherAdvisor.forecast(rain(2.5, 3.3, 2.1, 0.9, 0.6, 0.4, 0.0), at("2026-10-25T17:20"), oslo)
        assertEquals("Heavy rain, easing after 20:00", easing.title)
        // Heavy to the last wet hour: nothing eases before it stops.
        assertEquals("Heavy rain until 20:00", WeatherAdvisor.forecast(rain(2.5, 3.3, 2.1, 0.0), at("2026-10-25T17:20"), oslo).title)
        // A light hour between heavy ones is not the easing; the last heavy hour is.
        assertEquals("Heavy rain, easing after 21:00", WeatherAdvisor.forecast(rain(2.5, 1.0, 2.4, 3.0, 0.5, 0.0), at("2026-10-25T17:20"), oslo).title)
        assertEquals("Heavy rain until 19:00", WeatherAdvisor.forecast(rain(1.2, 0.8, 0.0), at("2026-10-25T17:20"), oslo).title)
    }

    @Test fun dayRowsSumRainOnceAcrossHourlyAndSixHourSteps() {
        fun step(time: String, temp: Double, one: Double?, six: Double?, symbol: String) = org.json.JSONObject().put("time", time)
            .put("data", org.json.JSONObject().put("instant", org.json.JSONObject().put("details", org.json.JSONObject().put("air_temperature", temp)))
                .apply {
                    if (one != null) put("next_1_hours", org.json.JSONObject().put("summary", org.json.JSONObject().put("symbol_code", symbol))
                        .put("details", org.json.JSONObject().put("precipitation_amount", one)))
                    if (six != null) put("next_6_hours", org.json.JSONObject().put("summary", org.json.JSONObject().put("symbol_code", symbol))
                        .put("details", org.json.JSONObject().put("precipitation_amount", six)))
                })
        val series = org.json.JSONArray()
            .put(step("2026-10-28T10:00:00Z", 4.0, 1.0, 5.0, "rain"))
            .put(step("2026-10-28T11:00:00Z", 7.0, 0.5, 4.0, "lightrain"))
            .put(step("2026-10-29T06:00:00Z", 2.0, null, 3.0, "cloudy"))
            .put(step("2026-10-29T12:00:00Z", 9.0, null, 0.0, "fair_day"))
        val body = org.json.JSONObject().put("properties", org.json.JSONObject().put("timeseries", series))
        val days = WeatherRepository.parseDays(body, at("2026-10-28T10:30").toEpochMilli(), oslo)
        assertEquals(2, days.length())
        assertEquals(1.5, days.getJSONObject(0).getDouble("mm"), 0.001)
        assertEquals(4.0, days.getJSONObject(0).getDouble("low"), 0.001)
        assertEquals(3.0, days.getJSONObject(1).getDouble("mm"), 0.001)
        // The step nearest local noon (13:00 CET is 12:00Z) names the day's sky.
        assertEquals("fair_day", days.getJSONObject(1).getString("symbol"))
        // Each day keeps its own steps for the day view: hourly ones first, six-hour ones later on.
        val today = days.getJSONObject(0).getJSONArray("steps")
        assertEquals(2, today.length())
        assertEquals(1, today.getJSONObject(0).getInt("span"))
        assertEquals(0.5, today.getJSONObject(1).getDouble("mm"), 0.001)
        val tomorrow = days.getJSONObject(1).getJSONArray("steps")
        assertEquals(listOf(6, 6), (0 until tomorrow.length()).map { tomorrow.getJSONObject(it).getInt("span") })
        assertEquals("cloudy", tomorrow.getJSONObject(0).getString("symbol"))
    }

    @Test fun rainAlreadyPastInTheWindowIsNotNews() {
        val points = listOf(WeatherPoint("A", series("2026-10-28T06:00", 30, mapOf(7 to (1.0 to 90.0)))))
        assertEquals(Advice.Kind.DRY, WeatherAdvisor.advise(points, at("2026-10-28T08:10"), oslo, rules).kind)
    }
}
