package io.gjessing.glance

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

/** Native network and cache layer: widgets can refresh without a WebView process. */
object BoardRepository {
    private const val endpoint = "https://api.entur.io/journey-planner/v3/graphql"
    private const val prefsName = "glance_widgets"
    private const val query = """query Board(${ '$' }id:String!,${ '$' }limit:Int!){stopPlace(id:${ '$' }id){id name estimatedCalls(numberOfDepartures:${ '$' }limit,timeRange:14400,arrivalDeparture:departures,includeCancelledTrips:true){aimedDepartureTime expectedDepartureTime realtime cancellation predictionInaccurate destinationDisplay{frontText} quay{id publicCode name stopPlace{id name}} serviceJourney{id transportMode transportSubmode line{publicCode} pointsOnLink{points} estimatedCalls{quay{id publicCode name latitude longitude stopPlace{id name}} aimedArrivalTime expectedArrivalTime aimedDepartureTime expectedDepartureTime cancellation forAlighting forBoarding stopPositionInPattern}}}}}"""

    fun defaults(): JSONObject = JSONObject().put("id", UUID.randomUUID().toString()).put("type", "transport").put("version", 3)
        .put("name", "My departures").put("groups", JSONArray()
            .put(group("engelsrud-asker", "Engelsrud → Asker stasjon", "NSR:StopPlace:3369", "NSR:StopPlace:3247", "bus", "71,280", 6, engelsrudTerrasse))
            .put(group("asker-engelsrud", "Asker stasjon → Engelsrud", "NSR:StopPlace:3247", "NSR:StopPlace:3369", "bus", "71,280", 6))
            .put(group("asker-national", "Asker stasjon → Nationaltheatret", "NSR:StopPlace:418", "NSR:StopPlace:288", "rail", "", 6))
            .put(group("national-asker", "Nationaltheatret → Asker stasjon", "NSR:StopPlace:288", "NSR:StopPlace:418", "rail", "", 6)))

    /** Line 71 toward Asker calls at Engelsrud terrasse a minute before Engelsrud; line 280 skips terrasse. */
    private const val engelsrudTerrasse = "71=NSR:StopPlace:70146"

    private fun group(id: String, name: String, origin: String, destination: String, mode: String, lines: String, count: Int, lineOrigins: String = "") =
        JSONObject().put("id", id).put("name", name).put("origin", origin).put("destination", destination)
            .put("mode", mode).put("lines", lines).put("lineOrigins", lineOrigins).put("fastOnly", mode == "rail")
            .put("count", count).put("enabled", true)

