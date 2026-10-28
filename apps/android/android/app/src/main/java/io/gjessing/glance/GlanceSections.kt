package io.gjessing.glance

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** How much room a section gets: one line, a short card, or the whole card. */
enum class SizeClass { LINE, COMPACT, FULL }

/** Everything a section reads to render one widget at one moment. */
class SectionContext(
    val context: Context, val widgetId: Int, val config: JSONObject,
    val now: Instant, val zone: ZoneId, val rules: TimeRules, val scale: Float,
) {
    /** Events per page in a full agenda: two, more when the widget has room to spare (see [AgendaSection.fill]). */
    var agendaRows = 2
    val local: ZonedDateTime get() = now.atZone(zone)
    val nowMs get() = now.toEpochMilli()
    fun dp(value: Int) = (value * scale).toInt()
}

/**
 * The section contract (roadmap C1). A section picks its size class from clear thresholds only, so
 * the widget does not reshuffle on small changes (C7); it can step down when room runs out, says how
 * urgent it is, and names the next instant its text changes without new data (F5).
 */
interface GlanceSection {
    val id: String
    /** The size this section asks for now, or null to stay hidden. */
    fun wanted(sc: SectionContext): SizeClass?
    /** One size down when the widget is short of room, or null at the smallest. */
    fun smaller(size: SizeClass): SizeClass? = when (size) {
        SizeClass.FULL -> SizeClass.COMPACT
        SizeClass.COMPACT -> SizeClass.LINE
        SizeClass.LINE -> null
    }
    /** Estimated height in dp including the gap above, measured on the emulator at font scale 1. */
    fun height(sc: SectionContext, size: SizeClass): Int
    fun render(sc: SectionContext, size: SizeClass): RemoteViews
    fun urgency(sc: SectionContext): Int
    fun nextBoundary(sc: SectionContext): Instant?
}

object Sections {
    fun of(id: String): GlanceSection = when (id) {
        "commute" -> CommuteSection
        "local" -> LocalSection
        "agenda" -> AgendaSection
        else -> WeatherSection
    }

    /** The countdown's next step before `edge`: every minute in the last `fine` minutes, otherwise every five. */
    fun step(now: Instant, edge: Instant, fine: Long): Instant {
        val until = Duration.between(now, edge)
        val step = if (until <= Duration.ofMinutes(fine + 1)) 1L else 5L
        val next = Instant.ofEpochSecond((now.epochSecond / 60 + step) * 60)
        return if (next.isBefore(edge)) next else edge
    }

    /** The next whole minute, or `edge` when that comes first. */
    fun minute(now: Instant, edge: Instant): Instant {
        val next = Instant.ofEpochSecond((now.epochSecond / 60 + 1) * 60)
        return if (next.isBefore(edge)) next else edge
    }

    /**
     * Minutes after which departures no longer pass for live: one missed refresh inside a commute
     * window (BoundaryAlarm asks every fifteen), a good part of the background interval outside them.
     */
    private const val TRANSPORT_FRESH = 15L

    /** "Updated 18 min ago" once transport checked at `checked` is past fresh ("Updated 14:05" after an hour), otherwise "". */
    fun transportAge(checked: String, nowMs: Long): String {
        val at = CommuteRepository.millis(checked) ?: return ""
        val minutes = (nowMs - at) / 60_000L
        return when {
            minutes <= TRANSPORT_FRESH -> ""
            minutes < 60 -> "Updated $minutes min ago"
            else -> "Updated ${CommuteWidget.clock(checked)}"
        }
    }

    /** When the age label next appears or changes: the instant transport goes past fresh, then every minute of the first hour. */
    fun transportAgeBoundary(checked: String, now: Instant): Instant? {
        val at = CommuteRepository.millis(checked)?.let { Instant.ofEpochMilli(it) } ?: return null
        val minutes = Duration.between(at, now).toMinutes()
        return when {
            minutes < TRANSPORT_FRESH -> at.plus(Duration.ofMinutes(TRANSPORT_FRESH + 1))
            minutes < 60 -> at.plus(Duration.ofMinutes(minutes + 1))
            else -> null
        }
    }

    fun openApp(context: Context, widgetId: Int, extras: Intent.() -> Unit = {}): Intent =
        Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("glance://open/$widgetId/${System.nanoTime()}")
            putExtra("widgetId", widgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            extras()
        }

