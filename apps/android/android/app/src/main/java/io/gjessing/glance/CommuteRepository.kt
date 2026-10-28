package io.gjessing.glance

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * A commute is three stops, from → via → to, asked as door-to-door journeys in both directions plus
 * the single rides to and from the via stop. Borrowed from Atlas's commute board, but planned directly
 * against Entur, each question its own request a few at a time. Widgets with the same three stops
 * share one set of answers per refresh.
 */
object CommuteRepository {
    private const val endpoint = "https://api.entur.io/journey-planner/v3/graphql"
    private const val prefsName = "glance_commutes"
    private const val trips = 10
    /** A tapped direction holds for this long, then the widget follows the clock again. */
    internal const val PIN_MS = 3 * 60 * 60 * 1000L
    /** A tracked journey ends this long past its expected arrival, so a train a little late on the board is not let go early. */
    private const val TRACK_GRACE_MS = 60 * 1000L
    /**
     * Journeys are asked from this far back. Entur drops a connection once its walk to the first stop
     * should have begun, so a bus a few minutes' walk away vanished while it was still at its stop.
     */
    private const val LOOKBACK_MS = 10 * 60 * 1000L

    const val OUTBOUND = "outbound"
    const val VIA = "via"
    const val INBOUND = "inbound"

    private const val legFields = """mode transportSubmode distance realtime aimedStartTime expectedStartTime aimedEndTime expectedEndTime line{id publicCode} serviceJourney{id} fromEstimatedCall{cancellation predictionInaccurate destinationDisplay{frontText}} toEstimatedCall{cancellation} fromPlace{name quay{id publicCode stopPlace{id parent{id}}}} toPlace{name quay{id publicCode stopPlace{id parent{id}}}} situations{situationNumber severity reportType summary{value language} description{value language} advice{value language} infoLinks{uri}}"""
    /** The stops between a ride's ends, for the journey sheet a tapped leg or local bus opens. */
    private const val stopFields = """intermediateEstimatedCalls{quay{publicCode stopPlace{name}} aimedArrivalTime expectedArrivalTime aimedDepartureTime expectedDepartureTime cancellation}"""
    private const val fragment = """fragment T on Trip{routingErrors{code} tripPatterns{legs{$legFields $stopFields}}}"""
    private val stopsQuery = """query Stops(${'$'}from:String!,${'$'}via:String!,${'$'}to:String!){fromStop:stopPlace(id:${'$'}from){name latitude longitude} viaStop:stopPlace(id:${'$'}via){name} toStop:stopPlace(id:${'$'}to){name latitude longitude}}"""
    private const val STOPS = "stops"
    private val STOP_KEYS = listOf("fromStop", "viaStop", "toStop")
    /** Requests in flight at once: a refresh is about ten trips, and the slowest takes some five seconds. */
    private const val PARALLEL = 4

    /** Engelsrud terrasse → Asker → Nationaltheatret. Asker is the multimodal parent, so bus and rail both count. */
    fun defaults(): JSONObject = JSONObject().put("id", UUID.randomUUID().toString()).put("type", "commute").put("version", 1)
        .put("name", "My commute")
        .put("from", "NSR:StopPlace:70146").put("fromName", "Engelsrud")
        .put("via", "NSR:StopPlace:59616").put("viaName", "Asker")
        .put("to", "NSR:StopPlace:288").put("toName", "Nationaltheatret")
        .put("lines", "").put("count", 6)

    private fun prefs(context: Context) = context.getSharedPreferences(prefsName, 0)

    fun config(context: Context, widgetId: Int): JSONObject {
        val raw = prefs(context).getString("config_$widgetId", null)
        if (raw != null) return JSONObject(raw)
        val template = if (widgetId > 0) prefs(context).getString("config_-1", null) else null
        val fresh = template?.let { JSONObject(it).put("id", UUID.randomUUID().toString()) } ?: defaults()
        prefs(context).edit().putString("config_$widgetId", fresh.toString()).apply()
        return fresh
    }

    fun saveConfig(context: Context, widgetId: Int, value: JSONObject) {
        require(value.optString("type") == "commute")
        for (key in listOf("from", "via", "to")) require(value.optString(key).startsWith("NSR:StopPlace:")) { "Stops use NSR:StopPlace:id" }
        require(value.optInt("count", 0) in 1..8)
        // New stops make the old journeys and any tracked one meaningless.
        prefs(context).edit().putString("config_$widgetId", value.toString()).remove("board_$widgetId").remove("track_$widgetId").apply()
    }

    fun remove(context: Context, widgetId: Int) {
        prefs(context).edit().remove("config_$widgetId").remove("board_$widgetId").remove("track_$widgetId").remove("pin_$widgetId")
            .remove("ends_$widgetId").remove("nearby_$widgetId").remove("stops_$widgetId").apply()
    }

    fun cached(context: Context, widgetId: Int): JSONObject? =
        prefs(context).getString("board_$widgetId", null)?.let { withTracked(context, widgetId, JSONObject(it)) }

    fun markStale(context: Context, widgetId: Int) {
        val raw = prefs(context).getString("board_$widgetId", null) ?: return
        prefs(context).edit().putString("board_$widgetId", JSONObject(raw).put("stale", true).toString()).apply()
    }

