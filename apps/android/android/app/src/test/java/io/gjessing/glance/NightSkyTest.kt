package io.gjessing.glance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class NightSkyTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private fun at(text: String): Long = ZonedDateTime.of(LocalDateTime.parse(text), oslo).toInstant().toEpochMilli()
    private val lat = 59.83
    private val lon = 10.43

    private fun sights(time: String, cloud: Double = 0.05, signal: AuroraSignal? = null, slot: Long = 1): NightSights {
        val ms = at(time)
        val sun = Sky.sun(ms, lat, lon)
        // Moon below the horizon, to test the rest on their own.
        return NightSky.sights(ms, lat, lon, sun.elevation, -20.0, 0.5, cloud, null, null, null, signal, slot)
    }

    @Test fun theShowersPeakOnTheirDates() {
        assertEquals("Geminids", NightSky.shower(at("2026-12-14T03:00"))?.first?.name)
        assertEquals("Quadrantids", NightSky.shower(at("2027-01-04T00:00"))?.first?.name)
        assertEquals("Orionids", NightSky.shower(at("2026-10-21T05:00"))?.first?.name)
        assertEquals("Leonids", NightSky.shower(at("2026-11-17T05:00"))?.first?.name)
        assertEquals("Ursids", NightSky.shower(at("2026-12-22T06:00"))?.first?.name)
        assertNull(NightSky.shower(at("2026-07-01T01:00")))
    }

    @Test fun theGeminidRadiantIsHighAfterMidnight() {
        val radiant = Sky.star(at("2026-12-14T02:00"), lat, lon, 7.47, 32.3)
        assertTrue("elevation ${radiant.elevation}", radiant.elevation > 50)
    }

    @Test fun aGeminidNightShowsMeteorsButNotUnderCloudOrInDaylight() {
        val clear = (1L..40L).map { sights("2026-12-14T02:00", slot = it) }
        assertTrue(clear.all { it.shower == "Geminids" })
        assertTrue("meteors in ${clear.count { it.meteors > 0 }} of 40", clear.count { it.meteors > 0 } > 30)
        val overcast = (1L..40L).map { sights("2026-12-14T02:00", cloud = 1.0, slot = it) }
        assertTrue(overcast.count { it.meteors > 0 } < 10)
        assertEquals(0, sights("2026-12-14T12:00").meteors)
    }

    @Test fun auroraNeedsActivityDarknessAndOpenSky() {
        assertEquals(0.0, sights("2026-10-30T23:00", signal = AuroraSignal(2.0, null)).aurora, 0.0)
        val storm = sights("2026-10-30T23:00", signal = AuroraSignal(6.3, null)).aurora
        assertTrue("storm $storm", storm > 0.5)
        assertEquals(0.0, sights("2026-10-30T23:00", cloud = 1.0, signal = AuroraSignal(6.3, null)).aurora, 0.0)
        assertEquals(0.0, sights("2026-10-30T14:00", signal = AuroraSignal(6.3, null)).aurora, 0.0)
        // OVATION, when there is a current outlook, decides over Kp.
        assertEquals(0.0, sights("2026-10-30T23:00", signal = AuroraSignal(6.3, 2.0)).aurora, 0.0)
    }

    @Test fun lowCloudHidesAuroraMoreThanHighCloud() {
        assertTrue(NightSky.cloudFactor(0.8, 80.0, 0.0, 0.0) < NightSky.cloudFactor(0.8, 0.0, 0.0, 80.0))
        assertEquals(0.06, NightSky.cloudFactor(1.0, 100.0, 100.0, 100.0), 0.001)
    }

    @Test fun ovationCountsAuroraToTheNorthForLess() {
        val overhead = NightSky.ovationInView({ _, y -> if (y == 60) 40 else 0 }, lat, lon)
        val north = NightSky.ovationInView({ _, y -> if (y == 68) 40 else 0 }, lat, lon)
        assertEquals(40.0, overhead, 0.01)
        assertTrue("north $north", north in 15.0..30.0)
    }

    @Test fun readsBothShapesOfTheKpForecast() {
        val objects = SpaceWeather.parseKp("""[{"time_tag":"2026-10-25T18:00:00","kp":4.67,"observed":"predicted","noaa_scale":null}]""")
        val rows = SpaceWeather.parseKp("""[["time_tag","kp","observed","noaa_scale"],["2026-10-25 18:00:00","4.67","predicted",null]]""")
        for (parsed in listOf(objects, rows)) {
            assertEquals(1, parsed.length())
            val kp = SpaceWeather.kpAt(parsed, java.time.Instant.parse("2026-10-25T19:30:00Z").toEpochMilli())
            assertNotNull(kp)
            assertEquals(4.67, kp!!, 0.001)
            assertNull(SpaceWeather.kpAt(parsed, java.time.Instant.parse("2026-10-25T21:00:00Z").toEpochMilli()))
        }
    }

    @Test fun readsOvationAndRefusesAPartialGrid() {
        val rows = buildString {
            append("""{"Observation Time": "2026-10-25T19:49:00Z", "Forecast Time": "2026-10-25T21:07:00Z", "Data Format": "[Longitude, Latitude, Aurora]", "coordinates": [""")
            var first = true
            for (x in 0..359) for (y in -90..90) {
                if (!first) append(", ")
                first = false
                append("[$x, $y, ${if (x == 10 && y == 66) 37 else 0}]")
            }
            append("]}")
        }
        val grid = SpaceWeather.parseOvation(rows)
        assertNotNull(grid)
        assertEquals(37, grid!!.at(10, 66))
        assertEquals(java.time.Instant.parse("2026-10-25T21:07:00Z").toEpochMilli(), grid.forecastAt)
        assertNull(SpaceWeather.parseOvation(rows.substring(0, 2000) + "]}"))
    }
}