    /**
     * Entur's tickets tab, where the active pass is the first card. `no.entur://kjop-billett` is not a
     * documented link (Entur documents only departure boards and travel searches), so when a release
     * stops resolving it the app is launched plainly, and its Play Store page is offered when it is
     * missing. Clearing the task makes every tap land like a cold start, not wherever Entur was left.
     */
    fun enturIntent(context: Context): Intent {
        val tickets = Intent(Intent.ACTION_VIEW, Uri.parse("$ENTUR://kjop-billett")).setPackage(ENTUR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (tickets.resolveActivity(context.packageManager) != null) return tickets
        return context.packageManager.getLaunchIntentForPackage(ENTUR)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$ENTUR")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private const val ENTUR = "no.entur"

    fun ticket(sc: SectionContext): PendingIntent = WidgetBlocks.activity(sc.context, sc.widgetId * 100 + 97, enturIntent(sc.context))

    fun note(context: Context, title: String, detail: String = ""): RemoteViews {
        val note = RemoteViews(context.packageName, R.layout.widget_glance_note)
        note.setTextViewText(R.id.note_title, title)
        if (detail.isNotEmpty()) {
            note.setTextViewText(R.id.note_detail, detail)
            note.setViewVisibility(R.id.note_detail, View.VISIBLE)
        }
        return note
    }
}

/**
 * The next connection with both vehicles side by side and a transfer verdict (C3). Through commute
 * focus (workdays from 03:00 until the return window ends), or while a journey is tracked, it takes
 * the full card, facing work before the switch and home from it (C4); outside focus it is hidden.
 */
object CommuteSection : GlanceSection {
    override val id = "commute"

    private class Next(val names: JSONObject, val direction: String, val journey: JSONObject?, val tracked: Boolean, val stale: String,
                       val later: List<JSONObject>)

    private fun next(sc: SectionContext): Next? {
        val board = CommuteRepository.cached(sc.context, sc.widgetId) ?: return null
        val mode = CommuteRepository.mode(sc.context, sc.widgetId, sc.nowMs)
        // A tracked journey holds the card until it arrives, whatever the clock or an older tap says by then.
        val direction = board.optJSONObject("tracked")?.optString("direction")?.takeIf { it.isNotEmpty() }
            ?: if (mode == CommuteRepository.VIA) CommuteRepository.clockMode(sc.nowMs, sc.rules) else mode
        val track = board.optJSONObject("tracked")?.takeIf { it.optString("direction") == direction }
        val trackedKey = track?.optJSONObject("journey")?.optString("key")
        val all = board.optJSONObject(direction)?.optJSONArray("journeys")
        val journeys = (0 until (all?.length() ?: 0)).map { all!!.getJSONObject(it) }
        val journey = journeys.firstOrNull { it.optString("key") == trackedKey }
            ?: journeys.firstOrNull { (CommuteRepository.millis(it.optString("rideStart")) ?: 0) > sc.nowMs }
        val stale = Sections.transportAge(board.optString("checkedAt"), sc.nowMs)
        val later = journeys.filter { it !== journey && (CommuteRepository.millis(it.optString("rideStart")) ?: 0) > sc.nowMs }.take(3)
        return Next(board.optJSONObject("names") ?: JSONObject(), direction, journey, journey != null && journey.optString("key") == trackedKey, stale, later)
    }

    /**
     * Outside commute focus (evenings, nights, weekends) the commute steps aside unless a journey is
     * being tracked; inside it the full card, so both vehicles and the later departures are always there.
     */
    override fun wanted(sc: SectionContext): SizeClass? {
        val next = next(sc)
        if (next?.tracked == true) return SizeClass.FULL
        if (!GlanceTime.commuteFocus(sc.local, sc.rules)) return null
        if (next == null || next.journey == null || CommuteRepository.restDay(sc.context, sc.widgetId, sc.nowMs)) return SizeClass.LINE
        return SizeClass.FULL
    }

    override fun height(sc: SectionContext, size: SizeClass) = when (size) {
        SizeClass.FULL -> sc.dp(166)
        SizeClass.COMPACT -> sc.dp(144)
        SizeClass.LINE -> sc.dp(46)
    }

    /** Full adds the later departures under the verdict; compact leaves them out when room is short. */
    override fun smaller(size: SizeClass) = when (size) {
        SizeClass.FULL -> SizeClass.COMPACT
        SizeClass.COMPACT -> SizeClass.LINE
        SizeClass.LINE -> null
    }

    override fun urgency(sc: SectionContext): Int {
        val next = next(sc) ?: return 0
        val rides = next.journey?.let { CommuteRepository.rides(it) } ?: return 0
        if (verdict(rides).second == Tone.CRITICAL) return 3
        if (next.tracked) return 3
        return if (GlanceTime.commuteFocus(sc.local, sc.rules)) 2 else 0
    }

    override fun nextBoundary(sc: SectionContext): Instant? {
        // A hidden commute keeps no age label to tick.
        val age = CommuteRepository.cached(sc.context, sc.widgetId)?.takeIf { wanted(sc) != null }?.let { Sections.transportAgeBoundary(it.optString("checkedAt"), sc.now) }
        val journey = next(sc)?.journey ?: return age
        val starts = CommuteRepository.rides(journey).mapNotNull { CommuteRepository.millis(it.optString("expectedStart")) }
            .map { Instant.ofEpochMilli(it) }.filter { it.isAfter(sc.now) }
        val edge = starts.minOrNull() ?: return age
        return listOfNotNull(Sections.step(sc.now, edge, 20), age).min()
    }

    override fun render(sc: SectionContext, size: SizeClass): RemoteViews {
        val context = sc.context
        val open = WidgetBlocks.activity(context, sc.widgetId * 100 + 61, Sections.openApp(context, sc.widgetId) {
            putExtra("commuteMode", CommuteRepository.mode(context, sc.widgetId, sc.nowMs))
            putExtra("journeyKey", "")
        })
        if (CommuteRepository.restDay(context, sc.widgetId, sc.nowMs)) return quietLine(context, "No commute today", open)
        val next = next(sc) ?: return quietLine(context, "Loading journeys…", open)
        val journey = next.journey ?: return quietLine(context, "No journeys in the next hours", open)
        val rides = CommuteRepository.rides(journey)
        if (size == SizeClass.LINE) return line(sc, next, journey, rides)

        val title = if (next.direction == CommuteRepository.OUTBOUND) "Towards ${next.names.optString("to")}" else "Towards home"
        val right = next.stale.ifEmpty { if (next.tracked) "Tracking" else "Next connection" }
        val card = WidgetBlocks.section(context, R.drawable.ic_commute, title, right)
        if (next.stale.isNotEmpty()) card.textColor(context, R.id.section_right, R.color.glance_late)
        if (next.tracked) card.setInt(R.id.section_card, "setBackgroundResource", R.drawable.tracked_card_background)
        ticketButton(sc, card)
        val legs = RemoteViews(context.packageName, R.layout.widget_glance_legs)
        legs.removeAllViews(R.id.legs)
        for ((index, ride) in rides.take(2).withIndex()) legs.addView(R.id.legs, leg(sc, ride).apply {
            setOnClickPendingIntent(R.id.leg_item, JourneyActivity.legIntent(context, sc.widgetId, next.direction, journey, ride, sc.widgetId * 100 + 50 + index))
        })
        card.addView(R.id.section_body, legs)
        val (text, tone) = verdict(rides)
        card.addView(R.id.section_body, WidgetBlocks.status(context, if (rides.size > 2) "$text · ${rides.size - 2} more" else text, tone))
        if (size == SizeClass.FULL && next.later.isNotEmpty()) card.addView(R.id.section_body, later(sc, next))
        // The title row and verdict open the whole commute in the app; a vehicle opens its own sheet.
        card.setOnClickPendingIntent(R.id.section_card, open)
        return card
    }

    /** The connections after the next: each first vehicle as a line chip with its time, a tap away from its sheet. */
    private fun later(sc: SectionContext, next: Next): RemoteViews {
        val row = RemoteViews(sc.context.packageName, R.layout.widget_glance_later)
        row.removeAllViews(R.id.later_items)
        for ((index, journey) in next.later.withIndex()) {
            val ride = CommuteRepository.rides(journey).firstOrNull() ?: continue
            val item = RemoteViews(sc.context.packageName, R.layout.widget_glance_later_item)
            WidgetBlocks.badge(item, ride.optString("line"), ride.optString("mode"))
            val start = ride.optString("expectedStart")
            item.setTextViewText(R.id.later_time, CommuteWidget.clock(start))
            if (ride.optBoolean("cancelled") || CommuteWidget.minutes(ride.optString("aimedStart"), start) > 0) item.textColor(sc.context, R.id.later_time, R.color.glance_late)
            item.setContentDescription(R.id.later_item, "${ride.optString("line")} at ${CommuteWidget.clock(start)}")
            item.setOnClickPendingIntent(R.id.later_item, JourneyActivity.legIntent(sc.context, sc.widgetId, next.direction, journey, ride, sc.widgetId * 100 + 52 + index))
            row.addView(R.id.later_items, item)
        }
        return row
    }

    private fun leg(sc: SectionContext, ride: JSONObject): RemoteViews {
        val leg = RemoteViews(sc.context.packageName, R.layout.widget_glance_leg)
        WidgetBlocks.badge(leg, ride.optString("line"), ride.optString("mode"))
        leg.setTextViewText(R.id.leg_from, shortStop(ride.optString("from")))
        val start = ride.optString("expectedStart")
        val late = ride.optBoolean("cancelled") || CommuteWidget.minutes(ride.optString("aimedStart"), start) > 0
        WidgetBlocks.timeWithCountdown(sc.context, leg, R.id.leg_time, R.id.leg_countdown, CommuteWidget.clock(start), countdown(start, sc.nowMs), late)
        leg.setTextViewText(R.id.leg_arrival, "${shortStop(ride.optString("to"))} ${CommuteWidget.clock(ride.optString("expectedEnd"))}")
        return leg
    }

    private fun line(sc: SectionContext, next: Next, journey: JSONObject, rides: List<JSONObject>): RemoteViews {
        val context = sc.context
        val first = rides.first()
        val views = RemoteViews(context.packageName, R.layout.widget_glance_commute_line)
        if (next.tracked) views.setInt(R.id.commute_line, "setBackgroundResource", R.drawable.tracked_card_background)
        WidgetBlocks.badge(views, first.optString("line"), first.optString("mode"))
        val start = first.optString("expectedStart")
        val late = first.optBoolean("cancelled") || CommuteWidget.minutes(first.optString("aimedStart"), start) > 0
        WidgetBlocks.timeWithCountdown(context, views, R.id.line_time, R.id.line_countdown, CommuteWidget.clock(start), countdown(start, sc.nowMs), late)
        val to = if (next.direction == CommuteRepository.OUTBOUND) next.names.optString("to") else next.names.optString("from")
        views.setTextViewText(R.id.line_dest, next.stale.ifEmpty { "→ ${shortStop(to)} ${CommuteWidget.clock(journey.optString("expectedEnd"))}" })
        if (next.stale.isNotEmpty()) views.textColor(context, R.id.line_dest, R.color.glance_late)
        else if (verdict(rides).second == Tone.CRITICAL) views.textColor(context, R.id.line_dest, R.color.glance_late)
        views.setViewVisibility(R.id.line_ticket, View.VISIBLE)
        views.setOnClickPendingIntent(R.id.line_ticket, Sections.ticket(sc))
        // Its sheet offers the whole commute too, so the row needs no separate way into the app.
        views.setOnClickPendingIntent(R.id.commute_line, JourneyActivity.legIntent(context, sc.widgetId, next.direction, journey, first, sc.widgetId * 100 + 55))
        return views
    }

    /** The ticket icon in a card's title row. */
    fun ticketButton(sc: SectionContext, card: RemoteViews) {
        card.setImageViewResource(R.id.section_action, R.drawable.ic_ticket)
        card.setContentDescription(R.id.section_action, "Ticket in Entur")
        card.setViewVisibility(R.id.section_action, View.VISIBLE)
        card.setOnClickPendingIntent(R.id.section_action, Sections.ticket(sc))
    }

    private fun quietLine(context: Context, text: String, open: android.app.PendingIntent): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_glance_commute_line)
        views.setViewVisibility(R.id.line_badge, View.GONE)
        views.setTextViewText(R.id.line_time, text)
        views.textColor(context, R.id.line_time, R.color.glance_muted)
        views.setViewVisibility(R.id.line_countdown, View.GONE)
        views.setOnClickPendingIntent(R.id.commute_line, open)
        return views
    }

