package io.gjessing.glance

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Every widget kind in one place: who is placed, and how each renders from its cache. */
object Widgets {
    private fun ids(context: Context, provider: Class<*>): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, provider))

    fun departures(context: Context) = ids(context, DepartureWidget::class.java)
    fun commutes(context: Context) = ids(context, CommuteWidget::class.java)
    fun smart(context: Context) = ids(context, SmartWidget::class.java)
    fun agendas(context: Context) = ids(context, AgendaWidget::class.java)
    fun weathers(context: Context) = ids(context, WeatherWidget::class.java)
    fun trees(context: Context) = ids(context, TreeWidget::class.java)
    fun worlds(context: Context) = ids(context, WorldWidget::class.java)

    fun anyPlaced(context: Context) = listOf(departures(context), commutes(context), smart(context), agendas(context), weathers(context), trees(context), worlds(context)).any { it.isNotEmpty() }

    /** Glance widgets whose arrangement includes this section. */
    fun smartWith(context: Context, section: String) =
        smart(context).filter { section in SectionConfigs.sections(SectionConfigs.config(context, it, SectionConfigs.SMART)) }

    fun renderAll(context: Context) {
        departures(context).forEach { DepartureWidget.render(context, it) }
        commutes(context).forEach { CommuteWidget.render(context, it) }
        smart(context).forEach { SmartWidget.render(context, it) }
        agendas(context).forEach { AgendaWidget.render(context, it) }
        weathers(context).forEach { WeatherWidget.render(context, it) }
        trees(context).forEach { TreeWidget.render(context, it) }
        worlds(context).forEach { WorldWidget.render(context, it) }
    }

    /** One widget, whatever its kind. */
    fun render(context: Context, id: Int) {
        when (id) {
            in departures(context) -> DepartureWidget.render(context, id)
            in commutes(context) -> CommuteWidget.render(context, id)
            in smart(context) -> SmartWidget.render(context, id)
            in agendas(context) -> AgendaWidget.render(context, id)
            in weathers(context) -> WeatherWidget.render(context, id)
            in trees(context) -> TreeWidget.render(context, id)
            in worlds(context) -> WorldWidget.render(context, id)
        }
    }

    /** The widgets with a refresh button, for the countdown's ticks; the pictures are left alone. */
    fun renderRefreshable(context: Context) {
        departures(context).forEach { DepartureWidget.render(context, it) }
        commutes(context).forEach { CommuteWidget.render(context, it) }
        smart(context).forEach { SmartWidget.render(context, it) }
        agendas(context).forEach { AgendaWidget.render(context, it) }
        weathers(context).forEach { WeatherWidget.render(context, it) }
    }
}

/**
 * The one refresh job (roadmap F4). Each source decides whether it is due: transport and the
 * agenda on every run (the agenda is one small request), weather only once MET's Expires has passed.
 */
object RefreshCoordinator {
    private const val prefsName = "glance_refresh"

    /** What one run has already asked, so the tapped widget's head start is not asked again. */
    private class Pass {
        val calls = mutableMapOf<String, JSONArray>()
        val trips = mutableMapOf<String, Pair<JSONObject, List<String>>>()
        val boards = mutableSetOf<Int>()
        val commutes = mutableSetOf<Int>()
        var agenda = false
    }

    /**
     * Returns false when any source failed, so WorkManager retries. With `first`, the tapped widget's
     * own sources come first: its countdown ends once it shows the new data, and the rest follow.
     */
    fun run(context: Context, first: Int? = null): Boolean {
        val prefs = context.getSharedPreferences(prefsName, 0)
        prefs.edit().putLong("runningAt", System.currentTimeMillis()).apply()
        try {
            val pass = Pass()
            var ok = true
            if (first != null) {
                ok = sources(context, pass, first)
                finishManual(context, record = true)
                Widgets.render(context, first)
            }
            ok = sources(context, pass, null) && ok
            // A tap without a widget ends here, and so does one on another widget while the rest were refreshed.
            finishManual(context, record = first == null)
            Widgets.renderAll(context)
            BoundaryAlarm.arm(context)
            return ok
        } finally { prefs.edit().remove("runningAt").apply() }
    }

    /** A run is under way, tapped or not; after two minutes it is let go. */
    fun running(context: Context): Boolean {
        val at = context.getSharedPreferences(prefsName, 0).getLong("runningAt", 0L)
        return at > 0 && System.currentTimeMillis() - at < 120_000L
    }

    /** Ends a tapped refresh's countdown; a whole tapped refresh's length sets the next one's. */
    private fun finishManual(context: Context, record: Boolean) {
        val prefs = context.getSharedPreferences(prefsName, 0)
        val manualAt = prefs.getLong("manualAt", 0L)
        if (manualAt <= 0) return
        prefs.edit().apply { if (record) putLong("manualMs", System.currentTimeMillis() - manualAt) }.remove("manualAt").apply()
    }

