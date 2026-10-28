package io.gjessing.glance

import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** One event from Nodecal's `/api/glance/agenda`, as the widget needs it. */
data class AgendaEvent(
    val id: String, val title: String, val start: Instant, val end: Instant, val allDay: Boolean,
    val calendarId: String, val location: String, val meetingUrl: String,
) {
    companion object {
        fun from(json: JSONObject): AgendaEvent? {
            val start = CommuteRepository.millis(json.optString("start")) ?: return null
            val end = CommuteRepository.millis(json.optString("end")) ?: start
            return AgendaEvent(json.optString("id"), json.optString("title"), Instant.ofEpochMilli(start), Instant.ofEpochMilli(end),
                json.optBoolean("allDay"), json.optString("calendarId"), json.optString("location"), json.optString("meetingUrl"))
        }
    }
}

data class AgendaCalendar(val id: String, val name: String, val color: String)

/** A shown event: what goes in the time column, the bar colour and the meta line. */
data class AgendaSlot(
    val event: AgendaEvent, val time: String, val countdown: String, val color: String, val meta: String,
    val ongoing: Boolean, val clash: Boolean, val futureDay: Boolean,
)

/** One of today's all-day events, with its calendar's name and colour. */
data class AllDayItem(val event: AgendaEvent, val calendar: String, val color: String)

data class AgendaState(
    val slots: List<AgendaSlot>,
    /** Today's all-day events; the row shows the first and "+N more" for the rest. */
    val allDay: List<AllDayItem>,
    /** The carousel page shown and how many there are; page 0 is the next events, the rest come after. */
    val page: Int = 0,
    val pages: Int = 1,
    /** The most rows any page has, so the section keeps one height while paging. */
    val rows: Int = slots.size,
    /** Names the next upcoming event; when it changes, the carousel goes back to page 0. */
    val anchor: String = "",
) {
    /** "All day: Emma's birthday +1 more", or null when today has none. */
    val allDayText: String? get() = allDay.firstOrNull()?.let { "All day: ${it.event.title}" + if (allDay.size > 1) " +${allDay.size - 1} more" else "" }
    val allDayLabel: String get() = allDay.firstOrNull()?.calendar.orEmpty()
}

/**
 * Agenda rules, all pure: which Nodecal profile applies, what each calendar is labelled, and which
 * two events the widget shows. Profiles are client-side presets in Nodecal, so the endpoint sends each
 * profile's hidden calendars and Glance filters locally, which is why the 03:00 and evening switches
 * need no network.
 */
object AgendaModel {
    const val AUTO = "auto"
    private const val LATER_DAYS = 7L

    /**
     * Auto is Combined while the commute has focus (workdays from 03:00 until the return window ends,
     * 18:30 by default), so the morning's work meetings show from the moment the widget turns to the
     * commute, and Personal from then until the next workday's 03:00.
     */
    fun resolveProfile(choice: String, t: ZonedDateTime, rules: TimeRules): String = when {
        choice.isNotEmpty() && choice != AUTO -> choice
        GlanceTime.commuteFocus(t, rules) -> "combined"
        else -> "personal"
    }

    fun profileName(profile: String) = profile.replaceFirstChar { it.uppercase() }

    /** A calendar Personal hides is Work, one Work hides is Personal; the rest get no label. */
    fun calendarLabel(calendarId: String, profiles: Map<String, Set<String>>): String = when {
        calendarId in (profiles["personal"] ?: emptySet()) -> "Work"
        calendarId in (profiles["work"] ?: emptySet()) -> "Personal"
        else -> ""
    }

    fun profiles(agenda: JSONObject): Map<String, Set<String>> {
        val map = mutableMapOf<String, Set<String>>()
        val json = agenda.optJSONObject("profiles") ?: return map
        for (key in json.keys()) {
            val list = json.optJSONArray(key)
            map[key] = (0 until (list?.length() ?: 0)).map { list!!.getString(it) }.toSet()
        }
        return map
    }

    fun calendars(agenda: JSONObject): Map<String, AgendaCalendar> {
        val list = agenda.optJSONArray("calendars")
        return (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }
            .associate { it.optString("id") to AgendaCalendar(it.optString("id"), it.optString("name"), it.optString("color")) }
    }

    fun events(agenda: JSONObject): List<AgendaEvent> {
        val list = agenda.optJSONArray("events")
        return (0 until (list?.length() ?: 0)).mapNotNull { AgendaEvent.from(list!!.getJSONObject(it)) }
    }

    /** The whole agenda state for one widget at `now`, from the cached response. */
    fun state(agenda: JSONObject, profile: String, now: Instant, zone: ZoneId, count: Int = 2, page: Int = 0): AgendaState {
        val profiles = profiles(agenda)
        return state(events(agenda), calendars(agenda), profiles, now, zone, count, page, profiles[profile] ?: emptySet())
    }

