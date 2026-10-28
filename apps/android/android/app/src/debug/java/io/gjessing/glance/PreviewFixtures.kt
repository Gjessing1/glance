package io.gjessing.glance

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Debug-only cache contents for checking the section widgets on an emulator: an agenda shaped like
 * the roadmap sketch (a Teams meeting, a dentist with a place, an all-day birthday) and a forecast
 * with rain in the return commute at both ends.
 */
object PreviewFixtures {
    /**
     * `transportAgeMinutes` back-dates the transport check, to see the age label; `emptyAgenda` leaves the
     * calendar empty and `agendaOffsetMinutes` moves its timed events later, so none is under way or about
     * to start; `timedEvents` keeps only that many of them (all when negative). `track` tracks the next
     * journey in the clock's direction.
     */
    fun install(context: Context, widgetId: Int, transportAgeMinutes: Long = 0, emptyAgenda: Boolean = false,
                agendaOffsetMinutes: Long = 0, track: Boolean = false, timedEvents: Int = -1) {
        val now = Instant.now()
        val today = LocalDate.now(GlanceTime.zone())
        val at = now.plus(Duration.ofMinutes(agendaOffsetMinutes))
        val events = JSONArray()
            // Under way and in its last half hour, so the first row counts down to its end.
            .put(event("e0", "Standup", at.minus(Duration.ofMinutes(19)), 30, "cal"))
            .put(event("e1", "Team meeting", at.plus(Duration.ofMinutes(75)), 30, "ics:work", meeting = "https://teams.microsoft.com/l/meetup-join/x"))
            .put(event("e2", "Dentist appointment", at.plus(Duration.ofMinutes(165)), 60, "cal", location = "Asker tannlegesenter"))
            // Later events fill the agenda carousel's further pages.
            .put(event("e4", "Pick up parcel", at.plus(Duration.ofMinutes(300)), 15, "cal", location = "Asker Torv"))
            .put(event("e5", "Dinner with Kari", at.plus(Duration.ofMinutes(480)), 90, "cal"))
            .put(event("e6", "Sprint planning", at.plus(Duration.ofHours(25)), 60, "ics:work", meeting = "https://teams.microsoft.com/l/meetup-join/y"))
            .put(JSONObject().put("id", "e3").put("title", "Emma's birthday").put("start", "${today}T00:00:00Z")
                .put("end", "${today.plusDays(1)}T00:00:00Z").put("allDay", true).put("calendarId", "cal"))
            // More all-day events give the row its "+2 more" chip and the all-day sheet.
            .put(JSONObject().put("id", "e7").put("title", "Project planning").put("start", "${today}T00:00:00Z")
                .put("end", "${today.plusDays(1)}T00:00:00Z").put("allDay", true).put("calendarId", "ics:work"))
            .put(JSONObject().put("id", "e8").put("title", "Autumn break").put("start", "${today.minusDays(2)}T00:00:00Z")
                .put("end", "${today.plusDays(3)}T00:00:00Z").put("allDay", true).put("calendarId", "cal"))
        val agenda = JSONObject().put("generatedAt", now.toString()).put("timezone", "Europe/Oslo")
            .put("profiles", JSONObject().put("personal", JSONArray().put("ics:work")).put("work", JSONArray().put("cal")).put("combined", JSONArray()))
            .put("calendars", JSONArray().put(JSONObject().put("id", "ics:work").put("name", "Enora").put("color", "#3b5bdb"))
                .put(JSONObject().put("id", "cal").put("name", "Private").put("color", "#0f9d76")))
            .put("events", if (emptyAgenda) JSONArray() else limited(events, timedEvents))
        context.getSharedPreferences("glance_agenda", 0).edit().putString("agenda",
            JSONObject().put("agenda", agenda).put("checkedAt", now.toString()).put("stale", false).put("error", "").toString()).apply()
        GlanceSettings.put(context, GlanceSettings.get(context).put("nodecalUrl", "https://nodecal.example"))
        if (GlanceSettings.nodecalToken(context).isEmpty()) GlanceSettings.setNodecalToken(context, "preview")

        commute(context, widgetId, now, now.minus(Duration.ofMinutes(transportAgeMinutes)))
        if (track) {
            val direction = CommuteRepository.clockMode(now.toEpochMilli(), GlanceTime.rules(context))
            CommuteRepository.cached(context, widgetId)?.optJSONObject(direction)?.optJSONArray("journeys")?.optJSONObject(0)
                ?.let { CommuteRepository.track(context, widgetId, direction, it) }
        }
        val places = WeatherRepository.places(context, widgetId, network = true)
        val start = Instant.ofEpochSecond(now.epochSecond / 3600 * 3600)
        for (place in places) {
            val hours = JSONArray()
            for (i in 0 until 48) {
                val t = start.plus(Duration.ofHours(i.toLong()))
                val hour = t.atZone(GlanceTime.zone()).hour
                val wet = hour in 15..17
                hours.put(JSONObject().put("t", t.toString()).put("temp", 6.0 + (hour % 12) / 2.0)
                    .put("symbol", if (wet) "rain" else if (hour in 7..18) "partlycloudy_day" else "cloudy")
                    .put("mm", if (wet) 1.2 else 0.0).put("p", if (wet) 80.0 else 10.0))
            }
            val expires = now.plus(Duration.ofHours(6)).toEpochMilli()
            context.getSharedPreferences("glance_weather", 0).edit()
                .putString("locationforecast/2.0/complete|${place.key}", JSONObject().put("hours", hours).put("expires", expires).put("checkedAt", now.toEpochMilli()).toString())
                .putString("nowcast/2.0/complete|${place.key}", JSONObject().put("now", JSONObject().put("temp", 7.0).put("symbol", "partlycloudy_day")).put("expires", expires).toString())
                .apply()
        }
    }

