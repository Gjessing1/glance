package io.gjessing.glance

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject

/**
 * The commute widget: whole journeys door to door, one tap to switch between outbound, the via stop
 * (which can be hidden) and the way home. Tapping a journey tracks it, which holds the widget on that journey until it
 * arrives instead of swapping it for the next departure.
 */
class CommuteWidget : AppWidgetProvider() {
    override fun onEnabled(context: Context) { DepartureWidget.schedule(context) }
    override fun onDisabled(context: Context) { DepartureWidget.cancelIfUnused(context) }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            CommuteRepository.config(context, id)
            render(context, id)
        }
        DepartureWidget.refresh(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, id)
    }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { CommuteRepository.remove(context, it) } }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_REFRESH -> DepartureWidget.refreshByHand(context, id.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID })
            ACTION_MODE -> {
                CommuteRepository.pin(context, id, intent.getStringExtra("mode") ?: CommuteRepository.OUTBOUND)
                render(context, id)
            }
            ACTION_TRACK -> {
                val direction = intent.getStringExtra("direction") ?: return
                val journey = CommuteRepository.findJourney(context, id, direction, intent.getStringExtra("key") ?: return) ?: return
                CommuteRepository.track(context, id, direction, journey)
                render(context, id)
            }
            ACTION_UNTRACK -> {
                CommuteRepository.track(context, id, CommuteRepository.OUTBOUND, null)
                render(context, id)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "io.gjessing.glance.COMMUTE_REFRESH"
        const val ACTION_MODE = "io.gjessing.glance.COMMUTE_MODE"
        const val ACTION_TRACK = "io.gjessing.glance.COMMUTE_TRACK"
        const val ACTION_UNTRACK = "io.gjessing.glance.COMMUTE_UNTRACK"
        // Colour resources, so the widget follows the system's light or dark theme.
        private val LATE = R.color.glance_late
        private val WARN = R.color.glance_warn
        private val INK = R.color.glance_ink

        fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, CommuteWidget::class.java))

        private fun broadcast(context: Context, id: Int, action: String, code: Int, path: String, extras: Intent.() -> Unit = {}): PendingIntent {
            val intent = Intent(context, CommuteWidget::class.java).apply {
                this.action = action
                data = Uri.parse("glance://commute/$id/$path")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                extras()
            }
            return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun openApp(context: Context, id: Int, mode: String, key: String, code: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                data = Uri.parse("glance://commute-open/$id/$mode/${Uri.encode(key)}")
                putExtra("widgetId", id)
                putExtra("commuteMode", mode)
                putExtra("journeyKey", key)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun render(context: Context, id: Int) {
            val manager = AppWidgetManager.getInstance(context)
            val options = manager.getAppWidgetOptions(id)
            val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val width = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0).takeIf { it > 0 } ?: 300
            val height = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0).takeIf { it > 0 } ?: 180
            val now = System.currentTimeMillis()
            val mode = CommuteRepository.mode(context, id, now)
            val board = CommuteRepository.cached(context, id)
            val config = CommuteRepository.config(context, id)
            val root = RemoteViews(context.packageName, R.layout.widget_commute_root)
            root.removeAllViews(R.id.content)
            root.setOnClickPendingIntent(R.id.refresh, broadcast(context, id, ACTION_REFRESH, id * 100, "refresh"))
            WidgetBlocks.refreshState(context, root, R.id.refresh, R.id.refresh_count, id)
            val rest = CommuteRepository.restDay(context, id, now)
            val chips = listOf(
                Triple(R.id.chip_outbound, CommuteRepository.OUTBOUND, "Outbound"),
                Triple(R.id.chip_via, CommuteRepository.VIA, "Interchange"),
                Triple(R.id.chip_inbound, CommuteRepository.INBOUND, "Return"))
            root.setViewVisibility(R.id.chip_via, if (config.optBoolean("hideInterchange")) View.GONE else View.VISIBLE)
            for ((index, chip) in chips.withIndex()) {
                val (view, chipMode, label) = chip
                val selected = chipMode == mode && !rest
                root.setTextViewText(view, label)
                root.setInt(view, "setBackgroundResource", if (selected) R.drawable.chip_selected else R.drawable.chip)
                root.textColor(context, view, if (selected) R.color.glance_on_chip_selected else INK)
                root.setOnClickPendingIntent(view, broadcast(context, id, ACTION_MODE, id * 100 + 1 + index, "mode/$chipMode") { putExtra("mode", chipMode) })
            }
            if (board == null) {
                root.setTextViewText(R.id.commute_status, "Loading journeys…")
                root.setViewVisibility(R.id.commute_status, View.VISIBLE)
                manager.updateAppWidget(id, root)
                return
            }
            val checked = board.optString("checkedAt")
            val age = CommuteRepository.millis(checked)?.let { (now - it) / 60000 } ?: 999L
            var used = 46
            // Hosts reapply a new update onto the views they already have, so the status is always set, never left as it was.
            val stale = board.optBoolean("stale") || age > 30
            root.setViewVisibility(R.id.commute_status, if (stale) View.VISIBLE else View.GONE)
            if (stale) {
                root.setTextViewText(R.id.commute_status, "Updated ${clock(checked)}")
                root.textColor(context, R.id.commute_status, LATE)
                used += 18
            }
            if (rest) {
                // Never blank: a quiet line says the widget is resting, and a chip still shows journeys.
                val card = RemoteViews(context.packageName, R.layout.widget_commute_card)
                card.setTextViewText(R.id.card_empty, "No commute today")
                card.setViewVisibility(R.id.card_empty, View.VISIBLE)
                root.addView(R.id.content, card)
            } else if (mode == CommuteRepository.VIA) renderHops(context, id, root, board, width, height, used, now)
            else renderJourneys(context, id, root, board, mode, width, height, used, now, context.resources.configuration.fontScale)
            manager.updateAppWidget(id, root)
        }

        /** Row sizes were measured at font scale 1.0; text grows with the system font size, so the estimates do too. */
        private fun renderJourneys(context: Context, id: Int, root: RemoteViews, board: JSONObject, mode: String, width: Int, height: Int, start: Int, now: Long, scale: Float) {
            val section = board.optJSONObject(mode)
            val trackedKey = board.optJSONObject("tracked")?.takeIf { it.optString("direction") == mode }?.optJSONObject("journey")?.optString("key")
            val all = section?.optJSONArray("journeys")
            val journeys = (0 until (all?.length() ?: 0)).map { all!!.getJSONObject(it) }.filter { journey ->
                // The cache can be fifteen minutes old: departures that have gone are dropped, except the one being ridden.
                journey.optString("key") == trackedKey || (CommuteRepository.millis(journey.optString("rideStart")) ?: 0) > now - 60_000
            }
            val card = RemoteViews(context.packageName, R.layout.widget_commute_card)
            card.removeAllViews(R.id.rows)
            var used = start + 14
            if (journeys.isEmpty()) {
                card.setTextViewText(R.id.card_empty, if (section?.optBoolean("noService") == true) "No connection found" else "No journeys in the next hours")
                card.setViewVisibility(R.id.card_empty, View.VISIBLE)
                root.addView(R.id.content, card)
                return
            }
            // A notice on every journey belongs to the corridor: said once under the list, not on each row.
            val shared = journeys.map { notices(it).map { notice -> notice.optString("summary") }.toSet() }.reduce { a, b -> a intersect b }
            if (shared.isNotEmpty()) used += (18 * scale).toInt()
            val shown = mutableListOf<Triple<JSONObject, Boolean, Pair<String, Int>?>>()
            for ((index, journey) in journeys.withIndex()) {
                val tracked = journey.optString("key") == trackedKey
                val info = info(journey, tracked, shared, now)
                val rowHeight = ((if (info != null) 58 else 40) * scale).toInt() + (if (index > 0) 1 else 0)
                if (index > 0 && used + rowHeight > height) break
                used += rowHeight
                shown += Triple(journey, tracked, info)
            }
            val columns = columns(shown.map { it.first }, width - 56, scale)
            for ((index, row) in shown.withIndex()) {
                if (index > 0) card.addView(R.id.rows, RemoteViews(context.packageName, R.layout.widget_commute_divider))
                card.addView(R.id.rows, journeyRow(context, id, mode, row.first, row.second, row.third, columns, index))
            }
            if (shared.isNotEmpty()) {
                card.setTextViewText(R.id.card_notice, "⚠ " + shared.first())
                card.setViewVisibility(R.id.card_notice, View.VISIBLE)
            }
            root.addView(R.id.content, card)
        }

        /**
         * One layout for every row of a card, so badges, times and changes stand in columns down the list.
         * [labels] is each badge column's label width in dp, sized for the longest line name in that column.
         */
        private class Columns(val labels: List<Int>, val compact: Boolean, val duration: Boolean, val laterTimes: Boolean)

        private fun columns(journeys: List<JSONObject>, inner: Int, scale: Float): Columns {
            val lines = journeys.map { journey -> CommuteRepository.rides(journey).map { it.optString("line").ifEmpty { "–" } } }
            val legs = lines.maxOfOrNull { it.size } ?: 0
            val longest = (0 until legs).map { i -> lines.maxOf { it.getOrNull(i)?.length ?: 0 } }
            // Widths in dp, measured on the emulator: a badge, its time beside it, the change, the arrival and total.
            fun text(dp: Int) = (dp * scale).toInt()
            val changes = (legs - 1).coerceAtLeast(0) * text(30)
            val full = longest.sumOf { badgeWidth(it, false, scale) + 5 + text(37) } + changes
            val compactWidth = longest.sumOf { badgeWidth(it, true, scale) + 4 + text(35) } + changes
            // The arrival keeps 8dp clear of the last leg; the total beside it goes first when space runs out.
            val arrival = 8 + text(41)
            val total = 5 + text(34)
            // Full badges if they fit, then drop the total, then narrow badges, and last only the first vehicle's time.
            val (compact, duration) = when {
                full + arrival + total <= inner -> false to true
                full + arrival <= inner -> false to false
                compactWidth + arrival + total <= inner -> true to true
                else -> true to false
            }
            val laterTimes = !compact || duration || compactWidth + arrival <= inner
            return Columns(longest.map { labelWidth(it, compact, scale) }, compact, duration, laterTimes)
        }

        private fun journeyRow(context: Context, id: Int, mode: String, journey: JSONObject, tracked: Boolean, info: Pair<String, Int>?, columns: Columns, index: Int): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_commute_journey)
            val rides = CommuteRepository.rides(journey)
            val density = context.resources.displayMetrics.density
            row.removeAllViews(R.id.legs)
            for ((i, ride) in rides.withIndex()) {
                if (i > 0) {
                    val change = RemoteViews(context.packageName, R.layout.widget_commute_change)
                    val previous = rides[i - 1]
                    val planned = minutes(previous.optString("aimedEnd"), ride.optString("aimedStart"))
                    val live = minutes(previous.optString("expectedEnd"), ride.optString("expectedStart"))
                    change.setTextViewText(R.id.change_text, if (live < 0) "×" else "${planned}m")
                    if (live < 0) change.textColor(context, R.id.change_text, LATE) else if (live < 4) change.textColor(context, R.id.change_text, WARN)
                    row.addView(R.id.legs, change)
                }
                val leg = RemoteViews(context.packageName, if (columns.compact) R.layout.widget_commute_leg_compact else R.layout.widget_commute_leg)
                val rail = ride.optString("mode") != "bus" && ride.optString("mode") != "coach"
                leg.setTextViewText(R.id.line_text, ride.optString("line").ifEmpty { "–" })
                leg.setInt(R.id.line_text, "setMinWidth", (columns.labels[i] * density).toInt())
                leg.setImageViewResource(R.id.mode_icon, if (rail) R.drawable.ic_train else R.drawable.ic_bus)
                leg.setInt(R.id.line_badge, "setBackgroundResource", if (rail) R.drawable.badge_rail else R.drawable.badge_bus)
                if (i > 0 && !columns.laterTimes) leg.setViewVisibility(R.id.leg_time, View.GONE)
                leg.setTextViewText(R.id.leg_time, clock(ride.optString("expectedStart")))
                if (ride.optBoolean("cancelled") || minutes(ride.optString("aimedStart"), ride.optString("expectedStart")) > 0) leg.textColor(context, R.id.leg_time, LATE)
                row.addView(R.id.legs, leg)
            }
            row.setTextViewText(R.id.arrival, clock(journey.optString("expectedEnd")))
            if (minutes(journey.optString("aimedEnd"), journey.optString("expectedEnd")) > 0) row.textColor(context, R.id.arrival, LATE)
            if (columns.duration) row.setTextViewText(R.id.duration, "${minutes(journey.optString("expectedStart"), journey.optString("expectedEnd"))} min")
            else row.setViewVisibility(R.id.duration, View.GONE)
            if (info != null) {
                row.setViewVisibility(R.id.info_row, View.VISIBLE)
                row.setTextViewText(R.id.info, info.first)
                row.textColor(context, R.id.info, info.second)
            }
            val key = journey.optString("key")
            if (tracked) {
                row.setInt(R.id.journey_item, "setBackgroundResource", R.drawable.tracked_background)
                row.setViewVisibility(R.id.untrack, View.VISIBLE)
                row.setOnClickPendingIntent(R.id.untrack, broadcast(context, id, ACTION_UNTRACK, id * 100 + 9, "untrack"))
                row.setOnClickPendingIntent(R.id.journey_item, openApp(context, id, mode, key, id * 100 + 10))
            } else {
                row.setOnClickPendingIntent(R.id.journey_item, broadcast(context, id, ACTION_TRACK, id * 100 + 20 + index, "track/$mode/${Uri.encode(key)}") {
                    putExtra("direction", mode)
                    putExtra("key", key)
                })
            }
            return row
        }

        /** The one line under a row, most urgent first. Most rows have none. */
        internal fun info(journey: JSONObject, tracked: Boolean, shared: Set<String>, now: Long): Pair<String, Int>? {
            val rides = CommuteRepository.rides(journey)
            if (tracked) return "Tracking · ${phase(rides, now)}" to INK
            rides.firstOrNull { it.optBoolean("cancelled") }?.let { return "${it.optString("line")} cancelled" to LATE }
            for (i in 1 until rides.size) {
                val live = minutes(rides[i - 1].optString("expectedEnd"), rides[i].optString("expectedStart"))
                val at = rides[i].optString("from")
                if (live < 0) return "Misses the ${rides[i].optString("line")} at $at" to LATE
                if (live < 4 && minutes(rides[i - 1].optString("aimedEnd"), rides[i].optString("aimedStart")) != live) return "Change at $at now $live min" to WARN
            }
            notices(journey).firstOrNull { it.optString("summary") !in shared }?.let { return "⚠ ${it.optString("summary")}" to WARN }
            return null
        }

        /** Where a tracked journey stands: the vehicle to catch next, or the one being ridden. */
        internal fun phase(rides: List<JSONObject>, now: Long): String {
            val current = rides.firstOrNull { (CommuteRepository.millis(it.optString("expectedEnd")) ?: 0) > now } ?: return "arrived"
            val leaves = CommuteRepository.millis(current.optString("expectedStart")) ?: 0
            return if (now < leaves) "${current.optString("line")} from ${current.optString("from")} ${clock(current.optString("expectedStart"))}"
            else "on ${current.optString("line")}, at ${current.optString("to")} ${clock(current.optString("expectedEnd"))}"
        }

        /** Disruptions worth a line on a home screen: incidents and anything the operator rates severe, never routine notices. */
        internal fun notices(journey: JSONObject): List<JSONObject> = CommuteRepository.rides(journey).flatMap { ride ->
            val situations = ride.optJSONArray("situations")
            (0 until (situations?.length() ?: 0)).map { situations!!.getJSONObject(it) }
        }.filter { it.optString("reportType") == "incident" || it.optString("severity") in setOf("severe", "verySevere") }
            .filter { it.optString("summary").isNotEmpty() }.distinctBy { it.optString("summary") }

        private fun renderHops(context: Context, id: Int, root: RemoteViews, board: JSONObject, width: Int, height: Int, start: Int, now: Long) {
            val hops = board.optJSONArray("hops") ?: return
            // In the afternoon the way home is the likelier question at the change.
            val order = if (CommuteRepository.clockMode(now, GlanceTime.rules(context)) == CommuteRepository.OUTBOUND) listOf(0, 1) else listOf(1, 0)
            val inner = width - 44
            val hopList = order.mapNotNull { hops.optJSONObject(it) }
            val rowsPerHop = hopList.map { hop ->
                val all = hop.optJSONArray("journeys")
                (0 until (all?.length() ?: 0)).map { all!!.getJSONObject(it) }
                    .filter { (CommuteRepository.millis(it.optString("rideStart")) ?: 0) > now - 60_000 }
            }
            val compact = hopList.indices.any { i -> rowsPerHop[i].size * DepartureWidget.columnWidth(mode(rowsPerHop[i]), false) > inner }
            var used = start
            for ((i, hop) in hopList.withIndex()) {
                val departures = rowsPerHop[i]
                val mode = mode(departures)
                val tracks = mode == "rail" && (i == 0 || used + DepartureWidget.cardHeight(compact, true) <= height)
                if (i > 0 && used + DepartureWidget.cardHeight(compact, tracks) > height) break
                used += DepartureWidget.cardHeight(compact, tracks)
                val card = RemoteViews(context.packageName, R.layout.widget_group)
                card.setTextViewText(R.id.group_title, "${hop.optString("from")}–${hop.optString("to")}")
                card.removeAllViews(R.id.departures)
                if (departures.isEmpty()) card.setViewVisibility(R.id.group_empty, View.VISIBLE)
                val maxRows = (inner / DepartureWidget.columnWidth(mode, compact)).coerceIn(1, 8)
                for ((j, journey) in departures.take(maxRows).withIndex()) {
                    val ride = CommuteRepository.rides(journey).first()
                    val cancelled = ride.optBoolean("cancelled")
                    val info = when {
                        cancelled -> "Cancelled"
                        tracks && ride.optString("fromQuay").isNotEmpty() -> "Track ${ride.optString("fromQuay")}"
                        else -> ""
                    }
                    val item = DepartureWidget.departureItem(context, compact, ride.optString("line"), ride.optString("mode"),
                        ride.optString("aimedStart"), ride.optString("expectedStart"), cancelled, info)
                    item.setOnClickPendingIntent(R.id.departure_item, JourneyActivity.hopIntent(context, id, hop.optString("id"), journey, id * 100 + 40 + i * 10 + j))
                    card.addView(R.id.departures, item)
                }
                root.addView(R.id.content, card)
            }
        }

        private fun mode(departures: List<JSONObject>) =
            departures.firstNotNullOfOrNull { CommuteRepository.rides(it).firstOrNull()?.optString("mode") } ?: "bus"

        /** A badge's label, a little over the widest glyphs (R, E) so the longest name in a column still fits its minimum. */
        private fun labelWidth(length: Int, compact: Boolean, scale: Float) =
            ((if (compact) 7.5 else 8.0) * length * scale).toInt()

        private fun badgeWidth(length: Int, compact: Boolean, scale: Float) =
            if (compact) maxOf(34, 12 + labelWidth(length, true, scale)) else maxOf(50, 33 + labelWidth(length, false, scale))

        internal fun minutes(from: String, to: String): Long {
            val a = CommuteRepository.millis(from) ?: return 0
            val b = CommuteRepository.millis(to) ?: return 0
            return Math.floorDiv(b - a, 60_000L)
        }

        internal fun clock(iso: String): String =
            CommuteRepository.millis(iso)?.let { GlanceTime.at(it).let { t -> "%02d:%02d".format(t.hour, t.minute) } } ?: "--:--"
    }
}