    /** The tracked journey, or null once it has arrived. Expiry is judged on its own times, so a failed refresh cannot end it early. */
    fun tracked(context: Context, widgetId: Int, nowMs: Long = System.currentTimeMillis()): JSONObject? {
        val raw = prefs(context).getString("track_$widgetId", null) ?: return null
        val track = JSONObject(raw)
        val end = millis(track.getJSONObject("journey").optString("expectedEnd"))
        if (end != null && nowMs > end + TRACK_GRACE_MS) {
            prefs(context).edit().remove("track_$widgetId").apply()
            return null
        }
        return track
    }

    /** When the tracked journey ends, for the widget to let it go then; null when none is tracked. */
    fun trackEnd(context: Context, widgetId: Int): Instant? = tracked(context, widgetId)
        ?.let { millis(it.getJSONObject("journey").optString("expectedEnd")) }?.let { Instant.ofEpochMilli(it + TRACK_GRACE_MS + 1_000) }

    /** Tracking a journey also pins its direction, so the widget cannot swap it out mid-journey. */
    fun track(context: Context, widgetId: Int, direction: String, journey: JSONObject?) = synchronized(trackLock) {
        val edit = prefs(context).edit()
        if (journey == null) edit.remove("track_$widgetId")
        else {
            edit.putString("track_$widgetId", JSONObject().put("direction", direction).put("journey", journey).toString())
            edit.putString("pin_$widgetId", JSONObject().put("mode", direction).put("at", System.currentTimeMillis()).toString())
        }
        edit.apply()
    }

    /** Guards the tracked journey between a refresh reading it and writing its fresh times back. */
    private val trackLock = Any()

    /**
     * Stores Entur's latest times for the tracked journey, but only while that same journey is still
     * tracked: a refresh takes seconds, and a "Stop tracking" tapped meanwhile must not be undone by it.
     */
    private fun retrack(context: Context, widgetId: Int, direction: String, key: String, fresh: JSONObject) {
        synchronized(trackLock) {
            val current = prefs(context).getString("track_$widgetId", null)?.let { JSONObject(it) } ?: return
            if (current.optString("direction") != direction || current.getJSONObject("journey").optString("key") != key) return
            prefs(context).edit().putString("track_$widgetId", current.put("journey", fresh).toString()).apply()
        }
    }

    fun pin(context: Context, widgetId: Int, mode: String) {
        prefs(context).edit().putString("pin_$widgetId", JSONObject().put("mode", mode).put("at", System.currentTimeMillis()).toString()).apply()
    }

    /** A fresh tap wins, then a journey in progress, then the clock: outbound before the switch time, home after. */
    fun mode(context: Context, widgetId: Int, nowMs: Long = System.currentTimeMillis()): String {
        pinned(context, widgetId, nowMs)?.let { return it }
        tracked(context, widgetId, nowMs)?.let { return it.getString("direction") }
        return clockMode(nowMs, GlanceTime.rules(context))
    }

    /** The mode a tap chose in the last three hours, if any. */
    private fun pinned(context: Context, widgetId: Int, nowMs: Long): String? {
        val pin = prefs(context).getString("pin_$widgetId", null)?.let { JSONObject(it) } ?: return null
        // A pin to a hidden interchange cannot be tapped away, so it is passed over.
        if (pin.optString("mode") == VIA && config(context, widgetId).optBoolean("hideInterchange")) return null
        return if (nowMs - pin.optLong("at") < PIN_MS) pin.optString("mode", OUTBOUND) else null
    }

    internal fun clockMode(nowMs: Long, rules: TimeRules = TimeRules()) = GlanceTime.clockDirection(GlanceTime.at(nowMs), rules)

    /**
     * A commute set to hide on weekends has nothing to say on a day off, unless a tap or a tracked
     * journey asks for it anyway.
     */
    fun restDay(context: Context, widgetId: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (config(context, widgetId).optString("weekend") != "hide") return false
        if (GlanceTime.isWorkday(GlanceTime.at(nowMs), GlanceTime.rules(context))) return false
        return pinned(context, widgetId, nowMs) == null && tracked(context, widgetId, nowMs) == null
    }

    fun findJourney(context: Context, widgetId: Int, direction: String, key: String): JSONObject? {
        val board = cached(context, widgetId) ?: return null
        val journeys = board.optJSONObject(direction)?.optJSONArray("journeys") ?: return null
        for (i in 0 until journeys.length()) if (journeys.getJSONObject(i).optString("key") == key) return journeys.getJSONObject(i)
        return null
    }