    /** "9 min" until a departure, "now" in its last minute, nothing beyond an hour and a half. */
    internal fun countdown(iso: String, nowMs: Long): String {
        val at = CommuteRepository.millis(iso) ?: return ""
        val minutes = Math.floorDiv(at - nowMs, 60_000L)
        return when {
            minutes < 1 -> "now"
            minutes <= 90 -> "$minutes min"
            else -> ""
        }
    }

    /** "Asker stasjon" stays, "Nationaltheatret stasjon" loses the suffix the line already implies. */
    private fun shortStop(name: String) = name.removeSuffix(" stasjon").ifEmpty { name }

    /** The transfer verdict under the legs: the minutes in green, amber when tight and red when broken; the colour says the rest. */
    internal fun verdict(rides: List<JSONObject>): Pair<String, Tone> {
        rides.firstOrNull { it.optBoolean("cancelled") }?.let { return "${it.optString("line")} cancelled" to Tone.CRITICAL }
        if (rides.size < 2) {
            val ride = rides.firstOrNull() ?: return "" to Tone.NORMAL
            return "Direct · arrives ${CommuteWidget.clock(ride.optString("expectedEnd"))}" to Tone.NORMAL
        }
        val previous = rides[0]
        val next = rides[1]
        val at = shortStop(next.optString("from"))
        val live = CommuteWidget.minutes(previous.optString("expectedEnd"), next.optString("expectedStart"))
        return when {
            live < 0 -> "Misses the ${next.optString("line")} at $at" to Tone.CRITICAL
            live < 4 -> "$live min transfer at $at · tight" to Tone.WARNING
            else -> "$live min transfer at $at" to Tone.OK
        }
    }
}

