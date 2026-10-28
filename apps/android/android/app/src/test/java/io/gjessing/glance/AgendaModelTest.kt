package io.gjessing.glance

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AgendaModelTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private fun at(text: String): Instant = ZonedDateTime.of(LocalDateTime.parse("2026-10-28T$text"), oslo).toInstant()
    private val now = at("08:15")

    private fun timed(id: String, title: String, start: String, end: String, calendar: String = "ics:work") =
        JSONObject().put("id", id).put("title", title).put("start", at(start).toString()).put("end", at(end).toString())
            .put("allDay", false).put("calendarId", calendar).put("location", "").put("meetingUrl", "")

    private fun allDay(id: String, title: String, date: String, calendar: String = "cal") =
        JSONObject().put("id", id).put("title", title).put("start", "${date}T00:00:00Z")
            .put("end", "${java.time.LocalDate.parse(date).plusDays(1)}T00:00:00Z").put("allDay", true).put("calendarId", calendar)

    private fun agenda(vararg events: JSONObject) = JSONObject()
        .put("profiles", JSONObject().put("personal", JSONArray().put("ics:work")).put("work", JSONArray().put("cal")).put("combined", JSONArray()))
        .put("calendars", JSONArray().put(JSONObject().put("id", "ics:work").put("name", "Enora").put("color", "#ba6e03"))
            .put(JSONObject().put("id", "cal").put("name", "Private").put("color", "#04701f")))
        .put("events", JSONArray().apply { events.forEach { put(it) } })

    @Test fun nextTwoWithCountdownsAndLabels() {
        val state = AgendaModel.state(agenda(
            timed("a", "Team meeting", "09:30", "10:00"),
            timed("b", "Dentist appointment", "11:00", "12:00", "cal"),
            timed("c", "Later", "13:00", "14:00")), "combined", now, oslo)
        assertEquals(listOf("Team meeting", "Dentist appointment"), state.slots.map { it.event.title })
        assertEquals(listOf("09:30", "11:00"), state.slots.map { it.time })
        assertEquals(listOf("In 75m", "In 2h 45m"), state.slots.map { it.countdown })
        assertEquals(listOf("Work · Enora", "Personal · Private"), state.slots.map { it.meta })
    }

    @Test fun profileHidesCalendars() {
        val state = AgendaModel.state(agenda(timed("a", "Team meeting", "09:30", "10:00"), timed("b", "Gym", "18:00", "19:00", "cal")),
            "personal", now, oslo)
        assertEquals(listOf("Gym"), state.slots.map { it.event.title })
        // Under Personal every event is personal, so the meta line is just the calendar's name.
        assertEquals(listOf("Private"), state.slots.map { it.meta })
    }

    @Test fun runningEventSaysWhenItEnds() {
        val running = AgendaModel.state(agenda(timed("a", "Workshop", "08:00", "10:30")), "combined", now, oslo)
        assertEquals("Until 10:30", running.slots[0].countdown)
        assertTrue(running.slots[0].ongoing)
        val ending = AgendaModel.state(agenda(timed("a", "Standup", "08:00", "08:30")), "combined", now, oslo)
        assertEquals("15m left", ending.slots[0].countdown)
    }

    @Test fun overlapIsShownAsAClash() {
        val state = AgendaModel.state(agenda(timed("a", "Review", "09:00", "10:00"), timed("b", "Interview", "09:30", "10:30", "cal")),
            "combined", now, oslo)
        assertTrue(state.slots[1].clash)
        assertEquals("Overlaps · Personal · Private", state.slots[1].meta)
    }

    @Test fun allDayEventsAreARowNotTheNextEvent() {
        val state = AgendaModel.state(agenda(allDay("x", "Emma's birthday", "2026-10-28"), allDay("y", "Flag day", "2026-10-28"),
            allDay("z", "Tomorrow thing", "2026-10-29"), timed("a", "Team meeting", "09:30", "10:00")), "combined", now, oslo)
        assertEquals("All day: Emma's birthday +1 more", state.allDayText)
        assertEquals(listOf("x", "y"), state.allDay.map { it.event.id })
        assertEquals("Private", state.allDayLabel)
        assertEquals(listOf("Team meeting"), state.slots.map { it.event.title })
    }

    private fun inDays(days: Long, id: String, start: String, end: String) =
        timed(id, id, start, end).put("start", at(start).plusSeconds(days * 86400).toString()).put("end", at(end).plusSeconds(days * 86400).toString())

    @Test fun pagesStayWithinTodayAndTomorrow() {
        // Wednesday morning: Thursday's events page, Friday's wait until there is nothing sooner.
        val events = agenda(timed("a", "One", "09:00", "10:00"), inDays(1, "Thu", "23:00", "23:30"), inDays(2, "Fri", "09:00", "10:00"))
        val state = AgendaModel.state(events, "combined", now, oslo, 1)
        assertEquals(2, state.pages)
        assertEquals(listOf("Thu"), AgendaModel.state(events, "combined", now, oslo, 1, 1).slots.map { it.event.title })
    }

    @Test fun emptyTwoDaysReachOnThroughTheWeek() {
        val state = AgendaModel.state(agenda(inDays(5, "Mon", "09:00", "09:15"), inDays(8, "Late", "09:00", "09:15")), "combined", now, oslo)
        assertEquals(listOf("Mon"), state.slots.map { it.event.title })
        assertEquals("Mon", state.slots[0].countdown)
        assertEquals(1, state.pages)
        assertTrue(AgendaModel.state(agenda(), "combined", now, oslo).slots.isEmpty())
    }

    @Test fun tomorrowAndLaterDaysAreNamed() {
        val tomorrow = JSONObject().put("id", "t").put("title", "Early").put("start", at("07:00").plusSeconds(86400).toString())
            .put("end", at("08:00").plusSeconds(86400).toString()).put("calendarId", "cal")
        assertEquals("Tomorrow", AgendaModel.state(agenda(tomorrow), "combined", now, oslo).slots[0].countdown)
    }

    @Test fun futureDayTintFollowsTheLocalDateAndClearsAtMidnight() {
        // 00:30 in Oslo is still the previous UTC date.
        val start = at("00:30").plusSeconds(86400)
        val tomorrow = JSONObject().put("id", "t").put("title", "Early")
            .put("start", start.toString()).put("end", start.plusSeconds(3600).toString()).put("calendarId", "cal")
        val events = agenda(timed("a", "Today", "09:00", "10:00"), tomorrow)
        val before = AgendaModel.state(events, "combined", now, oslo)
        assertEquals(listOf(false, true), before.slots.map { it.futureDay })
        val midnight = at("00:00").plusSeconds(86400)
        val after = AgendaModel.state(events, "combined", midnight, oslo)
        assertEquals(false, after.slots.single().futureDay)
        // An event that began yesterday and is still ongoing belongs with today's events.
        val overnight = timed("o", "Overnight", "23:30", "23:45")
            .put("end", midnight.plusSeconds(3600).toString())
        val ongoing = AgendaModel.state(agenda(overnight), "combined", midnight, oslo).slots.single()
        assertTrue(ongoing.ongoing)
        assertEquals(false, ongoing.futureDay)
    }

    @Test fun laterEventsArePagesAfterTheNextTwo() {
        val events = agenda(timed("a", "One", "09:00", "10:00"), timed("b", "Two", "10:00", "11:00"),
            timed("c", "Three", "11:00", "12:00"), timed("d", "Four", "12:00", "13:00"), timed("e", "Five", "13:00", "14:00"))
        val first = AgendaModel.state(events, "combined", now, oslo)
        assertEquals(3, first.pages)
        assertEquals(2, first.rows)
        assertEquals(listOf("One", "Two"), first.slots.map { it.event.title })
        assertEquals(listOf("Three", "Four"), AgendaModel.state(events, "combined", now, oslo, 2, 1).slots.map { it.event.title })
        val last = AgendaModel.state(events, "combined", now, oslo, 2, 9)
        assertEquals(2, last.page)
        assertEquals(listOf("Five"), last.slots.map { it.event.title })
        // Every page names the same next event, so paging alone never resets the carousel.
        assertEquals(first.anchor, last.anchor)
        assertEquals(1, AgendaModel.state(agenda(timed("a", "One", "09:00", "10:00")), "combined", now, oslo).pages)
    }

    @Test fun everyEventHasAPage() {
        val events = agenda(*(0 until 20).map { timed("e$it", "Event $it", "%02d:30".format(9 + it / 2), "%02d:45".format(9 + it / 2)) }.toTypedArray())
        val state = AgendaModel.state(events, "combined", now, oslo, count = 1)
        // The dots say how many pages there are, so no event may be left past the last of them.
        assertEquals("e19", AgendaModel.state(events, "combined", now, oslo, count = 1, page = state.pages - 1).slots.single().event.id)
    }

    @Test fun clashOnALaterPageIsAgainstItsOwnFirstEvent() {
        val events = agenda(timed("a", "One", "09:00", "09:30"), timed("b", "Two", "10:00", "10:30"),
            timed("c", "Three", "11:00", "12:00"), timed("d", "Four", "11:30", "12:30"))
        assertTrue(AgendaModel.state(events, "combined", now, oslo, 2, 1).slots[1].clash)
    }

    @Test fun anchorFollowsTheNextEvent() {
        val before = AgendaModel.state(agenda(timed("a", "One", "09:00", "10:00"), timed("b", "Two", "10:00", "11:00")), "combined", now, oslo)
        val after = AgendaModel.state(agenda(timed("a", "One", "09:00", "10:00"), timed("b", "Two", "10:00", "11:00")), "combined", at("10:05"), oslo)
        assertTrue(before.anchor != after.anchor)
    }

    @Test fun autoProfileFollowsCommuteFocus() {
        val rules = TimeRules()
        fun auto(time: String) = AgendaModel.resolveProfile("auto", ZonedDateTime.of(LocalDateTime.parse(time), oslo), rules)
        assertEquals("personal", auto("2026-10-28T02:59"))
        assertEquals("combined", auto("2026-10-28T03:00"))
        assertEquals("combined", auto("2026-10-28T07:30"))
        assertEquals("combined", auto("2026-10-28T18:29"))
        assertEquals("personal", auto("2026-10-28T18:30"))
        // Friday evening stays Personal through the weekend until Monday's 03:00.
        assertEquals("personal", auto("2026-10-31T09:00"))
        assertEquals("personal", auto("2026-11-02T02:00"))
        assertEquals("combined", auto("2026-11-02T03:00"))
        assertEquals("work", AgendaModel.resolveProfile("work", ZonedDateTime.of(LocalDateTime.parse("2026-10-31T09:00"), oslo), rules))
    }

    @Test fun boundariesStepFasterNearAStart() {
        val events = AgendaModel.events(agenda(timed("a", "Team meeting", "09:30", "10:00")))
        assertEquals(at("08:20"), AgendaModel.nextBoundary(events, now))
        assertEquals(at("09:21"), AgendaModel.nextBoundary(events, at("09:20")))
        assertEquals(at("09:30"), AgendaModel.nextBoundary(events, at("09:29:30")))
    }
}