    /**
     * Asks Entur and stores the widget's board. `shared` holds the answers already fetched in this
     * refresh, by stops, so a Commute widget and a Smart Glance going the same way ask once.
     */
    fun refresh(context: Context, widgetId: Int, shared: MutableMap<String, Pair<JSONObject, List<String>>> = mutableMapOf()): JSONObject {
        val config = config(context, widgetId)
        val track = tracked(context, widgetId)
        val nowMs = System.currentTimeMillis()
        val stops = "${config.getString("from")}|${config.getString("via")}|${config.getString("to")}"
        val (data, nearby) = shared.getOrPut(stops) { fetch(context, widgetId, config, nowMs) }
        if (data.has("fromStop")) prefs(context).edit().putString("stops_$widgetId", JSONObject().put("stops", stops)
            .put("data", JSONObject().apply { for (key in STOP_KEYS) put(key, data.opt(key)) }).toString()).apply()
        // Each journey Entur planned, with the earlier vehicles it left out put in front of their onward legs.
        fun filled(alias: String, stop: String?): JSONObject? {
            val trip = data.optJSONObject(if (stop == null) alias else "$alias@$stop") ?: return null
            val hop = when (alias) {
                "outbound" -> if (stop == null) "fromVia" else "fromVia@$stop"
                "inbound" -> "toVia"
                else -> return trip
            }
            return data.optJSONObject(hop)?.let { withEarlier(trip, it) } ?: trip
        }
        fun merged(alias: String, withNearby: Boolean): JSONObject? {
            val main = filled(alias, null) ?: return null
            if (!withNearby || nearby.isEmpty()) return main
            return mergeNearby(main, nearby.mapNotNull { filled(alias, it) }, leaving = alias == "outbound" || alias == "fromVia")
        }
        val lines = config.optString("lines").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val count = config.optInt("count", 6)
        fun name(alias: String, fallback: String) = data.optJSONObject(alias)?.optString("name").orEmpty().ifEmpty { fallback }
        val fromName = config.optString("fromName").ifEmpty { name("fromStop", "From") }
        val viaName = config.optString("viaName").ifEmpty { name("viaStop", "Via") }
        val toName = config.optString("toName").ifEmpty { name("toStop", "To") }
        fun direction(alias: String, from: String, to: String, hop: Boolean, withNearby: Boolean = false) = JSONObject().put("from", from).put("to", to)
            .put("journeys", journeys(merged(alias, withNearby), lines, hop, count, nowMs))
            .put("noService", data.optJSONObject(alias)?.optJSONArray("routingErrors")?.length()?.let { it > 0 } ?: false)
        val board = JSONObject().put("widgetId", widgetId).put("checkedAt", Instant.now().toString()).put("stale", false)
            .put("names", JSONObject().put("from", fromName).put("via", viaName).put("to", toName))
            .put("coords", JSONObject().put("from", point(data.optJSONObject("fromStop"))).put("to", point(data.optJSONObject("toStop"))))
            .put(OUTBOUND, direction("outbound", fromName, toName, false, true))
            .put(INBOUND, direction("inbound", toName, fromName, false, true))
            .put("hops", JSONArray().put(direction("viaTo", viaName, toName, true).put("id", "via-to"))
                .put(direction("viaFrom", viaName, fromName, true, true).put("id", "via-from"))
                // The local ride to the change, for days off when the whole commute is not the question.
                .put(direction("fromVia", fromName, viaName, true, true).put("id", "from-via")))
        if (track != null) {
            val outbound = track.optString("direction") != INBOUND
            val own = merged(if (outbound) "outbound" else "inbound", true)
            val asked = trackedTrip(track, config, nowMs)
            val candidates = listOfNotNull(asked, own).flatMap { trip ->
                trip.optJSONArray("tripPatterns")?.let { all -> (0 until all.length()).map { normalize(all.getJSONObject(it)) } }.orEmpty()
            }
            val fresh = refreshed(track.getJSONObject("journey"), candidates)
            if (fresh != null) retrack(context, widgetId, track.optString("direction"), track.getJSONObject("journey").optString("key"), fresh)
        }
        prefs(context).edit().putString("board_$widgetId", board.toString())
            .putString("ends_$widgetId", ends(config, board.getJSONObject("names"), board.getJSONObject("coords")).toString()).apply()
        return withTracked(context, widgetId, board)
    }

    /**
     * The commute's two ends with their positions, which are the weather points. Kept apart from the
     * board so a widget that only shows weather knows them too; asked of Entur once per pair of stops.
     */
    fun ends(context: Context, widgetId: Int, network: Boolean): JSONObject? {
        val config = config(context, widgetId)
        val stops = "${config.optString("from")}|${config.optString("to")}"
        val saved = prefs(context).getString("ends_$widgetId", null)?.let { JSONObject(it) }
        if (saved != null && saved.optString("stops") == stops) return saved
        if (!network) return null
        val data = post(JSONObject().put("query", endsQuery).put("variables", JSONObject().put("from", config.getString("from")).put("to", config.getString("to"))))
        val names = JSONObject().put("from", config.optString("fromName").ifEmpty { data.optJSONObject("fromStop")?.optString("name").orEmpty() })
            .put("to", config.optString("toName").ifEmpty { data.optJSONObject("toStop")?.optString("name").orEmpty() })
        val fresh = ends(config, names, JSONObject().put("from", point(data.optJSONObject("fromStop"))).put("to", point(data.optJSONObject("toStop"))))
        prefs(context).edit().putString("ends_$widgetId", fresh.toString()).apply()
        return fresh
    }

    private val endsQuery = """query Ends(${'$'}from:String!,${'$'}to:String!){fromStop:stopPlace(id:${'$'}from){name latitude longitude} toStop:stopPlace(id:${'$'}to){name latitude longitude}}"""

    private fun ends(config: JSONObject, names: JSONObject, coords: JSONObject) = JSONObject()
        .put("stops", "${config.optString("from")}|${config.optString("to")}").put("names", names).put("coords", coords)

    /** A stop's position for the weather points, or null when Entur has none. */
    private fun point(stop: JSONObject?): Any {
        if (stop == null || stop.isNull("latitude") || stop.isNull("longitude")) return JSONObject.NULL
        return JSONObject().put("lat", stop.optDouble("latitude")).put("lon", stop.optDouble("longitude"))
    }