/**
 * Outside commute focus: no commute, but the buses to the change and back (Engelsrud ⇄ Asker by
 * default), as two rows of departure badges like the departure widget's, each under its direction.
 * Every bus says the minutes to it; the first each way leads, later ones are lighter. Smart Glance
 * shows it in the commute's place on workday evenings, nights and days off unless a journey is tracked.
 */
object LocalSection : GlanceSection {
    override val id = "local"
    /** A departure's narrowest column in dp: four across the user's ~286dp widget, more on wider ones. */
    private const val COLUMN = 56

    /** Smart Glance asks this before placing the commute: outside commute focus, this section stands in for it. */
    fun replacesCommute(sc: SectionContext): Boolean {
        if (GlanceTime.commuteFocus(sc.local, sc.rules)) return false
        val board = CommuteRepository.cached(sc.context, sc.widgetId) ?: return false
        return board.optJSONObject("tracked") == null && hop(board, "from-via") != null
    }

    private fun hop(board: JSONObject, id: String): JSONObject? {
        val hops = board.optJSONArray("hops") ?: return null
        return (0 until hops.length()).map { hops.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
    }

    override fun wanted(sc: SectionContext) = SizeClass.FULL
    /** One size only: the title row holds the Entur ticket, so it never gives way. */
    override fun smaller(size: SizeClass): SizeClass? = null
    override fun height(sc: SectionContext, size: SizeClass) = sc.dp(175)
    override fun urgency(sc: SectionContext) = 0

    /**
     * Every minute until the first bus in either row leaves, so no countdown shown is ever behind and
     * that bus drops off as it goes; with no buses, when the age label next changes.
     */
    override fun nextBoundary(sc: SectionContext): Instant? {
        val board = CommuteRepository.cached(sc.context, sc.widgetId) ?: return null
        val age = Sections.transportAgeBoundary(board.optString("checkedAt"), sc.now)
        val edge = listOf("from-via", "via-from").mapNotNull { id -> departures(board, id, sc.nowMs).firstOrNull() }
            .mapNotNull { CommuteRepository.millis(it.optString("rideStart"))?.let { ms -> Instant.ofEpochMilli(ms) } }.minOrNull() ?: return age
        return listOfNotNull(Sections.minute(sc.now, edge), age).min()
    }

    private fun departures(board: JSONObject, id: String, nowMs: Long): List<JSONObject> {
        val all = hop(board, id)?.optJSONArray("journeys")
        return (0 until (all?.length() ?: 0)).map { all!!.getJSONObject(it) }
            .filter { (CommuteRepository.millis(it.optString("rideStart")) ?: 0) > nowMs }
    }

    override fun render(sc: SectionContext, size: SizeClass): RemoteViews {
        val context = sc.context
        val board = CommuteRepository.cached(context, sc.widgetId)
        val names = board?.optJSONObject("names") ?: JSONObject()
        val via = names.optString("via").ifEmpty { "the change" }
        val home = names.optString("from")
        val card = WidgetBlocks.section(context, R.drawable.ic_bus_ink, if (home.isEmpty()) "Buses to and from $via" else "$home ⇄ $via", "")
        CommuteSection.ticketButton(sc, card)
        val open = WidgetBlocks.activity(context, sc.widgetId * 100 + 62, Sections.openApp(context, sc.widgetId) {
            putExtra("commuteMode", CommuteRepository.VIA)
            putExtra("journeyKey", "")
        })
        card.setOnClickPendingIntent(R.id.section_card, open)
        if (board == null) {
            card.addView(R.id.section_body, Sections.note(context, "Loading departures…"))
            return card
        }
        val width = WidgetSize.of(context, sc.widgetId, 300, 400).width
        val columns = ((width - 44) / COLUMN).coerceIn(2, 8)
        // Only when the times may no longer be live: past fresh, they would otherwise pass for real time.
        val age = Sections.transportAge(board.optString("checkedAt"), sc.nowMs)
        for ((id, label) in listOf("from-via" to "→ $via", "via-from" to if (home.isEmpty()) "From $via" else "→ $home")) {
            val row = RemoteViews(context.packageName, R.layout.widget_glance_local_row)
            row.setTextViewText(R.id.local_label, label)
            if (id == "from-via" && age.isNotEmpty()) {
                row.setTextViewText(R.id.local_age, age)
                row.setViewVisibility(R.id.local_age, View.VISIBLE)
            }
            row.removeAllViews(R.id.local_departures)
            val departures = departures(board, id, sc.nowMs)
            if (departures.isEmpty()) row.setViewVisibility(R.id.local_empty, View.VISIBLE)
            for ((index, journey) in departures.take(columns).withIndex()) {
                val ride = CommuteRepository.rides(journey).firstOrNull() ?: continue
                val cancelled = ride.optBoolean("cancelled")
                val start = ride.optString("expectedStart")
                val item = DepartureWidget.departureItem(context, true, ride.optString("line"), ride.optString("mode"),
                    ride.optString("aimedStart"), start, cancelled, if (cancelled) "Cancelled" else countdown(start, sc.nowMs), R.layout.widget_glance_local_item)
                emphasis(context, item, index == 0, cancelled || DepartureWidget.minutesLate(ride.optString("aimedStart"), start) > 0)
                item.setOnClickPendingIntent(R.id.departure_item, JourneyActivity.hopIntent(context, sc.widgetId, id, journey, sc.widgetId * 100 + 63 + index))
                row.addView(R.id.local_departures, item)
            }
            // Invisible fillers keep both rows on the same columns, so times line up across directions.
            if (departures.isNotEmpty()) for (i in departures.size until columns) {
                val filler = DepartureWidget.departureItem(context, true, "", "bus", "", "", false, "", R.layout.widget_glance_local_item)
                filler.setViewVisibility(R.id.departure_item, View.INVISIBLE)
                row.addView(R.id.local_departures, filler)
            }
            card.addView(R.id.section_body, row)
        }
        return card
    }

    /** "6 min", "84 min", "now" in the last minute. */
    private fun countdown(iso: String, nowMs: Long): String {
        val at = CommuteRepository.millis(iso) ?: return ""
        val minutes = Math.floorDiv(at - nowMs, 60_000L)
        return if (minutes < 1) "now" else "$minutes min"
    }

    /**
     * The first bus in a row leads in ink, countdown too; later ones are muted unless late. Every time
     * keeps one size, so the columns stay on one baseline.
     */
    private fun emphasis(context: Context, item: RemoteViews, first: Boolean, late: Boolean) {
        if (late) return
        if (first) item.textColor(context, R.id.departure_info, R.color.glance_ink)
        else item.textColor(context, R.id.departure_time, R.color.glance_muted)
    }
}

/**
 * The next two events with colour bars and an all-day row (A4/A5), for one Nodecal profile; a tall
 * widget fits up to six. In Smart Glance the events after those page as many at a time, with arrows in
 * the title row and dots under the events; the all-day row stays put below them.
 */
object AgendaSection : GlanceSection {
    override val id = "agenda"
    private const val NODECAL = "io.gjessing.nodecal"

