package io.gjessing.glance

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.getcapacitor.Bridge
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@CapacitorPlugin(name = "Glance")
class GlancePlugin : Plugin() {
    private val executor = Executors.newSingleThreadExecutor()
    /** The web bundle the last check offered; applyWebUpdate installs exactly this one. */
    @Volatile private var offeredWeb: JSONObject? = null

    @PluginMethod fun listWidgets(call: PluginCall) {
        val list = JSArray()
        for (id in DepartureWidget.ids(context)) list.put(JSONObject().put("widgetId", id).put("type", "transport").put("config", BoardRepository.config(context, id)))
        for (id in CommuteWidget.ids(context)) list.put(JSONObject().put("widgetId", id).put("type", "commute").put("config", CommuteRepository.config(context, id)))
        for ((type, ids) in listOf(SectionConfigs.SMART to Widgets.smart(context), SectionConfigs.AGENDA to Widgets.agendas(context), SectionConfigs.WEATHER to Widgets.weathers(context))) {
            for (id in ids) list.put(JSONObject().put("widgetId", id).put("type", type).put("config", SectionConfigs.config(context, id, type)))
        }
        call.resolve(JSObject().put("widgets", list))
    }

    /** Both widget kinds share these methods; `type` picks the repository and defaults to the original transport board. */
    private fun commute(call: PluginCall) = call.getString("type") == "commute"
    private fun sectionType(call: PluginCall) = call.getString("type")?.takeIf { it in setOf(SectionConfigs.SMART, SectionConfigs.AGENDA, SectionConfigs.WEATHER) }

    private fun renderSection(id: Int, type: String) {
        if (id < 0) return
        when (type) {
            SectionConfigs.SMART -> SmartWidget.render(context, id)
            SectionConfigs.AGENDA -> AgendaWidget.render(context, id)
            else -> WeatherWidget.render(context, id)
        }
        BoundaryAlarm.arm(context)
    }

    /**
     * What a section widget shows right now, computed by the same code as the widget: the agenda
     * state for its profile, the rain advice and the temperature where the user likely is.
     */
    private fun glance(id: Int, type: String): JSObject {
        val sc = SectionWidget.sectionContext(context, id, type)
        val result = JSObject().put("config", sc.config).put("nodecalConfigured", AgendaRepository.configured(context))
        val cache = AgendaRepository.cached(context)
        val agenda = cache?.optJSONObject("agenda")
        if (type != SectionConfigs.WEATHER) {
            val profile = AgendaSection.profile(sc)
            result.put("profile", profile)
            if (cache != null) result.put("agendaCheckedAt", cache.optString("checkedAt")).put("agendaError", cache.optString("error"))
            if (agenda != null) {
                val state = AgendaModel.state(agenda, profile, sc.now, sc.zone, 4)
                val slots = JSArray()
                for (slot in state.slots) slots.put(JSONObject().put("id", slot.event.id).put("title", slot.event.title).put("time", slot.time)
                    .put("countdown", slot.countdown).put("color", slot.color).put("meta", slot.meta).put("ongoing", slot.ongoing).put("clash", slot.clash).put("futureDay", slot.futureDay)
                    .put("meetingUrl", slot.event.meetingUrl).put("location", slot.event.location))
                result.put("agenda", JSObject().put("slots", slots).put("allDay", state.allDayText ?: "").put("allDayLabel", state.allDayLabel))
            }
        }
        if (type != SectionConfigs.AGENDA) {
            val advice = WeatherSection.advice(sc)
            result.put("advice", JSObject().put("kind", advice.kind.name.lowercase()).put("title", advice.title).put("reason", advice.reason))
            val places = JSArray()
            for (place in WeatherRepository.places(context, id)) places.put(JSONObject().put("name", place.name).put("lat", place.lat).put("lon", place.lon))
            result.put("places", places)
            WeatherSection.here(sc)?.let { here ->
                WeatherRepository.now(context, here)?.let { now -> result.put("weatherNow", now.put("place", here.name).put("emoji", WeatherRepository.emoji(now.optString("symbol")))) }
                val hours = JSArray()
                for (hour in WeatherRepository.hourRows(context, here).take(12)) hours.put(hour.put("emoji", WeatherRepository.emoji(hour.optString("symbol"))))
                result.put("hours", hours)
            }
        }
        if (type == SectionConfigs.SMART) {
            result.put("context", GlanceTime.contextLabel(sc.local, sc.rules))
            result.put("sections", JSArray().apply { SmartWidget.plan(sc, Int.MAX_VALUE).forEach { put(JSONObject().put("id", it.first.id).put("size", it.second.name.lowercase())) } })
        }
        return result
    }

