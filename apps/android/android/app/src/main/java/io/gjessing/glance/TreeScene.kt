package io.gjessing.glance

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The Living Tree's model: everything the painter needs, worked out from the date, the sun and MET's
 * cache, with no Android in it so it can be tested. The tree itself never changes; only these
 * numbers do, so it stays the same birch in every season and weather.
 */
enum class Precip { NONE, RAIN, SLEET, SNOW }
enum class PerchedBird { CROW, WOOD_PIGEON, BLACKBIRD, GREAT_TIT }
/** Birds passing far overhead: a skein of geese on migration, crows and jackdaws flying to roost, or a few small birds. */
enum class Flight { GEESE, CORVIDS, SMALL_BIRDS }

/** One hour (or the moment now) of weather at the tree, read from the cached forecast or nowcast. */
data class TreeWeather(
    val temp: Double?, val mm: Double, val kind: Precip, val cloud: Double,
    val wind: Double, val windFrom: Double, val fog: Boolean, val thunder: Boolean,
    /** MET's low, medium and high cloud in percent, from 0.5.23 on; null in rows cached before. */
    val cloudLow: Double? = null, val cloudMedium: Double? = null, val cloudHigh: Double? = null,
) {
    companion object {
        /**
         * A cached hour row or nowcast entry: `temp`, `symbol`, `mm` (or nowcast's `rate`), and from
         * 0.6 on `wind`, `windFrom` and `cloud`. Rows cached before then fall back on the symbol.
         */
        private fun layer(row: JSONObject, key: String) = if (row.has(key)) row.optDouble(key).takeIf { !it.isNaN() } else null

        fun of(row: JSONObject): TreeWeather {
            val symbol = row.optString("symbol")
            val code = symbol.substringBefore('_')
            val temp = if (row.has("temp") && !row.isNull("temp")) row.optDouble("temp").takeIf { !it.isNaN() } else null
            val mm = if (row.has("rate")) row.optDouble("rate", 0.0) else row.optDouble("mm", 0.0)
            var kind = when {
                code.contains("snow") -> Precip.SNOW
                code.contains("sleet") -> Precip.SLEET
                code.contains("rain") -> Precip.RAIN
                else -> Precip.NONE
            }
            if (kind == Precip.NONE && mm >= 0.1) kind = if (temp != null && temp <= 0.5) Precip.SNOW else Precip.RAIN
            if (kind == Precip.RAIN && temp != null && temp <= -1.0) kind = Precip.SNOW
            val cloud = if (row.has("cloud")) row.optDouble("cloud", 50.0) / 100.0 else when {
                code == "clearsky" -> 0.05
                code == "fair" -> 0.25
                code == "partlycloudy" -> 0.55
                code.contains("showers") -> 0.7
                code == "" -> 0.5
                else -> 0.95
            }
            return TreeWeather(temp, max(0.0, mm), kind, cloud.coerceIn(0.0, 1.0),
                row.optDouble("wind", 2.0).takeIf { !it.isNaN() } ?: 2.0, row.optDouble("windFrom", 225.0).takeIf { !it.isNaN() } ?: 225.0,
                code == "fog", code.contains("thunder"),
                layer(row, "cloudLow"), layer(row, "cloudMedium"), layer(row, "cloudHigh"))
        }

        /** What the sky shows: a "light rain" symbol with no measured amount still rains a little. */
        fun shown(w: TreeWeather): Double {
            if (w.kind == Precip.NONE) return 0.0
            return max(w.mm, 0.25)
        }
    }
}

/**
 * Where the birch is in its year, each 0..1. Day numbers are Oslo's: buds from mid-April, leaves in
 * May, yellowing from mid-September and bare by late November. South of the equator the year turns
 * half way round. The calendar is only the frame: the weeks' temperatures move spring and autumn
 * (`springDay` and `autumnDay` are the days the tree has reached, see [TreeMemory.springShift]).
 */