    /** `events` with only the first `timed` of its timed ones, all-day events kept; all of them when `timed` is negative. */
    private fun limited(events: JSONArray, timed: Int): JSONArray {
        if (timed < 0) return events
        var kept = 0
        return JSONArray().apply {
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                if (event.optBoolean("allDay") || kept++ < timed) put(event)
            }
        }
    }

    /** Bus to the change, then the train: a nine-minute transfer, both ways, every quarter hour. */
    private fun commute(context: Context, widgetId: Int, now: Instant, checkedAt: Instant) {
        fun journeys(firstLine: String, firstMode: String, from: String, via: String, secondLine: String, secondMode: String, to: String): JSONArray {
            val list = JSONArray()
            for (i in 0 until 6) {
                val start = Instant.ofEpochSecond((now.epochSecond / 60 + 9 + i * 15L) * 60)
                val change = start.plus(Duration.ofMinutes(18))
                val second = change.plus(Duration.ofMinutes(9))
                val end = second.plus(Duration.ofMinutes(21))
                val legs = JSONArray().put(leg(firstLine, firstMode, from, via, start, change, "$i-a")).put(leg(secondLine, secondMode, via, to, second, end, "$i-b"))
                list.put(JSONObject().put("legs", legs).put("aimedStart", start.toString()).put("expectedStart", start.toString())
                    .put("aimedEnd", end.toString()).put("expectedEnd", end.toString()).put("key", "fixture-$firstLine-$i").put("rideStart", start.toString()))
            }
            return list
        }
        val board = JSONObject().put("widgetId", widgetId).put("checkedAt", checkedAt.toString()).put("stale", false)
            .put("names", JSONObject().put("from", "Engelsrud").put("via", "Asker").put("to", "Nationaltheatret"))
            .put("coords", JSONObject().put("from", JSONObject().put("lat", 59.819).put("lon", 10.361)).put("to", JSONObject().put("lat", 59.915).put("lon", 10.728)))
            .put("outbound", JSONObject().put("from", "Engelsrud").put("to", "Nationaltheatret").put("noService", false)
                .put("journeys", journeys("280", "bus", "Engelsrud terrasse", "Asker stasjon", "RE11", "rail", "Nationaltheatret stasjon")))
            .put("inbound", JSONObject().put("from", "Nationaltheatret").put("to", "Engelsrud").put("noService", false)
                .put("journeys", journeys("RE11", "rail", "Nationaltheatret stasjon", "Asker stasjon", "280", "bus", "Engelsrud terrasse")))
            .put("hops", JSONArray().put(hop("from-via", "Engelsrud terrasse", "Asker stasjon", 6)).put(hop("via-from", "Asker stasjon", "Engelsrud terrasse", 14)))
        context.getSharedPreferences("glance_commutes", 0).edit().putString("board_$widgetId", board.toString()).apply()
    }

    /** The local buses one way for days off: 71 and 280 taking turns, the first `first` minutes out. */
    private fun hop(id: String, from: String, to: String, first: Long): JSONObject {
        val now = Instant.now()
        val list = JSONArray()
        for (i in 0 until 6) {
            val start = Instant.ofEpochSecond((now.epochSecond / 60 + first + i * 10L) * 60)
            val end = start.plus(Duration.ofMinutes(12))
            val line = if (i % 2 == 0) "71" else "280"
            val names = listOf("Hagaløkka", "Oreholtet", "Drengsrud skole", "Måsan", "Trollstua").let { if (id == "via-from") it else it.reversed() }
            val stops = JSONArray()
            for ((n, name) in names.withIndex()) {
                val at = start.plus(Duration.ofMinutes(2L + n * 2)).toString()
                stops.put(JSONObject().put("name", name).put("platform", "").put("scheduledArrival", at).put("expectedArrival", at)
                    .put("scheduledDeparture", at).put("expectedDeparture", at).put("cancelled", false))
            }
            val ride = leg(line, "bus", from, to, start, end, "$id-$i").put("towards", to.removeSuffix(" stasjon")).put("fromQuay", "3").put("stops", stops)
            list.put(JSONObject().put("legs", JSONArray().put(ride))
                .put("aimedStart", start.toString()).put("expectedStart", start.toString()).put("aimedEnd", end.toString())
                .put("expectedEnd", end.toString()).put("key", "fixture-$id-$i").put("rideStart", start.toString()))
        }
        return JSONObject().put("id", id).put("from", from).put("to", to).put("journeys", list)
    }

    private fun leg(line: String, mode: String, from: String, to: String, start: Instant, end: Instant, id: String) = JSONObject()
        .put("mode", mode).put("line", line).put("serviceJourneyId", "fixture:$id").put("from", from).put("to", to)
        .put("aimedStart", start.toString()).put("expectedStart", start.toString()).put("aimedEnd", end.toString()).put("expectedEnd", end.toString())
        .put("cancelled", false).put("situations", JSONArray())

    private fun event(id: String, title: String, start: Instant, minutes: Long, calendar: String, meeting: String = "", location: String = "") =
        JSONObject().put("id", id).put("title", title).put("start", start.toString()).put("end", start.plus(Duration.ofMinutes(minutes)).toString())
            .put("allDay", false).put("calendarId", calendar).put("location", location).put("meetingUrl", meeting)
}