    /** The tracked journey rides along with the board, first in its direction even once it has left. */
    private fun withTracked(context: Context, widgetId: Int, board: JSONObject): JSONObject {
        val track = tracked(context, widgetId) ?: return board.put("tracked", JSONObject.NULL)
        board.put("tracked", track)
        val section = board.optJSONObject(track.getString("direction")) ?: return board
        val key = track.getJSONObject("journey").optString("key")
        val journeys = section.optJSONArray("journeys") ?: JSONArray()
        val merged = JSONArray().put(track.getJSONObject("journey"))
        for (i in 0 until journeys.length()) if (journeys.getJSONObject(i).optString("key") != key) merged.put(journeys.getJSONObject(i))
        section.put("journeys", merged)
        return board
    }

    /**
     * Every question of a refresh as its own request, a few at a time: one request carrying them all
     * took Entur some seventeen seconds, apart they take five. Answers are filed by question, trips
     * from a nearby stop as "outbound@NSR:StopPlace:3369". Returns the answers and the nearby stops.
     */
    private fun fetch(context: Context, widgetId: Int, config: JSONObject, nowMs: Long): Pair<JSONObject, List<String>> {
        val from = config.getString("from")
        val via = config.getString("via")
        val to = config.getString("to")
        val since = Instant.ofEpochMilli(nowMs - LOOKBACK_MS).toString()
        val data = JSONObject()
        val asks = mutableListOf(tripAsk("outbound", from, to, via, since), tripAsk("inbound", to, from, via, since),
            tripAsk("viaTo", via, to, null, since), tripAsk("viaFrom", via, from, null, since), tripAsk("fromVia", from, via, null, since),
            // The trains to the change, for the earlier ones Entur leaves out of the way home.
            tripAsk("toVia", to, via, null, since, optional = true))
        // Names and positions only change with the stops, so they are asked once.
        val saved = prefs(context).getString("stops_$widgetId", null)?.let { JSONObject(it) }?.takeIf { it.optString("stops") == "$from|$via|$to" }
        if (saved != null) saved.optJSONObject("data")?.let { stops -> STOP_KEYS.forEach { data.put(it, stops.opt(it)) } }
        else asks += Ask(STOPS, JSONObject().put("query", stopsQuery).put("variables", JSONObject().put("from", from).put("via", via).put("to", to)))
        fun nearbyAsks(stop: String) = listOf(tripAsk("outbound@$stop", stop, to, via, since, optional = true),
            tripAsk("inbound@$stop", to, stop, via, since, optional = true), tripAsk("fromVia@$stop", stop, via, null, since, optional = true),
            tripAsk("viaFrom@$stop", via, stop, null, since, optional = true))
        // The nearby stops found last time are asked alongside; one found only now takes a second round.
        val known = prefs(context).getString("nearby_$widgetId", null)?.let { JSONObject(it) }?.takeIf { it.optString("from") == from }
            ?.optJSONArray("stops")?.let { stops -> (0 until stops.length()).map { stops.getString(it) } }.orEmpty()
        ask(asks + known.flatMap { nearbyAsks(it) }, data)
        val nearby = (nearbyStops(data, from) + known).distinct().take(2)
        ask(nearby.filter { it !in known }.flatMap { nearbyAsks(it) }, data)
        prefs(context).edit().putString("nearby_$widgetId", JSONObject().put("from", from).put("stops", JSONArray(nearby)).toString()).apply()
        return data to nearby
    }

    /**
     * Entur's trips for a tracked journey that began before the commute's own trips reach back to;
     * null for a later one, which those trips still hold, or when the request fails.
     */
    private fun trackedTrip(track: JSONObject, config: JSONObject, nowMs: Long): JSONObject? {
        val start = millis(track.getJSONObject("journey").optString("aimedStart")) ?: return null
        if (start > nowMs - LOOKBACK_MS + 60_000) return null
        val outbound = track.optString("direction") != INBOUND
        val home = config.getString("from")
        val work = config.getString("to")
        // A minute early, so the journey itself is the first answer rather than the one after it.
        val into = JSONObject()
        ask(listOf(tripAsk("tracked", if (outbound) home else work, if (outbound) work else home, config.getString("via"),
            Instant.ofEpochMilli(start - 60_000).toString(), num = 4, optional = true)), into)
        return into.optJSONObject("tracked")
    }

    /** One request of a refresh. An optional one may fail without failing the refresh: the commute shows without it. */
    private class Ask(val key: String, val body: JSONObject, val optional: Boolean = false)

    private fun tripAsk(key: String, from: String, to: String, via: String?, at: String, num: Int = trips, optional: Boolean = false): Ask {
        val variables = JSONObject().put("from", JSONObject().put("place", from)).put("to", JSONObject().put("place", to)).put("at", at).put("num", num)
        if (via != null) variables.put("visit", JSONArray().put(JSONObject().put("visit",
            JSONObject().put("stopLocationIds", JSONArray().put(via)).put("minimumWaitTime", "PT0S"))))
        val visit = if (via == null) "" else ",${'$'}visit:[TripViaLocationInput!]"
        val query = "query Trip(${'$'}from:Location!,${'$'}to:Location!,${'$'}at:DateTime,${'$'}num:Int!$visit){trip(from:${'$'}from,to:${'$'}to" +
            "${if (via == null) "" else ",via:${'$'}visit"},dateTime:${'$'}at,numTripPatterns:${'$'}num){...T}}\n$fragment"
        return Ask(key, JSONObject().put("query", query).put("variables", variables), optional)
    }

