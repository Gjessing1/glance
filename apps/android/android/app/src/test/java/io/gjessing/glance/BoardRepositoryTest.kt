package io.gjessing.glance

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BoardRepositoryTest {
    private val defaults = BoardRepository.defaults().getJSONArray("groups")

    @Test fun departedRowsDropOutBetweenRefreshes() {
        fun row(expected: String) = JSONObject().put("expected", expected)
        val group = JSONObject().put("departures", JSONArray().put(row("2026-10-26T08:12:29+01:00")).put(row("2026-10-26T08:20:00+01:00")))
        val now = java.time.Instant.parse("2026-10-26T07:15:00Z").toEpochMilli()
        assertEquals(listOf("2026-10-26T08:20:00+01:00"), DepartureWidget.upcoming(group, now).map { it.getString("expected") })
        assertEquals(2, DepartureWidget.upcoming(group, now - 3 * 60_000L).size)
    }

    @Test fun busMustReachDestinationAfterOrigin() {
        val group = defaults.getJSONObject(0)
        val valid = call("280", "bus", "local", listOf("3369", "3366", "3247"), "3369")
        assertNotNull(BoardRepository.eligible(group, valid))
        assertNull(BoardRepository.eligible(group, call("280", "bus", "local", listOf("3247", "3369"), "3369")))
        assertNull(BoardRepository.eligible(group, call("280", "bus", "local", listOf("3369", "3366"), "3369")))
        assertNull(BoardRepository.eligible(group, call("270", "bus", "local", listOf("3369", "3247"), "3369")))
    }

    @Test fun line71BoardsAtEngelsrudTerrasse() {
        val group = defaults.getJSONObject(0)
        val stops = listOf("70146", "3369", "3247")
        val terrasse = BoardRepository.eligible(group, call("71", "bus", "local", stops, "70146"))
        assertNotNull(terrasse)
        assertEquals(0, terrasse!!.getInt("originIndex"))
        // The same journey fetched from Engelsrud is dropped, so it is listed once.
        assertNull(BoardRepository.eligible(group, call("71", "bus", "local", stops, "3369")))
        assertNotNull(BoardRepository.eligible(group, call("280", "bus", "local", listOf("3369", "3247"), "3369")))
    }

    @Test fun upgradeAddsTerrasseOnlyToUntouchedEngelsrudGroup() {
        val old = BoardRepository.defaults().put("version", 2)
        val groups = old.getJSONArray("groups")
        for (i in 0 until groups.length()) groups.getJSONObject(i).remove("lineOrigins")
        groups.getJSONObject(1).put("id", "engelsrud-asker-copy")
        BoardRepository.upgrade(old)
        assertEquals(3, old.getInt("version"))
        assertEquals("71=NSR:StopPlace:70146", groups.getJSONObject(0).getString("lineOrigins"))
        assertEquals("", groups.getJSONObject(1).optString("lineOrigins"))
    }

    @Test fun fastTrainUsesActualStoppingPattern() {
        val group = defaults.getJSONObject(2)
        val fast = call("R13", "rail", "local", listOf("418", "610", "157", "152", "288"), "418")
        val selected = BoardRepository.eligible(group, fast)
        assertNotNull(selected)
        assertEquals(4, selected!!.getInt("destinationIndex"))
        val local = call("L1", "rail", "local", listOf("418", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "288"), "418")
        assertNull(BoardRepository.eligible(group, local))
        assertNull(BoardRepository.eligible(group, call("R13", "rail", "local", listOf("288", "418"), "418")))
    }

    @Test fun boardingAndQuayMustMatch() {
        val group = defaults.getJSONObject(0)
        val valid = call("280", "bus", "local", listOf("3369", "3247"), "3369")
        valid.getJSONObject("serviceJourney").getJSONArray("estimatedCalls").getJSONObject(0).put("forBoarding", false)
        assertNull(BoardRepository.eligible(group, valid))
        valid.getJSONObject("serviceJourney").getJSONArray("estimatedCalls").getJSONObject(0).put("forBoarding", true)
        valid.getJSONObject("quay").put("id", "different-quay")
        assertNull(BoardRepository.eligible(group, valid))
    }

    private fun call(line: String, mode: String, submode: String, ids: List<String>, boardStop: String): JSONObject {
        val calls = JSONArray()
        for ((index, id) in ids.withIndex()) {
            calls.put(JSONObject().put("quay", JSONObject().put("id", "quay-$id").put("stopPlace", JSONObject()
                .put("id", "NSR:StopPlace:$id").put("name", id)))
                .put("forBoarding", true).put("forAlighting", true)
                .put("aimedArrivalTime", "2026-10-24T14:00:00+02:00").put("expectedArrivalTime", "2026-10-24T14:00:00+02:00")
                .put("stopPositionInPattern", index))
        }
        return JSONObject().put("quay", JSONObject().put("id", "quay-$boardStop"))
            .put("aimedDepartureTime", "2026-10-24T14:00:00+02:00")
            .put("expectedDepartureTime", "2026-10-24T14:00:00+02:00")
            .put("serviceJourney", JSONObject().put("id", "journey-1").put("transportMode", mode)
                .put("transportSubmode", submode).put("line", JSONObject().put("publicCode", line))
                .put("estimatedCalls", calls))
    }
}
