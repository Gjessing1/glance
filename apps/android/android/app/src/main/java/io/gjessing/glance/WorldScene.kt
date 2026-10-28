package io.gjessing.glance

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Tiny Living World's model: a clearing with one tree, a rock, a puddle hollow and a hill behind,
 * worked out from the same date, sun and MET cache as the Living Tree, with no Android in it so it
 * can be tested. The layout of the world never changes; only these numbers do.
 */
enum class Visitor { FOX, BIRD, RABBIT, BUTTERFLY, SHOOTING_STAR, SQUIRREL, OWL }

/** Who left tracks in the snow. */
enum class Tracks { FOX, HARE, BIRD }

/**
 * What the world keeps between refreshes. The weather part (wet ground, snow, eased sky) is the
 * Living Tree's own record; on top of it the clearing grows mushrooms after rain, opens and loses
 * its flowers with the season and the frost, and keeps tracks in the snow until new snow or a thaw
 * takes them.
 */
data class WorldMemory(
    val weather: TreeMemory,
    /** Mushrooms pushing up after rain, 0..1; each spot shows once this passes its own threshold. */
    val fungi: Double = 0.0,
    /** How far the meadow flowers are out, 0..1; -1 until the first step sets it from the season. */
    val bloom: Double = -1.0,
    /** When the latest tracks were made (0: none), and whose they are. */
    val tracksAt: Long = 0L,
    val tracksBy: Tracks = Tracks.FOX,
    /** Lake ice in centimetres: frost builds it, mild days and rain wear it away. -1 until the first step. */
    val ice: Double = -1.0,
) {
    fun toJson(): JSONObject = JSONObject().put("weather", weather.toJson()).put("fungi", fungi).put("bloom", bloom)
        .put("tracksAt", tracksAt).put("tracksBy", tracksBy.name).put("ice", ice)

    fun step(nowMs: Long, day: Int, weatherAt: (Long) -> TreeWeather?): WorldMemory {
        var fungi = fungi
        var bloom = if (bloom < 0) flowerSeason(day) else bloom
        var tracksAt = tracksAt
        var tracksBy = tracksBy
        // A world placed in midwinter starts with the lake as it would usually be then.
        var ice = if (ice < 0) (if (day <= 75 || day >= 360) 12.0 else 0.0) else ice
        val weather = weather.step(nowMs, day, weatherAt) { at, hours, w, m ->
            val temp = w?.temp ?: 6.0
            // Mushrooms come up a day or so into damp, mild weather (most of all in autumn) and wither over a few dry days.
            val autumn = Season.smooth(220.0, 250.0, day.toDouble()) * (1 - Season.smooth(310.0, 330.0, day.toDouble()))
            val damp = (m.soil - 0.4) / 0.3
            fungi = if (damp > 0 && temp in 3.0..24.0 && m.snow < 1) min(1.0, fungi + 0.035 * hours * min(damp, 1.5) * (0.5 + 1.2 * autumn))
            else max(0.0, fungi - hours * (if (temp < -1) 0.06 else 0.012))
            // Flowers follow the season on mild days and are cut back by a hard frost.
            val target = flowerSeason(day)
            bloom = when {
                temp < -1.5 -> bloom * exp(-0.08 * hours)
                temp > 4 && target > bloom -> min(target, bloom + 0.03 * hours)
                target < bloom -> max(target, bloom - 0.02 * hours)
                else -> bloom
            }
            // Still water needs a few cold days before it holds ice; a degree-hour of frost adds a little.
            ice = if (temp < 0) min(40.0, ice + hours * -temp * 0.012) else max(0.0, ice - hours * (temp * 0.02 + 0.01))
            val falling = w != null && (w.kind == Precip.SNOW || w.kind == Precip.SLEET) && w.mm >= 0.2
            when {
                m.snow < 0.8 || falling -> tracksAt = 0L
                // Once it has stopped, something crosses the fresh snow within a few hours.
                tracksAt == 0L && m.snow >= 1 && roll(at, 11) < 0.18 * hours -> {
                    tracksAt = at
                    tracksBy = Tracks.entries[(roll(at, 12) * Tracks.entries.size).toInt().coerceAtMost(Tracks.entries.size - 1)]
                }
            }
        }
        return WorldMemory(weather, fungi, bloom, tracksAt, tracksBy, ice)
    }

    companion object {
        fun fromJson(json: JSONObject?): WorldMemory? {
            json ?: return null
            val weather = TreeMemory.fromJson(json.optJSONObject("weather")) ?: return null
            return WorldMemory(weather, json.optDouble("fungi", 0.0), json.optDouble("bloom", -1.0), json.optLong("tracksAt", 0L),
                Tracks.entries.firstOrNull { it.name == json.optString("tracksBy") } ?: Tracks.FOX, json.optDouble("ice", -1.0))
        }

        /** Coltsfoot and anemones from mid-April, the meadow through summer, the last ones gone by mid-October. */
        fun flowerSeason(day: Int): Double = Season.smooth(100.0, 140.0, day.toDouble()) * (1 - Season.smooth(250.0, 288.0, day.toDouble()))

        /** A repeatable dice roll for an instant, so the same past always replays the same way. */
        internal fun roll(at: Long, salt: Int): Double = Random(at * 31 + salt * 1_000_003L).nextDouble()
    }
}

