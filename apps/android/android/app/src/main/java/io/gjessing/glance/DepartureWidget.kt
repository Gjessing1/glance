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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

class DepartureWidget : AppWidgetProvider() {
    override fun onEnabled(context: Context) { schedule(context) }
    override fun onDisabled(context: Context) { cancelIfUnused(context) }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            BoardRepository.config(context, id)
            render(context, id)
        }
        refresh(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, id)
    }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { BoardRepository.remove(context, it) } }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshByHand(context, intent.data?.lastPathSegment?.toIntOrNull())
    }

    companion object {
        const val ACTION_REFRESH = "io.gjessing.glance.REFRESH"
        fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, DepartureWidget::class.java))
        /** The background refresh, every `refreshMinutes` (30 by default). UPDATE carries a changed interval over to the running job. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetWorker>(GlanceSettings.refreshMinutes(context), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("glance-refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        /** One refresh job serves every widget kind; it stops only when the last widget of any kind is removed. */
        fun cancelIfUnused(context: Context) {
            if (!Widgets.anyPlaced(context)) WorkManager.getInstance(context).cancelUniqueWork("glance-refresh")
        }
        fun refresh(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WidgetWorker>().build())
        }

        /**
         * A tap on a widget's refresh button: that widget is refreshed first and counts down until it
         * shows the new data, then the others follow. A tap while a tapped one counts down does nothing, so tapping again
         * cannot pile requests on Entur; a tap after a failed one replaces its retry, which would
         * otherwise wait out WorkManager's backoff.
         */
        fun refreshByHand(context: Context, widgetId: Int?) {
            if (RefreshCoordinator.refreshing(context)) return
            RefreshCoordinator.markManual(context, widgetId)
            if (widgetId != null) Widgets.render(context, widgetId) else Widgets.renderRefreshable(context)
            // A run already under way refreshes this widget too; it counts down until that run ends.
            if (RefreshCoordinator.running(context)) return
            WorkManager.getInstance(context).enqueueUniqueWork("glance-manual", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetWorker>().setInputData(workDataOf(MANUAL to true, FIRST to (widgetId ?: -1))).build())
        }
        const val MANUAL = "manual"
        const val FIRST = "first"
        fun render(context: Context, id: Int) {
            val manager = AppWidgetManager.getInstance(context)
            val options = manager.getAppWidgetOptions(id)
            // Launchers report min width/max height in portrait and the reverse in landscape.
            val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val width = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0).takeIf { it > 0 } ?: 250
            val height = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0).takeIf { it > 0 } ?: 110
            // Root padding takes 14dp; card heights are in cardHeight.
            val inner = width - 44
            val root = RemoteViews(context.packageName, R.layout.widget_root)
            root.removeAllViews(R.id.groups)
            val refreshIntent = Intent(context, DepartureWidget::class.java).apply { action = ACTION_REFRESH; data = Uri.parse("glance://refresh/$id") }
            val refreshPending = PendingIntent.getBroadcast(context, id, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            root.setOnClickPendingIntent(R.id.refresh, refreshPending)
            WidgetBlocks.refreshState(context, root, R.id.refresh, R.id.refresh_count, id)
            val board = BoardRepository.cached(context, id)
            val rows = board?.optJSONArray("groups")
            if (rows == null) {
                root.setTextViewText(R.id.widget_status, "Loading departures…")
            } else if (rows.length() == 0) {
                root.setTextViewText(R.id.widget_status, "No groups enabled")
            } else {
                // The header row costs a card's worth of height, so the first card carries refresh instead.
                root.setViewVisibility(R.id.widget_header, View.GONE)
                val checked = board.optString("checkedAt")
                val ageMinutes = try { (System.currentTimeMillis() - Instant.parse(checked).toEpochMilli()) / 60000 } catch (_: Exception) { 999L }
                val stale = ageMinutes > STALE_MINUTES || board.optBoolean("stale")
                val nowMs = System.currentTimeMillis()
                // Show every chosen departure: switch all cards to narrow columns if any card overflows at full size.
                val compact = (0 until rows.length()).any { i ->
                    val group = rows.getJSONObject(i)
                    upcoming(group, nowMs).size * columnWidth(group.optString("mode"), false) > inner
                }
                var used = 14
                for (i in 0 until rows.length()) {
                    val group = rows.getJSONObject(i)
                    if (i > 0 && used + cardHeight(compact, false) > height) break
                    // A rail card without room for its track line drops that line rather than being clipped.
                    val tracks = group.optString("mode") == "rail" && (i == 0 || used + cardHeight(compact, true) <= height)
                    used += cardHeight(compact, tracks)
                    val card = RemoteViews(context.packageName, R.layout.widget_group)
                    card.setTextViewText(R.id.group_title, group.optString("name").replace(" → ", "–"))
                    card.removeAllViews(R.id.departures)
                    if (i == 0) {
                        card.setOnClickPendingIntent(R.id.group_refresh, refreshPending)
                        WidgetBlocks.refreshState(context, card, R.id.group_refresh, R.id.group_refresh_count, id)
                        if (stale) {
                            card.setTextViewText(R.id.group_stale, "Updated ${clock(checked)}")
                            card.setViewVisibility(R.id.group_stale, View.VISIBLE)
                        }
                    }
                    val departures = upcoming(group, nowMs)
                    if (departures.isEmpty()) {
                        card.setViewVisibility(R.id.group_empty, View.VISIBLE)
                    } else {
                        val maxRows = (inner / columnWidth(group.optString("mode"), compact)).coerceIn(1, 8)
                        for (j in 0 until minOf(departures.size, maxRows)) {
                            val departure = departures[j]
                            val cancelled = departure.optBoolean("cancelled")
                            val info = when {
                                cancelled -> "Cancelled"
                                tracks && departure.optString("platform").isNotEmpty() -> "Track ${departure.optString("platform")}"
                                else -> ""
                            }
                            val item = departureItem(context, compact, departure.optString("line"), departure.optString("mode"),
                                departure.optString("scheduled"), departure.optString("expected"), cancelled, info)
                            val intent = Intent(context, JourneyActivity::class.java).apply {
                                data = Uri.parse("glance://departure/$id/${group.optString("id")}/${j}")
                                putExtra("widgetId", id)
                                putExtra("groupId", group.optString("id"))
                                putExtra("journeyId", departure.optString("journeyId"))
                                putExtra("departureTime", departure.optString("scheduled"))
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            val pending = PendingIntent.getActivity(context, (id * 1000) + (i * 10) + j, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            item.setOnClickPendingIntent(R.id.departure_item, pending)
                            card.addView(R.id.departures, item)
                        }
                    }
                    root.addView(R.id.groups, card)
                }
            }
            manager.updateAppWidget(id, root)
        }
        private const val STALE_MINUTES = 30L

        /**
         * A group's departures that have not left yet. The board holds what Entur said at the last
         * refresh, so rows drop out as the clock passes their expected time rather than lingering until
         * the next one.
         */
        internal fun upcoming(group: JSONObject, nowMs: Long): List<JSONObject> {
            val departures = group.optJSONArray("departures") ?: return emptyList()
            return (0 until departures.length()).map { departures.getJSONObject(it) }
                .filter { (CommuteRepository.millis(it.optString("expected")) ?: Long.MAX_VALUE) > nowMs }
        }

        /** When the board next looks different from cache: its first departure leaving, or the "Updated" label appearing. */
        fun nextBoundary(context: Context, id: Int, now: Instant): Instant? {
            val board = BoardRepository.cached(context, id) ?: return null
            val groups = board.optJSONArray("groups") ?: return null
            val leaving = (0 until groups.length()).flatMap { upcoming(groups.getJSONObject(it), now.toEpochMilli()) }
                .mapNotNull { CommuteRepository.millis(it.optString("expected")) }.minOrNull()
            val label = CommuteRepository.millis(board.optString("checkedAt"))?.plus((STALE_MINUTES + 1) * 60_000L)
            return listOfNotNull(leaving, label).map { Instant.ofEpochMilli(it) }.filter { it.isAfter(now) }.minOrNull()
        }

        /** One departure column: line badge, expected time, and an optional line under it. */
        fun departureItem(context: Context, compact: Boolean, line: String, mode: String, scheduled: String, expected: String, cancelled: Boolean, info: String,
                          layout: Int = if (compact) R.layout.widget_departure_compact else R.layout.widget_departure): RemoteViews {
            val item = RemoteViews(context.packageName, layout)
            val rail = mode == "rail"
            item.setTextViewText(R.id.line_text, line)
            item.setImageViewResource(R.id.mode_icon, if (rail) R.drawable.ic_train else R.drawable.ic_bus)
            item.setInt(R.id.line_badge, "setBackgroundResource", if (rail) R.drawable.badge_rail else R.drawable.badge_bus)
            item.setTextViewText(R.id.departure_time, clock(expected))
            // Like Entur: a late departure shows its expected time in red.
            if (cancelled || minutesLate(scheduled, expected) > 0) item.textColor(context, R.id.departure_time, LATE)
            if (info.isNotEmpty()) {
                item.setTextViewText(R.id.departure_info, info)
                item.setViewVisibility(R.id.departure_info, View.VISIBLE)
                if (cancelled) item.textColor(context, R.id.departure_info, LATE)
            }
            return item
        }
        /** Card heights including the 6dp gap above, measured on device. */
        internal fun cardHeight(compact: Boolean, tracks: Boolean): Int = when {
            compact -> if (tracks) 109 else 96
            else -> if (tracks) 114 else 100
        }
        /** Full columns were measured on device; compact ones are estimated from the narrower badge. */
        internal fun columnWidth(mode: String, compact: Boolean): Int {
            val rail = mode == "rail"
            return when {
                compact -> if (rail) 48 else 45
                else -> if (rail) 82 else 72
            }
        }
        private val LATE = R.color.glance_late
        internal fun minutesLate(scheduled: String, expected: String): Long = try {
            (Instant.parse(expected).toEpochMilli() - Instant.parse(scheduled).toEpochMilli()) / 60000
        } catch (_: Exception) { 0L }
        internal fun clock(iso: String): String = try {
            Instant.parse(iso).atZone(GlanceTime.zone()).format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: Exception) { "--:--" }
    }
}

/** The name WorkManager has stored for the periodic job; the work itself lives in RefreshCoordinator. */
class WidgetWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // A tapped refresh ticks its countdown down while the requests run, a widget tapped on the way too.
        val ticks = if (inputData.getBoolean(DepartureWidget.MANUAL, false)) ticker() else null
        try {
            val first = inputData.getInt(DepartureWidget.FIRST, -1).takeIf { it >= 0 }
            return if (RefreshCoordinator.run(applicationContext, first)) Result.success() else Result.retry()
        } finally { ticks?.cancel() }
    }

    /** Re-renders the counting widget each time its number changes. */
    private fun ticker(): Timer = Timer(true).apply {
        var shown = ""
        scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val context = applicationContext
                val widget = RefreshCoordinator.manualWidget(context)
                val state = "$widget:${RefreshCoordinator.countdown(context, widget ?: -1)}"
                if (state == shown) return
                shown = state
                if (!RefreshCoordinator.refreshing(context)) return
                widget?.let { Widgets.render(context, it) } ?: Widgets.renderRefreshable(context)
            }
        }, 250L, 250L)
    }
}
