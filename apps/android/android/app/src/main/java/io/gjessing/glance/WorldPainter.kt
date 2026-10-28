package io.gjessing.glance

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paints Tiny Living World: a small round tree in a clearing by a mountain lake, two pines, a rock,
 * a puddle hollow, the forest on the far shore and snow-capped mountains behind. Soft, flat-shaded
 * shapes, lit from the sun's side, in the style of a little indie-game diorama.
 *
 * Everything is drawn in a 100×100 square (bottom-centred when the widget is not square, the sky and
 * ground running on to the edges), so the world reads the same at every size. Shapes are kept
 * chunky: at 1×1 a unit is only two or three pixels.
 */
object WorldPainter {
    // ---------------------------------------------------------------- light

    private class Light(s: WorldScene) {
        val e = s.sunElevation.toFloat()
        val cloud = sm(0.3f, 0.95f, s.cloud.toFloat())
        val rain = (1 - exp(-(s.rain + s.snowfall * 0.6) / 1.2)).toFloat()
        val night = 1 - sm(-12f, -2f, e)
        val day = sm(-1f, 14f, e)
        /** Direct sun: what casts shadows and lights one face of the mountains. */
        val sun = sm(-1f, 8f, e) * (1 - cloud * 0.9f) * (1 - rain * 0.6f)
        val golden = sm(-3f, 2f, e) * (1 - sm(6f, 16f, e)) * (1 - cloud)
        val moonVis = if (s.moonElevation > -2 && night > 0.2f) (1 - sm(0.55f, 0.95f, s.cloud.toFloat())) * (0.3f + 0.7f * Sky.illuminated(s.moonPhase).toFloat()) else 0f
        /** Overcast nights: low cloud faintly lit from below, a dim light from the whole sky. */
        val skyglow = night * cloud * (1 - 0.4f * rain)
        /** How much the night lights the crown's edge: a little always, more by moon or skyglow. */
        val rim = night * (0.3f + 0.5f * moonVis + 0.3f * skyglow).coerceAtMost(1f)
        /** Which side the light comes from: the sun by day, the moon by night, else the upper left. */
        val side = when {
            e > -4 -> s.sunSide.toFloat().coerceIn(-1f, 1f)
            moonVis > 0.1f -> s.moonSide.toFloat().coerceIn(-1f, 1f)
            else -> -0.5f
        }.let { if (abs(it) < 0.25f) (if (it < 0) -0.25f else 0.25f) else it }
        val skyTop: Int
        val skyHorizon: Int
        val r: Float
        val g: Float
        val b: Float
        val sat: Float

        init {
            val m = s.morning
            val stops = floatArrayOf(-16f, -9f, -4f, 1f, 7f, 20f)
            val clearTop = ramp(e, stops, intArrayOf(0xFF111836.toInt(), 0xFF1C2550.toInt(), 0xFF35407A.toInt(), 0xFF5A6FB4.toInt(), 0xFF6F9FDA.toInt(), 0xFF5FA8E6.toInt()))
            val clearHorizon = ramp(e, stops, intArrayOf(0xFF222C5C.toInt(), 0xFF3A3F78.toInt(), if (m) 0xFF9480BE.toInt() else 0xFFB57BA6.toInt(),
                if (m) 0xFFF7B39C.toInt() else 0xFFF8975F.toInt(), if (m) 0xFFFADDB8.toInt() else 0xFFF9C98A.toInt(), 0xFFC6E6F6.toInt()))
            val greyTop = ramp(e, floatArrayOf(-14f, -4f, 4f, 16f), intArrayOf(0xFF1B2030.toInt(), 0xFF3C4258.toInt(), 0xFF7A8597.toInt(), 0xFF97A3B3.toInt()))
            val greyHorizon = ramp(e, floatArrayOf(-14f, -4f, 4f, 16f), intArrayOf(0xFF2A2E40.toInt(), 0xFF575B70.toInt(), 0xFFA6AEBA.toInt(), 0xFFC4CBD3.toInt()))
            val dim = 1 - 0.2f * rain - if (s.thunder) 0.15f else 0f
            var top = shade(mix(clearTop, greyTop, cloud), dim)
            var horizon = shade(mix(clearHorizon, greyHorizon, cloud), dim)
            if (s.fog > 0) {
                val fog = ramp(e, floatArrayOf(-10f, 0f, 10f), intArrayOf(0xFF2A3040.toInt(), 0xFF8E93A0.toInt(), 0xFFC6CCD0.toInt()))
                top = mix(top, fog, 0.5f * s.fog.toFloat())
                horizon = mix(horizon, fog, 0.8f * s.fog.toFloat())
            }
            skyTop = top
            skyHorizon = horizon
            // Ambient colour of the land: moonlit blue, blue hour, sunrise pink or sunset gold, then day.
            val at = floatArrayOf(-12f, -4f, 1f, 7f, 18f)
            var ar = ramp1(e, at, floatArrayOf(0.4f, if (m) 0.58f else 0.62f, if (m) 0.98f else 1.04f, 1.04f, 1f))
            var ag = ramp1(e, at, floatArrayOf(0.47f, if (m) 0.6f else 0.56f, if (m) 0.84f else 0.8f, 0.96f, 1f))
            var ab = ramp1(e, at, floatArrayOf(0.74f, if (m) 0.8f else 0.74f, if (m) 0.86f else 0.7f, 0.86f, 1f))
            val avg = (ar + ag + ab) / 3
            val grey = cloud * 0.75f
            ar += (avg * 0.97f - ar) * grey
            ag += (avg * 0.99f - ag) * grey
            ab += (avg * 1.04f - ab) * grey
            val bright = (1 - 0.08f * cloud - 0.12f * rain) * (if (s.thunder) 0.85f else 1f)
            r = ar * bright + 0.05f * moonVis + 0.04f * skyglow + 0.02f * night
            g = ag * bright + 0.06f * moonVis + 0.04f * skyglow + 0.025f * night
            b = ab * bright + 0.08f * moonVis + 0.035f * skyglow + 0.03f * night
            sat = (1 - 0.22f * cloud) * (1 - 0.12f * rain) * (1 - 0.3f * night)
        }

        fun filter(strength: Float): ColorMatrixColorFilter {
            val matrix = ColorMatrix().apply { setSaturation(1 + (sat - 1) * strength) }
            matrix.postConcat(ColorMatrix(floatArrayOf(
                1 + (r - 1) * strength, 0f, 0f, 0f, 0f,
                0f, 1 + (g - 1) * strength, 0f, 0f, 0f,
                0f, 0f, 1 + (b - 1) * strength, 0f, 0f,
                0f, 0f, 0f, 1f, 0f)))
            return ColorMatrixColorFilter(matrix)
        }
    }

