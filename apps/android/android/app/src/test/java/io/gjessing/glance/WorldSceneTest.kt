package io.gjessing.glance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WorldSceneTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private fun at(text: String): Long = ZonedDateTime.of(LocalDateTime.parse(text), oslo).toInstant().toEpochMilli()
    private val hour = 3_600_000L
    private fun weather(temp: Double, mm: Double = 0.0, kind: Precip = Precip.NONE, cloud: Double = 0.5) =
        TreeWeather(temp, mm, kind, cloud, 2.0, 225.0, fog = false, thunder = false)

    @Test fun mushroomsComeUpAfterRainAndWitherInTheDry() {
        val start = at("2026-09-10T08:00")
        val wet = WorldMemory(TreeMemory(start)).step(start + 24 * hour, 253, weatherAt = { weather(13.0, 2.0, Precip.RAIN, 1.0) })
        assertTrue("fungi ${wet.fungi}", wet.fungi > 0.4)
        val later = wet.step(start + 96 * hour, 257, weatherAt = { weather(16.0, cloud = 0.1) })
        assertTrue("fungi ${later.fungi}", later.fungi < wet.fungi - 0.2)
    }

    @Test fun flowersFollowTheSeasonAndAFrostCutsThem() {
        assertEquals(0.0, WorldMemory.flowerSeason(30), 0.001)
        assertEquals(1.0, WorldMemory.flowerSeason(190), 0.001)
        val start = at("2026-05-20T08:00")
        val spring = WorldMemory(TreeMemory(start)).step(start + hour, 140, weatherAt = { weather(12.0) })
        val frost = spring.step(start + 13 * hour, 140, weatherAt = { weather(-3.0) })
        assertTrue(frost.bloom < spring.bloom * 0.5)
    }

    @Test fun tracksAppearAfterSnowfallAndGoWithNewSnowOrTheThaw() {
        val start = at("2027-01-10T20:00")
        val snowed = WorldMemory(TreeMemory(start)).step(start + 6 * hour, 10, weatherAt = { weather(-4.0, 1.0, Precip.SNOW, 1.0) })
        assertEquals(0L, snowed.tracksAt)
        val calm = snowed.step(start + 36 * hour, 11, weatherAt = { weather(-6.0, cloud = 0.1) })
        assertNotEquals(0L, calm.tracksAt)
        val again = calm.step(start + 38 * hour, 11, weatherAt = { weather(-3.0, 1.0, Precip.SNOW, 1.0) })
        assertEquals(0L, again.tracksAt)
    }

    @Test fun theLakeFreezesInAColdSpellAndThawsInMildWeather() {
        val start = at("2026-12-01T12:00")
        val first = WorldMemory(TreeMemory(start)).step(start + hour, 335, weatherAt = { weather(2.0) })
        assertEquals(0.0, first.ice, 0.01)
        val cold = first.step(start + 72 * hour, 338, weatherAt = { weather(-8.0, cloud = 0.0) })
        assertTrue("ice ${cold.ice}", cold.ice >= WorldScene.ICE_SHOWS)
        val mild = cold.step(start + 144 * hour, 341, weatherAt = { weather(6.0, 1.0, Precip.RAIN) })
        assertTrue("ice ${mild.ice}", mild.ice < WorldScene.ICE_SHOWS)
    }

    @Test fun memorySurvivesTheRoundTrip() {
        val memory = WorldMemory(TreeMemory(1000L, snow = 4.0), fungi = 0.3, bloom = 0.6, tracksAt = 900L, tracksBy = Tracks.HARE, ice = 5.0)
        assertEquals(memory, WorldMemory.fromJson(memory.toJson()))
    }

    @Test fun visitorsAreRareRepeatableAndSuitTheHour() {
        val summer = Season.at(190, 59.83)
        val day = (0L until 3000L).map { WorldScene.visitor(it, 40.0, summer, 0.2, 0.0, 0.0, 3.0, 20.0) }
        val share = day.count { it != null } / 3000.0
        assertTrue("share $share", share in 0.12..0.22)
        assertEquals(WorldScene.visitor(1234, 40.0, summer, 0.2, 0.0, 0.0, 3.0, 20.0), day[1234])
        assertFalse(Visitor.SHOOTING_STAR in day || Visitor.OWL in day)
        val night = (0L until 3000L).mapNotNull { WorldScene.visitor(it, -20.0, summer, 0.1, 0.0, 0.0, 3.0, 12.0) }
        assertFalse(Visitor.BUTTERFLY in night || Visitor.BIRD in night || Visitor.SQUIRREL in night)
        assertTrue(Visitor.SHOOTING_STAR in night)
        // A storm keeps nearly everyone in.
        val storm = (0L until 3000L).mapNotNull { WorldScene.visitor(it, 20.0, summer, 1.0, 3.0, 0.0, 15.0, 12.0) }
        assertTrue(storm.isEmpty())
    }

    @Test fun theSceneReadsTheMemory() {
        val now = at("2027-02-08T12:00")
        val memory = WorldMemory(TreeMemory(now, snow = 20.0, cloud = 0.1), bloom = 0.0, tracksAt = now - 4 * hour, tracksBy = Tracks.FOX, ice = 10.0)
        val scene = WorldScene.of(now, oslo, 59.83, 10.43, memory, weather(-7.0, cloud = 0.1))
        assertTrue(scene.lakeIce)
        assertEquals(Tracks.FOX, scene.tracks)
        assertEquals(0.0, scene.puddle, 0.001)
        assertTrue(scene.frozen)
        assertTrue(scene.describe().contains("winter"))
        val thawed = WorldScene.of(now, oslo, 59.83, 10.43, memory.copy(weather = memory.weather.copy(snow = 0.2)), weather(4.0))
        assertNull(thawed.tracks)
    }
}
