package io.gjessing.glance

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TreeSceneTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private fun at(text: String): Long = ZonedDateTime.of(LocalDateTime.parse(text), oslo).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    @Test fun sunIsHighAtMidsummerNoonAndBelowTheHorizonAtMidnight() {
        // Oslo's solar noon at midsummer is about 13:20 summer time, at about 53.5°.
        val noon = Sky.sun(at("2026-06-21T13:20"), 59.91, 10.75)
        assertEquals(53.5, noon.elevation, 1.0)
        assertEquals(180.0, noon.azimuth, 5.0)
        assertTrue(Sky.sun(at("2026-12-21T00:00"), 59.91, 10.75).elevation < -40)
        // Sunrise is in the east, which the picture puts on the left.
        val morning = Sky.sun(at("2026-03-20T07:00"), 59.91, 10.75)
        assertTrue(Sky.side(morning.azimuth) < -0.8)
    }

    @Test fun moonPhaseFollowsAKnownFullMoon() {
        // Full moon on 2026-10-26 04:12 UTC.
        assertEquals(0.5, Sky.moonPhase(at("2026-10-26T05:12")), 0.02)
    }

    @Test fun theBirchFollowsItsYear() {
        val july = Season.at(193, 59.85)
        assertEquals(1.0, july.leaves, 0.01)
        assertEquals(0.0, july.turn, 0.01)
        val lateOctober = Season.at(298, 59.85)
        assertTrue(lateOctober.turn > 0.99)
        assertTrue("late October leaves ${lateOctober.leaves}", lateOctober.leaves in 0.75..0.9)
        assertTrue("late October litter ${lateOctober.litter}", lateOctober.litter in 0.15..0.3)
        assertTrue(lateOctober.afterWind(0.6).leaves > 0.5)
        val midNovember = Season.at(319, 59.85)
        assertTrue(midNovember.leaves < 0.1)
        assertTrue(midNovember.litter > 0.9)
        val january = Season.at(20, 59.85)
        assertEquals(0.0, january.leaves, 0.001)
        assertTrue(Season.at(110, 59.85).buds > 0.5)
        // Six months on south of the equator.
        assertEquals(1.0, Season.at(10, -33.9).leaves, 0.05)
    }

    @Test fun theMapleTurnsLaterThanTheBirch() {
        val july = Season.at(193, 59.85).maple
        assertEquals(1.0, july.leaves, 0.01)
        assertEquals(0.0, july.turn, 0.01)
        // Early October: the birch is well into gold, the maple still green.
        assertTrue(Season.at(276, 59.85).turn > 0.6)
        assertEquals(0.0, Season.at(276, 59.85).maple.turn, 0.01)
        // Its peak in late October: fully turned and still holding most of its leaves.
        val peak = Season.at(298, 59.85).maple
        assertTrue(peak.turn > 0.95 && peak.leaves > 0.95)
        assertEquals(0.0, Season.at(325, 59.85).maple.leaves, 0.01)
        assertTrue(Season.at(325, 59.85).maple.litter > 0.95)
        // Bare in early May, while the birch is leafing out.
        assertTrue(Season.at(124, 59.85).maple.leaves < 0.01)
        // South of the equator it follows the turned year as the birch does.
        assertEquals(1.0, Season.at(10, -33.9).maple.leaves, 0.05)
    }

    @Test fun rainSoaksTheSoilWhichDriesOverDays() {
        val start = at("2026-08-10T12:00")
        val rain = TreeWeather(15.0, 3.0, Precip.RAIN, 1.0, 3.0, 225.0, fog = false, thunder = false)
        val dry = TreeWeather(18.0, 0.0, Precip.NONE, 0.2, 3.0, 225.0, fog = false, thunder = false)
        val wet = TreeMemory(start).step(start + 4 * hour, 222, weatherAt = { rain })
        assertTrue("topsoil ${wet.topsoil}", wet.topsoil > wet.soil)
        assertTrue("root zone ${wet.soil}", wet.soil > wet.deepSoil + 0.15)
        assertTrue("deep soil ${wet.deepSoil}", wet.deepSoil > TreeMemory.BASE_SOIL)
        assertTrue(wet.surface > 0.9)
        assertTrue(wet.rain > 2.5)
        // Puddles go within hours; the soil holds water for days.
        val evening = wet.step(start + 10 * hour, 222, weatherAt = { dry })
        assertTrue(evening.surface < 0.3)
        assertTrue("root zone ${evening.soil}", evening.soil > TreeMemory.BASE_SOIL + 0.2)
        assertTrue(evening.topsoil < wet.topsoil)
        assertTrue(evening.deepSoil > wet.deepSoil)
        val later = evening.step(start + 76 * hour, 225, weatherAt = { dry })
        assertTrue("soil ${later.soil}", later.soil < 0.4)
        assertTrue(later.soil >= TreeMemory.BASE_SOIL)
    }

    @Test fun thawFeedsUnfrozenTopsoilAndLayersSurviveSerialization() {
        val start = at("2027-03-10T09:00")
        val thaw = TreeWeather(5.0, 0.0, Precip.NONE, 0.4, 2.0, 225.0, fog = false, thunder = false)
        val frozen = TreeWeather(-3.0, 0.0, Precip.NONE, 0.5, 2.0, 225.0, fog = false, thunder = false)
        val snowy = TreeMemory(start, snow = 12.0)
        val cold = snowy.step(start + 3 * hour, 69, weatherAt = { frozen })
        assertEquals(TreeMemory.BASE_SOIL, cold.topsoil, 0.001)
        val melted = cold.step(start + 9 * hour, 69, weatherAt = { thaw })
        assertTrue(melted.topsoil > cold.topsoil)
        assertTrue(melted.soil > cold.soil)
        val restored = TreeMemory.fromJson(melted.toJson())!!
        assertEquals(melted.topsoil, restored.topsoil, 0.001)
        assertEquals(melted.deepSoil, restored.deepSoil, 0.001)
    }

    @Test fun birdsVisitOnlyInCalmWeather() {
        val calm = TreeWeather(16.0, 0.0, Precip.NONE, 0.2, 2.0, 225.0, fog = false, thunder = false)
        val storm = calm.copy(mm = 4.0, kind = Precip.RAIN, wind = 13.0, thunder = true)
        val start = at("2026-07-10T12:00")
        val visits = (0..150).map { start + it * 2 * hour }
        assertTrue(visits.any { TreeScene.of(it, oslo, 59.85, 10.45, TreeMemory(it, cloud = 0.2), calm).bird != null })
        assertTrue(visits.all { TreeScene.of(it, oslo, 59.85, 10.45, TreeMemory(it, cloud = 0.2), storm).bird == null })
    }

    @Test fun birdsAreRareAndContextual() {
        val days = (0L..3000L)
        // Roughly one daylit visit in ten.
        val share = days.count { TreeScene.perched(it, 190, 30.0) != null } / days.count().toDouble()
        assertTrue("share $share", share in 0.06..0.14)
        // No wood pigeons in winter, no tits in the dusk, no birds at all by night.
        assertTrue(days.none { TreeScene.perched(it, 20, 10.0) == PerchedBird.WOOD_PIGEON })
        assertTrue(days.none { TreeScene.perched(it, 20, 0.0).let { b -> b != null && b != PerchedBird.BLACKBIRD } })
        assertTrue(days.none { TreeScene.perched(it, 190, -10.0) != null || TreeScene.flight(it, 190, -10.0, false) != null })
        // Geese only in the migration weeks, crows to roost only on winter-half evenings.
        assertTrue(days.any { TreeScene.flight(it, 280, 15.0, true) == Flight.GEESE })
        assertTrue(days.none { TreeScene.flight(it, 190, 15.0, true) == Flight.GEESE })
        assertTrue(days.any { TreeScene.flight(it, 330, -2.0, false) == Flight.CORVIDS })
        assertTrue(days.none { TreeScene.flight(it, 330, -2.0, true) == Flight.CORVIDS })
    }

    @Test fun theWeeksWarmthMovesTheSeasons() {
        val start = at("2026-10-01T12:00")
        val mild = TreeWeather(TreeMemory.normalTemp(274) + 3, 0.0, Precip.NONE, 0.5, 2.0, 225.0, fog = false, thunder = false)
        // Three weeks of a mild autumn hold the leaves back several days.
        var m = TreeMemory(start).step(start + hour, 274, weatherAt = { mild })
        for (day in 1..21) m = m.step(start + day * 24 * hour, 274 + day, weatherAt = { mild })
        assertTrue("autumn shift ${m.autumnShift}", m.autumnShift < -4)
        assertTrue(Season.at(298, 59.85, autumnShift = m.autumnShift).leaves > Season.at(298, 59.85).leaves)
        // A sudden cold spell lets the tree catch up, but never run its year backwards.
        val cold = mild.copy(temp = TreeMemory.normalTemp(296) - 6)
        var shift = m.autumnShift
        for (h in 1..96) {
            m = m.step(start + 21 * 24 * hour + h * hour, 295 + h / 24, weatherAt = { cold })
            assertTrue(m.autumnShift >= shift - 1.0 / 24 - 1e-9)
            shift = m.autumnShift
        }
        // A warm spring brings the buds on sooner.
        val april = at("2027-04-01T12:00")
        val warm = TreeWeather(TreeMemory.normalTemp(100) + 3, 0.0, Precip.NONE, 0.3, 2.0, 225.0, fog = false, thunder = false)
        var spring = TreeMemory(april).step(april + hour, 91, weatherAt = { warm })
        for (day in 1..30) spring = spring.step(april + day * 24 * hour, 91 + day, weatherAt = { warm })
        assertTrue("spring shift ${spring.springShift}", spring.springShift > 5)
        assertTrue(Season.at(108, 59.85, springShift = spring.springShift).buds > Season.at(108, 59.85).buds)
        val restored = TreeMemory.fromJson(spring.toJson())!!
        assertEquals(spring.springShift, restored.springShift, 1e-9)
        assertEquals(spring.springWarmth, restored.springWarmth, 1e-9)
    }

    @Test fun hoarFrostFormsOnClearFreezingNightsAndGoesInTheThaw() {
        val start = at("2026-11-02T00:00")
        val night = TreeWeather(-4.0, 0.0, Precip.NONE, 0.05, 1.0, 0.0, fog = false, thunder = false)
        val frosty = TreeMemory(start).step(start + 6 * hour, 306, weatherAt = { night })
        assertTrue("frost ${frosty.frost}", frosty.frost > 0.7)
        val thaw = frosty.step(start + 10 * hour, 306, weatherAt = { night.copy(temp = 5.0) })
        assertTrue("frost ${thaw.frost}", thaw.frost < 0.1)
        assertTrue(TreeMemory.fromJson(TreeMemory(start).toJson())!!.recentWarmth.isNaN())
    }

    @Test fun snowStaysAfterItStopsAndMeltsInTheThaw() {
        val start = at("2027-01-10T08:00")
        val snowing = TreeWeather(-4.0, 1.0, Precip.SNOW, 1.0, 2.0, 0.0, fog = false, thunder = false)
        val cold = TreeWeather(-6.0, 0.0, Precip.NONE, 0.1, 1.0, 0.0, fog = false, thunder = false)
        val thaw = TreeWeather(5.0, 0.0, Precip.NONE, 0.8, 4.0, 225.0, fog = false, thunder = false)
        val fallen = TreeMemory(start).step(start + 6 * hour, 10, weatherAt = { snowing })
        assertTrue("snow ${fallen.snow}", fallen.snow > 5)
        assertTrue(fallen.branchSnow > 0.5)
        val nextDay = fallen.step(start + 30 * hour, 11, weatherAt = { cold })
        assertTrue(nextDay.snow > 5)
        assertEquals(0.0, nextDay.snowfall, 0.01)
        val melted = nextDay.step(start + 50 * hour, 12, weatherAt = { thaw })
        assertTrue("snow ${melted.snow}", melted.snow < 0.5)
        assertTrue(melted.branchSnow < 0.05)
    }

    @Test fun theSkyEasesInsteadOfJumping() {
        val start = at("2026-08-10T12:00")
        val clear = TreeMemory(start).step(start + hour, 222, weatherAt = { TreeWeather(15.0, 0.0, Precip.NONE, 0.0, 2.0, 225.0, fog = false, thunder = false) })
        val overcast = TreeWeather(15.0, 0.0, Precip.NONE, 1.0, 2.0, 225.0, fog = false, thunder = false)
        val soon = clear.step(start + hour + 15 * 60_000L, 222, weatherAt = { overcast })
        assertTrue(soon.cloud in 0.1..0.5)
        assertTrue(clear.step(start + 4 * hour, 222, weatherAt = { overcast }).cloud > 0.95)
    }

    @Test fun aGaleStripsAutumnLeavesEarly() {
        val start = at("2026-10-08T12:00")
        val gale = TreeWeather(9.0, 0.0, Precip.NONE, 0.7, 15.0, 270.0, fog = false, thunder = false)
        val after = TreeMemory(start).step(start + 6 * hour, 281, weatherAt = { gale })
        assertTrue(after.windLoss > 0.3)
        val stripped = Season.at(281, 59.85).afterWind(after.windLoss)
        assertTrue(stripped.leaves < Season.at(281, 59.85).leaves)
        // New leaves in May start the count over.
        assertEquals(0.0, after.step(start + 7 * hour, 140, weatherAt = { null }).windLoss, 0.0)
    }

    @Test fun weatherComesFromTheCachedRows() {
        val row = JSONObject().put("t", "2026-10-25T16:00:00Z").put("temp", 0.0).put("symbol", "lightsnow").put("mm", 0.4)
            .put("wind", 6.0).put("windFrom", 90.0).put("cloud", 92.0)
        val weather = TreeWeather.of(row)
        assertEquals(Precip.SNOW, weather.kind)
        assertEquals(0.92, weather.cloud, 0.001)
        assertEquals(6.0, weather.wind, 0.001)
        // Rows cached before wind and cloud were kept fall back on the symbol.
        val old = TreeWeather.of(JSONObject().put("temp", 12.0).put("symbol", "partlycloudy_day").put("mm", 0.0))
        assertEquals(0.55, old.cloud, 0.001)
        assertEquals(Precip.NONE, old.kind)
        // Rain shown even when the hour's amount rounds to nothing.
        assertEquals(0.25, TreeWeather.shown(TreeWeather.of(JSONObject().put("symbol", "lightrain").put("mm", 0.0))), 0.001)
    }

    @Test fun nowcastCoversTheLastHourAndTheForecastBeforeThat() {
        val now = at("2026-10-25T18:30")
        val hours = listOf(
            JSONObject().put("t", "2026-10-25T15:00:00Z").put("temp", 6.0).put("symbol", "rain").put("mm", 1.5).put("cloud", 100.0),
            JSONObject().put("t", "2026-10-25T17:00:00Z").put("temp", 5.0).put("symbol", "cloudy").put("mm", 0.0).put("cloud", 80.0),
        )
        val nowcast = JSONObject().put("temp", 4.0).put("symbol", "fair_night").put("rate", 0.0).put("wind", 3.0)
        assertEquals(Precip.RAIN, TreeWidget.weatherAt(hours, nowcast, now - 2 * hour, now)!!.kind)
        val current = TreeWidget.weatherAt(hours, nowcast, now, now)!!
        assertEquals(4.0, current.temp!!, 0.001)
        assertEquals(0.8, current.cloud, 0.001)
        assertNull(TreeWidget.weatherAt(hours, nowcast, now - 10 * hour, now))
    }
}