    private fun sm(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun ramp(x: Float, at: FloatArray, colors: IntArray): Int {
        if (x <= at.first()) return colors.first()
        for (i in 1 until at.size) if (x <= at[i]) return mix(colors[i - 1], colors[i], (x - at[i - 1]) / (at[i] - at[i - 1]))
        return colors.last()
    }

    private fun ramp1(x: Float, at: FloatArray, values: FloatArray): Float {
        if (x <= at.first()) return values.first()
        for (i in 1 until at.size) if (x <= at[i]) return values[i - 1] + (values[i] - values[i - 1]) * (x - at[i - 1]) / (at[i] - at[i - 1])
        return values.last()
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb((Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
    }

    private fun shade(c: Int, f: Float): Int = Color.argb(Color.alpha(c), (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255), (Color.blue(c) * f).toInt().coerceIn(0, 255))

    private fun alpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * Color.alpha(c)).toInt() shl 24)

    private fun Random.f(a: Float, b: Float) = a + nextFloat() * (b - a)

    // ---------------------------------------------------------------- the world's fixed layout

    private const val TREE_X = 36f
    private const val TREE_BASE = 71f
    private const val SHORE = 58f
    private const val ROCK_X = 66f
    private const val ROCK_Y = 77f
    private const val PUDDLE_X = 52f
    private const val PUDDLE_Y = 88.5f

    /** The clearing's edge along the lake: a low knoll under the tree. */
    private fun ground(x: Float) = 65f + 1.1f * sin(x * 0.07f + 1f) - 3.2f * exp(-((x - TREE_X) / 20f).pow(2))

    /** The round crown as clumps (x, y, r) from back to front; `at` is how bare the tree must be before each goes. */
    private class Clump(val x: Float, val y: Float, val r: Float, val at: Float, val tint: Float)

    private val clumps = listOf(
        Clump(28f, 29f, 10.5f, 0.55f, 0.2f), Clump(45f, 28f, 10.5f, 0.35f, 0.7f), Clump(36f, 20f, 10.5f, 0.15f, 0.45f),
        Clump(19f, 34f, 7f, 0.8f, 0.9f), Clump(53f, 34f, 7f, 0.7f, 0.1f), Clump(25f, 41f, 9.5f, 0.6f, 0.6f),
        Clump(47f, 41f, 10f, 0.45f, 0.3f), Clump(36f, 37f, 12.5f, 0.05f, 0.5f), Clump(36f, 46f, 8.5f, 0.25f, 0.8f),
    )

    /** Limbs as (x1, y1, x2, y2, w1, w2): what shows of the tree in winter, and between autumn's last leaves. */
    private val limbs = listOf(
        floatArrayOf(36f, 54f, 27f, 40f, 2.2f, 1.3f), floatArrayOf(27f, 40f, 20f, 32f, 1.3f, 0.6f), floatArrayOf(27f, 40f, 28f, 27f, 1.0f, 0.5f),
        floatArrayOf(36f, 53f, 46f, 40f, 2.2f, 1.3f), floatArrayOf(46f, 40f, 53f, 32f, 1.2f, 0.6f), floatArrayOf(46f, 40f, 45f, 26f, 1.0f, 0.5f),
        floatArrayOf(36f, 53f, 36f, 34f, 2.0f, 1.2f), floatArrayOf(36f, 34f, 32f, 22f, 1.0f, 0.5f), floatArrayOf(36f, 34f, 40f, 19f, 1.0f, 0.45f),
        floatArrayOf(20f, 32f, 16f, 29f, 0.5f, 0.3f), floatArrayOf(20f, 32f, 19f, 27f, 0.5f, 0.3f), floatArrayOf(28f, 27f, 25f, 23f, 0.5f, 0.3f),
        floatArrayOf(28f, 27f, 31f, 22f, 0.5f, 0.3f), floatArrayOf(53f, 32f, 57f, 29f, 0.5f, 0.3f), floatArrayOf(53f, 32f, 54f, 27f, 0.5f, 0.3f),
        floatArrayOf(45f, 26f, 48f, 22f, 0.5f, 0.3f), floatArrayOf(45f, 26f, 42f, 22f, 0.5f, 0.3f), floatArrayOf(32f, 22f, 29f, 18f, 0.5f, 0.3f),
        floatArrayOf(40f, 19f, 43f, 15f, 0.5f, 0.3f), floatArrayOf(40f, 19f, 37f, 14f, 0.5f, 0.3f), floatArrayOf(36f, 34f, 36f, 26f, 0.7f, 0.4f),
    )

    /** Pines in the clearing: base x, base y, height. */
    private val pines = listOf(floatArrayOf(84f, 72f, 25f), floatArrayOf(95f, 74f, 17f), floatArrayOf(5f, 69f, 12f))

    private val flowers = listOf(
        floatArrayOf(10f, 78f), floatArrayOf(16f, 86f), floatArrayOf(22f, 79f), floatArrayOf(27f, 92f), floatArrayOf(47f, 79f), floatArrayOf(60f, 94f),
        floatArrayOf(75f, 88f), floatArrayOf(82f, 95f), floatArrayOf(7f, 94f), floatArrayOf(41f, 91f), floatArrayOf(19f, 97f), floatArrayOf(88f, 84f),
    )
    private val mushrooms = listOf(floatArrayOf(27.5f, 71f, 0.05f), floatArrayOf(72f, 79.5f, 0.3f), floatArrayOf(45.5f, 72.5f, 0.45f),
        floatArrayOf(90f, 80f, 0.6f), floatArrayOf(30.5f, 72.5f, 0.75f))

    // ---------------------------------------------------------------- painting

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    /** Paint state for one picture: the scene, its light, and how far the content runs past the square. */
    private class Frame(val s: WorldScene, val light: Light, val frame: Int, val left: Float, val right: Float, val top: Float) {
        val swing = if (s.animated) (frame * 2 - 1).toFloat() else 0f
        val lean = sm(3f, 15f, s.wind.toFloat()) * s.windSide.toFloat()
        /** How far something at height y sways, for something rooted at `base` and `tall` high. */
        fun sway(y: Float, base: Float, tall: Float) = ((base - y) / tall).coerceIn(0f, 1f).pow(2) * (lean * 2.4f + swing * (0.25f + 0.9f * abs(lean)))
        val snowCover = sm(0.3f, 5f, s.snow.toFloat())
        val wintry = s.snow > 1 || s.season.dayOfYear >= 335 || s.season.dayOfYear <= 75
    }

    /**
     * One frame. Frame 1 is the same moment a few seconds on: branches swung the other way, rain and
     * snow further down, wings in another beat. `cornerRadius` is in pixels.
     */
    @Synchronized
    fun paint(scene: WorldScene, width: Int, height: Int, frame: Int, cornerRadius: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val side = min(width, height).toFloat()
        val u = side / 100f
        val ox = (width - side) / 2
        val oy = height - side
        val f = Frame(scene, Light(scene), frame, -ox / u, 100 + ox / u, -oy / u)
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(u, u)
        val layer = RectF(f.left, f.top, f.right, 100f)

        sky(canvas, f)
        canvas.saveLayer(layer, Paint().apply { colorFilter = f.light.filter(0.85f) })
        mountains(canvas, f)
        farShore(canvas, f)
        canvas.restore()
        lake(canvas, f)
        canvas.saveLayer(layer, Paint().apply { colorFilter = f.light.filter(1f) })
        clearing(canvas, f)
        canvas.restore()
        weather(canvas, f)
        canvas.restore()
        finish(canvas, width.toFloat(), height.toFloat(), f, cornerRadius)
        return bitmap
    }

    // ---------------------------------------------------------------- sky

    private fun sky(canvas: Canvas, f: Frame) {
        val s = f.s
        val light = f.light
        fill.alpha = 255
        fill.shader = LinearGradient(0f, f.top, 0f, 60f, intArrayOf(light.skyTop, mix(light.skyTop, light.skyHorizon, 0.5f), light.skyHorizon),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(f.left, f.top, f.right, 66f, fill)
        fill.shader = null

        val stars = light.night * (1 - sm(0.2f, 0.65f, s.cloud.toFloat())) * (1 - 0.4f * light.moonVis)
        if (stars > 0.02f) {
            val rnd = Random(41)
            val twinkle = Random(s.slot * 7 + f.frame)
            repeat(44) {
                val x = f.left + rnd.nextFloat() * (f.right - f.left)
                val y = f.top + rnd.nextFloat().pow(1.4f) * (52f - f.top)
                val size = rnd.f(0.3f, 0.75f)
                fill.color = alpha(0xFFF4F1FF.toInt(), stars * rnd.f(0.4f, 1f) * twinkle.f(0.7f, 1f))
                canvas.drawCircle(x, y, size, fill)
                // A few bright ones get a tiny cross.
                if (size > 0.68f) {
                    stroke.color = alpha(0xFFF4F1FF.toInt(), stars * 0.5f)
                    stroke.strokeWidth = 0.18f
                    canvas.drawLine(x - 1.4f, y, x + 1.4f, y, stroke)
                    canvas.drawLine(x, y - 1.4f, x, y + 1.4f, stroke)
                }
            }
        }
        aurora(canvas, f)
        if (s.visitor == Visitor.SHOOTING_STAR && stars > 0.1f && (f.frame == 1 || !s.animated)) shootingStar(canvas, f)
        if (s.sights.meteors > 0 && (f.frame == 1 || !s.animated)) meteors(canvas, f)
        if (light.moonVis > 0.02f) moon(canvas, f)
        sun(canvas, f)
        clouds(canvas, f)
    }

    private fun sun(canvas: Canvas, f: Frame) {
        val s = f.s
        val light = f.light
        if (s.sunElevation < -4) return
        val x = 50f + s.sunSide.toFloat() * 40f
        val y = 57f - (s.sunElevation.toFloat() / 38f).coerceIn(-0.1f, 1f) * 47f
        val warm = mix(0xFFFFF4D6.toInt(), if (s.morning) 0xFFFFC7A0.toInt() else 0xFFFFB067.toInt(), light.golden + (1 - light.day) * 0.8f)
        val glow = 0.55f * (1 - light.cloud * 0.75f) * sm(-4f, 1f, s.sunElevation.toFloat())
        fill.alpha = 255
        fill.shader = RadialGradient(x, y, 55f, intArrayOf(alpha(warm, glow), alpha(warm, glow * 0.3f), alpha(warm, 0f)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(f.left, f.top, f.right, 66f, fill)
        fill.shader = null
        val disc = (1 - sm(0.45f, 0.9f, s.cloud.toFloat())) * (1 - f.light.rain)
        if (disc > 0.02f) {
            fill.color = alpha(warm, 0.35f * disc)
            canvas.drawCircle(x, y, 6.5f, fill)
            fill.color = alpha(mix(0xFFFFFBEA.toInt(), warm, 0.5f), disc)
            canvas.drawCircle(x, y, 4.4f, fill)
        }
    }

    private fun moon(canvas: Canvas, f: Frame) {
        val s = f.s
        val vis = f.light.moonVis
        val r = 3.3f
        val mx = 50f + s.moonSide.toFloat() * 34f
        val my = 46f - (s.moonElevation.toFloat() / 40f).coerceIn(0f, 1f) * 34f
        fill.alpha = 255
        fill.shader = RadialGradient(mx, my, 16f, intArrayOf(alpha(0xFFDCE2FF.toInt(), 0.28f * vis), alpha(0xFFDCE2FF.toInt(), 0f)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(mx, my, 16f, fill)
        fill.shader = null
        fill.color = alpha(0xFF444C78.toInt(), 0.45f * vis)
        canvas.drawCircle(mx, my, r, fill)
        val phase = s.moonPhase.toFloat()
        val waxing = phase < 0.5f
        val k = r * cos(2 * PI.toFloat() * phase)
        val path = Path()
        path.moveTo(mx, my - r)
        path.arcTo(RectF(mx - r, my - r, mx + r, my + r), -90f, if (waxing) 180f else -180f, false)
        val crescent = k > 0
        path.arcTo(RectF(mx - abs(k), my - r, mx + abs(k), my + r), 90f, if (waxing == crescent) -180f else 180f, false)
        path.close()
        fill.color = alpha(0xFFFFF6DC.toInt(), min(1f, vis * 1.5f))
        canvas.drawPath(path, fill)
    }

    /**
     * The northern lights behind the mountains, when NOAA's outlook, the dark and the cloud allow it:
     * a soft green arc with rays, a faint red top only in a strong display. Kept dim, as in a photo
     * taken without a long exposure.
     */
    private fun aurora(canvas: Canvas, f: Frame) {
        val shown = f.s.sights.aurora.toFloat()
        if (shown < 0.03f) return
        // A moderate display should catch the eye at a glance; a strong one stays short of neon.
        val a = shown.pow(0.6f)
        val base = 38f
        val reach = 16f + 26f * a
        val green = 0xFF86E2AE.toInt()
        val red = 0xFFC77E96.toInt()
        fill.alpha = 255
        fill.shader = LinearGradient(0f, base - reach * 0.6f, 0f, base + 16f, intArrayOf(alpha(green, 0f), alpha(green, 0.4f * a), alpha(green, 0.12f * a), alpha(green, 0f)),
            floatArrayOf(0f, 0.55f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(f.left, base - reach * 0.6f, f.right, base + 16f, fill)
        val rnd = Random(97)
        fill.maskFilter = BlurMaskFilter(1.3f, BlurMaskFilter.Blur.NORMAL)
        val rays = 20
        val span = f.right - f.left + 10f
        repeat(rays) { i ->
            val drift = (f.frame * 2 - 1) * rnd.f(0.5f, 1.6f)
            val x = f.left - 5f + (i + rnd.nextFloat()) / rays * span + drift
            val y = base + sin(x * 0.05f + 1.1f) * 3f
            val h = reach * rnd.f(0.4f, 1f) * (0.85f + 0.15f * (f.frame * 2 - 1) * sin(i * 2.1f))
            val k = rnd.f(0.3f, 1f) * a
            val crown = if (a > 0.55f) alpha(red, 0.4f * k * sm(0.55f, 0.9f, a)) else alpha(green, 0f)
            val rw = rnd.f(1.6f, 3.2f)
            fill.shader = LinearGradient(0f, y + 2f, 0f, y - h, intArrayOf(alpha(green, 0f), alpha(green, 0.75f * k), alpha(green, 0.24f * k), crown, alpha(crown, 0f)),
                floatArrayOf(0f, 0.12f, 0.5f, 0.82f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(x - rw / 2, y - h, x + rw / 2, y + 2f, fill)
        }
        fill.maskFilter = null
        fill.shader = null
    }

    /** A shower's meteors, each streaking away from the radiant. */
    private fun meteors(canvas: Canvas, f: Frame) {
        val sights = f.s.sights
        val rnd = Random(f.s.slot * 19 + 7)
        val rx = 50f + sights.radiantSide.toFloat() * 60f
        val ry = 30f - 40f * (sights.radiantElevation.toFloat() / 90f).coerceIn(0f, 1f)
        repeat(sights.meteors) {
            val x = rnd.f(10f, 90f)
            val y = rnd.f(2f, 24f) + f.top * 0.5f
            var dx = x - rx
            var dy = y - ry
            val len = max(0.001f, sqrt(dx * dx + dy * dy))
            dx /= len
            dy /= len
            val streak = rnd.f(11f, 17f)
            stroke.alpha = 255
            stroke.shader = LinearGradient(x, y, x + dx * streak, y + dy * streak, intArrayOf(0x00FFFFFF, alpha(0xFFFFF8E8.toInt(), 0.9f)), null, Shader.TileMode.CLAMP)
            stroke.strokeWidth = 0.5f
            canvas.drawLine(x, y, x + dx * streak, y + dy * streak, stroke)
            stroke.shader = null
            fill.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(x + dx * streak, y + dy * streak, 0.55f, fill)
        }
    }

    private fun shootingStar(canvas: Canvas, f: Frame) {
        val rnd = Random(f.s.slot * 13 + 5)
        val x = rnd.f(8f, 50f)
        val y = rnd.f(4f, 22f) + f.top * 0.5f
        val dx = rnd.f(14f, 20f)
        val dy = dx * rnd.f(0.25f, 0.45f)
        stroke.alpha = 255
        stroke.shader = LinearGradient(x, y, x + dx, y + dy, intArrayOf(0x00FFFFFF, alpha(0xFFFFF8E8.toInt(), 0.95f)), null, Shader.TileMode.CLAMP)
        stroke.strokeWidth = 0.55f
        canvas.drawLine(x, y, x + dx, y + dy, stroke)
        stroke.shader = null
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x + dx, y + dy, 0.6f, fill)
    }

    /** Puffy clouds with flat bottoms, drifting a little further on each refresh. */
    private fun clouds(canvas: Canvas, f: Frame) {
        val s = f.s
        val light = f.light
        val cloud = s.cloud.toFloat()
        if (cloud < 0.05f) return
        val span = (f.right - f.left) + 60f
        val drift = ((s.slot * (1.2 + s.wind * 0.35) + f.frame * (0.5 + s.wind * 0.08)) % span).toFloat()
        val dim = 0.25f + 0.75f * (1 - light.night)
        val wet = light.rain
        val top = mix(mix(light.skyHorizon, 0xFFFFFFFF.toInt(), (0.6f * light.day + 0.15f) * dim), shade(0xFF8D95A6.toInt(), dim), wet * 0.7f)
        val under = mix(mix(light.skyTop, shade(0xFF7480A0.toInt(), dim), 0.5f), shade(0xFF5B6273.toInt(), dim), wet * 0.6f + if (s.thunder) 0.3f else 0f)
        // Overcast: a soft grey deck over the whole sky first.
        if (cloud > 0.7f) {
            fill.color = alpha(mix(under, top, 0.45f), (cloud - 0.7f) / 0.3f * 0.75f)
            canvas.drawRect(f.left, f.top, f.right, 50f, fill)
        }
        val rnd = Random(97)
        repeat(7) { i ->
            val rank = rnd.nextFloat()
            val x0 = rnd.nextFloat() * span
            val y = f.top * 0.6f + rnd.f(8f, 34f)
            val size = rnd.f(5f, 9f) * (0.8f + 0.4f * cloud)
            val shown = ((cloud * 1.25f - rank) * 4f).coerceIn(0f, 1f) * (0.75f + 0.25f * cloud)
            val x = ((x0 + drift * (0.6f + 0.6f * rank)) % span) + f.left - 30f
            if (shown < 0.02f) return@repeat
            val path = Path()
            val lobes = 3 + i % 3
            for (k in 0 until lobes) {
                val t = k / (lobes - 1f) - 0.5f
                val r = size * (0.62f + 0.38f * cos(t * 2.4f)) * (0.85f + 0.3f * ((i * 7 + k * 3) % 5) / 5f)
                path.addCircle(x + t * size * 2.6f, y - r * 0.55f, r, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipRect(x - size * 3f, y - size * 3f, x + size * 3f, y + size * 0.12f)
            fill.color = alpha(under, shown)
            canvas.drawPath(path, fill)
            canvas.translate(-size * 0.12f, -size * 0.32f)
            fill.color = alpha(top, shown)
            canvas.drawPath(path, fill)
            canvas.restore()
        }
    }

    // ---------------------------------------------------------------- the land beyond the lake

    /** Three mountains, a lit face and a shaded face each, with snow on top that grows in winter. */
    private fun mountains(canvas: Canvas, f: Frame) {
        val s = f.s
        val haze = f.light.skyHorizon
        val rock = 0xFF6B7A9C.toInt()
        // Snow line: a little left all summer, the upper half in winter, almost all of it after snow at home.
        val d = s.season.dayOfYear
        val cold = 1 - Season.smooth(140.0, 175.0, d.toDouble()).toFloat() * (1 - Season.smooth(265.0, 300.0, d.toDouble()).toFloat())
        val cap = (0.16f + 0.34f * cold + 0.25f * f.snowCover).coerceAtMost(0.8f)
        mountain(canvas, f, -24f, 14f, 33f, 50f, 0.12f, mix(rock, haze, 0.55f), cap)
        mountain(canvas, f, 80f, 96f, 37f, 120f, 0.6f, mix(rock, haze, 0.5f), cap * 0.9f)
        mountain(canvas, f, 34f, 70f, 22f, 112f, 0.35f, mix(rock, haze, 0.28f), cap)
    }

    private fun mountain(canvas: Canvas, f: Frame, x0: Float, peak: Float, peakY: Float, x1: Float, seed: Float, tone: Int, cap: Float) {
        val base = SHORE + 1f
        val tall = base - peakY
        val outline = Path().apply {
            moveTo(x0, base)
            // Rounded shoulders and a slightly blunt top, like worn old fjells.
            quadTo(x0 + (peak - x0) * 0.55f, peakY + tall * (0.35f + 0.1f * seed), peak - 2.2f, peakY + 1.2f)
            quadTo(peak, peakY - 0.6f, peak + 2.2f, peakY + 1.0f)
            quadTo(peak + (x1 - peak) * 0.4f, peakY + tall * (0.3f + 0.1f * seed), x1, base)
            close()
        }
        val lit = f.light.side
        val litTone = mix(tone, 0xFFFFF1DC.toInt(), 0.18f + 0.22f * f.light.sun)
        val darkTone = mix(tone, 0xFF2E3558.toInt(), 0.25f + 0.2f * f.light.sun)
        fill.color = darkTone
        canvas.drawPath(outline, fill)
        // The ridge runs down from the peak, kinked; the face towards the light is lit.
        canvas.save()
        canvas.clipPath(outline)
        val ridge = Path().apply {
            moveTo(peak, peakY - 2f)
            lineTo(peak + 1.5f * lit, peakY + tall * 0.35f)
            lineTo(peak - 1.5f * lit, peakY + tall * 0.65f)
            lineTo(peak + 3f * lit, base + 1f)
            lineTo(if (lit < 0) x0 - 10f else x1 + 10f, base + 1f)
            lineTo(if (lit < 0) x0 - 10f else x1 + 10f, peakY - 5f)
            close()
        }
        fill.color = litTone
        canvas.drawPath(ridge, fill)
        // Snow from the peak down to a ragged line, two-toned like the rock.
        val line = peakY + tall * cap
        val snow = Path().apply {
            moveTo(x0 - 5f, peakY - 5f)
            lineTo(x1 + 5f, peakY - 5f)
            lineTo(x1 + 5f, line)
            var x = x1 + 5f
            var k = 0
            while (x > x0 - 5f) {
                x -= 2.6f
                lineTo(x, line + (if (k % 2 == 0) 2.2f else -0.6f) + ((k * 37) % 7) * 0.35f)
                k++
            }
            close()
        }
        fill.color = mix(0xFFC9D2EA.toInt(), f.light.skyHorizon, 0.2f)
        canvas.drawPath(snow, fill)
        canvas.clipPath(ridge)
        fill.color = mix(0xFFFFFFFF.toInt(), 0xFFFFE9D0.toInt(), f.light.golden)
        canvas.drawPath(snow, fill)
        canvas.restore()
    }

    /** The far shore: a dark band of spruce and pine with a few taller ones. */
    private fun farShore(canvas: Canvas, f: Frame) {
        val tone = mix(0xFF2F5A4F.toInt(), f.light.skyHorizon, 0.32f)
        val snowy = f.snowCover
        fill.color = tone
        canvas.drawRect(f.left, SHORE - 1.6f, f.right, SHORE + 0.6f, fill)
        val rnd = Random(23)
        var x = f.left - 2f
        while (x < f.right + 2f) {
            val h = rnd.f(2.6f, 5.6f) * if (rnd.nextFloat() < 0.15f) 1.4f else 1f
            val w = h * 0.36f
            val path = Path().apply {
                moveTo(x, SHORE - 1f - h)
                lineTo(x + w, SHORE + 0.2f)
                lineTo(x - w, SHORE + 0.2f)
                close()
            }
            fill.color = mix(tone, 0xFF1F3B3A.toInt(), rnd.nextFloat() * 0.4f)
            canvas.drawPath(path, fill)
            if (snowy > 0.2f) {
                fill.color = alpha(0xFFE6ECF6.toInt(), snowy * 0.7f)
                canvas.drawCircle(x, SHORE - 1f - h * 0.55f, w * 0.35f, fill)
            }
            x += rnd.f(1.4f, 2.8f)
        }
    }

    /** The lake mirrors the sky and the far shore; rain rings it, wind ruffles it, winter freezes it. */
    private fun lake(canvas: Canvas, f: Frame) {
        val s = f.s
        val light = f.light
        val top = SHORE + 0.5f
        val bottom = 68f
        if (s.lakeIce) {
            fill.alpha = 255
            fill.shader = LinearGradient(0f, top, 0f, bottom, intArrayOf(mix(0xFFC8D6E6.toInt(), light.skyHorizon, 0.35f), mix(0xFFA9BCD3.toInt(), light.skyTop, 0.3f)), null, Shader.TileMode.CLAMP)
            canvas.drawRect(f.left, top, f.right, bottom, fill)
            fill.shader = null
            // Snow lies on the ice in drifts, with the blue showing through.
            val rnd = Random(61)
            fill.color = alpha(shade(0xFFF2F6FB.toInt(), 0.55f + 0.45f * light.r), 0.4f + 0.5f * f.snowCover)
            repeat(6) { canvas.drawOval(RectF(f.left + rnd.nextFloat() * 110f, top + rnd.f(1f, 6f), f.left + rnd.nextFloat() * 110f + rnd.f(8f, 20f), top + rnd.f(2f, 8f) + 1.5f), fill) }
            return
        }
        val deep = 0xFF2D5C7A.toInt()
        val near = mix(light.skyTop, deep, 0.35f)
        val far = mix(light.skyHorizon, deep, 0.2f)
        fill.alpha = 255
        fill.shader = LinearGradient(0f, top, 0f, bottom, intArrayOf(far, near), null, Shader.TileMode.CLAMP)
        canvas.drawRect(f.left, top, f.right, bottom, fill)
        fill.shader = null
        // The far shore's forest upside down, softened.
        val reflect = alpha(shade(mix(0xFF2F5A4F.toInt(), light.skyHorizon, 0.3f), light.r * 0.9f), 0.45f * (1 - sm(4f, 10f, s.wind.toFloat())))
        fill.color = reflect
        canvas.drawRect(f.left, top, f.right, top + 2.2f, fill)
        // Sun or moon glitter, and slow light streaks that move between frames.
        val glitterX = when {
            s.sunElevation > -2 && light.cloud < 0.8f -> 50f + s.sunSide.toFloat() * 40f
            light.moonVis > 0.2f -> 50f + s.moonSide.toFloat() * 34f
            else -> Float.NaN
        }
        val rnd = Random(29L + f.frame)
        val ruffle = sm(2f, 10f, s.wind.toFloat())
        stroke.strokeWidth = 0.35f
        repeat(9 + (ruffle * 8).toInt()) {
            val y = rnd.f(top + 2.5f, bottom - 1f)
            val x = f.left + rnd.nextFloat() * (f.right - f.left)
            val len = rnd.f(2f, 6f) * (1 - 0.4f * ruffle)
            stroke.color = alpha(mix(light.skyHorizon, 0xFFFFFFFF.toInt(), 0.5f), 0.25f + 0.2f * ruffle)
            canvas.drawLine(x, y, x + len, y, stroke)
        }
        if (!glitterX.isNaN()) {
            val glow = if (s.sunElevation > -2) mix(0xFFFFF0C8.toInt(), 0xFFFFB46A.toInt(), light.golden) else 0xFFF6F2E2.toInt()
            repeat(6) { k ->
                val y = top + 1.5f + k * 1.4f
                val w = 1.5f + k * 0.5f + rnd.nextFloat()
                stroke.color = alpha(glow, 0.65f - k * 0.07f)
                canvas.drawLine(glitterX - w + rnd.f(-0.6f, 0.6f), y, glitterX + w + rnd.f(-0.6f, 0.6f), y, stroke)
            }
        }
        if (s.rain > 0.05) rings(canvas, Random(71L + f.frame * 3), f.left, top + 2f, f.right, bottom - 1f, 4 + (s.rain * 3).toInt(), light, 2.2f)
    }

    private fun rings(canvas: Canvas, rnd: Random, x0: Float, y0: Float, x1: Float, y1: Float, count: Int, light: Light, size: Float) {
        stroke.strokeWidth = 0.28f
        repeat(count.coerceAtMost(9)) {
            val x = rnd.f(x0, x1)
            val y = rnd.f(y0, y1)
            val r = rnd.f(0.4f, 1f) * size
            stroke.color = alpha(mix(light.skyHorizon, 0xFFFFFFFF.toInt(), 0.6f), 0.6f * (1.1f - r / size))
            canvas.drawOval(RectF(x - r, y - r * 0.35f, x + r, y + r * 0.35f), stroke)
        }
    }

    // ---------------------------------------------------------------- the clearing

    private fun clearing(canvas: Canvas, f: Frame) {
        val s = f.s
        val d = s.season.dayOfYear.toDouble()
        val green = (Season.smooth(95.0, 135.0, d) * (1 - Season.smooth(258.0, 300.0, d))).toFloat()
        val lush = (Season.smooth(135.0, 165.0, d) * (1 - Season.smooth(225.0, 260.0, d))).toFloat()
        val grassTop = mix(mix(0xFFA79E5C.toInt(), 0xFFA6D66A.toInt(), green), 0xFF74BE52.toInt(), lush)
        val grassBottom = mix(mix(0xFF6F6A3E.toInt(), 0xFF5DA247.toInt(), green), 0xFF3F8A3C.toInt(), lush)
        val top = mix(grassTop, 0xFFF3F6FB.toInt(), f.snowCover)
        val bottom = mix(grassBottom, 0xFFD5DFEE.toInt(), f.snowCover)
        val edge = Path().apply {
            moveTo(f.left, ground(f.left))
            var x = f.left
            while (x < f.right) { x += 2f; lineTo(x, ground(x)) }
            lineTo(f.right, 101f)
            lineTo(f.left, 101f)
            close()
        }
        fill.alpha = 255
        fill.shader = LinearGradient(0f, 60f, 0f, 100f, intArrayOf(top, bottom), null, Shader.TileMode.CLAMP)
        canvas.drawPath(edge, fill)
        fill.shader = null
        // The bank: a dark wet line at the water, then a lighter lip of grass.
        val shore = Path().apply {
            moveTo(f.left, ground(f.left))
            var x = f.left
            while (x < f.right) { x += 2f; lineTo(x, ground(x)) }
        }
        stroke.color = alpha(0xFF24402E.toInt(), 0.45f * (1 - f.snowCover * 0.6f))
        stroke.strokeWidth = 0.7f
        canvas.drawPath(shore, stroke)
        canvas.save()
        canvas.translate(0f, 0.9f)
        stroke.color = alpha(mix(top, 0xFFFFFFFF.toInt(), 0.3f), 0.8f)
        stroke.strokeWidth = 1f
        canvas.drawPath(shore, stroke)
        canvas.restore()
        // Light falls across the clearing from the sun's side.
        val lx = 50f + f.light.side * 50f
        fill.alpha = 255
        fill.shader = RadialGradient(lx, 62f, 70f, intArrayOf(alpha(0xFFFFF4D8.toInt(), 0.22f * f.light.sun), 0x00FFF4D8), null, Shader.TileMode.CLAMP)
        canvas.drawPath(edge, fill)
        fill.shader = null

        shadows(canvas, f)
        litter(canvas, f)
        puddle(canvas, f)
        tracks(canvas, f)
        if (s.visitor == Visitor.RABBIT) hare(canvas, f)
        rock(canvas, f)
        for ((i, m) in mushrooms.withIndex()) if (s.fungi > m[2] || (i == 0 && autumnFungus(s))) mushroom(canvas, f, m[0], m[1], i, if (s.fungi > m[2]) sm(m[2], m[2] + 0.15f, s.fungi.toFloat()) else 1f)
        for (p in pines) pine(canvas, f, p[0], p[1], p[2])
        tree(canvas, f)
        if (s.visitor == Visitor.SQUIRREL) squirrel(canvas, f)
        flowers(canvas, f)
        grass(canvas, f)
        when (s.visitor) {
            Visitor.FOX -> fox(canvas, f)
            Visitor.BIRD -> perched(canvas, f)
            Visitor.OWL -> owl(canvas, f)
            Visitor.BUTTERFLY -> butterfly(canvas, f)
            else -> {}
        }
    }

    /** One fly agaric by the tree all autumn, whatever the rain did. */
    private fun autumnFungus(s: WorldScene) = s.season.dayOfYear in 235..305 && s.snow < 1

    private fun shadows(canvas: Canvas, f: Frame) {
        val s = f.s
        // Soft contact shade under everything that stands, then cast shadows away from a low sun.
        fill.color = alpha(0xFF1E3A26.toInt(), 0.22f * (1 - 0.4f * f.snowCover))
        canvas.drawOval(RectF(TREE_X - 13f, TREE_BASE - 1.8f, TREE_X + 13f, TREE_BASE + 2.4f), fill)
        val sun = f.light.sun
        if (sun < 0.05f) return
        val reach = 6f + 22f * (1 - sm(5f, 45f, s.sunElevation.toFloat()))
        val dir = -f.light.side
        fill.color = alpha(0xFF203C36.toInt(), 0.26f * sun)
        canvas.drawOval(RectF(TREE_X + dir * reach - 13f, TREE_BASE - 0.5f, TREE_X + dir * reach + 13f, TREE_BASE + 4.5f), fill)
        for (p in pines) {
            val px = p[0] + dir * reach * p[2] / 40f
            canvas.drawOval(RectF(px - 4f, p[1] - 0.5f, px + 4f, p[1] + 2f), fill)
        }
        canvas.drawOval(RectF(ROCK_X + dir * 3f - 6f, ROCK_Y + 1f, ROCK_X + dir * 3f + 6f, ROCK_Y + 3f), fill)
    }

    /** Fallen leaves round the tree from autumn into winter, darkening as they age, under any snow. */
    private fun litter(canvas: Canvas, f: Frame) {
        val s = f.s
        val count = (s.season.litter * 34).toInt() - 3
        if (count <= 0 || f.snowCover > 0.85f) return
        val rnd = Random(53)
        val shown = 1 - f.snowCover
        repeat(count) {
            val a = rnd.nextFloat() * 2 * PI.toFloat()
            val dist = sqrt(rnd.nextFloat())
            val x = TREE_X + cos(a) * dist * 22f
            val y = TREE_BASE + 2f + sin(a) * dist * 6f + rnd.f(0f, 6f)
            val c = when (rnd.nextInt(3)) { 0 -> 0xFFE9A93A.toInt(); 1 -> 0xFFD8702E.toInt(); else -> 0xFFC4502E.toInt() }
            fill.color = alpha(mix(c, 0xFF7A5A3A.toInt(), s.season.litterAge.toFloat() * 0.7f), shown)
            canvas.save()
            canvas.rotate(rnd.f(0f, 180f), x, y)
            canvas.drawOval(RectF(x - 0.9f, y - 0.45f, x + 0.9f, y + 0.45f), fill)
            canvas.restore()
        }
    }

    private fun puddle(canvas: Canvas, f: Frame) {
        val s = f.s
        val p = s.puddle.toFloat()
        if (p < 0.08f) return
        val rx = 4f + 7.5f * p
        val ry = 1.3f + 1.9f * p
        val rect = RectF(PUDDLE_X - rx, PUDDLE_Y - ry, PUDDLE_X + rx, PUDDLE_Y + ry)
        fill.color = 0x33402C1E
        canvas.drawOval(RectF(rect.left - 0.6f, rect.top - 0.2f, rect.right + 0.6f, rect.bottom + 0.5f), fill)
        if (s.frozen) {
            fill.color = 0xFFDCE8F4.toInt()
            canvas.drawOval(rect, fill)
            stroke.color = 0xAAFFFFFF.toInt()
            stroke.strokeWidth = 0.25f
            canvas.drawLine(PUDDLE_X - rx * 0.5f, PUDDLE_Y - ry * 0.2f, PUDDLE_X + rx * 0.3f, PUDDLE_Y + ry * 0.3f, stroke)
            return
        }
        // Puddles show the sky, lighter towards the far edge.
        fill.alpha = 255
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, intArrayOf(mix(f.light.skyHorizon, 0xFFFFFFFF.toInt(), 0.2f), f.light.skyTop), null, Shader.TileMode.CLAMP)
        canvas.drawOval(rect, fill)
        fill.shader = null
        stroke.color = 0x88FFFFFF.toInt()
        stroke.strokeWidth = 0.3f
        canvas.drawLine(PUDDLE_X - rx * 0.45f, PUDDLE_Y - ry * 0.35f, PUDDLE_X + rx * 0.1f, PUDDLE_Y - ry * 0.35f, stroke)
        if (s.rain > 0.05) {
            canvas.save()
            canvas.clipPath(Path().apply { addOval(rect, Path.Direction.CW) })
            rings(canvas, Random(19L + f.frame * 7), rect.left, rect.top, rect.right, rect.bottom, 2 + (s.rain * 1.5).toInt(), f.light, 1.4f)
            canvas.restore()
        }
    }

    /** A trail across the fresh snow: a fox's line of prints, a hare's hops, or a bird's little forks. */
    private fun tracks(canvas: Canvas, f: Frame) {
        val s = f.s
        val kind = s.tracks ?: return
        val fade = (0.55f - 0.25f * sm(0f, 36f, s.tracksAge.toFloat())) * f.snowCover
        if (fade < 0.05f) return
        fill.color = alpha(0xFF8696B8.toInt(), fade)
        stroke.color = alpha(0xFF8696B8.toInt(), fade)
        stroke.strokeWidth = 0.3f
        val step = when (kind) { Tracks.FOX -> 2.6f; Tracks.HARE -> 6.5f; Tracks.BIRD -> 1.8f }
        var x = f.left - 2f
        var i = 0
        while (x < f.right + 2f) {
            val y = 90f - (x / 100f) * 9f + 2.2f * sin(x * 0.08f)
            val side = if (i % 2 == 0) -0.7f else 0.7f
            when (kind) {
                Tracks.FOX -> canvas.drawOval(RectF(x - 0.45f, y + side * 0.3f - 0.32f, x + 0.45f, y + side * 0.3f + 0.32f), fill)
                Tracks.HARE -> {
                    canvas.drawOval(RectF(x - 0.4f, y - 1.1f, x + 0.8f, y - 0.5f), fill)
                    canvas.drawOval(RectF(x - 0.4f, y + 0.5f, x + 0.8f, y + 1.1f), fill)
                    canvas.drawCircle(x - 1.8f, y - 0.1f, 0.35f, fill)
                    canvas.drawCircle(x - 2.6f, y + 0.2f, 0.35f, fill)
                }
                Tracks.BIRD -> {
                    val by = y + side
                    canvas.drawLine(x - 0.6f, by, x + 0.3f, by, stroke)
                    canvas.drawLine(x + 0.3f, by, x + 0.8f, by - 0.4f, stroke)
                    canvas.drawLine(x + 0.3f, by, x + 0.8f, by + 0.4f, stroke)
                }
            }
            x += step
            i++
        }
    }

    private fun rock(canvas: Canvas, f: Frame) {
        val path = Path().apply {
            moveTo(ROCK_X - 6.5f, ROCK_Y + 1.5f)
            cubicTo(ROCK_X - 7f, ROCK_Y - 3.5f, ROCK_X - 3f, ROCK_Y - 6.5f, ROCK_X + 0.5f, ROCK_Y - 6f)
            cubicTo(ROCK_X + 4.5f, ROCK_Y - 5.6f, ROCK_X + 6.5f, ROCK_Y - 2.5f, ROCK_X + 6f, ROCK_Y + 1.5f)
            close()
        }
        fill.color = 0xFF7D8494.toInt()
        canvas.drawPath(path, fill)
        canvas.save()
        canvas.clipPath(path)
        fill.color = 0xFFA6ACB8.toInt()
        canvas.drawCircle(ROCK_X + f.light.side * 2.4f, ROCK_Y - 5f, 5.2f, fill)
        fill.color = 0xFF62697A.toInt()
        canvas.drawOval(RectF(ROCK_X - 8f, ROCK_Y - 0.2f, ROCK_X + 8f, ROCK_Y + 4f), fill)
        // Moss on the shaded side in the green months, snow on top in winter.
        if (f.s.season.leaves > 0.3 && f.snowCover < 0.5) {
            fill.color = 0xCC6E9A48.toInt()
            canvas.drawCircle(ROCK_X - f.light.side * 5f, ROCK_Y - 1f, 2.6f, fill)
        }
        if (f.snowCover > 0.05f) {
            fill.color = alpha(0xFFF6F9FD.toInt(), f.snowCover)
            canvas.drawOval(RectF(ROCK_X - 6f, ROCK_Y - 7.5f, ROCK_X + 5.5f, ROCK_Y - 6f + 2.6f * f.snowCover + 0.6f), fill)
        }
        canvas.restore()
        fill.color = 0xFF8A909E.toInt()
        canvas.drawOval(RectF(ROCK_X - 10.5f, ROCK_Y + 0.2f, ROCK_X - 7.5f, ROCK_Y + 2f), fill)
    }

    /** A pine in tiers, lit on one side; snow settles on every tier. */
    private fun pine(canvas: Canvas, f: Frame, x: Float, base: Float, tall: Float) {
        val s = f.s
        fill.color = 0xFF6A4A36.toInt()
        canvas.drawRect(x - tall * 0.04f, base - tall * 0.2f, x + tall * 0.04f, base + 0.3f, fill)
        val tiers = 4
        val snow = max(f.snowCover * 0.8f, s.treeSnow.toFloat())
        for (k in 0 until tiers) {
            val t0 = 0.14f + k * 0.2f
            val bottomY = base - tall * t0
            val topY = base - tall * (t0 + 0.38f).coerceAtMost(1f)
            val half = tall * (0.3f - k * 0.055f)
            val dx = f.sway(topY, base, tall)
            val bx = f.sway(bottomY, base, tall)
            val tier = Path().apply {
                moveTo(x + dx, topY)
                quadTo(x + bx + half * 0.5f, bottomY - tall * 0.08f, x + bx + half, bottomY)
                quadTo(x + bx, bottomY + tall * 0.035f, x + bx - half, bottomY)
                quadTo(x + bx - half * 0.5f, bottomY - tall * 0.08f, x + dx, topY)
                close()
            }
            fill.color = 0xFF2E6A4E.toInt()
            canvas.drawPath(tier, fill)
            canvas.save()
            canvas.clipPath(tier)
            fill.color = 0xFF4C8C5C.toInt()
            canvas.drawRect(if (f.light.side < 0) x - half - 2f else x + bx + half * 0.1f, topY, if (f.light.side < 0) x + bx - half * 0.1f else x + half + 2f, bottomY + 2f, fill)
            if (snow > 0.05f) {
                fill.color = alpha(0xFFF6F9FD.toInt(), min(1f, snow * 1.3f))
                val cap = tall * (0.05f + 0.06f * snow)
                canvas.drawPath(Path().apply {
                    moveTo(x + dx, topY - 1f)
                    lineTo(x + bx + half + 1f, bottomY - cap * 0.6f)
                    lineTo(x + bx + half * 0.3f, bottomY - cap)
                    lineTo(x + bx - half * 0.2f, bottomY - cap * 0.4f)
                    lineTo(x + bx - half - 1f, bottomY - cap * 0.8f)
                    close()
                }, fill)
            }
            canvas.restore()
        }
    }

    // ---------------------------------------------------------------- the tree

    private fun leafColor(season: Season, tint: Float): Int {
        val fresh = mix(0xFF8CCB5A.toInt(), 0xFFB4E07A.toInt(), tint * 0.6f)
        val summer = mix(0xFF4F9A48.toInt(), 0xFF68AE4E.toInt(), tint)
        var c = mix(summer, fresh, season.fresh.toFloat())
        val turn = season.turn.toFloat()
        // Each clump turns in its own time: yellow first, then orange and a little red.
        val own = sm(tint * 0.35f, tint * 0.35f + 0.6f, turn)
        if (own > 0) {
            val autumn = mix(0xFFF0C24A.toInt(), if (tint > 0.6f) 0xFFD9532F.toInt() else 0xFFE8862E.toInt(), sm(0.4f, 1f, turn))
            c = mix(c, autumn, own)
        }
        return c
    }

    private fun tree(canvas: Canvas, f: Frame) {
        val s = f.s
        val season = s.season
        val tall = TREE_BASE - 12f
        fun dx(y: Float) = f.sway(y, TREE_BASE, tall)
        val bark = 0xFF6E4B3A.toInt()
        // By night the lit side of the trunk catches a cool grey from the moon or the sky.
        val barkLit = mix(0xFF8E6650.toInt(), 0xFFA9B0C0.toInt(), f.light.rim * 0.6f)
        // Trunk, slightly flared at the foot.
        val trunk = Path().apply {
            moveTo(TREE_X - 2.6f, TREE_BASE + 0.5f)
            quadTo(TREE_X - 1.6f, TREE_BASE - 4f, TREE_X - 1.5f + dx(54f), 54f)
            lineTo(TREE_X + 1.5f + dx(54f), 54f)
            quadTo(TREE_X + 1.6f, TREE_BASE - 4f, TREE_X + 2.6f, TREE_BASE + 0.5f)
            close()
        }
        fill.color = bark
        canvas.drawPath(trunk, fill)
        canvas.save()
        canvas.clipPath(trunk)
        fill.color = barkLit
        canvas.drawRect(if (f.light.side < 0) TREE_X - 4f else TREE_X + 0.2f, 50f, if (f.light.side < 0) TREE_X - 0.2f else TREE_X + 4f, TREE_BASE + 1f, fill)
        canvas.restore()
        for (l in limbs) {
            taper(canvas, l[0] + dx(l[1]), l[1], l[2] + dx(l[3]), l[3], l[4], l[5], bark)
        }
        // Snow along the tops of bare limbs.
        val leaves = season.leaves.toFloat()
        val snow = s.treeSnow.toFloat()
        if (snow > 0.05f && leaves < 0.5f) {
            stroke.color = alpha(0xFFF6F9FD.toInt(), min(1f, snow * 1.4f) * (1 - leaves * 2))
            for (l in limbs) {
                stroke.strokeWidth = max(0.35f, l[5] * 0.8f)
                canvas.drawLine(l[0] + dx(l[1]), l[1] - l[4] * 0.45f, l[2] + dx(l[3]), l[3] - l[5] * 0.45f, stroke)
            }
        }
        // Buds and the first leaves on bare twigs in spring.
        if (leaves < 0.15f && season.buds > 0.1) {
            fill.color = alpha(0xFFB7DE7A.toInt(), season.buds.toFloat())
            for (l in limbs) if (l[5] <= 0.5f) canvas.drawCircle(l[2] + dx(l[3]), l[3], 0.7f + 0.6f * season.buds.toFloat(), fill)
        }
        // The crown: clumps come and go one by one as the leaves do.
        val grow = (0.45f + 0.55f * season.leafSize.toFloat())
        class Shown(val x: Float, val y: Float, val r: Float, val color: Int, val shown: Float, val path: Path)
        val crown = clumps.mapNotNull { c ->
            val shown = sm(c.at * 0.92f, c.at * 0.92f + 0.08f, leaves)
            if (shown < 0.02f) return@mapNotNull null
            val x = c.x + dx(c.y)
            val r = c.r * grow * (0.9f + 0.1f * shown)
            val path = Path().apply {
                addCircle(x, c.y, r, Path.Direction.CW)
                for (a in listOf(-160f, -120f, -80f, -40f, 0f, 180f)) {
                    val rad = a * PI.toFloat() / 180f
                    addCircle(x + cos(rad) * r * 0.7f, c.y + sin(rad) * r * 0.62f, r * 0.42f, Path.Direction.CW)
                }
            }
            Shown(x, c.y, r, leafColor(season, c.tint), shown, path)
        }
        if (crown.isEmpty()) return
        // One shaded silhouette, then the lit masses inside it with no edges between them, then a
        // glint on the clumps that face the light, so it reads as one soft crown.
        val lx = f.light.side
        val outline = Path().apply { for (c in crown) addPath(c.path) }
        for (c in crown) {
            fill.color = alpha(shade(c.color, 0.66f), c.shown)
            canvas.drawPath(c.path, fill)
        }
        canvas.save()
        canvas.clipPath(outline)
        for (c in crown) {
            fill.color = alpha(c.color, c.shown)
            canvas.drawCircle(c.x + lx * c.r * 0.18f, c.y - c.r * 0.28f, c.r * 0.86f, fill)
        }
        for (c in crown) {
            val facing = (lx * (c.x - TREE_X) / 20f - (c.y - 34f) / 16f).coerceIn(0f, 1f)
            if (facing < 0.1f) continue
            // Warm sunlight by day, cool moonlight by night.
            val glint = mix(0xFFFFF4C0.toInt(), 0xFFB8C8FF.toInt(), f.light.night)
            fill.color = alpha(mix(c.color, glint, 0.18f + 0.16f * f.light.sun), c.shown * facing * (0.75f - 0.35f * f.light.night * (1 - f.light.moonVis)))
            canvas.drawCircle(c.x + lx * c.r * 0.4f, c.y - c.r * 0.48f, c.r * 0.5f, fill)
        }
        for (c in crown) {
            val rnd = Random((c.x * 10).toLong() + (c.y * 7).toLong())
            fill.color = alpha(mix(c.color, 0xFFFFFFFF.toInt(), 0.28f), c.shown * 0.7f)
            repeat(4) { canvas.drawCircle(c.x + rnd.f(-0.7f, 0.7f) * c.r, c.y + rnd.f(-0.8f, 0.3f) * c.r, c.r * 0.08f, fill) }
        }
        // By night a faint cool edge on the side the light comes from, so the crown holds its shape
        // against the sky without looking lit.
        if (f.light.rim > 0.02f) {
            val edge = crown.fold(Path()) { acc, c -> acc.apply { op(c.path, Path.Op.UNION) } }
            val tone = 0xFFCAD4E6.toInt()
            val a = 0.6f * f.light.rim * crown.maxOf { it.shown }
            stroke.strokeWidth = 1.8f
            stroke.color = 0xFFFFFFFF.toInt()
            stroke.shader = LinearGradient(TREE_X - lx * 2f, 38f, TREE_X + lx * 18f, 16f,
                intArrayOf(alpha(tone, 0f), alpha(tone, a * 0.25f), alpha(tone, a)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(edge, stroke)
            stroke.shader = null
        }
        canvas.restore()
        if (snow > 0.05f) for (c in crown) {
            canvas.save()
            canvas.clipPath(c.path)
            fill.color = alpha(0xFFF6F9FD.toInt(), min(1f, snow * 1.3f) * c.shown)
            canvas.drawCircle(c.x, c.y - c.r * (1.75f - 0.45f * snow), c.r, fill)
            canvas.restore()
        }
    }

    private fun taper(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, w1: Float, w2: Float, color: Int) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
        val nx = -dy / len
        val ny = dx / len
        fill.color = color
        canvas.drawPath(Path().apply {
            moveTo(x1 + nx * w1 / 2, y1 + ny * w1 / 2)
            lineTo(x2 + nx * w2 / 2, y2 + ny * w2 / 2)
            lineTo(x2 - nx * w2 / 2, y2 - ny * w2 / 2)
            lineTo(x1 - nx * w1 / 2, y1 - ny * w1 / 2)
            close()
        }, fill)
        canvas.drawCircle(x2, y2, w2 / 2, fill)
    }

    // ---------------------------------------------------------------- small things

    private fun mushroom(canvas: Canvas, f: Frame, x: Float, y: Float, index: Int, grown: Float) {
        val k = 0.5f + 0.5f * grown
        val agaric = index % 2 == 0
        fill.color = 0xFFF1E8D6.toInt()
        canvas.drawRect(x - 0.55f * k, y - 2.6f * k, x + 0.55f * k, y, fill)
        val cap = RectF(x - 2f * k, y - 3.9f * k, x + 2f * k, y - 1.7f * k)
        fill.color = if (agaric) 0xFFD8402E.toInt() else 0xFFB07848.toInt()
        canvas.drawArc(cap, 180f, 180f, true, fill)
        canvas.drawRect(cap.left, cap.centerY() - 0.05f, cap.right, cap.centerY() + 0.35f * k, fill)
        if (agaric) {
            fill.color = 0xFFFFF6EA.toInt()
            canvas.drawCircle(x - 0.8f * k, y - 3.1f * k, 0.32f * k, fill)
            canvas.drawCircle(x + 0.7f * k, y - 3.3f * k, 0.28f * k, fill)
            canvas.drawCircle(x + 0.1f * k, y - 2.5f * k, 0.25f * k, fill)
        }
    }

    private fun flowers(canvas: Canvas, f: Frame) {
        val s = f.s
        val bloom = s.bloom.toFloat()
        if (bloom < 0.03f || f.snowCover > 0.6f) return
        val d = s.season.dayOfYear
        // Spring is yellow and white (coltsfoot, anemones); summer adds pink, violet and blue.
        val palette = if (d < 160) intArrayOf(0xFFF6D348.toInt(), 0xFFFFFFFF.toInt(), 0xFFF6D348.toInt(), 0xFFB6A3F0.toInt())
        else intArrayOf(0xFFF27BA6.toInt(), 0xFFFFFFFF.toInt(), 0xFFF6D348.toInt(), 0xFF8C8AF0.toInt(), 0xFFF29A4A.toInt())
        for ((i, p) in flowers.withIndex()) {
            val threshold = (i * 0.618f) % 1f
            if (bloom < threshold * 0.9f + 0.05f) continue
            val x = p[0] + f.sway(p[1] - 3f, p[1], 6f) * 0.4f
            val y = p[1]
            stroke.color = 0xFF4E8A3E.toInt()
            stroke.strokeWidth = 0.35f
            canvas.drawLine(p[0], y, x, y - 2.6f, stroke)
            val c = palette[i % palette.size]
            fill.color = c
            for (a in 0 until 5) {
                val rad = a * 2 * PI.toFloat() / 5
                canvas.drawCircle(x + cos(rad) * 0.75f, y - 2.8f + sin(rad) * 0.75f, 0.55f, fill)
            }
            fill.color = if (c == 0xFFF6D348.toInt()) 0xFFE08A2A.toInt() else 0xFFF6D348.toInt()
            canvas.drawCircle(x, y - 2.8f, 0.4f, fill)
        }
    }

    /** Tufts of grass, leaning with the wind; snow buries them. */
    private fun grass(canvas: Canvas, f: Frame) {
        val s = f.s
        val hidden = sm(1.5f, 8f, s.snow.toFloat())
        if (hidden > 0.98f) return
        val d = s.season.dayOfYear.toDouble()
        val green = (Season.smooth(95.0, 135.0, d) * (1 - Season.smooth(258.0, 300.0, d))).toFloat()
        val tone = mix(0xFFB5A762.toInt(), 0xFF5FA845.toInt(), green)
        val light = mix(0xFFD6C886.toInt(), 0xFF9AD46A.toInt(), green)
        val rnd = Random(83)
        stroke.strokeWidth = 0.45f
        repeat(26) {
            val x = f.left + rnd.nextFloat() * (f.right - f.left)
            val y = rnd.f(70f, 100f)
            if (y < ground(x) + 2f) return@repeat
            if (abs(x - PUDDLE_X) < 9f && abs(y - PUDDLE_Y) < 3f) return@repeat
            val h = (1.4f + (y - 70f) / 30f * 1.8f) * (0.6f + 0.4f * (0.4f + 0.6f * green))
            val bend = (f.lean * 1.3f + f.swing * 0.35f) * h * 0.5f
            stroke.color = alpha(tone, 1 - hidden)
            for (k in -1..1) {
                stroke.color = alpha(if (k == f.light.side.toInt()) light else tone, 1 - hidden)
                canvas.drawLine(x + k * 0.5f, y, x + k * 0.9f + bend, y - h * (1 - abs(k) * 0.25f), stroke)
            }
        }
    }

    // ---------------------------------------------------------------- visitors

    private fun fox(canvas: Canvas, f: Frame) {
        val rnd = Random(f.s.slot * 3 + 1)
        val spots = floatArrayOf(14f, 24f, 82f, 44f)
        val x = spots[rnd.nextInt(spots.size)]
        val y = 83f + rnd.f(-1f, 3f)
        val facing = if (rnd.nextBoolean()) 1f else -1f
        val orange = if (f.s.night) 0xFFC2662C.toInt() else 0xFFE07B2E.toInt()
        val dark = 0xFF3B2A24.toInt()
        canvas.save()
        canvas.scale(facing * 1.35f, 1.35f, x, y)
        stroke.color = dark
        stroke.strokeWidth = 0.6f
        // Legs mid-stride.
        canvas.drawLine(x - 2.6f, y - 3f, x - 3.4f, y, stroke)
        canvas.drawLine(x - 1.8f, y - 3f, x - 1.2f, y, stroke)
        canvas.drawLine(x + 2.4f, y - 3f, x + 3.2f, y, stroke)
        canvas.drawLine(x + 3.0f, y - 3f, x + 2.0f, y, stroke)
        // Brush tail, white-tipped, lifting a little in the other frame.
        val lift = if (f.frame == 1) 0.5f else 0f
        val tail = Path().apply {
            moveTo(x - 3.5f, y - 4.4f)
            quadTo(x - 6.5f, y - 5.5f - lift, x - 8.6f, y - 3.8f - lift)
            quadTo(x - 6.5f, y - 2.6f, x - 3.5f, y - 3.2f)
            close()
        }
        fill.color = orange
        canvas.drawPath(tail, fill)
        fill.color = 0xFFFFF4E6.toInt()
        canvas.drawCircle(x - 8.2f, y - 3.85f - lift, 0.75f, fill)
        fill.color = orange
        canvas.drawOval(RectF(x - 4.2f, y - 5.8f, x + 4.2f, y - 2.6f), fill)
        fill.color = shade(orange, 1.12f)
        canvas.drawOval(RectF(x - 3.6f, y - 5.8f, x + 3.4f, y - 4.4f), fill)
        // Head, pointed snout and ears.
        fill.color = orange
        canvas.drawCircle(x + 4.6f, y - 6.4f, 1.8f, fill)
        canvas.drawPath(Path().apply { moveTo(x + 5.6f, y - 7.2f); lineTo(x + 8.2f, y - 5.8f); lineTo(x + 5.4f, y - 5.2f); close() }, fill)
        canvas.drawPath(Path().apply { moveTo(x + 3.4f, y - 7.4f); lineTo(x + 3.8f, y - 10f); lineTo(x + 4.9f, y - 7.8f); close() }, fill)
        canvas.drawPath(Path().apply { moveTo(x + 4.7f, y - 7.8f); lineTo(x + 5.6f, y - 10f); lineTo(x + 6f, y - 7.2f); close() }, fill)
        fill.color = 0xFFFFF4E6.toInt()
        canvas.drawPath(Path().apply { moveTo(x + 5.2f, y - 5.6f); lineTo(x + 8.2f, y - 5.8f); lineTo(x + 4.4f, y - 4.6f); close() }, fill)
        canvas.drawOval(RectF(x + 2.6f, y - 5f, x + 4.6f, y - 3.2f), fill)
        fill.color = dark
        canvas.drawCircle(x + 8.1f, y - 5.8f, 0.35f, fill)
        canvas.drawCircle(x + 5.3f, y - 6.8f, 0.3f, fill)
        canvas.restore()
    }

    private fun hare(canvas: Canvas, f: Frame) {
        // A mountain hare peeks over the rock: brown in summer, white in winter.
        val white = f.wintry
        val coat = if (white) 0xFFEDEFF2.toInt() else 0xFFA08466.toInt()
        val x = ROCK_X + 2.2f
        val y = ROCK_Y - 8.2f
        canvas.save()
        canvas.scale(1.45f, 1.45f, x, y + 2.6f)
        canvas.save()
        canvas.rotate(if (f.frame == 1) 4f else -4f, x, y)
        fill.color = coat
        canvas.drawOval(RectF(x - 1.6f, y - 6.2f, x - 0.4f, y - 0.4f), fill)
        canvas.drawOval(RectF(x + 0.1f, y - 6.6f, x + 1.3f, y - 0.6f), fill)
        if (white) {
            fill.color = 0xFF2E2A2A.toInt()
            canvas.drawOval(RectF(x - 1.5f, y - 6.2f, x - 0.5f, y - 5f), fill)
            canvas.drawOval(RectF(x + 0.2f, y - 6.6f, x + 1.2f, y - 5.4f), fill)
        }
        canvas.restore()
        fill.color = coat
        canvas.drawOval(RectF(x - 2.4f, y - 1.8f, x + 2.2f, y + 2.6f), fill)
        fill.color = 0xFF2E2A2A.toInt()
        canvas.drawCircle(x + 0.9f, y + 0.1f, 0.38f, fill)
        canvas.restore()
    }

    private fun perched(canvas: Canvas, f: Frame) {
        val s = f.s
        val leafy = s.season.leaves > 0.4
        val x = if (leafy) TREE_X + 2f + f.sway(10f, TREE_BASE, 59f) else 43f + f.sway(15f, TREE_BASE, 59f)
        val y = if (leafy) 10.2f else 14.4f
        // A bullfinch in winter, a robin the rest of the year.
        val winter = f.wintry
        val back = if (winter) 0xFF6E7480.toInt() else 0xFF8A6A4A.toInt()
        val breast = if (winter) 0xFFE0566A.toInt() else 0xFFF08A3C.toInt()
        val head = if (f.frame == 1) -0.4f else 0f
        canvas.save()
        canvas.scale(1.6f, 1.6f, x, y + 1.4f)
        fill.color = back
        canvas.drawPath(Path().apply { moveTo(x - 1.2f, y); lineTo(x - 3.2f, y + 1.4f); lineTo(x - 1f, y + 1.2f); close() }, fill)
        canvas.drawOval(RectF(x - 1.8f, y - 1.7f, x + 1.6f, y + 1.4f), fill)
        fill.color = breast
        canvas.drawOval(RectF(x - 0.3f, y - 1.3f, x + 1.7f, y + 1.3f), fill)
        fill.color = if (winter) 0xFF222226.toInt() else back
        canvas.drawCircle(x + 1.1f + head, y - 1.9f, 1.1f, fill)
        fill.color = 0xFF2A1E18.toInt()
        canvas.drawCircle(x + 1.5f + head, y - 2.1f, 0.25f, fill)
        fill.color = 0xFFE8A040.toInt()
        canvas.drawPath(Path().apply { moveTo(x + 2.1f + head, y - 2.1f); lineTo(x + 3f + head, y - 1.8f); lineTo(x + 2.1f + head, y - 1.5f); close() }, fill)
        canvas.restore()
    }

    private fun squirrel(canvas: Canvas, f: Frame) {
        val x = TREE_X - 2.4f
        val y = 61f
        val red = 0xFFB9572C.toInt()
        canvas.save()
        canvas.scale(1.3f, 1.3f, x, y + 2.6f)
        fill.color = red
        // Tail curled up behind, body hugging the trunk, head up.
        canvas.drawPath(Path().apply {
            moveTo(x - 0.4f, y + 2.6f)
            cubicTo(x - 4.6f, y + 3.2f, x - 4.8f, y - 2.8f, x - 2.2f, y - 3.2f)
            cubicTo(x - 3.2f, y - 1.6f, x - 2.6f, y + 1f, x - 0.2f, y + 1.2f)
            close()
        }, fill)
        canvas.drawOval(RectF(x - 1.2f, y - 2.2f, x + 1f, y + 2.8f), fill)
        canvas.drawCircle(x + 0.2f, y - 2.8f, 1.15f, fill)
        canvas.drawPath(Path().apply { moveTo(x - 0.6f, y - 3.4f); lineTo(x - 0.4f, y - 5f); lineTo(x + 0.2f, y - 3.7f); close() }, fill)
        fill.color = 0xFFF2E3CC.toInt()
        canvas.drawOval(RectF(x + 0.1f, y - 1.6f, x + 1f, y + 1.6f), fill)
        fill.color = 0xFF231A16.toInt()
        canvas.drawCircle(x + 0.7f, y - 3f, 0.26f, fill)
        canvas.restore()
    }

    private fun owlAt(f: Frame) = floatArrayOf(47.5f + f.sway(38f, TREE_BASE, 59f), 37.2f)

    private fun owl(canvas: Canvas, f: Frame) {
        val (x, y) = owlAt(f).let { it[0] to it[1] }
        canvas.save()
        canvas.scale(1.4f, 1.4f, x, y + 2.6f)
        fill.color = 0xFF7A6652.toInt()
        canvas.drawOval(RectF(x - 2.3f, y - 3.2f, x + 2.3f, y + 2.6f), fill)
        canvas.drawPath(Path().apply { moveTo(x - 2.1f, y - 2.2f); lineTo(x - 1.8f, y - 4.2f); lineTo(x - 0.8f, y - 2.8f); close() }, fill)
        canvas.drawPath(Path().apply { moveTo(x + 2.1f, y - 2.2f); lineTo(x + 1.8f, y - 4.2f); lineTo(x + 0.8f, y - 2.8f); close() }, fill)
        fill.color = 0xFFB8A488.toInt()
        canvas.drawOval(RectF(x - 1.3f, y - 0.4f, x + 1.3f, y + 2.4f), fill)
        canvas.restore()
    }

    /** The owl's eyes catch whatever light there is; one blinks in the other frame. */
    private fun owlEyes(canvas: Canvas, f: Frame) {
        val (x, y) = owlAt(f).let { it[0] to it[1] }
        canvas.save()
        canvas.scale(1.4f, 1.4f, x, y + 2.6f)
        fill.color = 0xFFFFD25A.toInt()
        canvas.drawCircle(x - 0.95f, y - 1.6f, 0.75f, fill)
        if (f.frame == 0) canvas.drawCircle(x + 0.95f, y - 1.6f, 0.75f, fill)
        fill.color = 0xFF1A1410.toInt()
        canvas.drawCircle(x - 0.95f, y - 1.6f, 0.32f, fill)
        if (f.frame == 0) canvas.drawCircle(x + 0.95f, y - 1.6f, 0.32f, fill)
        else { stroke.color = 0xFF3A2E24.toInt(); stroke.strokeWidth = 0.3f; canvas.drawLine(x + 0.3f, y - 1.6f, x + 1.6f, y - 1.6f, stroke) }
        canvas.restore()
    }

    private fun butterfly(canvas: Canvas, f: Frame) {
        val rnd = Random(f.s.slot * 5 + 2)
        val x = rnd.f(14f, 30f) + f.frame * 2.4f
        val y = rnd.f(70f, 76f) - f.frame * 1.6f
        val c = if (f.s.season.dayOfYear < 165) 0xFFF4E35A.toInt() else if (rnd.nextBoolean()) 0xFFF29A3A.toInt() else 0xFF7FA8F4.toInt()
        val open = if (f.frame == 0) 1f else 0.45f
        canvas.save()
        canvas.scale(1.35f, 1.35f, x, y)
        fill.color = c
        canvas.drawOval(RectF(x - 2.2f * open, y - 2.1f, x, y), fill)
        canvas.drawOval(RectF(x, y - 2.1f, x + 2.2f * open, y), fill)
        fill.color = shade(c, 0.85f)
        canvas.drawOval(RectF(x - 1.5f * open, y - 0.3f, x, y + 1.3f), fill)
        canvas.drawOval(RectF(x, y - 0.3f, x + 1.5f * open, y + 1.3f), fill)
        stroke.color = 0xFF2A2420.toInt()
        stroke.strokeWidth = 0.3f
        canvas.drawLine(x, y - 1.6f, x, y + 1f, stroke)
        canvas.restore()
    }

    // ---------------------------------------------------------------- weather and finish

    private fun weather(canvas: Canvas, f: Frame) {
        val s = f.s
        val light = f.light
        val slant = s.windSide.toFloat() * (0.15f + 0.5f * sm(2f, 14f, s.wind.toFloat()))
        if (s.flock) flock(canvas, f)
        if (s.visitor == Visitor.OWL) owlEyes(canvas, f)
        if (s.fireflies) {
            val rnd = Random(s.slot * 11 + 3)
            repeat(7) { i ->
                val x = rnd.f(6f, 94f)
                val y = rnd.f(62f, 92f)
                if ((i + f.frame) % 3 == 0) return@repeat
                fill.alpha = 255
                fill.shader = RadialGradient(x, y, 2.4f, intArrayOf(0xCCF4F79A.toInt(), 0x00F4F79A), null, Shader.TileMode.CLAMP)
                canvas.drawCircle(x, y, 2.4f, fill)
                fill.shader = null
                fill.color = 0xFFFDFFD0.toInt()
                canvas.drawCircle(x, y, 0.38f, fill)
            }
        }
        val leaves = s.fallingLeaves
        if (leaves > 0) {
            val rnd = Random(s.slot * 17 + 9)
            repeat(leaves) {
                val x = rnd.f(10f, 70f) + f.frame * (1.5f + slant * 4f)
                val y = rnd.f(30f, 75f) + f.frame * 3f
                fill.color = leafColor(s.season, rnd.nextFloat())
                canvas.save()
                canvas.rotate(rnd.f(0f, 180f) + f.frame * 50f, x, y)
                canvas.drawOval(RectF(x - 1.1f, y - 0.55f, x + 1.1f, y + 0.55f), fill)
                canvas.restore()
            }
        }
        if (s.rain > 0.05) {
            val rnd = Random(7L + f.frame * 101)
            val count = (16 + 28 * light.rain).toInt()
            stroke.color = alpha(mix(light.skyHorizon, 0xFFFFFFFF.toInt(), 0.65f), 0.6f + 0.25f * light.rain)
            stroke.strokeWidth = 0.42f
            repeat(count) {
                val x = f.left + rnd.nextFloat() * (f.right - f.left + 10f)
                val y = f.top + rnd.nextFloat() * (100f - f.top)
                val len = rnd.f(3f, 4.6f)
                canvas.drawLine(x, y, x + slant * len, y + len, stroke)
            }
        }
        if (s.snowfall > 0.05) {
            val rnd = Random(13)
            val count = (18 + 30 * sm(0f, 2f, s.snowfall.toFloat())).toInt()
            fill.color = 0xF2FFFFFF.toInt()
            repeat(count) {
                val x = f.left + rnd.nextFloat() * (f.right - f.left) + f.frame * (0.8f + slant * 3f)
                val y = f.top + rnd.nextFloat() * (100f - f.top) + f.frame * 2.4f
                canvas.drawCircle(x, y, rnd.f(0.45f, 0.9f), fill)
            }
        }
        if (s.fog > 0) {
            fill.alpha = 255
            fill.shader = LinearGradient(0f, 40f, 0f, 80f, intArrayOf(0x00FFFFFF, alpha(mix(light.skyHorizon, 0xFFFFFFFF.toInt(), 0.3f), 0.7f * s.fog.toFloat()), 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(f.left, 40f, f.right, 80f, fill)
            fill.shader = null
        }
    }

    private fun flock(canvas: Canvas, f: Frame) {
        val rnd = Random(f.s.slot * 19 + 4)
        val x0 = rnd.f(55f, 85f) - f.frame * 3f
        val y0 = rnd.f(10f, 22f)
        stroke.color = alpha(mix(0xFF2A2C3A.toInt(), f.light.skyTop, 0.2f), 0.85f)
        stroke.strokeWidth = 0.42f
        repeat(3) { i ->
            val x = x0 + i * 3.6f + rnd.f(-0.8f, 0.8f)
            val y = y0 + i * 1.4f + rnd.f(-0.8f, 0.8f)
            val up = if ((i + f.frame) % 2 == 0) 1.1f else -0.3f
            val path = Path().apply {
                moveTo(x - 1.6f, y - up)
                quadTo(x - 0.7f, y - up * 0.6f, x, y)
                quadTo(x + 0.7f, y - up * 0.6f, x + 1.6f, y - up)
            }
            canvas.drawPath(path, stroke)
        }
    }

    private fun finish(canvas: Canvas, w: Float, h: Float, f: Frame, radius: Float) {
        fill.alpha = 255
        fill.shader = RadialGradient(w / 2, h * 0.45f, max(w, h) * 0.75f, intArrayOf(0x00000000, 0x00000000, alpha(0xFF0A0E22.toInt(), 0.18f + 0.1f * f.light.night)),
            floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        if (radius > 0) {
            val corners = Path().apply { addRoundRect(RectF(0f, 0f, w, h), radius, radius, Path.Direction.CW); fillType = Path.FillType.INVERSE_WINDING }
            fill.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            canvas.drawPath(corners, fill)
            fill.xfermode = null
        }
    }
}
