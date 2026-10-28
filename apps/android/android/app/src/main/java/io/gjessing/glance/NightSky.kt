package io.gjessing.glance

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What NOAA's Space Weather Prediction Center says about the northern lights: the planetary
 * K-index for the three hours around now, and on promising nights OVATION's half-hour outlook.
 */
data class AuroraSignal(
    /** Observed, estimated or predicted Kp for the three-hour block now. */
    val kp: Double?,
    /** OVATION's chance (0–100) of aurora in view from home, overhead or low in the north; null when not fetched or stale. */
    val ovation: Double?,
)

/** What the night sky holds besides the moon and stars, as the painters need it. */
data class NightSights(
    /** How plainly the northern lights show: 0 not at all, about 0.8 a bright display on a clear dark night. */
    val aurora: Double = 0.0,
    /** Meteors to draw in this picture, and where their radiant stands (picture side, elevation in degrees). */
    val meteors: Int = 0, val radiantSide: Double = 0.0, val radiantElevation: Double = 0.0,
    /** The shower running now when its meteors could be seen tonight, for the description. */
    val shower: String? = null,
)

/**
 * The night sky's events for the Living Tree and Tiny Living World: the aurora and the big meteor
 * showers, each weighed by darkness, cloud and moonlight so the picture shows them only when they
 * could really be seen from home. No Android in it, so it can be tested.
 *
 * The darkness and cloud factors are Atlas's (packages/conditions/src/sky.ts), copied so Glance
 * stays standalone: civil twilight is too bright, low cloud hides most aurora, high cloud little.
 */
object NightSky {
    class Shower(val name: String, val month: Int, val day: Int, val hourUtc: Int, val sigmaDays: Double, val zhr: Double, val raHours: Double, val decDeg: Double)

    /** The showers worth looking for from Norway, with their usual peak (it moves by a day at most) and radiant. */
    val showers = listOf(
        Shower("Quadrantids", 1, 3, 22, 0.5, 110.0, 15.33, 49.5),
        Shower("Orionids", 10, 21, 12, 2.5, 20.0, 6.33, 15.5),
        Shower("Leonids", 11, 17, 12, 1.2, 15.0, 10.27, 21.6),
        Shower("Geminids", 12, 14, 6, 1.0, 150.0, 7.47, 32.3),
        Shower("Ursids", 12, 22, 9, 0.6, 10.0, 14.47, 75.4),
    )

    /** Suburban Asker: a share of what a dark site would show, of the aurora and of the faint meteors. */
    private const val SKY_GLOW = 0.8
    private const val LIMITING = 0.35

    /** The shower with the highest zenithal hourly rate now (at least one an hour), and that rate. */
    fun shower(ms: Long): Pair<Shower, Double>? {
        val year = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).year
        return showers.map { s ->
            val days = (year - 1..year + 1).minOf { y ->
                abs(ms - LocalDateTime.of(y, s.month, s.day, s.hourUtc, 0).toInstant(ZoneOffset.UTC).toEpochMilli())
            } / 86_400_000.0
            s to s.zhr * exp(-days * days / (2 * s.sigmaDays * s.sigmaDays))
        }.filter { it.second >= 1.0 }.maxByOrNull { it.second }
    }

    /** Atlas: civil twilight or brighter shows almost nothing, astronomical darkness everything. */
    fun darkness(sunElevation: Double): Double = when {
        sunElevation >= -6 -> 0.05
        sunElevation <= -12 -> 1.0
        else -> 0.05 + (-6 - sunElevation) / 6 * 0.95
    }

    /** Atlas: low cloud obscures most, high cloud is often translucent. Total cover (0..1) when the layers are unknown. */
    fun cloudFactor(total: Double, low: Double?, medium: Double?, high: Double?): Double =
        if (low != null && medium != null && high != null) max(0.06, 1 - (0.88 * low + 0.58 * medium + 0.3 * high) / 100)
        else max(0.06, 1 - 0.94 * total)

    /**
     * The aurora's strength from the K-index alone: southern Norway (about 57° geomagnetic) sees a
     * faint glow low in the north from about Kp 4, a clear display at Kp 5 to 6, a sky full of it beyond.
     */
    fun fromKp(kp: Double, lat: Double): Double {
        val need = 4.0 + (59.8 - lat) * 0.5
        return Season.smooth(need - 0.7, need + 2.5, kp)
    }

    /**
     * The same from OVATION's chance in view (0–100). A quiet night still gives about 10 from the
     * oval far to the north (2026-10-25, Kp 2), so the picture starts above that.
     */
    fun fromOvation(chance: Double): Double = Season.smooth(12.0, 50.0, chance)

    /**
     * OVATION's chance of aurora in view from (lat, lon): overhead, or up to ten degrees further
     * north where it shows lower in the sky, counting for less the further away it is. `grid` gives
     * the 0–100 probability at whole degrees (longitude 0..359), or null outside the grid.
     */
    fun ovationInView(grid: (Int, Int) -> Int?, lat: Double, lon: Double): Double {
        val x = ((lon.roundToInt() % 360) + 360) % 360
        val y = lat.roundToInt()
        return (0..10).maxOf { k -> (grid(x, y + k) ?: 0) * (1 - 0.065 * k) }
    }

    fun sights(
        ms: Long, lat: Double, lon: Double, sunElevation: Double, moonElevation: Double, moonPhase: Double,
        cloud: Double, low: Double?, medium: Double?, high: Double?, signal: AuroraSignal?, slot: Long,
    ): NightSights {
        val dark = darkness(sunElevation)
        if (dark <= 0.05) return NightSights()
        // Atlas keeps 6 % through the thickest cloud, a chance on its map; a picture shows nothing through it.
        val clear = (cloudFactor(cloud, low, medium, high) - 0.06) / 0.94
        val moonlight = if (moonElevation > 0) Sky.illuminated(moonPhase) else 0.0
        val potential = signal?.ovation?.let { fromOvation(it) } ?: signal?.kp?.let { fromKp(it, lat) } ?: 0.0
        val aurora = (potential * dark * clear * (1 - 0.35 * moonlight) * SKY_GLOW).takeIf { it >= 0.04 } ?: 0.0

        val (shower, zhr) = shower(ms) ?: return NightSights(aurora)
        val radiant = Sky.star(ms, lat, lon, shower.raHours, shower.decDeg)
        val rate = zhr * max(0.0, sin(radiant.elevation * PI / 180)) * LIMITING * dark * clear * (1 - 0.5 * moonlight)
        // A picture is a moment: one meteor now and then on an ordinary shower night, two or three at a strong peak.
        val dice = Random(slot * 0x5DEECE66DL + 11)
        val meteors = when {
            dice.nextDouble() >= 1 - exp(-rate / 10) -> 0
            rate > 40 && dice.nextDouble() < 0.5 -> 3
            rate > 15 && dice.nextDouble() < 0.6 -> 2
            else -> 1
        }
        return NightSights(aurora, meteors, Sky.side(radiant.azimuth), radiant.elevation, shower.name.takeIf { rate >= 2 })
    }

    /** For the widget's description. */
    fun describe(sights: NightSights): List<String> = listOfNotNull(
        when {
            sights.aurora >= 0.25 -> "northern lights in view"
            sights.aurora > 0 -> "a chance of northern lights low in the north"
            else -> null
        },
        sights.shower?.let { "the $it meteor shower" },
    )
}