    /** Sends the requests, PARALLEL at a time, and files each answer in `into`; the first required one to fail is thrown. */
    private fun ask(asks: List<Ask>, into: JSONObject) {
        if (asks.isEmpty()) return
        val pool = Executors.newFixedThreadPool(minOf(PARALLEL, asks.size))
        try {
            val answers = asks.map { ask -> pool.submit<JSONObject?> { try { post(ask.body) } catch (e: Exception) { if (ask.optional) null else throw e } } }
            for ((ask, answer) in asks.zip(answers)) {
                val data = try { answer.get() } catch (e: ExecutionException) { throw e.cause ?: e } ?: continue
                if (ask.key == STOPS) data.keys().forEach { into.put(it, data.get(it)) } else into.put(ask.key, data.opt("trip"))
            }
        } finally { pool.shutdownNow() }
    }

    /**
     * Stops near home that Entur walked to before the first vehicle, or from after the last one: the
     * stop of another line, as the 280's Engelsrud is to the 71's Engelsrud terrasse. At most two.
     */
    internal fun nearbyStops(data: JSONObject, home: String): List<String> {
        val found = linkedSetOf<String>()
        fun stop(place: JSONObject?): String? {
            val stopPlace = place?.optJSONObject("quay")?.optJSONObject("stopPlace") ?: return null
            if (stopPlace.optJSONObject("parent")?.optString("id") == home) return null
            return stopPlace.optString("id").takeIf { it.isNotEmpty() && it != home }
        }
        for ((alias, leaving) in listOf("outbound" to true, "fromVia" to true, "inbound" to false, "viaFrom" to false)) {
            val patterns = data.optJSONObject(alias)?.optJSONArray("tripPatterns") ?: continue
            for (i in 0 until patterns.length()) {
                val legs = patterns.getJSONObject(i).optJSONArray("legs") ?: continue
                val all = (0 until legs.length()).map { legs.getJSONObject(it) }
                val walked = if (leaving) all.takeWhile { it.optString("mode") == "foot" } else all.takeLastWhile { it.optString("mode") == "foot" }
                if (walked.isEmpty() || walked.size == all.size) continue
                val ride = if (leaving) all[walked.size] else all[all.size - walked.size - 1]
                stop(ride.optJSONObject(if (leaving) "fromPlace" else "toPlace"))?.let { found += it }
            }
        }
        return found.take(2)
    }

    /**
     * The home stop's trips plus the nearby stops', leaving out a line that also uses the home stop:
     * like the departure board, the 71 counts from Engelsrud terrasse and the 280 from Engelsrud.
     */
    internal fun mergeNearby(main: JSONObject, others: List<JSONObject>, leaving: Boolean): JSONObject {
        fun patterns(trip: JSONObject) = trip.optJSONArray("tripPatterns")?.let { all -> (0 until all.length()).map { all.getJSONObject(it) } }.orEmpty()
        fun legs(pattern: JSONObject) = pattern.optJSONArray("legs")?.let { all -> (0 until all.length()).map { all.getJSONObject(it) } }.orEmpty()
        // The home end's vehicle: the first ride leaving home, the last one coming back.
        fun homeRide(pattern: JSONObject): Pair<JSONObject, Boolean>? {
            val all = if (leaving) legs(pattern) else legs(pattern).reversed()
            val index = all.indexOfFirst { it.optString("mode") != "foot" }
            return if (index < 0) null else all[index] to (index > 0)
        }
        val homeLines = patterns(main).mapNotNull { homeRide(it) }.filter { !it.second }
            .map { it.first.optJSONObject("line")?.optString("publicCode").orEmpty() }.toSet()
        val merged = JSONArray()
        patterns(main).forEach { merged.put(it) }
        for (pattern in others.flatMap { patterns(it) }) {
            val ride = homeRide(pattern) ?: continue
            if (ride.first.optJSONObject("line")?.optString("publicCode").orEmpty() !in homeLines) merged.put(pattern)
        }
        return JSONObject().put("routingErrors", main.optJSONArray("routingErrors") ?: JSONArray()).put("tripPatterns", merged)
    }