    /** Refreshes what widget `only` shows, or with null what every widget shows. False when a source failed. */
    private fun sources(context: Context, pass: Pass, only: Int?): Boolean {
        fun among(ids: List<Int>) = if (only == null) ids else ids.filter { it == only }
        var ok = transport(context, pass, among(Widgets.departures(context).toList()), among(commuteIds(context))).isEmpty()
        if (!pass.agenda && among(Widgets.agendas(context).toList() + Widgets.smartWith(context, "agenda")).isNotEmpty() && AgendaRepository.configured(context)) {
            pass.agenda = true
            try { AgendaRepository.refresh(context) } catch (error: Exception) { ok = false; AgendaRepository.markStale(context, error.message ?: "Nodecal unavailable") }
        }
        val weatherIds = among(Widgets.weathers(context).toList() + Widgets.smartWith(context, "weather"))
        val places = (weatherIds.flatMap { WeatherRepository.places(context, it, network = true) } + (if (only != null) emptyList() else
            Widgets.trees(context).toList().mapNotNull { TreeWidget.place(context, it, network = true) } +
            Widgets.worlds(context).toList().mapNotNull { WorldWidget.place(context, it, network = true) })).distinctBy { it.key }
        if (!WeatherRepository.refreshDue(context, places)) ok = false
        // The northern lights for the pictures' sky, at home; after the weather, whose cloud decides whether OVATION is worth it.
        if (only == null) (Widgets.trees(context).firstOrNull()?.let { TreeWidget.place(context, it) } ?: Widgets.worlds(context).firstOrNull()?.let { WorldWidget.place(context, it) })
            ?.let { SpaceWeather.refreshDue(context, it) }
        return ok
    }

    private fun commuteIds(context: Context) = Widgets.commutes(context).toList() + Widgets.smartWith(context, "commute")

    /**
     * Every transport widget at once, departure boards and commutes alike, so a refresh asked for by
     * one never leaves another showing an older moment. Boards share their stop requests: a stop that
     * several boards leave from is asked of Entur once, and commutes with the same stops share their trips.
     * The extra ids add a widget that is not placed (the app's preview). Returns the first error, or "" when everything answered.
     */
    fun refreshTransport(context: Context, extraBoard: Int? = null, extraCommute: Int? = null): String =
        transport(context, Pass(), Widgets.departures(context).toList() + listOfNotNull(extraBoard), commuteIds(context) + listOfNotNull(extraCommute))

    private fun transport(context: Context, pass: Pass, boards: List<Int>, commutes: List<Int>): String {
        var error = ""
        for (id in boards.distinct().filter { pass.boards.add(it) }) {
            try { BoardRepository.refresh(context, id, pass.calls) } catch (e: Exception) { error = error.ifEmpty { e.message ?: "Entur unavailable" }; BoardRepository.markStale(context, id) }
        }
        for (id in commutes.distinct().filter { pass.commutes.add(it) }) {
            try { CommuteRepository.refresh(context, id, pass.trips) } catch (e: Exception) { error = error.ifEmpty { e.message ?: "Entur unavailable" }; CommuteRepository.markStale(context, id) }
        }
        if (boards.isNotEmpty() || commutes.isNotEmpty()) context.getSharedPreferences(prefsName, 0).edit().putLong("transportAt", System.currentTimeMillis()).apply()
        return error
    }

    fun transportAt(context: Context) = context.getSharedPreferences(prefsName, 0).getLong("transportAt", 0L)

    /** A tap on `widgetId`'s refresh button; only that widget counts down. */
    fun markManual(context: Context, widgetId: Int?) {
        context.getSharedPreferences(prefsName, 0).edit().putLong("manualAt", System.currentTimeMillis()).putInt("manualWidget", widgetId ?: -1).apply()
    }

    /** The widget whose tapped refresh is running, or null. */
    fun manualWidget(context: Context): Int? =
        if (refreshing(context)) context.getSharedPreferences(prefsName, 0).getInt("manualWidget", -1).takeIf { it >= 0 } else null

    /** A refresh button was tapped and its job has not finished; after a minute it is let go, offline or not. */
    fun refreshing(context: Context): Boolean {
        val at = context.getSharedPreferences(prefsName, 0).getLong("manualAt", 0L)
        return at > 0 && System.currentTimeMillis() - at < 60_000L
    }

    /**
     * What a refresh button shows while a tapped refresh runs: the seconds it should still take, judged
     * by the last tapped one, down to 1, where it waits until the refresh is in. Null when none runs.
     */
    fun countdown(context: Context, widgetId: Int, nowMs: Long = System.currentTimeMillis()): Int? {
        if (!refreshing(context)) return null
        // Without a widget (an older tap) every button counts; otherwise only the one tapped.
        val prefs = context.getSharedPreferences(prefsName, 0)
        val tapped = prefs.getInt("manualWidget", -1)
        if (tapped >= 0 && tapped != widgetId) return null
        return secondsLeft(prefs.getLong("manualAt", nowMs), prefs.getLong("manualMs", 0L), nowMs)
    }