    /**
     * Page 0 is the next `count` events; later pages carry on `count` at a time until every event has
     * one, as the pager says how many there are. Both stay within today and tomorrow, reaching on through the week only when those two days
     * have nothing. A page past the end shows the last one.
     */
    fun state(all: List<AgendaEvent>, calendars: Map<String, AgendaCalendar>, profiles: Map<String, Set<String>>,
              now: Instant, zone: ZoneId, count: Int = 2, page: Int = 0, hidden: Set<String> = emptySet()): AgendaState {
        val events = all.filter { it.calendarId !in hidden }
        val labelled = mixes(calendars.keys - hidden, profiles)
        val today = now.atZone(zone).toLocalDate()
        val timed = events.filter { !it.allDay && it.end.isAfter(now) }.sortedWith(compareBy({ it.start }, { it.end }))
        val twoDays = today.plusDays(2).atStartOfDay(zone).toInstant()
        val week = now.plus(Duration.ofDays(LATER_DAYS))
        val soon = timed.filter { it.start.isBefore(twoDays) }.ifEmpty { timed.filter { it.start.isBefore(week) } }
        val pages = soon.chunked(count.coerceAtLeast(1)).ifEmpty { listOf(emptyList()) }
        val current = page.coerceIn(0, pages.lastIndex)
        val shown = pages[current]
        // The second slot overlapping the first is a clash, worth saying out loud.
        val slots = shown.mapIndexed { index, event -> slot(event, index > 0 && event.start.isBefore(shown[0].end), calendars, profiles, labelled, now, zone) }
        val allDay = events.filter { it.allDay && covers(it, today) }
            .map { AllDayItem(it, calendars[it.calendarId]?.name.orEmpty(), calendars[it.calendarId]?.color.orEmpty()) }
        val anchor = timed.firstOrNull()?.let { "${it.id}@${it.start.epochSecond}" }.orEmpty()
        return AgendaState(slots, allDay, current, pages.size, pages.maxOf { it.size }, anchor)
    }

    /**
     * Work/Personal labels only say something when the calendars shown include both kinds (Combined);
     * under Personal or Work every event is that kind already.
     */
    internal fun mixes(shown: Set<String>, profiles: Map<String, Set<String>>): Boolean =
        shown.any { calendarLabel(it, profiles) == "Work" } && shown.any { calendarLabel(it, profiles) == "Personal" }

    /** The meta line: the calendar's own name, after its Work/Personal label where the profile mixes both. */
    private fun slot(event: AgendaEvent, clash: Boolean, calendars: Map<String, AgendaCalendar>, profiles: Map<String, Set<String>>,
                     labelled: Boolean, now: Instant, zone: ZoneId): AgendaSlot {
        val calendar = calendars[event.calendarId]
        val meta = listOf(if (labelled) calendarLabel(event.calendarId, profiles) else "", calendar?.name.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
        return AgendaSlot(event, clock(event.start, zone), countdown(event, now, zone), calendar?.color.orEmpty(),
            if (clash) "Overlaps · $meta".trimEnd(' ', '·') else meta, !event.start.isAfter(now), clash,
            event.start.atZone(zone).toLocalDate().isAfter(now.atZone(zone).toLocalDate()))
    }

    /**
     * An all-day event is stored as UTC midnights; its days are compared as dates, never as instants
     * shifted into the local zone.
     */
    internal fun covers(event: AgendaEvent, day: LocalDate): Boolean {
        val first = LocalDate.parse(event.start.toString().substring(0, 10))
        val endExclusive = LocalDate.parse(event.end.toString().substring(0, 10)).let { if (it.isAfter(first)) it else first.plusDays(1) }
        return !day.isBefore(first) && day.isBefore(endExclusive)
    }

    /**
     * Under the start time: "In 75m" or "In 2h 45m" today, "Tomorrow" or the weekday beyond. A running
     * event says "Until 10:30", and in its last half hour "15m left".
     */
    internal fun countdown(event: AgendaEvent, now: Instant, zone: ZoneId): String {
        if (!event.start.isAfter(now)) {
            val left = ceilMinutes(now, event.end)
            return if (left <= 30) "${left.coerceAtLeast(1)}m left" else "Until ${clock(event.end, zone)}"
        }
        val day = event.start.atZone(zone).toLocalDate()
        if (day != now.atZone(zone).toLocalDate()) return dayName(event.start, now, zone)
        return "In " + span(ceilMinutes(now, event.start))
    }

    /** Whole minutes to go, rounded up: a start 74.5 minutes away still reads "In 75m". */
    internal fun ceilMinutes(from: Instant, to: Instant) = (Duration.between(from, to).seconds + 59) / 60

    /** Minutes up to 90, then hours and minutes: "In 75m", "In 2h 45m", "In 3h". */
    internal fun span(minutes: Long): String {
        val m = minutes.coerceAtLeast(1)
        if (m <= 90) return "${m}m"
        val rest = m % 60
        return if (rest == 0L) "${m / 60}h" else "${m / 60}h ${rest}m"
    }

    internal fun dayName(at: Instant, now: Instant, zone: ZoneId): String {
        val day = at.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (day) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        }
    }

    internal fun clock(at: Instant, zone: ZoneId): String = at.atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }

    /**
     * The next instant the agenda's text changes on its own: an event starting or ending, or the
     * countdown's next step (every minute in the last 15 before a start or end, otherwise every 5).
     */
    fun nextBoundary(events: List<AgendaEvent>, now: Instant): Instant? {
        val edges = events.filter { !it.allDay }.flatMap { listOf(it.start, it.end) }.filter { it.isAfter(now) }
        val next = edges.minOrNull() ?: return null
        val until = Duration.between(now, next)
        val step = if (until <= Duration.ofMinutes(16)) Duration.ofMinutes(1) else Duration.ofMinutes(5)
        val stepped = now.plus(step).let { Instant.ofEpochSecond(it.epochSecond / 60 * 60) }
        return if (stepped.isBefore(next)) stepped else next
    }
}