    /**
     * Puts back the vehicles Entur's planner leaves out. Of two buses from one stop that make the same
     * train it keeps only the later, but the next bus is the one to know about. Each single ride of
     * `hop` (from the same stop to the change) that starts none of `trip`'s journeys takes the onward
     * legs of the journey it can still make that arrives first: a later vehicle's from the same stop,
     * reaching the change no sooner than this one (two minutes sooner at another platform).
     */
    internal fun withEarlier(trip: JSONObject, hop: JSONObject): JSONObject {
        fun patterns(trip: JSONObject) = trip.optJSONArray("tripPatterns")?.let { all -> (0 until all.length()).map { all.getJSONObject(it) } }.orEmpty()
        fun legs(pattern: JSONObject) = pattern.optJSONArray("legs")?.let { all -> (0 until all.length()).map { all.getJSONObject(it) } }.orEmpty()
        fun riding(leg: JSONObject) = leg.optString("mode") != "foot"
        fun quay(leg: JSONObject, end: String) = leg.optJSONObject(end)?.optJSONObject("quay")
        fun stopPlace(leg: JSONObject, end: String) = quay(leg, end)?.optJSONObject("stopPlace")?.optString("id")
        fun key(leg: JSONObject) = "${leg.optJSONObject("serviceJourney")?.optString("id")}@${leg.optString("aimedStartTime")}"
        val main = patterns(trip)
        val firsts = main.mapNotNull { pattern -> legs(pattern).firstOrNull { riding(it) }?.let { key(it) } }.toSet()
        // Only journeys that begin with their vehicle: one put in its place needs nothing before it.
        val onward = main.filter { pattern -> legs(pattern).let { it.isNotEmpty() && riding(it[0]) && it.count { leg -> riding(leg) } > 1 } }
        val all = JSONArray().apply { main.forEach { put(it) } }
        for (pattern in patterns(hop)) {
            val ride = legs(pattern).filter { riding(it) }.singleOrNull() ?: continue
            if (key(ride) in firsts) continue
            val start = millis(ride.optString("aimedStartTime")) ?: continue
            val end = millis(ride.optString("expectedEndTime")) ?: continue
            val best = onward.filter { later ->
                val first = legs(later)[0]
                val platform = if (quay(first, "toPlace")?.optString("id") == quay(ride, "toPlace")?.optString("id")) 0 else 120_000
                stopPlace(first, "fromPlace") == stopPlace(ride, "fromPlace") && stopPlace(first, "toPlace") == stopPlace(ride, "toPlace") &&
                    (millis(first.optString("aimedStartTime")) ?: 0) > start && end <= (millis(first.optString("expectedEndTime")) ?: 0) - platform
            }.minByOrNull { millis(legs(it).last().optString("expectedEndTime")) ?: Long.MAX_VALUE } ?: continue
            all.put(JSONObject().put("legs", JSONArray().put(ride).apply { legs(best).drop(1).forEach { put(it) } }))
        }
        return JSONObject().put("routingErrors", trip.optJSONArray("routingErrors") ?: JSONArray()).put("tripPatterns", all)
    }

    /**
     * A tracked journey with Entur's latest times: the same journey when it is planned again, otherwise
     * each of its vehicles found in any of `candidates`, as one put in front of a later one's train is
     * not planned whole once it has left. Null when none is found.
     */
    internal fun refreshed(journey: JSONObject, candidates: List<JSONObject>): JSONObject? {
        candidates.firstOrNull { it.optString("key") == journey.optString("key") }?.let { return it }
        val fresh = candidates.flatMap { rides(it) }.associateBy { rideKey(it) }
        val old = journey.getJSONArray("legs")
        val legs = (0 until old.length()).map { old.getJSONObject(it) }
        if (legs.none { it.optString("mode") != "foot" && rideKey(it) in fresh }) return null
        return summed(JSONArray().apply { legs.forEach { leg -> put(if (leg.optString("mode") == "foot") leg else fresh[rideKey(leg)] ?: leg) } })
    }