    /** The countdown's length: the last tapped refresh, five seconds before there was one. */
    private fun span(lastMs: Long) = (if (lastMs > 0) lastMs else 5_000L).coerceIn(2_000L, 9_000L)

    internal fun secondsLeft(tappedAt: Long, lastMs: Long, nowMs: Long): Int =
        Math.floorDiv(span(lastMs) - (nowMs - tappedAt) + 999, 1000L).toInt().coerceAtLeast(1)
}

/**
 * Re-renders from cache at the next interesting instant (roadmap F5): a countdown step, an event
 * starting or ending, a departure leaving a board, a commute window or work-hours edge, the direction switch, midnight. It is an
 * inexact RTC alarm, so it never wakes the phone: it fires when the screen is on to see it. Inside a
 * commute window, or while a journey is tracked, it also asks for fresh transport every fifteen minutes.
 */
object BoundaryAlarm {
    const val ACTION = "io.gjessing.glance.BOUNDARY"
    private const val TRANSPORT_MS = 15 * 60_000L

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 1,
        Intent(context, BoundaryReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun arm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val next = next(context)
        if (next == null) {
            manager.cancel(pending(context))
            return
        }
        manager.set(AlarmManager.RTC, next.toEpochMilli(), pending(context))
    }

    private fun next(context: Context): Instant? {
        val smart = Widgets.smart(context)
        val departures = Widgets.departures(context)
        val agendas = Widgets.agendas(context)
        val commutes = Widgets.commutes(context)
        val weathers = Widgets.weathers(context)
        val trees = Widgets.trees(context)
        val worlds = Widgets.worlds(context)
        if (smart.isEmpty() && departures.isEmpty() && agendas.isEmpty() && commutes.isEmpty() && weathers.isEmpty() && trees.isEmpty() && worlds.isEmpty()) return null
        val now = Instant.now()
        val rules = GlanceTime.rules(context)
        val local = GlanceTime.at(now.toEpochMilli())
        val candidates = mutableListOf(GlanceTime.nextRuleChange(local, rules).toInstant())
        smart.forEach { id -> SmartWidget.nextBoundary(context, id)?.let { candidates += it } }
        departures.forEach { id -> DepartureWidget.nextBoundary(context, id, now)?.let { candidates += it } }
        agendas.forEach { id -> AgendaWidget.nextBoundary(context, id)?.let { candidates += it } }
        // The Living Tree follows the light as the sun moves.
        trees.firstOrNull()?.let { candidates += TreeWidget.nextBoundary(context, it) }
        // Tiny Living World too, and its visitors come and go every twenty minutes.
        worlds.firstOrNull()?.let { candidates += WorldWidget.nextBoundary(context, it) }
        // The hourly strip's "Now" moves on at the top of the hour.
        if (weathers.isNotEmpty()) candidates += local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant()
        // A tracked journey ends on arrival; the widget showing it lets it go then.
        val tracking = (commutes.toList() + smart.toList()).mapNotNull { CommuteRepository.trackEnd(context, it) }
        candidates += tracking
        if ((GlanceTime.commuteWindow(local, rules) != null || tracking.isNotEmpty()) && (commutes.isNotEmpty() || smart.isNotEmpty() || departures.isNotEmpty())) {
            candidates += Instant.ofEpochMilli(maxOf(now.toEpochMilli() + 60_000L, RefreshCoordinator.transportAt(context) + TRANSPORT_MS))
        }
        return candidates.filter { it.isAfter(now) }.minOrNull()
    }

    /** Inside a commute window or while a journey is tracked, transport older than fifteen minutes is refreshed. */
    fun transportDue(context: Context): Boolean {
        val commutes = Widgets.commutes(context).toList() + Widgets.smartWith(context, "commute")
        if (commutes.isEmpty() && Widgets.departures(context).isEmpty()) return false
        val local = GlanceTime.at(System.currentTimeMillis())
        val live = GlanceTime.commuteWindow(local, GlanceTime.rules(context)) != null || commutes.any { CommuteRepository.trackEnd(context, it) != null }
        return live && System.currentTimeMillis() - RefreshCoordinator.transportAt(context) >= TRANSPORT_MS
    }
}

/** The boundary alarm, plus the system events after which it must be armed again. */
class BoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BoundaryAlarm.ACTION -> if (BoundaryAlarm.transportDue(context)) DepartureWidget.refresh(context)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> if (Widgets.anyPlaced(context)) DepartureWidget.schedule(context)
        }
        Widgets.renderAll(context)
        BoundaryAlarm.arm(context)
    }
}