    fun profile(sc: SectionContext) = AgendaModel.resolveProfile(sc.config.optString("profile", AgendaModel.AUTO), sc.local, sc.rules)

    private fun state(sc: SectionContext, count: Int, page: Int = 0): AgendaState? {
        val agenda = AgendaRepository.cached(sc.context)?.optJSONObject("agenda") ?: return null
        return AgendaModel.state(agenda, profile(sc), sc.now, sc.zone, count, page)
    }

    /** Room under the title row when it holds the page arrows, whose circles reach below the title. */
    private const val PAGER_GAP = 6

    /** The most events a page grows to when a tall widget has the room. */
    private const val MAX_ROWS = 6

    private fun count(sc: SectionContext, size: SizeClass) = if (size == SizeClass.FULL) sc.agendaRows else 1

    /** Grows the full agenda an event row at a time while `fits` still holds, up to MAX_ROWS. */
    fun fill(sc: SectionContext, fits: () -> Boolean) {
        while (sc.agendaRows < MAX_ROWS) {
            sc.agendaRows++
            if (!fits()) {
                sc.agendaRows--
                return
            }
        }
    }

    private fun smart(sc: SectionContext) = sc.config.optString("type") == SectionConfigs.SMART

    /** Only Smart Glance pages; one line has no room for the dots. */
    private fun pageable(sc: SectionContext, size: SizeClass) = size != SizeClass.LINE && smart(sc)

    private fun paged(sc: SectionContext, size: SizeClass, state: AgendaState?) = state != null && state.pages > 1 && pageable(sc, size)

    override fun wanted(sc: SectionContext) = SizeClass.FULL

    /**
     * Smart Glance keeps the room of a whole page whatever the calendar holds: every event row, the
     * dots and the all-day row, empty or not. Its sections then never resize or move as events come and
     * go or a day is empty, and what is tapped stays under the same finger. The agenda widget on its own
     * has nothing below it to move, so it fits what it shows.
     */
    override fun height(sc: SectionContext, size: SizeClass): Int {
        // Event rows are 46dp with a 1dp divider between them; the dots 16dp under PAGER_GAP; the all-day row 19dp under its own divider.
        if (smart(sc)) return sc.dp(42 + 47 * count(sc, size) - 1 + (if (pageable(sc, size)) PAGER_GAP + 16 else 0) + 20)
        val state = state(sc, count(sc, size))
        val shown = state?.slots?.size ?: 0
        val rows = when {
            shown == 0 -> 38
            size == SizeClass.FULL -> 46 * shown + (shown - 1)
            else -> 46
        }
        // The all-day row sits under a divider when events are above it.
        val allDay = if (allDay(sc, size) && !state?.allDay.isNullOrEmpty()) if (shown > 0) 20 else 19 else 0
        val header = if (size == SizeClass.LINE) 22 else 42
        return sc.dp(header + rows + allDay)
    }

    /**
     * Never urgent: an event under way or about to start says so in its own row, and taking room from
     * the commute for it would reshape the widget around every meeting.
     */
    override fun urgency(sc: SectionContext) = 0

    override fun nextBoundary(sc: SectionContext): Instant? {
        val agenda = AgendaRepository.cached(sc.context)?.optJSONObject("agenda") ?: return null
        val hidden = AgendaModel.profiles(agenda)[profile(sc)] ?: emptySet()
        return AgendaModel.nextBoundary(AgendaModel.events(agenda).filter { it.calendarId !in hidden }, sc.now)
    }

    override fun render(sc: SectionContext, size: SizeClass): RemoteViews {
        val context = sc.context
        val profile = profile(sc)
        val cache = AgendaRepository.cached(context)
        val checked = cache?.optString("checkedAt").orEmpty()
        val age = CommuteRepository.millis(checked)?.let { (sc.nowMs - it) / 60000 } ?: 0L
        val stale = cache != null && cache.optBoolean("stale") && age > 60
        // Smart Glance's header already says what part of the day it is, so the profile name is left to the agenda widget.
        val smart = smart(sc)
        val card = WidgetBlocks.section(context, R.drawable.ic_calendar, "Upcoming agenda",
            if (stale) "Updated ${CommuteWidget.clock(checked)}" else if (smart) "" else AgendaModel.profileName(profile))
        if (stale) card.textColor(context, R.id.section_right, R.color.glance_late)
        // Smart Glance keeps the title row at every size: its calendar icon is what says "agenda" at a glance.
        if (size == SizeClass.LINE && !smart) card.setViewVisibility(R.id.section_header, View.GONE)
        card.setOnClickPendingIntent(R.id.section_card, WidgetBlocks.activity(context, sc.widgetId * 100 + 70, nodecalIntent(context, "")))
        if (pageable(sc, size)) card.setViewPadding(R.id.section_body, 0, sc.dp(PAGER_GAP), 0, 0)
        if (!AgendaRepository.configured(context)) {
            card.setOnClickPendingIntent(R.id.section_card, WidgetBlocks.activity(context, sc.widgetId * 100 + 71, Sections.openApp(context, sc.widgetId) { putExtra("screen", "settings") }))
            return quiet(sc, size, card, null, "Connect Nodecal", "Add its address and a token in Glance settings")
        }
        val first = state(sc, count(sc, size))
        val page = if (paged(sc, size, first)) AgendaPager.page(context, sc.widgetId, first!!.anchor) else 0
        val state = if (page > 0) state(sc, count(sc, size), page) else first
        if (state == null) {
            val error = cache?.optString("error").orEmpty()
            return quiet(sc, size, card, null, if (error.isEmpty()) "Loading agenda…" else "Agenda unavailable", error)
        }
        if (state.slots.isEmpty()) return quiet(sc, size, card, state, "No upcoming events")
        for ((index, slot) in state.slots.withIndex()) {
            if (index > 0) card.addView(R.id.section_body, WidgetBlocks.divider(context))
            card.addView(R.id.section_body, event(sc, slot, index))
        }
        // The rest of the page's rows, empty, so the dots and the all-day row stay under the same finger.
        if (smart) for (i in state.slots.size until count(sc, size)) placeholderRow(sc, card, R.id.section_body, true)
        if (paged(sc, size, state)) {
            card.addView(R.id.section_body, dots(sc, state.page, state.pages))
            arrows(sc, card, state.page, state.pages)
            // Away from the first page the title goes back to it; on it, the tap opens Nodecal as before.
            if (state.page > 0) card.setOnClickPendingIntent(R.id.section_header, AgendaPager.intent(context, sc.widgetId, 0))
        } else if (pageable(sc, size)) card.addView(R.id.section_body, hiddenPager(sc))
        if (allDay(sc, size) && state.allDay.isNotEmpty()) {
            card.addView(R.id.section_body, WidgetBlocks.divider(context))
            card.addView(R.id.section_body, allDayRow(sc, state.allDay))
        } else if (smart) hiddenAllDay(sc, card, R.id.section_body)
        return card
    }

