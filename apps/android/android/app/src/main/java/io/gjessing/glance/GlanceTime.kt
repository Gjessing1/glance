package io.gjessing.glance

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A daily span of wall-clock time, [start, end). */
data class Window(val start: LocalTime, val end: LocalTime) {
    fun contains(time: LocalTime) = !time.isBefore(start) && time.isBefore(end)
    override fun toString() = "%02d:%02d–%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)
}

/**
 * The time rules every widget reads: work hours, the two commute windows and when the commute
 * turns for home. Settings, not constants, but these are the defaults (roadmap "Settled").
 */
data class TimeRules(
    val workDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    val work: Window = Window(LocalTime.of(8, 0), LocalTime.of(17, 0)),
    val morning: Window = Window(LocalTime.of(7, 0), LocalTime.of(12, 0)),
    val evening: Window = Window(LocalTime.of(12, 0), LocalTime.of(18, 30)),
    /** Before this the commute shows the way out, from it the way home. Where the windows meet by default. */
    val switchAt: LocalTime = LocalTime.NOON,
    /** On workdays the widget turns to the commute at this hour of the night, and back after the return window. */
    val focusStart: LocalTime = LocalTime.of(3, 0),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("workStart", hhmm(work.start)).put("workEnd", hhmm(work.end))
        .put("morningStart", hhmm(morning.start)).put("morningEnd", hhmm(morning.end))
        .put("eveningStart", hhmm(evening.start)).put("eveningEnd", hhmm(evening.end))
        .put("switchAt", hhmm(switchAt)).put("focusStart", hhmm(focusStart))

    companion object {
        private fun hhmm(time: LocalTime) = "%02d:%02d".format(time.hour, time.minute)
        private fun parse(value: String?, fallback: LocalTime) = try { if (value.isNullOrEmpty()) fallback else LocalTime.parse(value) } catch (_: Exception) { fallback }

        /** Missing or unreadable fields keep their default, so a partial save cannot break the clock. */
        fun fromJson(json: JSONObject?): TimeRules {
            val d = TimeRules()
            if (json == null) return d
            val rules = TimeRules(
                work = Window(parse(json.optString("workStart"), d.work.start), parse(json.optString("workEnd"), d.work.end)),
                morning = Window(parse(json.optString("morningStart"), d.morning.start), parse(json.optString("morningEnd"), d.morning.end)),
                evening = Window(parse(json.optString("eveningStart"), d.evening.start), parse(json.optString("eveningEnd"), d.evening.end)),
                switchAt = parse(json.optString("switchAt"), d.switchAt),
                focusStart = parse(json.optString("focusStart"), d.focusStart),
            )
            val windows = listOf(rules.work, rules.morning, rules.evening)
            return if (windows.all { it.start.isBefore(it.end) } && !rules.focusStart.isAfter(rules.morning.start)) rules else d
        }
    }
}

/**
 * One source for "what time is it here, and what does that mean": the zone, workdays and work
 * hours, the commute windows, which way the commute faces, and the header's context label. All of
 * it is java.time on local dates, so the DST changes need no special cases.
 */
object GlanceTime {
    /** Tests pin a zone; the app follows the device. */
    @Volatile var zoneOverride: ZoneId? = null
    fun zone(): ZoneId = zoneOverride ?: ZoneId.systemDefault()

    fun at(ms: Long, zone: ZoneId = zone()): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

    fun rules(context: Context): TimeRules = TimeRules.fromJson(GlanceSettings.get(context).optJSONObject("time"))

    fun isWorkday(t: ZonedDateTime, rules: TimeRules) = t.dayOfWeek in rules.workDays
    /** Smart Glance keeps the previous day's transport until commute focus starts. */
    fun isGlanceWorkday(t: ZonedDateTime, rules: TimeRules): Boolean {
        val day = if (t.toLocalTime().isBefore(rules.focusStart)) t.dayOfWeek.minus(1) else t.dayOfWeek
        return day in rules.workDays
    }
    fun inWorkHours(t: ZonedDateTime, rules: TimeRules) = isWorkday(t, rules) && rules.work.contains(t.toLocalTime())