/** Everything one picture needs. `slot` changes every twenty minutes, so visitors come and go. */
data class WorldScene(
    val season: Season,
    val sunElevation: Double, val sunSide: Double, val morning: Boolean,
    val moonElevation: Double, val moonSide: Double, val moonPhase: Double,
    val cloud: Double, val rain: Double, val snowfall: Double,
    val wind: Double, val windSide: Double, val fog: Double, val thunder: Boolean,
    /** Water standing in the hollow, 0..1; frozen when it is cold. The lake freezes over in deep winter. */
    val puddle: Double, val frozen: Boolean, val lakeIce: Boolean,
    val snow: Double, val treeSnow: Double,
    val fungi: Double, val bloom: Double,
    val tracks: Tracks?, val tracksAge: Double,
    val temp: Double, val slot: Long,
    val visitor: Visitor?, val flock: Boolean, val fireflies: Boolean,
    /** The northern lights and meteors, when they could be seen from home. */
    val sights: NightSights = NightSights(),
) {
    val night get() = sunElevation < -6
    val dark get() = sunElevation < -3

    /** Leaves in the air: autumn while there are leaves left to drop, and in a gale. */
    val fallingLeaves: Int get() {
        val dropping = season.turn > 0.25 && season.leaves in 0.02..0.97
        val gust = wind > 9 && season.leaves > 0.3
        if (!dropping && !gust) return 0
        return (2 + (wind / 4).toInt()).coerceAtMost(5)
    }

    /** Worth a second frame: something in the picture moves. */
    val animated get() = rain > 0.05 || snowfall > 0.05 || wind > 6 || fallingLeaves > 0 || fireflies || flock ||
        visitor == Visitor.BUTTERFLY || visitor == Visitor.SHOOTING_STAR || sights.aurora > 0.05 || sights.meteors > 0

    fun describe(): String {
        val s = season
        val seasonName = when {
            s.dayOfYear in 80..151 -> "spring"
            s.dayOfYear in 152..243 -> "summer"
            s.dayOfYear in 244..334 -> "autumn"
            else -> "winter"
        }
        val time = when {
            sunElevation > 8 -> "day"
            sunElevation > -6 -> if (morning) "morning" else "evening"
            else -> "night"
        }
        val weather = when {
            snowfall > 0.05 -> "snowing"
            rain > 0.05 -> "raining"
            fog > 0.5 -> "foggy"
            cloud > 0.75 -> "overcast"
            cloud > 0.35 -> "partly cloudy"
            else -> "clear"
        }
        val seen = listOfNotNull(
            if (snow > 1) "snow on the ground" else null,
            if (puddle > 0.3) if (frozen) "a frozen puddle" else "puddles" else null,
            if (lakeIce) "the lake frozen over" else null,
            if (fungi > 0.3) "mushrooms" else null,
            if (tracks != null) "tracks in the snow" else null,
            when (visitor) {
                Visitor.FOX -> "a fox passing through"
                Visitor.BIRD -> "a bird in the tree"
                Visitor.RABBIT -> "a hare behind the rock"
                Visitor.BUTTERFLY -> "a butterfly"
                Visitor.SHOOTING_STAR -> "a shooting star"
                Visitor.SQUIRREL -> "a squirrel on the trunk"
                Visitor.OWL -> "an owl in the tree"
                null -> null
            },
            if (fireflies) "fireflies" else null,
        ) + NightSky.describe(sights)
        return (listOf("Tiny living world: $seasonName", time, weather) + seen).joinToString(", ")
    }

    companion object {
        const val SLOT_MS = 20 * 60_000L
        /** Ice this thick (cm) covers the lake in the picture; a few frosty days' worth. */
        const val ICE_SHOWS = 2.0

        fun of(nowMs: Long, zone: ZoneId, lat: Double, lon: Double, memory: WorldMemory, now: TreeWeather?, aurora: AuroraSignal? = null): WorldScene {
            val local = Instant.ofEpochMilli(nowMs).atZone(zone)
            val sun = Sky.sun(nowMs, lat, lon)
            val moon = Sky.moon(nowMs, lat, lon)
            val m = memory.weather
            val season = Season.at(local.dayOfYear, lat).afterWind(m.windLoss)
            val toward = (now?.windFrom ?: 225.0) + 180
            val temp = now?.temp ?: 6.0
            val snow = m.snow
            // Standing water: while it rains and for a while after, and in the hollow while the ground is soaked.
            val puddle = if (snow > 3) 0.0 else max(Season.smooth(0.42, 0.8, m.soil), m.surface * 0.85).coerceIn(0.0, 1.0)
            val slot = nowMs / SLOT_MS
            val cloud = m.cloud.coerceIn(0.0, 1.0)
            val fog = if (now?.fog == true) 1.0 else 0.0
            val tracks = memory.tracksBy.takeIf { memory.tracksAt > 0 && snow >= 0.8 }
            return WorldScene(
                season, sun.elevation, Sky.side(sun.azimuth), sun.azimuth < 180,
                moon.elevation, Sky.side(moon.azimuth), Sky.moonPhase(nowMs),
                cloud, m.rain, m.snowfall, m.wind, if (Sky.side(toward) >= 0) 1.0 else -1.0, fog, now?.thunder == true,
                puddle, temp < -0.5, memory.ice >= ICE_SHOWS, snow, m.branchSnow, memory.fungi, max(memory.bloom, 0.0),
                tracks, if (tracks != null) (nowMs - memory.tracksAt) / 3_600_000.0 else 0.0,
                temp, slot,
                visitor(slot, sun.elevation, season, cloud, m.rain, m.snowfall, m.wind, temp),
                flock(slot, sun.elevation, sun.azimuth < 180, season, m.rain + m.snowfall, m.wind),
                fireflies(slot, sun.elevation, season, temp, m.rain, m.wind),
                NightSky.sights(nowMs, lat, lon, sun.elevation, moon.elevation, Sky.moonPhase(nowMs), cloud, now?.cloudLow, now?.cloudMedium, now?.cloudHigh, aurora, slot),
            )
        }

        private fun dice(slot: Long, salt: Int) = Random(slot * 0x9E3779B1L + salt).nextDouble()

        /**
         * The rare visitor for this twenty minutes, if any: about one slot in six has one, picked
         * among those that would plausibly be out in this light and weather. The same slot always
         * gives the same answer, so a refresh does not chase it away.
         */
        internal fun visitor(slot: Long, sunElevation: Double, season: Season, cloud: Double, rain: Double, snowfall: Double, wind: Double, temp: Double): Visitor? {
            if (dice(slot, 1) > 0.17) return null
            val night = sunElevation < -6
            val day = sunElevation > 2
            val wet = rain > 0.3 || snowfall > 0.5
            val calm = wind < 11
            val d = season.dayOfYear
            val choices = buildList {
                if (calm && rain < 1) add(Visitor.FOX to if (day) 0.6 else 2.0)
                if (day && !wet && calm) add(Visitor.BIRD to 2.0)
                if (!night && rain < 1) add(Visitor.RABBIT to 1.5)
                if (day && temp > 11 && !wet && wind < 7 && d in 110..260) add(Visitor.BUTTERFLY to if (d < 170) 3.0 else 2.0)
                if (night && cloud < 0.35) add(Visitor.SHOOTING_STAR to 2.0)
                if (day && !wet && calm) add(Visitor.SQUIRREL to 1.0)
                if (night && !wet && calm) add(Visitor.OWL to 1.0)
            }
            if (choices.isEmpty()) return null
            var pick = dice(slot, 2) * choices.sumOf { it.second }
            for ((visitor, weight) in choices) {
                pick -= weight
                if (pick <= 0) return visitor
            }
            return choices.last().first
        }

        /** Small birds crossing the sky: often in the morning, now and then on a summer day. */
        internal fun flock(slot: Long, sunElevation: Double, morning: Boolean, season: Season, wet: Double, wind: Double): Boolean {
            if (sunElevation < -2 || wet > 0.3 || wind > 12) return false
            val chance = when {
                morning && sunElevation < 20 -> 0.45
                season.dayOfYear in 120..250 -> 0.15
                else -> 0.06
            }
            return dice(slot, 3) < chance
        }

        /** Warm, still summer nights. */
        internal fun fireflies(slot: Long, sunElevation: Double, season: Season, temp: Double, rain: Double, wind: Double): Boolean =
            sunElevation < -3 && temp > 11 && rain < 0.2 && wind < 6 && season.dayOfYear in 150..240 && dice(slot, 4) < 0.7

        /** Light changes fastest around sunrise and sunset, and visitors come and go every twenty minutes. */
        fun refreshAfterMinutes(sunElevation: Double) = if (abs(sunElevation) < 8) 10L else 20L
    }
}