    /**
     * The longer forecast behind a tap on the weather: at both ends of the commute, now, the next
     * 48 hours and up to nine days. A forecast cached before day rows existed is fetched again.
     */
    @PluginMethod fun getForecast(call: PluginCall) {
        val id = call.getInt("widgetId") ?: -1
        executor.execute {
            val places = try { WeatherRepository.places(context, id, network = true) } catch (_: Exception) { emptyList() }
            // A forecast cached before day rows (or their steps) existed is fetched again.
            if (places.any { place -> WeatherRepository.dayRows(context, place).let { it.isEmpty() || !it[0].has("steps") } }) WeatherRepository.forget(context, places)
            WeatherRepository.refreshDue(context, places)
            val list = JSArray()
            for (place in places) {
                val hours = JSArray()
                for (hour in WeatherRepository.hourRows(context, place).filter { (CommuteRepository.millis(it.optString("t")) ?: 0) + 3_600_000L > System.currentTimeMillis() })
                    hours.put(hour.put("emoji", WeatherRepository.emoji(hour.optString("symbol"))))
                val days = JSArray()
                for (day in WeatherRepository.dayRows(context, place)) {
                    val steps = day.optJSONArray("steps") ?: JSONArray()
                    for (i in 0 until steps.length()) steps.getJSONObject(i).let { it.put("emoji", WeatherRepository.emoji(it.optString("symbol"))) }
                    days.put(day.put("emoji", WeatherRepository.emoji(day.optString("symbol"))).put("steps", steps))
                }
                val entry = JSONObject().put("name", place.name).put("hours", hours).put("days", days)
                    .put("checkedAt", WeatherRepository.checkedAt(context, place) ?: 0)
                WeatherRepository.now(context, place)?.let { entry.put("now", it.put("emoji", WeatherRepository.emoji(it.optString("symbol")))) }
                list.put(entry)
            }
            val sc = SectionWidget.sectionContext(context, id, SectionConfigs.WEATHER)
            val advice = WeatherSection.advice(sc)
            call.resolve(JSObject().put("places", list).put("here", WeatherSection.here(sc)?.name ?: "")
                .put("advice", JSObject().put("kind", advice.kind.name.lowercase()).put("title", advice.title).put("reason", advice.reason)))
        }
    }

    @PluginMethod fun getGlance(call: PluginCall) {
        val type = sectionType(call) ?: return call.reject("Unknown widget type")
        call.resolve(glance(call.getInt("widgetId") ?: -1, type))
    }

    /** Settings the web layer may see: never the token itself, only whether one is saved. */
    @PluginMethod fun getSettings(call: PluginCall) {
        val settings = GlanceSettings.get(context)
        call.resolve(JSObject().put("nodecalUrl", settings.optString("nodecalUrl"))
            .put("hasToken", GlanceSettings.nodecalToken(context).isNotEmpty())
            .put("refreshMinutes", GlanceSettings.refreshMinutes(context))
            .put("time", GlanceTime.rules(context).toJson())
            .put("weather", WeatherSection.thresholds(context).let { JSONObject().put("probability", it.probability).put("amount", it.amount).put("heavy", it.heavy) }))
    }