    /** `lineOrigins` is "line=NSR:StopPlace:id" pairs, comma separated: those lines board at that stop instead of the group origin. */
    private fun lineOrigins(group: JSONObject): Map<String, String> =
        group.optString("lineOrigins").split(',').mapNotNull { entry ->
            val parts = entry.split('=', limit = 2).map { it.trim() }
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) parts[0] to parts[1] else null
        }.toMap()

    private fun originFor(group: JSONObject, line: String) = lineOrigins(group)[line] ?: group.getString("origin")

    fun config(context: Context, widgetId: Int): JSONObject {
        val raw = context.getSharedPreferences(prefsName, 0).getString("config_$widgetId", null)
        if (raw != null) return migrate(context, widgetId, JSONObject(raw))
        val template = if (widgetId > 0) context.getSharedPreferences(prefsName, 0).getString("config_-1", null) else null
        val fresh = if (template != null) upgrade(JSONObject(template)).put("id", UUID.randomUUID().toString()) else defaults()
        saveConfig(context, widgetId, fresh)
        return fresh
    }

    private fun migrate(context: Context, widgetId: Int, value: JSONObject): JSONObject {
        if (value.optInt("version", 1) >= 3) return value
        upgrade(value)
        context.getSharedPreferences(prefsName, 0).edit().putString("config_$widgetId", value.toString()).apply()
        return value
    }

    /** Version 2 shows as many departures as the widget fits, so the old default of three becomes six.
     *  Version 3 boards line 71 at Engelsrud terrasse in the default Engelsrud → Asker group. */
    internal fun upgrade(value: JSONObject): JSONObject {
        val version = value.optInt("version", 1)
        val groups = value.optJSONArray("groups") ?: JSONArray()
        for (i in 0 until groups.length()) {
            val group = groups.getJSONObject(i)
            if (version < 2 && group.optInt("count", 3) == 3) group.put("count", 6)
            if (version < 3 && group.optString("id") == "engelsrud-asker" && group.optString("origin") == "NSR:StopPlace:3369" &&
                group.optString("lineOrigins").isEmpty()) group.put("lineOrigins", engelsrudTerrasse)
        }
        return value.put("version", 3)
    }

    fun saveConfig(context: Context, widgetId: Int, value: JSONObject) {
        require(value.optString("type") == "transport")
        val groups = value.optJSONArray("groups") ?: throw IllegalArgumentException("Missing groups")
        require(groups.length() <= 12)
        for (i in 0 until groups.length()) {
            val group = groups.getJSONObject(i)
            require(group.optString("origin").startsWith("NSR:StopPlace:"))
            require(group.optString("destination").startsWith("NSR:StopPlace:"))
            require(group.optInt("count", 0) in 1..8)
            require(group.optString("lineOrigins").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .all { Regex("""[^=\s]+\s*=\s*NSR:StopPlace:\S+""").matches(it) }) { "Line stops use line=NSR:StopPlace:id" }
        }
        // Settings saved from the app are deliberate choices, never migrated again.
        value.put("version", 3)
        context.getSharedPreferences(prefsName, 0).edit().putString("config_$widgetId", value.toString()).apply()
    }

    fun remove(context: Context, widgetId: Int) {
        context.getSharedPreferences(prefsName, 0).edit().remove("config_$widgetId").remove("board_$widgetId").apply()
    }

    fun markStale(context: Context, widgetId: Int) {
        val board = cached(context, widgetId) ?: return
        context.getSharedPreferences(prefsName, 0).edit().putString("board_$widgetId", board.put("stale", true).toString()).apply()
    }

    fun cached(context: Context, widgetId: Int): JSONObject? =
        context.getSharedPreferences(prefsName, 0).getString("board_$widgetId", null)?.let { JSONObject(it) }

    /** `origins` holds the stops already asked in this run, by stop place, so boards sharing a stop share its answer. */
    fun refresh(context: Context, widgetId: Int, origins: MutableMap<String, JSONArray> = mutableMapOf()): JSONObject {
        val config = config(context, widgetId)
        val groups = config.getJSONArray("groups")
        for (i in 0 until groups.length()) {
            val group = groups.getJSONObject(i)
            if (group.optBoolean("enabled", true)) {
                for (origin in listOf(group.getString("origin")) + lineOrigins(group).values) {
                    if (!origins.containsKey(origin)) origins[origin] = fetchCalls(origin)
                }
            }
        }
        val boards = JSONArray()
        for (i in 0 until groups.length()) {
            val group = groups.getJSONObject(i)
            if (!group.optBoolean("enabled", true)) continue
            val rows = JSONArray()
            val candidates = mutableListOf<JSONObject>()
            // eligible() keeps a call only from its line's boarding quay, so a journey seen at two origins is listed once.
            for (origin in (listOf(group.getString("origin")) + lineOrigins(group).values).distinct()) {
                val calls = origins[origin] ?: JSONArray()
                for (j in 0 until calls.length()) {
                    val row = eligible(group, calls.getJSONObject(j))
                    if (row != null) candidates.add(row)
                }
            }
            candidates.sortBy { Instant.parse(it.getString("sortTime")).toEpochMilli() }
            for (row in candidates.take(group.optInt("count", 3))) rows.put(row)
            boards.put(JSONObject().put("id", group.getString("id")).put("name", group.getString("name"))
                .put("mode", group.optString("mode")).put("departures", rows))
        }
        val board = JSONObject().put("widgetId", widgetId).put("checkedAt", Instant.now().toString())
            .put("stale", false).put("groups", boards)
        context.getSharedPreferences(prefsName, 0).edit().putString("board_$widgetId", board.toString()).apply()
        return board
    }

    private fun fetchCalls(origin: String): JSONArray {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("ET-Client-Name", "gjessing-glance")
        connection.connectTimeout = 12000
        connection.readTimeout = 25000
        connection.doOutput = true
        try {
            val body = JSONObject().put("query", query).put("variables", JSONObject().put("id", origin).put("limit", 120))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (response.has("errors")) throw IllegalStateException(response.getJSONArray("errors").getJSONObject(0).optString("message"))
            return response.optJSONObject("data")?.optJSONObject("stopPlace")?.optJSONArray("estimatedCalls") ?: JSONArray()
        } finally { connection.disconnect() }
    }

    internal fun eligible(group: JSONObject, call: JSONObject): JSONObject? {
        val journey = call.optJSONObject("serviceJourney") ?: return null
        val mode = journey.optString("transportMode")
        val configuredMode = group.optString("mode")
        if (configuredMode.isNotEmpty() && configuredMode != mode) return null
        val line = journey.optJSONObject("line")?.optString("publicCode") ?: ""
        val lineFilter = group.optString("lines").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (lineFilter.isNotEmpty() && line !in lineFilter) return null
        val stops = journey.optJSONArray("estimatedCalls") ?: return null
        val origin = originFor(group, line)
        val destination = group.getString("destination")
        var originIndex = -1
        var destinationIndex = -1
        for (i in 0 until stops.length()) {
            val stop = stops.getJSONObject(i)
            val stopId = stop.optJSONObject("quay")?.optJSONObject("stopPlace")?.optString("id")
            if (stopId == origin && originIndex < 0) originIndex = i
            if (stopId == destination && originIndex >= 0 && i > originIndex && stop.optBoolean("forAlighting", true)) {
                destinationIndex = i
                break
            }
        }
        if (originIndex < 0 || destinationIndex < 0) return null
        if (mode == "rail" && group.optBoolean("fastOnly", false)) {
            val submode = journey.optString("transportSubmode")
            val category = submode == "regionalRail" || submode == "longDistance" || line.startsWith("R")
            // R12/R13/R14 are classified as local by Entur, but their four-stop corridor is express.
            if (!category || destinationIndex - originIndex > 5) return null
        }
        val boardQuay = call.optJSONObject("quay")
        val originStop = stops.getJSONObject(originIndex)
        if (!originStop.optBoolean("forBoarding", true)) return null
        // Match the actual stop call as well as the stop place. A service can visit one stop twice.
        if (boardQuay?.optString("id") != originStop.optJSONObject("quay")?.optString("id")) return null
        val scheduled = call.optString("aimedDepartureTime")
        val expected = call.optString("expectedDepartureTime", scheduled)
        if (expected.isEmpty()) return null
        val destinationStop = stops.getJSONObject(destinationIndex)
        val names = JSONArray()
        for (i in 0 until stops.length()) {
            val stop = stops.getJSONObject(i)
            val quay = stop.optJSONObject("quay") ?: continue
            names.put(JSONObject().put("id", quay.optJSONObject("stopPlace")?.optString("id"))
                .put("name", quay.optJSONObject("stopPlace")?.optString("name", quay.optString("name")))
                .put("scheduledArrival", stop.optString("aimedArrivalTime"))
                .put("expectedArrival", stop.optString("expectedArrivalTime"))
                .put("scheduledDeparture", stop.optString("aimedDepartureTime"))
                .put("expectedDeparture", stop.optString("expectedDepartureTime"))
                .put("platform", if (quay.isNull("publicCode")) "" else quay.optString("publicCode"))
                .put("latitude", if (quay.has("latitude") && !quay.isNull("latitude")) quay.optDouble("latitude") else JSONObject.NULL)
                .put("longitude", if (quay.has("longitude") && !quay.isNull("longitude")) quay.optDouble("longitude") else JSONObject.NULL)
                .put("cancelled", stop.optBoolean("cancellation")))
        }
        return JSONObject().put("journeyId", journey.getString("id"))
            .put("line", line).put("headsign", call.optJSONObject("destinationDisplay")?.optString("frontText", "") ?: "")
            .put("mode", mode).put("scheduled", scheduled).put("expected", expected).put("sortTime", Instant.parse(expected).toString())
            .put("arrival", destinationStop.optString("expectedArrivalTime", destinationStop.optString("aimedArrivalTime")))
            .put("platform", if (boardQuay == null || boardQuay.isNull("publicCode")) "" else boardQuay.optString("publicCode"))
            .put("realtime", call.optBoolean("realtime")).put("cancelled", call.optBoolean("cancellation"))
            .put("predictionInaccurate", call.optBoolean("predictionInaccurate"))
            .put("originIndex", originIndex).put("destinationIndex", destinationIndex).put("stops", names)
            .put("routePoints", journey.optJSONObject("pointsOnLink")?.optString("points", "") ?: "")
    }
}
