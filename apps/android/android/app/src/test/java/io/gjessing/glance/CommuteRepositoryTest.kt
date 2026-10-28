package io.gjessing.glance

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuteRepositoryTest {
    // Widget times are shown in the device zone; these fixtures are Oslo times.
    @org.junit.Before fun zone() { GlanceTime.zoneOverride = java.time.ZoneId.of("Europe/Oslo") }

    @Test fun slowTrainLeavingWithAFastOneIsDropped() {
        val trip = trip(
            pattern(walk("17:38", "17:42"), ride("280", "bus", "17:42", "17:57"), ride("RE11", "rail", "17:59", "18:17")),
            pattern(walk("17:38", "17:42"), ride("280", "bus", "17:42", "17:57"), ride("L1", "rail", "18:04", "18:35")),
            pattern(ride("71", "bus", "17:29", "17:45"), ride("R13", "rail", "17:49", "18:07")))
        val journeys = CommuteRepository.journeys(trip, emptySet(), hop = false, count = 6)
        assertEquals(2, journeys.length())
        // Sorted by the first vehicle, not by the walk to it.
        assertEquals(listOf("71", "R13"), lines(journeys.getJSONObject(0)))
        assertEquals(listOf("280", "RE11"), lines(journeys.getJSONObject(1)))
    }

    @Test fun earlierDepartureArrivingAtTheSameTimeStays() {
        // Both make the 18:57 280: the R13 is the next train, the RE10 the later one with less wait.
        val early = CommuteRepository.normalize(pattern(ride("R13", "rail", "18:21", "18:40"), ride("280", "bus", "18:57", "19:09")))
        val late = CommuteRepository.normalize(pattern(ride("RE10", "rail", "18:31", "18:48"), ride("280", "bus", "18:57", "19:09")))
        assertTrue(!CommuteRepository.dominates(late, early))
        assertTrue(!CommuteRepository.dominates(early, late))
    }

    @Test fun earlierBusTakesTheTrainALaterOneMakes() {
        // Seen live for a Thursday morning: Entur planned the 07:42 and 07:52 280s but left out the 07:37 and 07:47 before them.
        fun bus(start: String, end: String) = ride("280", "bus", start, end).put("fromPlace", place("Engelsrud", "NSR:Quay:5965", "NSR:StopPlace:3369"))
            .put("toPlace", place("Asker stasjon", "NSR:Quay:5732", "NSR:StopPlace:59612"))
        val trip = trip(pattern(bus("07:42", "07:53"), walk("07:53", "07:56"), ride("RE11", "rail", "07:59", "08:17")),
            pattern(bus("07:52", "08:03"), walk("08:03", "08:06"), ride("RE10", "rail", "08:09", "08:27")))
        val hop = trip(pattern(bus("07:37", "07:48")), pattern(bus("07:42", "07:53")), pattern(bus("07:47", "07:58")), pattern(bus("07:52", "08:03")),
            // At another platform a bus needs two minutes more than the one it stands in for.
            pattern(bus("07:50", "08:02").put("toPlace", place("Asker stasjon", "NSR:Quay:5733", "NSR:StopPlace:59612"))))
        val journeys = CommuteRepository.journeys(CommuteRepository.withEarlier(trip, hop), emptySet(), hop = false, count = 6)
        assertEquals(listOf("07:37 RE11", "07:42 RE11", "07:47 RE10", "07:52 RE10"), (0 until journeys.length()).map {
            val rides = CommuteRepository.rides(journeys.getJSONObject(it))
            "${CommuteWidget.clock(rides.first().getString("expectedStart"))} ${rides.last().getString("line")}"
        })
        assertEquals(3, journeys.getJSONObject(0).getJSONArray("legs").length())
    }

    @Test fun trackedJourneyIsRenewedVehicleByVehicle() {
        // The 07:37 280 put in front of the 07:42's train is not planned whole; its train still runs late.
        val tracked = CommuteRepository.normalize(pattern(ride("280", "bus", "07:37", "07:48"), ride("RE11", "rail", "07:59", "08:17")))
        val planned = CommuteRepository.normalize(pattern(ride("280", "bus", "07:42", "07:53"),
            ride("RE11", "rail", "07:59", "08:17").put("expectedStartTime", at("08:03")).put("expectedEndTime", at("08:21"))))
        val fresh = CommuteRepository.refreshed(tracked, listOf(planned))!!
        assertEquals(tracked.getString("key"), fresh.getString("key"))
        assertEquals(at("07:37"), fresh.getString("rideStart"))
        assertEquals(at("08:21"), fresh.getString("expectedEnd"))
        assertNull(CommuteRepository.refreshed(tracked, emptyList()))
    }

    @Test fun lateBusAtTheDoorIsNotBeatenByOneAWalkAway() {
        // Seen live: the 12:59 71 at Engelsrud terrasse, sixteen minutes late, makes the same R14 as the 13:22 280 at Engelsrud.
        val late = ride("71", "bus", "12:59", "13:09").put("fromPlace", JSONObject().put("name", "Engelsrud terrasse"))
            .put("expectedStartTime", at("13:15")).put("expectedEndTime", at("13:25"))
        val trip = trip(
            pattern(late, ride("R14", "rail", "13:39", "13:57")),
            pattern(walk("13:18", "13:22"), ride("280", "bus", "13:22", "13:33"), ride("R14", "rail", "13:39", "13:57")))
        val journeys = CommuteRepository.journeys(trip, emptySet(), hop = false, count = 6)
        assertEquals(listOf(listOf("71", "R14"), listOf("280", "R14")), (0 until journeys.length()).map { lines(journeys.getJSONObject(it)) })
    }

    @Test fun lookbackKeepsABusStillAtItsStopButNotOneThatLeft() {
        // Asked at 08:00 from ten minutes back: the walk to the 08:02 280 began at 07:58, the 07:49 71 has gone.
        val trip = trip(
            pattern(ride("71", "bus", "07:49", "07:59"), ride("R14", "rail", "08:09", "08:27")),
            pattern(walk("07:58", "08:02"), ride("280", "bus", "08:02", "08:13"), ride("R13", "rail", "08:19", "08:37")),
            pattern(ride("71", "bus", "08:09", "08:20"), ride("R12", "rail", "08:29", "08:47")))
        val journeys = CommuteRepository.journeys(trip, emptySet(), hop = false, count = 6, nowMs = CommuteRepository.millis(at("08:00"))!!)
        assertEquals(listOf(listOf("280", "R13"), listOf("71", "R12")), (0 until journeys.length()).map { lines(journeys.getJSONObject(it)) })
    }

    @Test fun eachLineCountsFromItsOwnStop() {
        val home = trip(
            pattern(ride("71", "bus", "08:09", "08:20").put("fromPlace", stop("Engelsrud terrasse", "NSR:StopPlace:70146")), ride("R12", "rail", "08:29", "08:47")),
            pattern(walk("08:18", "08:22"), ride("280", "bus", "08:22", "08:33").put("fromPlace", stop("Engelsrud", "NSR:StopPlace:3369")),
                ride("R14", "rail", "08:39", "08:57")))
        assertEquals(listOf("NSR:StopPlace:3369"), CommuteRepository.nearbyStops(JSONObject().put("outbound", home), "NSR:StopPlace:70146"))
        // From Engelsrud the 71 calls a minute later; it still counts from Engelsrud terrasse, while the 08:12 280 is added.
        val nearby = trip(
            pattern(ride("71", "bus", "08:10", "08:20"), ride("R12", "rail", "08:29", "08:47")),
            pattern(ride("280", "bus", "08:12", "08:23"), ride("R12", "rail", "08:29", "08:47")))
        val merged = CommuteRepository.mergeNearby(home, listOf(nearby), leaving = true)
        val journeys = CommuteRepository.journeys(merged, emptySet(), hop = false, count = 6)
        assertEquals(listOf("71@08:09", "280@08:12", "280@08:22"), (0 until journeys.length()).map {
            val first = CommuteRepository.rides(journeys.getJSONObject(it)).first()
            "${first.getString("line")}@${CommuteWidget.clock(first.getString("expectedStart"))}"
        })
    }

    @Test fun hopKeepsOnlyDirectRidesAndLineFilterApplies() {
        val trip = trip(
            pattern(ride("71", "bus", "17:19", "17:28")),
            pattern(ride("R13", "rail", "17:20", "17:30"), ride("280", "bus", "17:40", "17:50")),
            pattern(ride("270", "bus", "17:25", "17:40")))
        val journeys = CommuteRepository.journeys(trip, setOf("71", "280"), hop = true, count = 6)
        assertEquals(1, journeys.length())
        assertEquals(listOf("71"), lines(journeys.getJSONObject(0)))
    }

    @Test fun keyFollowsServicesNotLiveTimes() {
        val planned = CommuteRepository.normalize(pattern(ride("280", "bus", "17:42", "17:57")))
        val late = pattern(ride("280", "bus", "17:42", "17:57"))
        late.getJSONArray("legs").getJSONObject(0).put("expectedStartTime", "2026-10-24T17:46:00+02:00")
        assertEquals(planned.getString("key"), CommuteRepository.normalize(late).getString("key"))
    }

    @Test fun tightAndBrokenChangesAreFlagged() {
        val now = CommuteRepository.millis("2026-10-24T17:00:00+02:00")!!
        val tight = CommuteRepository.normalize(pattern(ride("280", "bus", "17:42", "17:57"), ride("RE11", "rail", "17:59", "18:17")))
        tight.getJSONArray("legs").getJSONObject(0).put("expectedEnd", "2026-10-24T17:57:00+02:00")
        // Planned two minutes, still two: tight by plan is not news.
        assertNull(CommuteWidget.info(tight, false, emptySet(), now))
        tight.getJSONArray("legs").getJSONObject(0).put("expectedEnd", "2026-10-24T18:01:00+02:00")
        assertEquals("Misses the RE11 at Asker stasjon", CommuteWidget.info(tight, false, emptySet(), now)!!.first)
    }

    @Test fun trackedJourneySaysWhatComesNext() {
        val journey = CommuteRepository.normalize(pattern(ride("280", "bus", "17:42", "17:57"), ride("RE11", "rail", "17:59", "18:17")))
        val rides = CommuteRepository.rides(journey)
        assertEquals("280 from Engelsrud 17:42", CommuteWidget.phase(rides, CommuteRepository.millis("2026-10-24T17:30:00+02:00")!!))
        assertEquals("on 280, at Asker stasjon 17:57", CommuteWidget.phase(rides, CommuteRepository.millis("2026-10-24T17:50:00+02:00")!!))
        assertEquals("RE11 from Asker stasjon 17:59", CommuteWidget.phase(rides, CommuteRepository.millis("2026-10-24T17:58:00+02:00")!!))
    }

    @Test fun clockPicksDirectionInLocalTime() {
        assertEquals(CommuteRepository.OUTBOUND, CommuteRepository.clockMode(CommuteRepository.millis("2026-10-24T11:59:00+02:00")!!))
        assertEquals(CommuteRepository.INBOUND, CommuteRepository.clockMode(CommuteRepository.millis("2026-10-24T12:00:00+02:00")!!))
    }

    @Test fun glanceVerdictNamesTheTransfer() {
        val journey = CommuteRepository.normalize(pattern(ride("280", "bus", "17:42", "17:57"), ride("RE11", "rail", "18:06", "18:17")))
        val rides = CommuteRepository.rides(journey)
        assertEquals("9 min transfer at Asker" to Tone.OK, CommuteSection.verdict(rides))
        rides[0].put("expectedEnd", "2026-10-24T18:04:00+02:00")
        assertEquals("2 min transfer at Asker · tight" to Tone.WARNING, CommuteSection.verdict(rides))
        rides[0].put("expectedEnd", "2026-10-24T18:08:00+02:00")
        assertEquals("Misses the RE11 at Asker" to Tone.CRITICAL, CommuteSection.verdict(rides))
        val direct = CommuteRepository.rides(CommuteRepository.normalize(pattern(ride("RE11", "rail", "18:06", "18:17"))))
        assertEquals("Direct · arrives 18:17" to Tone.NORMAL, CommuteSection.verdict(direct))
    }

    @Test fun glanceCountdownRoundsDown() {
        val now = CommuteRepository.millis("2026-10-24T17:00:30+02:00")!!
        assertEquals("now", CommuteSection.countdown("2026-10-24T17:01:00+02:00", now))
        assertEquals("9 min", CommuteSection.countdown("2026-10-24T17:10:00+02:00", now))
        assertEquals("", CommuteSection.countdown("2026-10-24T19:00:00+02:00", now))
    }

    @Test fun hopReadsAsADepartureBoardForTheJourneySheet() {
        val call = JSONObject().put("quay", JSONObject().put("publicCode", "").put("stopPlace", JSONObject().put("name", "Sportstuen")))
            .put("aimedArrivalTime", at("17:45")).put("expectedArrivalTime", at("17:46")).put("aimedDepartureTime", at("17:45")).put("expectedDepartureTime", at("17:46"))
        val passed = CommuteRepository.normalize(pattern(ride("71", "bus", "17:30", "17:44")))
        val next = CommuteRepository.normalize(pattern(walk("17:38", "17:42"), ride("280", "bus", "17:42", "17:57").put("intermediateEstimatedCalls", JSONArray().put(call))))
        val commute = JSONObject().put("checkedAt", at("17:35")).put("hops", JSONArray().put(JSONObject().put("id", "from-via")
            .put("from", "Engelsrud").put("to", "Asker").put("journeys", JSONArray().put(passed).put(next))))
        val board = CommuteRepository.hopBoard(commute, "from-via", next.getString("key"), CommuteRepository.millis(at("17:40"))!!)!!
        val group = board.getJSONArray("groups").getJSONObject(0)
        assertEquals("Engelsrud → Asker", group.getString("name"))
        // The 71 left ten minutes ago, so only the tapped 280 is offered.
        val departures = group.getJSONArray("departures")
        assertEquals(1, departures.length())
        val departure = departures.getJSONObject(0)
        assertEquals(next.getString("key"), departure.getString("journeyId"))
        val stops = departure.getJSONArray("stops")
        assertEquals(listOf("Engelsrud", "Sportstuen", "Asker stasjon"), (0 until stops.length()).map { stops.getJSONObject(it).getString("name") })
        assertEquals(0, departure.getInt("originIndex"))
        assertEquals(2, departure.getInt("destinationIndex"))
        assertEquals(at("17:46"), stops.getJSONObject(1).getString("expectedArrival"))
    }

    @Test fun legReadsAsADepartureBoardWithTheSameStretchAsChips() {
        // One bus feeding two trains is one chip; the train leg is not mixed in.
        val bus = ride("280", "bus", "07:20", "07:35")
        val early = CommuteRepository.normalize(pattern(bus, ride("RE11", "rail", "07:40", "08:05")))
        val late = CommuteRepository.normalize(pattern(JSONObject(bus.toString()), ride("L1", "rail", "07:45", "08:20")))
        val next = CommuteRepository.normalize(pattern(ride("71", "bus", "07:30", "07:44"), ride("RE11", "rail", "07:50", "08:15")))
        val commute = JSONObject().put("checkedAt", at("07:10")).put("outbound", JSONObject().put("journeys", JSONArray().put(early).put(late).put(next)))
        val tapped = CommuteRepository.rides(late).first()
        val board = CommuteRepository.legBoard(commute, "outbound", CommuteRepository.rideKey(tapped), late.getString("key"), CommuteRepository.millis(at("07:10"))!!)!!
        val group = board.getJSONArray("groups").getJSONObject(0)
        assertEquals(CommuteRepository.LEG_GROUP, group.getString("id"))
        assertEquals("Engelsrud → Asker stasjon", group.getString("name"))
        val departures = group.getJSONArray("departures")
        assertEquals(listOf("280", "71"), (0 until departures.length()).map { departures.getJSONObject(it).getString("line") })
        // The tapped bus keeps the journey it was tapped in, so "Whole commute" opens that one.
        assertEquals(late.getString("key"), departures.getJSONObject(0).getString("journeyKey"))
        assertEquals(next.getString("key"), departures.getJSONObject(1).getString("journeyKey"))
    }

    private fun lines(journey: JSONObject) = CommuteRepository.rides(journey).map { it.getString("line") }
    private fun trip(vararg patterns: JSONObject) = JSONObject().put("tripPatterns", JSONArray().apply { patterns.forEach { put(it) } })
    private fun pattern(vararg legs: JSONObject) = JSONObject().put("legs", JSONArray().apply { legs.forEach { put(it) } })
    private fun at(clock: String) = "2026-10-24T$clock:00+02:00"
    private fun place(name: String, quay: String, stopPlace: String) =
        JSONObject().put("name", name).put("quay", JSONObject().put("id", quay).put("stopPlace", JSONObject().put("id", stopPlace)))
    private fun stop(name: String, id: String) = JSONObject().put("name", name).put("quay", JSONObject().put("stopPlace", JSONObject().put("id", id)))
    private fun walk(start: String, end: String) =JSONObject().put("mode", "foot")
        .put("aimedStartTime", at(start)).put("expectedStartTime", at(start)).put("aimedEndTime", at(end)).put("expectedEndTime", at(end))
        .put("fromPlace", JSONObject().put("name", "Engelsrud terrasse")).put("toPlace", JSONObject().put("name", "Engelsrud"))
    private fun ride(line: String, mode: String, start: String, end: String) = JSONObject().put("mode", mode)
        .put("line", JSONObject().put("publicCode", line)).put("serviceJourney", JSONObject().put("id", "SJ:$line:$start"))
        .put("aimedStartTime", at(start)).put("expectedStartTime", at(start)).put("aimedEndTime", at(end)).put("expectedEndTime", at(end))
        .put("fromPlace", JSONObject().put("name", if (mode == "bus") "Engelsrud" else "Asker stasjon"))
        .put("toPlace", JSONObject().put("name", if (mode == "bus") "Asker stasjon" else "Nationaltheatret"))
}