    /**
     * A message where the events go: "No upcoming events", "Loading agenda…". In Smart Glance it stands
     * centred in the room the card keeps for its events and dots, and for the all-day row on a day
     * without one, so the card keeps its height.
     */
    private fun quiet(sc: SectionContext, size: SizeClass, card: RemoteViews, state: AgendaState?, title: String, detail: String = ""): RemoteViews {
        val context = sc.context
        val allDay = state?.allDay.orEmpty().takeIf { allDay(sc, size) }.orEmpty()
        if (!smart(sc)) {
            card.addView(R.id.section_body, Sections.note(context, title, detail))
            if (allDay.isNotEmpty()) card.addView(R.id.section_body, allDayRow(sc, allDay))
            return card
        }
        val note = RemoteViews(context.packageName, R.layout.widget_glance_agenda_empty)
        note.setTextViewText(R.id.note_title, title)
        if (detail.isNotEmpty()) {
            note.setTextViewText(R.id.note_detail, detail)
            note.setViewVisibility(R.id.note_detail, View.VISIBLE)
        }
        for (i in 0 until count(sc, size)) placeholderRow(sc, note, R.id.agenda_space, i > 0)
        if (pageable(sc, size)) note.addView(R.id.agenda_space, hiddenPager(sc))
        if (allDay.isEmpty()) hiddenAllDay(sc, note, R.id.agenda_space)
        card.addView(R.id.section_body, note)
        if (allDay.isNotEmpty()) {
            card.addView(R.id.section_body, WidgetBlocks.divider(context))
            card.addView(R.id.section_body, allDayRow(sc, allDay))
        }
        return card
    }

    /** An empty event row, under an invisible divider unless it is the first: the height of a real one. */
    private fun placeholderRow(sc: SectionContext, views: RemoteViews, id: Int, divider: Boolean) {
        if (divider) views.addView(id, WidgetBlocks.divider(sc.context).apply { setViewVisibility(R.id.divider, View.INVISIBLE) })
        val filler = RemoteViews(sc.context.packageName, R.layout.widget_glance_event)
        filler.setViewVisibility(R.id.event_item, View.INVISIBLE)
        views.addView(id, filler)
    }

    /** The dots' room when there is one page. */
    private fun hiddenPager(sc: SectionContext) = RemoteViews(sc.context.packageName, R.layout.widget_glance_pager).apply {
        setViewVisibility(R.id.pager_dots, View.INVISIBLE)
    }

    /** The all-day row's room, divider included, on a day without one. */
    private fun hiddenAllDay(sc: SectionContext, views: RemoteViews, id: Int) {
        views.addView(id, WidgetBlocks.divider(sc.context).apply { setViewVisibility(R.id.divider, View.INVISIBLE) })
        views.addView(id, RemoteViews(sc.context.packageName, R.layout.widget_glance_allday).apply { setViewVisibility(R.id.allday_row, View.INVISIBLE) })
    }