data class Season(
    val buds: Double, val catkins: Double, val leafSize: Double, val leaves: Double, val turn: Double,
    val fresh: Double, val litter: Double, val litterAge: Double, val warmth: Double, val dayOfYear: Int,
    val springDay: Double = dayOfYear.toDouble(), val autumnDay: Double = dayOfYear.toDouble(),
) {
    /** Leaves let go faster than the calendar alone when an autumn gale has stripped some. */
    fun afterWind(loss: Double) = stripped(loss)

    /** ...or a hard frost has loosened them. */
    fun afterFrost(loss: Double) = stripped(loss)

    private fun stripped(loss: Double): Season {
        val stripped = loss.coerceIn(0.0, 0.35)
        return copy(leaves = leaves * (1 - stripped), litter = min(1.0, litter + stripped * leaves * 0.8))
    }

    /** The Japanese maple beside the birch in the large view, moved by the same weather. */
    val maple get() = MapleSeason.at(springDay, autumnDay)

    companion object {
        /** `springShift` and `autumnShift` are days ahead (+) or behind (-) the calendar. */
        fun at(day: Int, lat: Double, springShift: Double = 0.0, autumnShift: Double = 0.0): Season {
            val d = if (lat < 0) (day + 182 - 1) % 365 + 1 else day
            val sp = d + springShift
            val au = d + autumnShift
            val leafOut = smooth(116.0, 136.0, sp)
            // Keep the golden canopy through late October; most leaves fall during November.
            val falling = smooth(288.0, 325.0, au)
            // Old leaves stay on the ground through winter, pressed and darkening, and are gone by May.
            val litter = when {
                d >= 250 -> 0.08 + 0.92 * falling
                d < 140 -> 0.08 + 0.92 * (1 - smooth(70.0, 135.0, sp))
                else -> 0.08
            }
            val litterAge = if (d >= 250) smooth(310.0, 365.0, au) else 1.0
            return Season(
                buds = smooth(95.0, 118.0, sp) * (1 - smooth(128.0, 145.0, sp)),
                catkins = smooth(100.0, 115.0, sp) * (1 - smooth(132.0, 150.0, sp)),
                leafSize = 0.25 + 0.75 * smooth(116.0, 152.0, sp),
                leaves = leafOut * (1 - falling),
                turn = smooth(248.0, 292.0, au),
                fresh = 1 - smooth(140.0, 190.0, sp),
                litter = litter, litterAge = litterAge,
                warmth = -cos(2 * PI * (d - 20) / 365.0), dayOfYear = d,
                springDay = sp, autumnDay = au,
            )
        }

        internal fun smooth(a: Double, b: Double, x: Double): Double {
            val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }
        private fun smooth(a: Double, b: Double, x: Int) = smooth(a, b, x.toDouble())
    }
}

/**
 * A Japanese maple's year (Acer palmatum, the green form), each 0..1, for the same (already
 * hemisphere-turned) day: bronze-tinged new leaves in mid-May, a little after the birch's, fresh
 * green all summer, then late to turn, crimson at its peak in late October, after the birch has
 * gone gold, and bare by mid-November.
 */
data class MapleSeason(val leafSize: Double, val leaves: Double, val turn: Double, val fresh: Double, val litter: Double, val litterAge: Double) {
    companion object {
        /** `sp` and `au` are the days spring and autumn have reached, as for the birch. */
        fun at(sp: Double, au: Double = sp): MapleSeason {
            val falling = Season.smooth(298.0, 320.0, au)
            return MapleSeason(
                leafSize = 0.3 + 0.7 * Season.smooth(126.0, 158.0, sp),
                leaves = Season.smooth(124.0, 142.0, sp) * (1 - falling),
                turn = Season.smooth(276.0, 298.0, au),
                fresh = 1 - Season.smooth(140.0, 180.0, sp),
                litter = when {
                    au >= 270 -> falling
                    sp < 140 -> 1 - Season.smooth(75.0, 135.0, sp)
                    else -> 0.0
                },
                litterAge = if (au >= 270) Season.smooth(318.0, 365.0, au) else 1.0,
            )
        }
    }
}