    /** The commute window `t` falls in, on workdays only. */
    fun commuteWindow(t: ZonedDateTime, rules: TimeRules): Window? {
        if (!isWorkday(t, rules)) return null
        return listOf(rules.morning, rules.evening).firstOrNull { it.contains(t.toLocalTime()) }
    }

    /** Outbound before the switch time, home from it. */
    fun clockDirection(t: ZonedDateTime, rules: TimeRules): String =
        if (t.toLocalTime().isBefore(rules.switchAt)) CommuteRepository.OUTBOUND else CommuteRepository.INBOUND

    /**
     * Commute focus: on workdays from the night (03:00) until the return window ends. Inside it the
     * weather is the commute's rain check; outside it, and all weekend, the plain forecast.
     */
    fun commuteFocus(t: ZonedDateTime, rules: TimeRules): Boolean {
        if (!isWorkday(t, rules)) return false
        val time = t.toLocalTime()
        return !time.isBefore(rules.focusStart) && time.isBefore(rules.evening.end)
    }

    /** "Morning", "Workday", "Afternoon", "Evening" or "Weekend": the header's word for now. */
    fun contextLabel(t: ZonedDateTime, rules: TimeRules): String {
        if (!isGlanceWorkday(t, rules)) return "Weekend"
        val time = t.toLocalTime()
        return when {
            time.isBefore(rules.focusStart) -> "Evening"
            time.isBefore(rules.morning.end) -> "Morning"
            time.isBefore(rules.evening.start) -> "Workday"
            time.isBefore(rules.evening.end) -> "Afternoon"
            else -> "Evening"
        }
    }

    /**
     * The next instant after `t` at which any rule above changes its answer: a window or work-hours
     * edge, the direction switch, the start of commute focus or midnight. Searched over local dates, so a
     * time skipped by DST resolves to the instant after the gap.
     */
    fun nextRuleChange(t: ZonedDateTime, rules: TimeRules): ZonedDateTime {
        val times = listOf(rules.work.start, rules.work.end, rules.morning.start, rules.morning.end,
            rules.evening.start, rules.evening.end, rules.switchAt, rules.focusStart, LocalTime.MIDNIGHT)
        for (day in 0L..1L) {
            val date = t.toLocalDate().plusDays(day)
            val next = times.map { ZonedDateTime.of(date, it, t.zone) }.filter { it.isAfter(t) }.minOrNull()
            if (next != null) return next
        }
        return t.plusDays(1)
    }
}

/** App-wide settings kept natively: time rules and the Nodecal connection. The token never goes back to the web layer. */
object GlanceSettings {
    private const val prefsName = "glance_settings"

    fun get(context: Context): JSONObject {
        val settings = context.getSharedPreferences(prefsName, 0).getString("settings", null)?.let { JSONObject(it) } ?: JSONObject()
        // The windows used to be 07–10 and 15–18:30; a save that kept those defaults moves once to the new ones.
        val time = settings.optJSONObject("time")
        if (time != null && !settings.has("windowsMet") && time.optString("morningEnd") == "10:00" && time.optString("eveningStart") == "15:00") {
            time.put("morningEnd", "12:00").put("eveningStart", "12:00")
            put(context, settings.put("windowsMet", true))
        }
        return settings
    }

    fun put(context: Context, value: JSONObject) {
        context.getSharedPreferences(prefsName, 0).edit().putString("settings", value.toString()).apply()
    }

    /** Minutes between background refreshes; the refresh buttons cover anything sooner. */
    val refreshChoices = listOf(15L, 30L, 60L)
    fun refreshMinutes(context: Context): Long =
        get(context).optLong("refreshMinutes", 30L).takeIf { it in refreshChoices } ?: 30L

    fun nodecalToken(context: Context): String = context.getSharedPreferences(prefsName, 0).getString("nodecalToken", "") ?: ""
    fun setNodecalToken(context: Context, token: String) {
        context.getSharedPreferences(prefsName, 0).edit().putString("nodecalToken", token).apply()
    }
}