    @PluginMethod fun saveSettings(call: PluginCall) {
        val settings = GlanceSettings.get(context)
        call.getString("nodecalUrl")?.let { url ->
            val clean = url.trim().trimEnd('/')
            if (clean.isNotEmpty() && !clean.startsWith("https://") && !clean.startsWith("http://")) return call.reject("The Nodecal address starts with https://")
            if (clean != settings.optString("nodecalUrl")) AgendaRepository.clear(context)
            settings.put("nodecalUrl", clean)
        }
        call.getObject("time")?.let { settings.put("time", TimeRules.fromJson(it).toJson()) }
        call.getInt("refreshMinutes")?.toLong()?.let { minutes ->
            if (minutes !in GlanceSettings.refreshChoices) return call.reject("Refresh every 15, 30 or 60 minutes")
            settings.put("refreshMinutes", minutes)
        }
        call.getObject("weather")?.let { weather ->
            val d = Thresholds()
            settings.put("weather", JSONObject().put("probability", weather.optDouble("probability", d.probability).coerceIn(0.0, 100.0))
                .put("amount", weather.optDouble("amount", d.amount).coerceIn(0.0, 20.0)).put("heavy", weather.optDouble("heavy", d.heavy).coerceIn(0.1, 50.0)))
        }
        GlanceSettings.put(context, settings)
        if (Widgets.anyPlaced(context)) DepartureWidget.schedule(context)
        call.getString("token")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            GlanceSettings.setNodecalToken(context, it)
            AgendaRepository.clear(context)
        }
        if (call.getBoolean("clearToken") == true) {
            GlanceSettings.setNodecalToken(context, "")
            AgendaRepository.clear(context)
        }
        Widgets.renderAll(context)
        BoundaryAlarm.arm(context)
        DepartureWidget.refresh(context)
        call.resolve()
    }

    /** Asks Nodecal with the address and token given (or the saved ones) and reports what came back. */
    @PluginMethod fun testNodecal(call: PluginCall) {
        val url = call.getString("nodecalUrl")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: AgendaRepository.url(context)
        val token = call.getString("token")?.trim()?.takeIf { it.isNotEmpty() } ?: GlanceSettings.nodecalToken(context)
        executor.execute {
            try {
                val agenda = AgendaRepository.fetch(url, token)
                call.resolve(JSObject().put("ok", true).put("events", agenda.optJSONArray("events")?.length() ?: 0)
                    .put("calendars", agenda.optJSONArray("calendars")?.length() ?: 0))
            } catch (error: Exception) {
                call.resolve(JSObject().put("ok", false).put("error", error.message ?: "Nodecal unavailable"))
            }
        }
    }

    @PluginMethod fun getBoard(call: PluginCall) {
        val id = call.getInt("widgetId") ?: -1
        sectionType(call)?.let { return call.resolve(glance(id, it)) }
        val commute = commute(call)
        val board = if (commute) CommuteRepository.cached(context, id) else BoardRepository.cached(context, id)
        val result = JSObject().put("config", if (commute) CommuteRepository.config(context, id) else BoardRepository.config(context, id))
        if (board != null) result.put("board", board)
        if (commute) result.put("mode", CommuteRepository.mode(context, id))
        call.resolve(result)
    }

    @PluginMethod fun saveConfig(call: PluginCall) {
        val id = call.getInt("widgetId") ?: return call.reject("Missing widgetId")
        val config = call.getObject("config") ?: return call.reject("Missing config")
        try {
            val type = sectionType(call)
            if (type != null) {
                SectionConfigs.saveConfig(context, id, config)
                renderSection(id, type)
            } else if (commute(call)) {
                CommuteRepository.saveConfig(context, id, config)
                if (id >= 0) CommuteWidget.render(context, id)
                if (id >= 0 && id in Widgets.smart(context)) SmartWidget.render(context, id)
            } else {
                BoardRepository.saveConfig(context, id, config)
                if (id >= 0) DepartureWidget.render(context, id)
            }
            DepartureWidget.refresh(context)
            call.resolve()
        } catch (error: Exception) { call.reject(error.message ?: "Invalid configuration") }
    }

    @PluginMethod fun refresh(call: PluginCall) {
        val id = call.getInt("widgetId") ?: -1
        val type = sectionType(call)
        if (type != null) {
            executor.execute {
                var error = ""
                if (type != SectionConfigs.WEATHER) {
                    // Every transport widget with it, so the departure boards match what this one shows.
                    if (type == SectionConfigs.SMART) error = RefreshCoordinator.refreshTransport(context, extraCommute = id)
                    if (AgendaRepository.configured(context)) {
                        try { AgendaRepository.refresh(context) } catch (e: Exception) { AgendaRepository.markStale(context, e.message ?: ""); error = e.message ?: "" }
                    }
                }
                if (type != SectionConfigs.AGENDA) {
                    val places = try { WeatherRepository.places(context, id, network = true) } catch (_: Exception) { emptyList() }
                    if (!WeatherRepository.refreshDue(context, places)) error = error.ifEmpty { "MET unavailable" }
                }
                if (type == SectionConfigs.SMART) Widgets.renderAll(context)
                renderSection(id, type)
                call.resolve(glance(id, type).put("error", error))
            }
            return
        }
        val commute = commute(call)
        executor.execute {
            // One board or commute asked for, every transport widget refreshed, so none of them lags the others.
            val error = if (commute) RefreshCoordinator.refreshTransport(context, extraCommute = id) else RefreshCoordinator.refreshTransport(context, extraBoard = id)
            Widgets.renderAll(context)
            BoundaryAlarm.arm(context)
            val board = if (commute) CommuteRepository.cached(context, id) else BoardRepository.cached(context, id)
            when {
                board == null -> call.reject(error.ifEmpty { "Entur unavailable" })
                // A failed refresh leaves this board marked stale; another widget's failure does not.
                board.optBoolean("stale") -> call.resolve(JSObject().put("board", board).put("error", error))
                else -> call.resolve(JSObject().put("board", board))
            }
        }
    }

    /** Track a commute journey, or stop with no journey. The widget holds the same journey. */
    @PluginMethod fun track(call: PluginCall) {
        val id = call.getInt("widgetId") ?: return call.reject("Missing widgetId")
        val direction = call.getString("direction") ?: CommuteRepository.OUTBOUND
        CommuteRepository.track(context, id, direction, call.getObject("journey"))
        if (id >= 0) {
            if (id in Widgets.smart(context)) SmartWidget.render(context, id) else CommuteWidget.render(context, id)
            BoundaryAlarm.arm(context)
        }
        call.resolve(JSObject().put("board", CommuteRepository.cached(context, id)))
    }

    /** Fetched natively: the WebView's localhost origin would need CORS from the homepage. */
    @PluginMethod fun checkUpdate(call: PluginCall) {
        executor.execute {
            val result = JSObject().put("versionName", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
                .put("webId", WebBundle.currentId(context))
            try {
                val connection = URL(UPDATE_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.useCaches = false
                // SSO answers with a login redirect, not the manifest; treat that as no update.
                connection.instanceFollowRedirects = false
                try {
                    if (connection.responseCode == 200) {
                        val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        // Lets a manual check tell "up to date" apart from "could not reach the manifest".
                        result.put("checked", true)
                        val url = release.optString("apkUrl")
                        if (release.optInt("versionCode") > BuildConfig.VERSION_CODE && url.startsWith(UPDATE_ORIGIN)) {
                            result.put("update", JSObject().put("versionName", release.optString("versionName"))
                                .put("versionCode", release.optInt("versionCode")).put("apkUrl", url).put("bytes", release.optLong("bytes")))
                        }
                        // A newer APK replaces the bundle anyway, and a bundle only ever targets one APK.
                        offeredWeb = if (result.has("update")) null else WebBundle.offered(context, release.optJSONObject("web"), UPDATE_ORIGIN)
                        offeredWeb?.let { result.put("web", JSObject().put("id", it.optString("id")).put("bytes", it.optLong("bytes"))) }
                    }
                } finally { connection.disconnect() }
            } catch (_: Exception) { }
            call.resolve(result)
        }
    }

    /** The system browser downloads the APK and hands it to Android's installer. */
    @PluginMethod fun openUpdate(call: PluginCall) {
        val url = call.getString("url") ?: return call.reject("Missing url")
        if (!url.startsWith(UPDATE_ORIGIN)) return call.reject("Not a Glance release URL")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            call.resolve()
        } catch (error: Exception) { call.reject("No app can open this link") }
    }

    /** Installs the offered web bundle and reloads the WebView into it; no APK involved. */
    @PluginMethod fun applyWebUpdate(call: PluginCall) {
        val web = offeredWeb ?: return call.reject("No update to apply")
        executor.execute {
            val dir = try { WebBundle.install(context, web) } catch (error: Exception) { return@execute call.reject(error.message ?: "Update failed") }
            WebBundle.activate(context, dir)
            offeredWeb = null
            call.resolve()
            activity.runOnUiThread { bridge.setServerBasePath(dir.path) }
            Handler(Looper.getMainLooper()).postDelayed({
                if (WebBundle.revertUnconfirmed(context)) bridge.setServerAssetPath(Bridge.DEFAULT_WEB_ASSET_DIR)
            }, WebBundle.CONFIRM_MS)
        }
    }

    /** Every bundle calls this once it has rendered, which keeps an applied update. */
    @PluginMethod fun webReady(call: PluginCall) {
        WebBundle.confirm(context)
        call.resolve()
    }

    @PluginMethod fun getLaunchTarget(call: PluginCall) {
        val target = MainActivity.launchTarget(activity.intent)
        activity.intent.removeExtra("journeyId")
        activity.intent.removeExtra("commuteMode")
        activity.intent.removeExtra("screen")
        activity.intent.removeExtra("widgetId")
        call.resolve(JSObject().put("target", target))
    }

    companion object {
        private const val UPDATE_ORIGIN = "https://home.gjessing.io/downloads/"
        private const val UPDATE_URL = UPDATE_ORIGIN + "glance-version.json"
    }
}