/** Sun and moon positions, accurate to about a degree, which is plenty for lighting a picture. */
object Sky {
    data class Position(val elevation: Double, val azimuth: Double)

    private fun rad(deg: Double) = deg * PI / 180
    private fun deg(rad: Double) = rad * 180 / PI

    fun sun(ms: Long, lat: Double, lon: Double): Position {
        val d = ms / 86_400_000.0 - 10_957.5 // days since J2000.0
        val g = rad((357.529 + 0.98560028 * d) % 360)
        val q = (280.459 + 0.98564736 * d) % 360
        val l = rad(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = rad(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        return star(ms, lat, lon, deg(ra) / 15, deg(dec))
    }

    /** Where a fixed point of the sky (right ascension in hours, declination in degrees) stands now: a meteor radiant. */
    fun star(ms: Long, lat: Double, lon: Double, raHours: Double, decDeg: Double): Position {
        val d = ms / 86_400_000.0 - 10_957.5
        val gmst = (18.697374558 + 24.06570982441908 * d) % 24
        val ha = rad(((gmst + lon / 15 - raHours) * 15) % 360)
        val dec = rad(decDeg)
        val phi = rad(lat)
        val elevation = deg(asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(ha)))
        val azimuth = (deg(atan2(sin(ha), cos(ha) * sin(phi) - tan(dec) * cos(phi))) + 180) % 360
        return Position(elevation, azimuth)
    }

    /** 0 new, 0.5 full. */
    fun moonPhase(ms: Long): Double {
        val days = ms / 86_400_000.0 - 10_962.6 // a new moon, 2000-01-06 18:14 UTC
        val cycle = days / 29.530588853
        return cycle - floor(cycle)
    }

    /** The moon runs a phase's worth of a day behind the sun; its own declination is left out. */
    fun moon(ms: Long, lat: Double, lon: Double): Position =
        sun(ms - (moonPhase(ms) * 24.84 * 3_600_000).toLong(), lat, lon)

    /** Fraction of the disc lit, from the phase. */
    fun illuminated(phase: Double) = (1 - cos(2 * PI * phase)) / 2

    /** Left (-1) to right (+1) in the picture, for a viewer looking south: east is on the left. */
    fun side(azimuth: Double) = -sin(rad(azimuth))
}

/**
 * Evidence of recent weather that the picture keeps (the brief's "weather memory"). MET's forecast
 * has no past, so the tree keeps its own record, stepped each render through the hours since the last
 * one: the nowcast for the last hour, the cached forecast's own hour before that, and nothing where
 * the cache does not reach (things only dry and melt then).
 */
data class TreeMemory(
    val atMs: Long,
    /** Water held in the soil; pockets around the roots show above ~0.35. Recedes over days. */
    val soil: Double = BASE_SOIL,
    /** The upper soil responds first; the root zone and deep soil follow over hours. */
    val topsoil: Double = BASE_SOIL,
    val deepSoil: Double = BASE_SOIL,
    /** Wet bark and puddles: hours. */
    val surface: Double = 0.0,
    /** Snow on the ground in centimetres, and how much still sits on the branches. */
    val snow: Double = 0.0,
    val branchSnow: Double = 0.0,
    /** Leaves an autumn gale has stripped early, 0..0.35. */
    val windLoss: Double = 0.0,
    /** The sky as shown, eased so a change between two refreshes does not jump. */
    val cloud: Double = -1.0,
    val rain: Double = 0.0,
    val snowfall: Double = 0.0,
    val wind: Double = 2.0,
    /**
     * How much warmer (+) or colder than Oslo's normal for the date the air has been, in °C: over
     * about the last two weeks for autumn, the last month for spring. NaN until the first step,
     * which starts them from the coming days' forecast.
     */
    val recentWarmth: Double = Double.NaN,
    val springWarmth: Double = Double.NaN,
    /**
     * Days the birch is ahead of (+) or behind (-) the calendar. A warm spring brings the buds on
     * sooner, a mild autumn holds the leaves longer; neither ever runs the year backwards.
     */
    val springShift: Double = 0.0,
    val autumnShift: Double = 0.0,
    /** Leaves a hard autumn frost has loosened, 0..0.35. */
    val frostLoss: Double = 0.0,
    /** Hoar frost on the grass, 0..1: forms on cold nights, gone once the air is above freezing. */
    val frost: Double = 0.0,
    /** A dry spell, 0..1: warm days without rain bleach the grass; rain brings it back. */
    val dry: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject().put("at", atMs).put("soil", soil).put("topsoil", topsoil).put("deepSoil", deepSoil)
        .put("surface", surface).put("snow", snow)
        .put("branchSnow", branchSnow).put("windLoss", windLoss).put("cloud", cloud).put("rain", rain).put("snowfall", snowfall).put("wind", wind)
        .also { json ->
            if (!recentWarmth.isNaN()) json.put("recentWarmth", recentWarmth)
            if (!springWarmth.isNaN()) json.put("springWarmth", springWarmth)
        }
        .put("springShift", springShift).put("autumnShift", autumnShift).put("frostLoss", frostLoss).put("frost", frost).put("dry", dry)

    /**
     * The memory carried forward to `nowMs`. `weatherAt` answers for an instant, or null where there
     * is no data. Steps are at most half an hour; more than three days back is not replayed.
     * `onStep` sees each step (its start, its length in hours, its weather, the memory after it), for
     * records that build on this one.
     */
    fun step(nowMs: Long, day: Int, weatherAt: (Long) -> TreeWeather?, onStep: ((Long, Double, TreeWeather?, TreeMemory) -> Unit)? = null): TreeMemory {
        var m = this
        var t = max(atMs, nowMs - 72 * 3_600_000L)
        if (cloud < 0) {
            val w = weatherAt(nowMs)
            m = m.copy(cloud = w?.cloud ?: 0.5, wind = w?.wind ?: 2.0, rain = 0.0, snowfall = 0.0)
        }
        if (recentWarmth.isNaN() || springWarmth.isNaN()) {
            // No record yet: the next two days' forecast is the best guess at the weather of late.
            val temps = (0..48 step 3).mapNotNull { weatherAt(nowMs + it * 3_600_000L)?.temp }
            val warmth = if (temps.isEmpty()) 0.0 else temps.average() - normalTemp(day)
            val recent = if (recentWarmth.isNaN()) warmth else recentWarmth
            val spring = if (springWarmth.isNaN()) warmth * 0.5 else springWarmth
            m = m.copy(recentWarmth = recent, springWarmth = spring, springShift = springTarget(spring), autumnShift = autumnTarget(recent))
        }
        while (t < nowMs) {
            val next = min(nowMs, t + 30 * 60_000L)
            val w = weatherAt(t)
            m = m.advance((next - t) / 3_600_000.0, w, day)
            onStep?.invoke(t, (next - t) / 3_600_000.0, w, m)
            t = next
        }
        return m.copy(atMs = nowMs)
    }

    private fun advance(hours: Double, w: TreeWeather?, day: Int): TreeMemory {
        val measured = w?.temp
        val temp = measured ?: 6.0
        val mm = (w?.mm ?: 0.0) * hours
        val frozen = w != null && (w.kind == Precip.SNOW || (w.kind != Precip.NONE && temp <= 0.5))
        val snowMm = when { w == null -> 0.0; frozen -> mm; w.kind == Precip.SLEET -> mm * 0.4; else -> 0.0 }
        val rainMm = mm - snowMm
        // Fresh snow is about ten times its water; a degree-hour above zero melts a little under a millimetre.
        val melt = if (temp > 0) min(snow + snowMm, (0.07 * temp + 0.04 * rainMm) * hours) else 0.0
        val snow = (snow + snowMm - melt).coerceIn(0.0, 60.0)
        // This is an illustration inferred from recent precipitation, not a soil-moisture reading.
        // Water reaches the surface first, then moves through the root zone into deeper soil.
        val cloudNow = w?.cloud ?: 0.6
        val dryRate = 0.016 * (1 + max(temp, 0.0) / 12) * (1 + (1 - cloudNow) * 0.6) * (if (snow > 1 || temp < -2) 0.3 else 1.0)
        val infiltration = if (temp > 0.5) rainMm + melt else 0.0
        val topsoil = (BASE_SOIL + (topsoil + infiltration * 0.19 - BASE_SOIL) * exp(-dryRate * 2.2 * hours)).coerceIn(BASE_SOIL, 1.0)
        val rootFlow = max(0.0, topsoil - soil) * (1 - exp(-hours / 3.0))
        val soil = (BASE_SOIL + (soil + rootFlow - BASE_SOIL) * exp(-dryRate * hours)).coerceIn(BASE_SOIL, 1.0)
        val deepFlow = max(0.0, soil - deepSoil) * (1 - exp(-hours / 10.0))
        val deepSoil = (BASE_SOIL + (deepSoil + deepFlow - BASE_SOIL) * exp(-dryRate * 0.45 * hours)).coerceIn(BASE_SOIL, 1.0)
        val wetNow = if (w != null && rainMm > 0) min(1.0, 0.35 + rainMm / hours * 0.5) else 0.0
        val surface = max(wetNow, surface * exp(-(0.3 * (1 + max(temp, 0.0) / 15) * (1 + (1 - cloudNow))) * hours))
        val windNow = w?.wind ?: 2.0
        val branchSnow = (branchSnow + snowMm * 0.3).coerceAtMost(1.0) *
            exp(-(0.06 + 0.02 * max(windNow - 3, 0.0) + 0.35 * max(temp, 0.0)) * hours)
        // A gale strips turning leaves early; the count starts over when the new leaves come.
        val windLoss = when {
            day in 100..240 -> 0.0
            day >= 270 && windNow > 9 -> min(0.35, windLoss + (windNow - 9) * 0.012 * hours)
            else -> windLoss
        }
        // The tree's own year: the weeks' warmth against the normal for the date. Where the cache does
        // not reach, the record holds.
        val warmth = measured?.let { it - normalTemp(day) }
        val recentWarmth = if (warmth == null) recentWarmth else recentWarmth + (warmth - recentWarmth) * (1 - exp(-hours / (14 * 24.0)))
        val springWarmth = if (warmth == null) springWarmth else springWarmth + (warmth - springWarmth) * (1 - exp(-hours / (30 * 24.0)))
        // A shift may grow at once, but shrinks no faster than time passes, so the tree only stands still.
        val springShift = max(springTarget(springWarmth), springShift - hours / 24)
        val autumnShift = max(autumnTarget(recentWarmth), autumnShift - hours / 24)
        // A hard frost loosens the leaves still on; they come down over the next days.
        val frostLoss = when {
            day in 100..240 -> 0.0
            day >= 250 && measured != null && measured < -2 -> min(0.35, frostLoss + (-2 - measured) * 0.02 * hours)
            else -> frostLoss
        }
        // Hoar frost grows on still, clear, freezing nights and goes as soon as it thaws.
        val clear = 1 - cloudNow
        val frost = when {
            measured == null -> frost * exp(-0.1 * hours)
            measured <= -0.5 -> min(1.0, frost + hours * (0.1 + 0.4 * clear) * min(1.0, (0.5 - measured) / 3) * (1 - sm01((windNow - 4) / 6)))
            measured > 1.5 || rainMm > 0 -> frost * exp(-(0.8 + 0.3 * (measured - 1.5).coerceAtLeast(0.0)) * hours)
            else -> frost
        }
        // A dry spell builds over a week or two of warm days and is washed out by rain.
        val wetting = rainMm + melt
        val dry = when {
            measured == null -> dry
            wetting > 0.05 -> max(0.0, dry - wetting * 0.06)
            else -> min(1.0, dry + hours / 24 * max(0.0, measured - 8) / 120 * (0.5 + 0.5 * clear))
        }
        val ease = 1 - exp(-hours / 0.75)
        val shownRain = w?.let { if (it.kind == Precip.RAIN || it.kind == Precip.SLEET) TreeWeather.shown(it) else 0.0 } ?: 0.0
        val shownSnow = w?.let { if (it.kind == Precip.SNOW || it.kind == Precip.SLEET) TreeWeather.shown(it) else 0.0 } ?: 0.0
        return copy(soil = soil, topsoil = topsoil, deepSoil = deepSoil, surface = surface, snow = snow, branchSnow = branchSnow, windLoss = windLoss,
            cloud = cloud + (cloudNow - cloud) * ease, rain = rain + (shownRain - rain) * ease,
            snowfall = snowfall + (shownSnow - snowfall) * ease, wind = wind + (windNow - wind) * ease,
            recentWarmth = recentWarmth, springWarmth = springWarmth, springShift = springShift, autumnShift = autumnShift,
            frostLoss = frostLoss, frost = frost, dry = dry)
    }

    companion object {
        const val BASE_SOIL = 0.15

        /** Oslo-Blindern's mean temperature for each mid-month (1991–2020), January first. */
        private val NORMALS = doubleArrayOf(-2.3, -2.4, 0.9, 5.6, 10.6, 14.7, 17.4, 16.2, 11.8, 6.4, 2.1, -1.6)

        /** The normal daily mean for a day of the year, between the months' normals. */
        fun normalTemp(day: Int): Double {
            val m = (day - 15.5) / (365 / 12.0)
            val i = floor(m).toInt()
            val f = m - i
            return NORMALS[Math.floorMod(i, 12)] * (1 - f) + NORMALS[Math.floorMod(i + 1, 12)] * f
        }

        /** Birch buds burst some four days sooner for each degree a spring runs warm, up to a fortnight. */
        fun springTarget(warmth: Double) = (4.0 * warmth).coerceIn(-14.0, 14.0)

        /** Its leaves turn and fall some two and a half days later for each degree an autumn runs mild. */
        fun autumnTarget(warmth: Double) = (-2.5 * warmth).coerceIn(-10.0, 10.0)

        private fun sm01(x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        fun fromJson(json: JSONObject?): TreeMemory? {
            json ?: return null
            val soil = json.optDouble("soil", BASE_SOIL)
            return TreeMemory(json.optLong("at"), soil, json.optDouble("topsoil", soil), json.optDouble("deepSoil", BASE_SOIL),
                json.optDouble("surface", 0.0), json.optDouble("snow", 0.0), json.optDouble("branchSnow", 0.0),
                json.optDouble("windLoss", 0.0), json.optDouble("cloud", -1.0), json.optDouble("rain", 0.0),
                json.optDouble("snowfall", 0.0), json.optDouble("wind", 2.0),
                json.optDouble("recentWarmth", Double.NaN), json.optDouble("springWarmth", Double.NaN),
                json.optDouble("springShift", 0.0), json.optDouble("autumnShift", 0.0), json.optDouble("frostLoss", 0.0),
                json.optDouble("frost", 0.0), json.optDouble("dry", 0.0))
        }
    }
}

/** Everything one picture needs. `variation` changes from render to render so rain and leaves move on. */
data class TreeScene(
    val season: Season,
    val sunElevation: Double, val sunSide: Double, val morning: Boolean,
    val moonElevation: Double, val moonSide: Double, val moonPhase: Double,
    val cloud: Double, val rain: Double, val snowfall: Double, val sleet: Boolean,
    val wind: Double, val windSide: Double, val fog: Double, val thunder: Boolean,
    val soil: Double, val topsoil: Double, val deepSoil: Double, val surface: Double, val snow: Double, val branchSnow: Double,
    val temp: Double, val hour: Double, val variation: Long,
    /** The northern lights and meteors, when they could be seen from home. */
    val sights: NightSights = NightSights(),
    val bird: PerchedBird? = null,
    /** Birds passing far overhead, now and then. */
    val flight: Flight? = null,
    /** Hoar frost on the grass and a dry spell, each 0..1, from [TreeMemory]. */
    val frost: Double = 0.0,
    val dry: Double = 0.0,
) {
    /** Worth a second frame: something in the picture moves (the aurora's rays shift, a meteor flashes). */
    val animated get() = rain > 0.05 || snowfall > 0.05 || wind > 6 || fallingLeaves > 0 || sights.aurora > 0.05 || sights.meteors > 0

    /** Leaves in the air: autumn while there are leaves left to drop, and in a gale. */
    val fallingLeaves: Int get() {
        val dropping = season.turn > 0.25 && season.leaves in 0.02..0.97
        val gust = wind > 9 && season.leaves > 0.3
        if (!dropping && !gust) return 0
        return (1 + (wind / 4).toInt() + if (dropping && season.turn > 0.6) 1 else 0).coerceAtMost(5)
    }

    fun describe(): String {
        val s = season
        val time = when {
            sunElevation > 8 -> "day"
            sunElevation > -6 -> if (morning) "dawn" else "dusk"
            else -> "night"
        }
        val tree = when {
            s.leaves < 0.03 && s.buds > 0.2 -> "birch in bud"
            s.leaves < 0.03 -> "bare birch"
            s.turn > 0.5 -> "birch in autumn colour"
            s.fresh > 0.5 -> "birch in new leaf"
            else -> "birch in full leaf"
        }
        val weather = when {
            snowfall > 0.05 -> "snowing"
            rain > 0.05 -> "raining"
            fog > 0.5 -> "foggy"
            cloud > 0.75 -> "overcast"
            cloud > 0.35 -> "partly cloudy"
            else -> "clear"
        }
        val ground = listOfNotNull(if (snow > 1) "snow on the ground" else null, if (soil > 0.45) "damp soil" else null)
        val visitor = bird?.let {
            when (it) {
                PerchedBird.WOOD_PIGEON -> "a wood pigeon in the birch"
                PerchedBird.CROW -> "a crow in the birch"
                PerchedBird.BLACKBIRD -> "a blackbird in the birch"
                PerchedBird.GREAT_TIT -> "a great tit among the twigs"
            }
        }
        val passing = flight?.let {
            when (it) {
                Flight.GEESE -> "geese passing over"
                Flight.CORVIDS -> "crows flying to roost"
                Flight.SMALL_BIRDS -> "small birds passing"
            }
        }
        val frosty = if (frost > 0.4 && snow < 1) "frost on the grass" else null
        return (listOf("Living tree: $tree", time, weather) + ground + listOfNotNull(frosty, visitor, passing) + NightSky.describe(sights)).joinToString(", ")
    }

    companion object {
        fun of(nowMs: Long, zone: ZoneId, lat: Double, lon: Double, memory: TreeMemory, now: TreeWeather?, aurora: AuroraSignal? = null): TreeScene {
            val local = Instant.ofEpochMilli(nowMs).atZone(zone)
            val sun = Sky.sun(nowMs, lat, lon)
            val moon = Sky.moon(nowMs, lat, lon)
            val slot = nowMs / (20 * 60_000L)
            val sights = NightSky.sights(nowMs, lat, lon, sun.elevation, moon.elevation, Sky.moonPhase(nowMs),
                memory.cloud.coerceIn(0.0, 1.0), now?.cloudLow, now?.cloudMedium, now?.cloudHigh, aurora, slot)
            val season = Season.at(local.dayOfYear, lat, memory.springShift, memory.autumnShift)
                .afterWind(memory.windLoss).afterFrost(memory.frostLoss)
            val toward = (now?.windFrom ?: 225.0) + 180
            // One choice per two-hour visit, so refreshes do not make a bird flicker in and out.
            val visit = nowMs / (2 * 3_600_000L)
            val dry = memory.rain < 0.1 && memory.snowfall < 0.1 && now?.kind != Precip.RAIN &&
                now?.kind != Precip.SLEET && now?.kind != Precip.SNOW && now?.thunder != true && now?.fog != true
            val calm = dry && memory.wind < 7 && (now?.wind ?: 0.0) < 7
            val bird = if (calm) perched(visit, local.dayOfYear, sun.elevation) else null
            val flight = if (dry && memory.wind < 11) flight(visit, local.dayOfYear, sun.elevation, sun.azimuth < 180) else null
            return TreeScene(
                season, sun.elevation, Sky.side(sun.azimuth), sun.azimuth < 180,
                moon.elevation, Sky.side(moon.azimuth), Sky.moonPhase(nowMs),
                memory.cloud.coerceIn(0.0, 1.0), memory.rain, memory.snowfall, now?.kind == Precip.SLEET,
                memory.wind, if (Sky.side(toward) >= 0) 1.0 else -1.0, if (now?.fog == true) 1.0 else 0.0, now?.thunder == true,
                memory.soil, memory.topsoil, memory.deepSoil, memory.surface, memory.snow, memory.branchSnow,
                now?.temp ?: 6.0, local.hour + local.minute / 60.0, slot, sights, bird, flight,
                memory.frost, memory.dry,
            )
        }

        /** A well-mixed number in 0..1 for a visit, different for each `salt`. */
        internal fun roll(visit: Long, salt: Long): Double {
            var z = visit * 0x9E3779B97F4A7C15uL.toLong() + salt * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            z = z xor (z ushr 31)
            return (z ushr 11).toDouble() / (1L shl 53).toDouble()
        }

        /**
         * A bird in the birch: a discovery, not a fixture, so roughly one calm daylit visit in ten.
         * A blackbird is the likeliest, and stays into the dusk; in winter a great tit works the bare
         * twigs; wood pigeons only in the summer half, when they are here.
         */
        internal fun perched(visit: Long, day: Int, sunElevation: Double): PerchedBird? {
            if (sunElevation < -3 || roll(visit, 1) >= 0.1) return null
            val pick = roll(visit, 2)
            val winter = day >= 305 || day < 80
            val bird = if (winter) when {
                pick < 0.5 -> PerchedBird.GREAT_TIT
                pick < 0.85 -> PerchedBird.BLACKBIRD
                else -> PerchedBird.CROW
            } else when {
                pick < 0.45 -> PerchedBird.BLACKBIRD
                pick < 0.65 -> PerchedBird.WOOD_PIGEON
                pick < 0.8 -> PerchedBird.CROW
                else -> PerchedBird.GREAT_TIT
            }
            // Only the blackbird lingers in the twilight.
            return if (sunElevation < 1 && bird != PerchedBird.BLACKBIRD) null else bird
        }

        /**
         * Birds passing far overhead: geese in their skeins in the migration weeks of spring and
         * autumn, crows and jackdaws flying to roost on winter-half evenings, now and then a few small
         * birds by day. Never by night.
         */
        internal fun flight(visit: Long, day: Int, sunElevation: Double, morning: Boolean): Flight? {
            if (sunElevation < -5) return null
            val r = roll(visit, 3)
            val migration = day in 255..300 || day in 75..115
            val roost = !morning && sunElevation < 4 && (day >= 280 || day < 90)
            return when {
                migration && sunElevation > 0 && r < 0.1 -> Flight.GEESE
                roost && r < 0.3 -> Flight.CORVIDS
                sunElevation > 3 && r > 0.95 -> Flight.SMALL_BIRDS
                else -> null
            }
        }

        /** How far a picture can go before the light has visibly moved: quicker in twilight. */
        fun refreshAfterMinutes(sunElevation: Double) = if (abs(sunElevation) < 8) 10L else 30L
    }
}