    /**
     * "All day: Project planning" and a "+2 more" chip. One event opens in Nodecal; more open the
     * all-day sheet, where each one does.
     */
    private fun allDayRow(sc: SectionContext, items: List<AllDayItem>): RemoteViews {
        val context = sc.context
        val row = RemoteViews(context.packageName, R.layout.widget_glance_allday)
        val prefix = "All day: "
        val text = SpannableString(prefix + items[0].event.title)
        text.setSpan(StyleSpan(Typeface.BOLD), prefix.length, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        row.setTextViewText(R.id.allday_text, text)
        row.setTextViewText(R.id.allday_label, items[0].calendar)
        val more = items.size - 1
        row.setViewVisibility(R.id.allday_more, if (more > 0) View.VISIBLE else View.GONE)
        row.setTextViewText(R.id.allday_more, "+$more more")
        row.setOnClickPendingIntent(R.id.allday_row, WidgetBlocks.activity(context, sc.widgetId * 100 + 76, allDayIntent(context, items)))
        row.setContentDescription(R.id.allday_row, if (more > 0) "All day: ${items.joinToString(", ") { it.event.title }}" else "All day: ${items[0].event.title}")
        return row
    }

    /** One event opens in Nodecal; more open the all-day sheet, where each one does. */
    private fun allDayIntent(context: Context, items: List<AllDayItem>): Intent =
        if (items.size > 1) AllDayActivity.intent(context, items) else nodecalIntent(context, items[0].event.id)

    /** The all-day row from full size up; Smart Glance keeps it at every size, however short the agenda gets. */
    private fun allDay(sc: SectionContext, size: SizeClass) = size == SizeClass.FULL || smart(sc)

    /** Dots stay countable at a glance up to this many pages; past it the row says "2 of 8" instead. */
    private const val MAX_DOTS = 5

    /**
     * Under the events, one dot per page there is, or the count of them past MAX_DOTS, so the row only
     * ever promises events that are there; a tap pages forward, wrapping at the end.
     */
    private fun dots(sc: SectionContext, page: Int, pages: Int): RemoteViews {
        val context = sc.context
        val pager = RemoteViews(context.packageName, R.layout.widget_glance_pager)
        pager.removeAllViews(R.id.pager_dots)
        if (pages > MAX_DOTS) pager.addView(R.id.pager_dots, RemoteViews(context.packageName, R.layout.widget_glance_page_count).apply {
            setTextViewText(R.id.page_count, "${page + 1} of $pages")
        })
        else for (index in 0 until pages) {
            val dot = RemoteViews(context.packageName, R.layout.widget_glance_dot)
            dot.setImageViewResource(R.id.dot, if (index == page) R.drawable.dot_on else R.drawable.dot_off)
            pager.addView(R.id.pager_dots, dot)
        }
        pager.setOnClickPendingIntent(R.id.pager_dots, AgendaPager.intent(context, sc.widgetId, (page + 1) % pages))
        pager.setContentDescription(R.id.pager_dots, "Page ${page + 1} of $pages")
        return pager
    }

    /** The round back and forward buttons in the title row, wrapping at the ends. */
    private fun arrows(sc: SectionContext, card: RemoteViews, page: Int, pages: Int) {
        card.setViewVisibility(R.id.section_pager, View.VISIBLE)
        card.setOnClickPendingIntent(R.id.section_prev, AgendaPager.intent(sc.context, sc.widgetId, (page - 1 + pages) % pages))
        card.setOnClickPendingIntent(R.id.section_next, AgendaPager.intent(sc.context, sc.widgetId, (page + 1) % pages))
    }

    /** One event row: start and countdown, the calendar's colour bar, title and calendar, and a join or map action. */
    private fun event(sc: SectionContext, slot: AgendaSlot, index: Int): RemoteViews {
        val context = sc.context
        val row = RemoteViews(context.packageName, R.layout.widget_glance_event)
        if (slot.futureDay) row.setInt(R.id.event_item, "setBackgroundResource", R.drawable.agenda_future_background)
        row.setTextViewText(R.id.event_time, slot.time)
        row.setTextViewText(R.id.event_count, slot.countdown)
        row.textColor(context, R.id.event_count, if (slot.ongoing) R.color.glance_ok else R.color.glance_muted)
        row.setInt(R.id.event_bar, "setColorFilter", WidgetBlocks.parseColor(context, slot.color))
        row.setTextViewText(R.id.event_title, slot.event.title)
        row.setTextViewText(R.id.event_meta, slot.meta)
        row.textColor(context, R.id.event_meta, if (slot.clash) R.color.glance_warn else R.color.glance_muted)
        val code = sc.widgetId * 100 + 72 + index * 2
        row.setOnClickPendingIntent(R.id.event_item, WidgetBlocks.activity(context, code, nodecalIntent(context, slot.event.id)))
        val action = when {
            slot.event.meetingUrl.isNotEmpty() -> R.drawable.ic_video to slot.event.meetingUrl
            slot.event.location.isNotEmpty() -> R.drawable.ic_pin to "geo:0,0?q=" + Uri.encode(slot.event.location)
            else -> null
        }
        row.setViewVisibility(R.id.event_action, if (action == null) View.GONE else View.VISIBLE)
        if (action != null) {
            row.setImageViewResource(R.id.event_action, action.first)
            row.setOnClickPendingIntent(R.id.event_action, WidgetBlocks.activity(context, code + 1,
                Intent(Intent.ACTION_VIEW, Uri.parse(action.second)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
        }
        return row
    }

    /**
     * Nodecal's own deep link (`nodecal_kind`/`nodecal_target`) when the app is installed, otherwise
     * its web address in the browser.
     */
    fun nodecalIntent(context: Context, eventId: String): Intent {
        val installed = try { context.packageManager.getPackageInfo(NODECAL, 0); true } catch (_: Exception) { false }
        if (installed) return Intent().apply {
            component = ComponentName(NODECAL, "$NODECAL.MainActivity")
            data = Uri.parse("glance://nodecal/${Uri.encode(eventId)}")
            if (eventId.isNotEmpty()) {
                putExtra("nodecal_kind", "event")
                putExtra("nodecal_target", eventId)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val url = AgendaRepository.url(context).ifEmpty { "https://nodecal.gjessing.io" }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Weather (W4), useful at any hour: in commute focus (workdays, 03:00 until the return window ends)
 * it is the rain check for the commute; evenings, nights and weekends it is the forecast where the
 * user is, with the next hours as a strip when the widget has room.
 */
object WeatherSection : GlanceSection {
    override val id = "weather"
    /** Hours in the strip and its height in dp. */
    private const val STRIP = 70

    fun thresholds(context: Context): Thresholds {
        val json = GlanceSettings.get(context).optJSONObject("weather") ?: return Thresholds()
        val d = Thresholds()
        return Thresholds(json.optDouble("probability", d.probability), json.optDouble("amount", d.amount), json.optDouble("heavy", d.heavy))
    }

    fun advice(sc: SectionContext): Advice {
        if (!GlanceTime.commuteFocus(sc.local, sc.rules)) {
            val here = here(sc) ?: return Advice(Advice.Kind.UNKNOWN, "No forecast yet", "")
            return WeatherAdvisor.forecast(WeatherRepository.hours(sc.context, here), sc.now, sc.zone, thresholds(sc.context))
        }
        val points = WeatherRepository.places(sc.context, sc.widgetId).map { WeatherPoint(it.name, WeatherRepository.hours(sc.context, it)) }
        return WeatherAdvisor.advise(points, sc.now, sc.zone, sc.rules, thresholds(sc.context))
    }

    /** The end of the commute the user is most likely at: work during work hours, home otherwise. */
    fun here(sc: SectionContext): Place? {
        val places = WeatherRepository.places(sc.context, sc.widgetId)
        return if (GlanceTime.inWorkHours(sc.local, sc.rules)) places.lastOrNull() else places.firstOrNull()
    }

    override fun wanted(sc: SectionContext): SizeClass? {
        val advice = advice(sc)
        if (sc.config.optBoolean("weatherAdviceOnly") && !advice.actionable && !advice.wetSoon) return null
        // Keep the hourly weather alongside the commute advice, including a dry commute.
        // The layout can still collapse to the summary when the widget is short of room.
        return SizeClass.FULL
    }

    override fun smaller(size: SizeClass): SizeClass? = if (size == SizeClass.FULL) SizeClass.LINE else null
    override fun height(sc: SectionContext, size: SizeClass) = sc.dp(if (size == SizeClass.FULL) 58 + STRIP else 58)
    override fun urgency(sc: SectionContext) = if (advice(sc).actionable) 1 else 0
    /** The top of the hour, when the strip's first column rolls over to the next "Now". */
    override fun nextBoundary(sc: SectionContext): Instant? = sc.local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant()

    override fun render(sc: SectionContext, size: SizeClass): RemoteViews {
        val context = sc.context
        val advice = advice(sc)
        val card = RemoteViews(context.packageName, R.layout.widget_glance_weather)
        val emoji = WeatherRepository.emoji(advice.symbol)
        if (advice.kind == Advice.Kind.FORECAST && emoji.isNotEmpty()) {
            card.setTextViewText(R.id.weather_emoji, emoji)
            card.setViewVisibility(R.id.weather_emoji, View.VISIBLE)
            card.setViewVisibility(R.id.weather_icon, View.GONE)
        } else if (advice.kind == Advice.Kind.UNKNOWN) {
            card.setViewVisibility(R.id.weather_icon, View.GONE)
        } else {
            card.setImageViewResource(R.id.weather_icon, if (advice.actionable) R.drawable.ic_umbrella else R.drawable.ic_check)
        }
        card.setTextViewText(R.id.weather_title, if (advice.kind == Advice.Kind.UNKNOWN) "Loading forecast…" else advice.title)
        if (advice.kind == Advice.Kind.HEAVY) card.textColor(context, R.id.weather_title, R.color.glance_late)
        card.setTextViewText(R.id.weather_reason, advice.reason)
        card.setViewVisibility(R.id.weather_reason, if (advice.reason.isEmpty()) View.GONE else View.VISIBLE)
        if (size == SizeClass.FULL) here(sc)?.let { strip(sc, card, it, 5) }
        card.setOnClickPendingIntent(R.id.weather_card, WidgetBlocks.activity(context, sc.widgetId * 100 + 80,
            Sections.openApp(context, sc.widgetId) { putExtra("screen", "weather") }))
        return card
    }

    /**
     * The next hours at `place`: hour, symbol, temperature and rain in mm when there is any. The
     * first column is the hour under way, so it says "Now".
     */
    fun strip(sc: SectionContext, card: RemoteViews, place: Place, columns: Int) {
        val context = sc.context
        val hours = WeatherRepository.hourRows(context, place).filter {
            (CommuteRepository.millis(it.optString("t")) ?: 0) + 3_600_000L > sc.nowMs
        }.take(columns)
        card.removeAllViews(R.id.weather_hours)
        for (hour in hours) {
            val column = RemoteViews(context.packageName, R.layout.widget_glance_hour)
            val start = CommuteRepository.millis(hour.optString("t"))
            val time = start?.let { GlanceTime.at(it, sc.zone) }
            val now = start != null && start <= sc.nowMs
            column.setTextViewText(R.id.hour_time, if (now) "Now" else time?.let { "%02d".format(it.hour) }.orEmpty())
            if (now) column.textColor(context, R.id.hour_time, R.color.glance_ink)
            column.setTextViewText(R.id.hour_symbol, WeatherRepository.emoji(hour.optString("symbol")))
            column.setTextViewText(R.id.hour_temp, if (hour.isNull("temp")) "" else WeatherRepository.temperature(hour.optDouble("temp")))
            val mm = hour.optDouble("mm", 0.0)
            column.setTextViewText(R.id.hour_rain, if (mm >= 0.1) "%.1f".format(java.util.Locale.ROOT, mm) else "")
            card.addView(R.id.weather_hours, column)
        }
        // The amounts are millimetres in that hour; the unit follows them once, at the end of the row.
        if (hours.any { it.optDouble("mm", 0.0) >= 0.1 }) card.addView(R.id.weather_hours, RemoteViews(context.packageName, R.layout.widget_glance_hour_unit))
        card.setViewVisibility(R.id.weather_hours, if (hours.isEmpty()) View.GONE else View.VISIBLE)
    }
}

/**
 * Which agenda carousel page each Smart Glance shows, kept with the next upcoming event it was paged
 * from: once that event changes (it ended, or the profile switched), the carousel starts over on page 0.
 */
object AgendaPager {
    const val ACTION = "io.gjessing.glance.AGENDA_PAGE"
    private const val prefsName = "glance_agenda_pages"

    private fun prefs(context: Context) = context.getSharedPreferences(prefsName, 0)

    fun page(context: Context, widgetId: Int, anchor: String): Int {
        val prefs = prefs(context)
        if (prefs.getString("anchor_$widgetId", null) != anchor) {
            prefs.edit().putString("anchor_$widgetId", anchor).putInt("page_$widgetId", 0).apply()
            return 0
        }
        return prefs.getInt("page_$widgetId", 0)
    }

    fun set(context: Context, widgetId: Int, page: Int) { prefs(context).edit().putInt("page_$widgetId", page).apply() }

    fun remove(context: Context, widgetId: Int) { prefs(context).edit().remove("anchor_$widgetId").remove("page_$widgetId").apply() }

    /** A tap that shows `page`; SmartWidget stores it and renders again. */
    fun intent(context: Context, widgetId: Int, page: Int): PendingIntent {
        val intent = Intent(context, SmartWidget::class.java).setAction(ACTION)
            .setData(Uri.parse("glance://agenda-page/$widgetId/$page")).putExtra("widgetId", widgetId).putExtra("page", page)
        return PendingIntent.getBroadcast(context, widgetId * 100 + 90 + page, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