    private fun post(body: JSONObject): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("ET-Client-Name", "gjessing-glance")
        connection.connectTimeout = 12000
        connection.readTimeout = 30000
        connection.doOutput = true
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            // 429 is Entur asking us to slow down; the refresh job retries later with a growing delay.
            if (connection.responseCode != 200) throw IOException("Entur answered ${connection.responseCode}")
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val data = response.optJSONObject("data")
            if (data == null && response.has("errors")) throw IllegalStateException(response.getJSONArray("errors").getJSONObject(0).optString("message"))
            return data ?: JSONObject()
        } finally { connection.disconnect() }
    }

    /**
     * Trip patterns as journeys: ride legs only where the line filter allows, a hop limited to one
     * vehicle, and no journey another one beats on both ends. Those whose vehicle has left by `nowMs`
     * (the lookback asks for some) are dropped, so they do not take a place in `count`.
     */
    internal fun journeys(trip: JSONObject?, lines: Set<String>, hop: Boolean, count: Int, nowMs: Long = 0): JSONArray {
        val patterns = trip?.optJSONArray("tripPatterns") ?: JSONArray()
        val candidates = (0 until patterns.length()).map { normalize(patterns.getJSONObject(it)) }.filter { journey ->
            val rides = rides(journey)
            rides.isNotEmpty() && (!hop || rides.size == 1) && (lines.isEmpty() || rides.all { it.optString("line") in lines }) &&
                (millis(journey.optString("rideStart")) ?: Long.MAX_VALUE) > nowMs - 60_000
        }.distinctBy { it.getString("key") }
        val kept = candidates.filter { journey -> candidates.none { other -> other !== journey && dominates(other, journey) } }
            .sortedBy { millis(it.optString("rideStart")) ?: Long.MAX_VALUE }
        return JSONArray().apply { kept.take(count).forEach { put(it) } }
    }

    /**
     * Leaves no earlier and arrives sooner. This is what "fast rail only" did on the departure widget:
     * an L1 leaving with the RE11 but arriving twenty minutes after it is never the one to take. Two
     * arriving together both stay, as the earlier is the next bus, with time to spare at the change.
     * Only from the same stop: a late 71 at the door still shows when a 280 a walk away makes its train.
     */
    internal fun dominates(a: JSONObject, b: JSONObject): Boolean {
        if (rides(a).firstOrNull()?.optString("from") != rides(b).firstOrNull()?.optString("from")) return false
        val aStart = millis(a.optString("rideStart")) ?: return false
        val bStart = millis(b.optString("rideStart")) ?: return false
        val aEnd = millis(a.optString("expectedEnd")) ?: return false
        val bEnd = millis(b.optString("expectedEnd")) ?: return false
        if (aStart < bStart || aEnd > bEnd) return false
        // Identical times: keep the one with fewer vehicles.
        return aEnd < bEnd || (aStart == bStart && rides(a).size < rides(b).size)
    }

    internal fun rides(journey: JSONObject): List<JSONObject> {
        val legs = journey.getJSONArray("legs")
        return (0 until legs.length()).map { legs.getJSONObject(it) }.filter { it.optString("mode") != "foot" }
    }

    internal fun normalize(pattern: JSONObject): JSONObject {
        val source = pattern.optJSONArray("legs") ?: JSONArray()
        val legs = JSONArray()
        for (i in 0 until source.length()) {
            val leg = source.getJSONObject(i)
            val fromCall = leg.optJSONObject("fromEstimatedCall")
            val situations = JSONArray()
            val raw = leg.optJSONArray("situations") ?: JSONArray()
            for (j in 0 until raw.length()) {
                val situation = raw.getJSONObject(j)
                situations.put(JSONObject().put("id", situation.optString("situationNumber"))
                    .put("severity", situation.optString("severity")).put("reportType", situation.optString("reportType"))
                    .put("summary", text(situation.optJSONArray("summary"))).put("detail", text(situation.optJSONArray("description")))
                    .put("advice", text(situation.optJSONArray("advice")))
                    .put("url", situation.optJSONArray("infoLinks")?.optJSONObject(0)?.optString("uri").orEmpty()))
            }
            legs.put(JSONObject().put("mode", leg.optString("mode")).put("submode", string(leg, "transportSubmode"))
                .put("line", leg.optJSONObject("line")?.let { string(it, "publicCode") }.orEmpty())
                .put("serviceJourneyId", leg.optJSONObject("serviceJourney")?.let { string(it, "id") }.orEmpty())
                .put("towards", fromCall?.optJSONObject("destinationDisplay")?.let { string(it, "frontText") }.orEmpty())
                .put("from", leg.optJSONObject("fromPlace")?.let { string(it, "name") }.orEmpty())
                .put("fromQuay", leg.optJSONObject("fromPlace")?.optJSONObject("quay")?.let { string(it, "publicCode") }.orEmpty())
                .put("to", leg.optJSONObject("toPlace")?.let { string(it, "name") }.orEmpty())
                .put("toQuay", leg.optJSONObject("toPlace")?.optJSONObject("quay")?.let { string(it, "publicCode") }.orEmpty())
                .put("aimedStart", string(leg, "aimedStartTime")).put("expectedStart", string(leg, "expectedStartTime"))
                .put("aimedEnd", string(leg, "aimedEndTime")).put("expectedEnd", string(leg, "expectedEndTime"))
                .put("distance", leg.optDouble("distance", 0.0).toInt())
                .put("realtime", leg.optBoolean("realtime"))
                .put("cancelled", fromCall?.optBoolean("cancellation") == true || leg.optJSONObject("toEstimatedCall")?.optBoolean("cancellation") == true)
                .put("inaccurate", fromCall?.optBoolean("predictionInaccurate") == true)
                .put("situations", situations)
                .apply { leg.optJSONArray("intermediateEstimatedCalls")?.let { put("stops", stops(it)) } })
        }
        return summed(legs)
    }

    /** Normalized legs as a journey, with its ends, its key and when its first vehicle leaves. */
    private fun summed(legs: JSONArray): JSONObject {
        val first = legs.optJSONObject(0)
        val last = legs.optJSONObject(legs.length() - 1)
        val journey = JSONObject().put("legs", legs)
            .put("aimedStart", first?.optString("aimedStart").orEmpty()).put("expectedStart", first?.optString("expectedStart").orEmpty())
            .put("aimedEnd", last?.optString("aimedEnd").orEmpty()).put("expectedEnd", last?.optString("expectedEnd").orEmpty())
        val rides = rides(journey)
        // The services and their timetabled departures identify a journey across refreshes; live times do not.
        journey.put("key", rides.joinToString("|") { rideKey(it) })
        journey.put("rideStart", rides.firstOrNull()?.optString("expectedStart").orEmpty())
        return journey
    }

    /** One vehicle's identity across journeys and refreshes: its service and timetabled departure. */
    internal fun rideKey(ride: JSONObject) = "${ride.optString("serviceJourneyId")}@${ride.optString("aimedStart")}"

    /** Calls in the departure board's stop shape, which the journey sheet reads. */
    private fun stops(calls: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val quay = call.optJSONObject("quay")
            put(stop(quay?.optJSONObject("stopPlace")?.let { string(it, "name") }.orEmpty(), quay?.let { string(it, "publicCode") }.orEmpty(),
                string(call, "aimedArrivalTime"), string(call, "expectedArrivalTime"), string(call, "aimedDepartureTime"), string(call, "expectedDepartureTime"))
                .put("cancelled", call.optBoolean("cancellation")))
        }
    }

    private fun stop(name: String, platform: String, aimedArrival: String, expectedArrival: String, aimedDeparture: String, expectedDeparture: String) =
        JSONObject().put("name", name).put("platform", platform).put("scheduledArrival", aimedArrival).put("expectedArrival", expectedArrival)
            .put("scheduledDeparture", aimedDeparture).put("expectedDeparture", expectedDeparture).put("cancelled", false)

    /**
     * One hop as a departure board with a single group, so the journey sheet shows a tapped local bus
     * the way it shows a departure widget's: the ride's stops, and the hop's other departures as chips.
     * Departures that have left drop out, except `keep`, the one tapped.
     */
    fun hopBoard(board: JSONObject, hopId: String, keep: String, nowMs: Long = System.currentTimeMillis()): JSONObject? {
        val hops = board.optJSONArray("hops") ?: return null
        val hop = (0 until hops.length()).map { hops.getJSONObject(it) }.firstOrNull { it.optString("id") == hopId } ?: return null
        val journeys = hop.optJSONArray("journeys") ?: JSONArray()
        val departures = JSONArray()
        for (journey in (0 until journeys.length()).map { journeys.getJSONObject(it) }) {
            val ride = rides(journey).firstOrNull() ?: continue
            val key = journey.optString("key")
            if (key != keep && (millis(ride.optString("expectedStart")) ?: 0) < nowMs - 60_000) continue
            departures.put(departure(ride, key))
        }
        val group = JSONObject().put("id", hopId).put("name", "${hop.optString("from")} → ${hop.optString("to")}").put("departures", departures)
        return JSONObject().put("checkedAt", board.optString("checkedAt")).put("groups", JSONArray().put(group))
    }

    const val LEG_GROUP = "leg"

    /**
     * One vehicle of a commute journey as a departure board, so a leg tapped in Smart Glance opens the
     * same sheet as a departure: the ride's stops, and as chips the same stretch in the direction's
     * other journeys. Each departure names the whole journey it belongs to (`keepJourney` for the one
     * tapped), which the sheet opens in the app. Rides that have left drop out, except `keep`.
     */
    fun legBoard(board: JSONObject, direction: String, keep: String, keepJourney: String, nowMs: Long = System.currentTimeMillis()): JSONObject? {
        val all = board.optJSONObject(direction)?.optJSONArray("journeys") ?: return null
        val journeys = (0 until all.length()).map { all.getJSONObject(it) }
        val tapped = journeys.flatMap { rides(it) }.firstOrNull { rideKey(it) == keep } ?: return null
        val from = tapped.optString("from")
        val to = tapped.optString("to")
        // The same vehicle can start several journeys (one bus, different trains after it); it is listed once.
        val legs = journeys.flatMap { journey -> rides(journey).filter { it.optString("from") == from && it.optString("to") == to }.map { it to journey } }
            .groupBy { rideKey(it.first) }.values
            .map { same -> same.firstOrNull { it.second.optString("key") == keepJourney } ?: same.first() }
            .sortedBy { millis(it.first.optString("expectedStart")) ?: Long.MAX_VALUE }
        val departures = JSONArray()
        for ((ride, journey) in legs) {
            val key = rideKey(ride)
            if (key != keep && (millis(ride.optString("expectedStart")) ?: 0) < nowMs - 60_000) continue
            departures.put(departure(ride, key).put("journeyKey", journey.optString("key")))
        }
        val group = JSONObject().put("id", LEG_GROUP).put("name", "$from → $to").put("departures", departures)
        return JSONObject().put("checkedAt", board.optString("checkedAt")).put("groups", JSONArray().put(group))
    }

    /** A ride in the departure board's departure shape, its ends around the stops Entur listed between them. */
    private fun departure(ride: JSONObject, key: String): JSONObject {
        val stops = JSONArray().put(stop(ride.optString("from"), ride.optString("fromQuay"), "", "", ride.optString("aimedStart"), ride.optString("expectedStart"))
            .put("cancelled", ride.optBoolean("cancelled")))
        ride.optJSONArray("stops")?.let { for (i in 0 until it.length()) stops.put(it.getJSONObject(i)) }
        stops.put(stop(ride.optString("to"), ride.optString("toQuay"), ride.optString("aimedEnd"), ride.optString("expectedEnd"), "", ""))
        return JSONObject().put("journeyId", key).put("line", ride.optString("line")).put("headsign", ride.optString("towards"))
            .put("mode", ride.optString("mode")).put("scheduled", ride.optString("aimedStart")).put("expected", ride.optString("expectedStart"))
            .put("arrival", ride.optString("expectedEnd")).put("platform", ride.optString("fromQuay"))
            .put("realtime", ride.optBoolean("realtime")).put("cancelled", ride.optBoolean("cancelled"))
            .put("originIndex", 0).put("destinationIndex", stops.length() - 1).put("stops", stops)
    }

    private fun string(value: JSONObject, key: String) = if (value.isNull(key)) "" else value.optString(key)

    /** Operator text in English when published, otherwise the first language given. */
    private fun text(values: JSONArray?): String {
        if (values == null || values.length() == 0) return ""
        val all = (0 until values.length()).map { values.getJSONObject(it) }
        return (all.firstOrNull { it.optString("language") == "en" } ?: all.first()).optString("value")
    }

    internal fun millis(iso: String?): Long? = try { if (iso.isNullOrEmpty()) null else Instant.parse(iso).toEpochMilli() } catch (_: Exception) {
        try { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() } catch (_: Exception) { null }
    }
}
