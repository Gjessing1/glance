package io.gjessing.glance

import android.graphics.Bitmap
import android.graphics.BitmapShader
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paints the Living Tree: a weeping silver birch (Betula pendula) over a soil cross-section. The
 * geometry comes from a fixed seed and fixed segment counts, so it is the same tree at every size
 * and in every state; the scene only decides colour, light, what is on the branches and what falls.
 *
 * Lengths are in `u`, a hundredth of the picture's width, so the drawing scales with the widget.
 */
object TreePainter {
    private class Limb(val x: FloatArray, val y: FloatArray, val w: FloatArray, val hang: FloatArray, val front: Boolean = true) {
        val size get() = x.size
    }

    private class Leaf(val curtain: Int, val point: Int, val angle: Float, val size: Float, val rank: Float, val early: Float, val tint: Float)

    private class Pocket(val x: Float, val y: Float, val size: Float, val threshold: Float)
    private class Stone(val x: Float, val y: Float, val r: Float, val shape: FloatArray, val tone: Float)

    /** A flat pad of pine needles: overlapping ovals as (dx, dy, r) triples around its centre. */
    private class Pad(val x: Float, val y: Float, val rx: Float, val ry: Float, val blobs: FloatArray)
    /** A Scots pine of the large view; `back` (0..1) is how far behind the birch it stands. */
    private class Pine(val trunk: Limb, val boughs: List<Limb>, val stubs: List<FloatArray>, val pads: List<Pad>, val height: Float, val back: Float, val seed: Int)
    /** `outer` is how sunlit a leaf is (0 deep in shade, 1 on top of a spray at the crown's edge), where it reddens most. */
    private class MapleLeaf(val x: Float, val y: Float, val angle: Float, val size: Float, val rank: Float, val early: Float, val outer: Float, val tint: Float)
    /**
     * The large view's distance, seen from a hilltop. Skylines are sampled every `step` pixels across:
     * two ranges of forested hills, the treetops on our own slope showing over its brow, and the brow
     * itself. The lake lies in the valley between, from `lake[0]` to `lake[1]` across with its far
     * shore at `shore`. Broadleaf crowns on the slope are (x, y, r), the Scots pines rising over its
     * spruce (x, foot, top, seed); granite outcrops and juniper on
     * the hilltop (x, y, size, seed).
     */
    private class Land(val horizon: Float, val step: Float, val far: FloatArray, val near: FloatArray, val shore: Float, val lake: FloatArray,
                       val slope: FloatArray, val brow: FloatArray, val broadleaves: List<FloatArray>, val pines: List<FloatArray>,
                       val rocks: List<FloatArray>, val shrubs: List<FloatArray>)
    /** `twigs` are its fine outer twigs as (x0, y0, x1, y1), the net that shows when it is bare. */
    private class Maple(val x: Float, val crownY: Float, val rx: Float, val limbs: List<Limb>, val twigs: List<FloatArray>, val leaves: List<MapleLeaf>)

    /** Everything that depends on the picture's size alone; built once per size. */
    private class Tree(
        val w: Float, val h: Float, val u: Float, val ground: Float, val top: Float, val sky: Float, val baseW: Float,
        val trunk: Limb, val branches: List<Limb>, val curtains: List<Limb>, val leaves: List<Leaf>,
        val roots: List<Limb>, val pockets: List<Pocket>, val stones: List<Stone>, val threads: List<FloatArray>,
        val junctions: List<FloatArray>, val tw: Float = w,
        /** The large view's neighbours, their roots, and the fungal threads joining theirs to the birch's. */
        val pines: List<Pine> = emptyList(), val maple: Maple? = null, val neighbourRoots: List<Limb> = emptyList(),
        val pineRootCount: Int = 0,
        val links: List<FloatArray> = emptyList(), val land: Land? = null,
    ) {
        /** Where a low sun sits: just over the ground in the widget, over the far ridges in the large view. */
        val skyline get() = land?.let { it.horizon - 16 * u } ?: ground
        val cx get() = w / 2
        /**
         * How many times wider the picture is than the tree's own frame: 1 in the widget, more in the
         * large view, where the same tree stands in a wider landscape with as many things per width.
         */
        val wide get() = w / tw
        fun spread(n: Int) = if (tw == w) n else (n * wide).toInt()
        val height get() = ground - top
        /** The open sky's height above the ground, where the weather, the moon and the stars are. */
        val skyHeight get() = ground - sky
        val depth get() = h - ground
        /** The ground's gentle undulation. */
        fun surface(x: Float) = ground + u * (0.6f * sin(x / w * 6.3f + 1f) + 0.35f * sin(x / w * 17f + 2f))
    }

    private var cached: Tree? = null

    private fun tree(w: Int, h: Int, tw: Int): Tree {
        cached?.let { if (it.w == w.toFloat() && it.h == h.toFloat() && it.tw == tw.toFloat()) return it }
        return grow(w.toFloat(), h.toFloat(), tw.toFloat()).also { cached = it }
    }

    private fun Random.f(a: Float, b: Float) = a + nextFloat() * (b - a)

    /** A gradient fill at full strength, whatever alpha the shared paint last held. */
    private fun Paint.with(gradient: Shader) {
        alpha = 255
        shader = gradient
    }
    private fun sm(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    // ---------------------------------------------------------------- growth

    /** The tree is grown in a frame `tw` wide, centred in a picture `w` wide; the widget passes the same. */
    private fun grow(w: Float, h: Float, tw: Float): Tree {
        val rnd = Random(1907)
        val u = tw / 100f
        val left = (w - tw) / 2
        // Roots and soil take the lowest 30 %, the tree, its landscape and the sky the rest, with the
        // weather, the moon and the stars from the top 13 % down.
        val ground = h * 0.7f
        val sky = h * 0.13f
        // A weeping birch stands two and a half to three times as tall as its crown is wide. In a
        // narrow widget the crown runs a little past both edges, as in a close view, and the tree keeps
        // that shape with open sky above it instead of stretching to the top: a mature, grounded tree
        // rather than a tall thin one.
        val top = ground - min(ground - sky, tw * 2.9f)
        val treeH = ground - top
        val cx = w / 2
        val crown = min(tw * 0.52f, treeH * 0.42f)
        /** The crown's rounded top: limbs that reach above it turn down sooner. */
        fun dome(x: Float) = top + treeH * 0.03f + treeH * 0.2f * ((x - cx) / crown).pow(2)
        val baseW = min(10f * u, treeH * 0.055f)

        // A slender trunk with a slight S, its leader arching over at the top as weeping birches do.
        val tn = 44
        val trunk = Limb(FloatArray(tn + 1), FloatArray(tn + 1), FloatArray(tn + 1), FloatArray(tn + 1))
        for (i in 0..tn) {
            val t = i / tn.toFloat()
            trunk.y[i] = ground - t * treeH * 0.95f
            trunk.x[i] = cx + u * 2.4f * (sin(t * 2.7f + 0.5f) - sin(0.5f)) - u * 1.2f * t + crown * 0.22f * sm(0.82f, 1f, t)
            trunk.w[i] = baseW * (1 - t).pow(0.95f) * 0.92f + 0.3f * u + baseW * 0.5f * exp(-t * 22f)
        }
        fun trunkAt(t: Float): Int = (t * tn).toInt().coerceIn(0, tn)

        val branches = mutableListOf<Limb>()
        val curtains = mutableListOf<Limb>()
        val leaves = mutableListOf<Leaf>()
        val junctions = mutableListOf<FloatArray>()

        /**
         * How far a twig hangs from height `y0`: the curtains hang in tiers, shorter high in the crown,
         * and only the lowest tier reaches far down towards the ground.
         */
        fun hang(y0: Float): Float {
            val room = ground - y0 - treeH * (0.03f + 0.08f * rnd.nextFloat() + 0.06f * rnd.nextFloat().pow(2))
            val low = sm(0.3f, 0.75f, (y0 - top) / treeH)
            return max(4f * u, room * (rnd.f(0.28f, 0.5f) * (1 - low) + rnd.f(0.65f, 0.97f) * low))
        }

        fun curtain(x0: Float, y0: Float, outward: Float, length: Float, n: Int = 30, front: Boolean = rnd.nextFloat() < 0.65f) {
            val seg = length / n
            val limb = Limb(FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1), front)
            val phase = rnd.f(0f, 6.3f)
            val wave = rnd.f(0.05f, 0.2f) * u
            var x = x0
            var y = y0
            for (k in 0..n) {
                limb.x[k] = x
                limb.y[k] = y
                limb.w[k] = u * (0.3f - 0.12f * k / 30f)
                limb.hang[k] = k * seg
                // Leaves the branch outward, then hangs straight, swaying a little along its length.
                x += outward * seg * 0.9f * exp(-k * 30f / n / 3.5f) + sin(k * 0.45f + phase) * wave
                y += seg
            }
            val index = curtains.size
            curtains += limb
            var k = if (n < 12) 1 else 2
            while (k <= n) {
                for (side in listOf(-1f, 1f)) {
                    if (rnd.nextFloat() < 0.1f) continue
                    val angle = (PI / 2).toFloat() + side * rnd.f(0.35f, 0.95f)
                    val early = rnd.nextFloat()
                    leaves += Leaf(index, k, angle, rnd.f(0.8f, 1.15f), 0.6f * rnd.nextFloat() + 0.4f * early, early, rnd.nextFloat())
                }
                k += if (rnd.nextFloat() < 0.6f) 1 else 2
            }
        }

        /**
         * A limb that rises fairly straight and dark, bending down only near its tip, as on the real
         * tree; nearly all of its length drops curtains.
         */
        fun limb(x0: Float, y0: Float, w0: Float, side: Float, start: Float, reach: Float, n: Int, secondary: Boolean): Limb {
            val limb = Limb(FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1))
            var a = start
            var x = x0
            var y = y0
            val seg = reach / n
            for (k in 0..n) {
                limb.x[k] = x
                limb.y[k] = y
                limb.w[k] = w0 * (1 - k / n.toFloat()).pow(0.85f) + 0.25f * u
                if (k > n * 0.55f) a += (1.8f - a) * 0.1f
                if (y < dome(x)) a += 0.3f
                a += rnd.f(-0.07f, 0.07f)
                x += sin(a) * side * seg
                y -= cos(a) * seg
                // Past the picture's edges a little, no further: the crown is seen close, not cut off flat.
                val edge = -0.12f * tw
                if (x < left + edge || x > left + tw - edge) { x = x.coerceIn(left + edge, left + tw - edge); a += 0.25f }
            }
            branches += limb
            for (k in (if (secondary) 2 else 4)..n) {
                if (rnd.nextFloat() < (if (secondary) 0.6f else 0.45f)) curtain(limb.x[k], limb.y[k], side * rnd.f(0.1f, 0.5f), hang(limb.y[k]))
                // Short leafy sprays all along the limb fill the crown between the curtains.
                if (rnd.nextFloat() < 0.45f) curtain(limb.x[k], limb.y[k], side * rnd.f(0.2f, 0.8f), treeH * rnd.f(0.025f, 0.08f), 8)
            }
            return limb
        }

        // Limbs of every length and angle, not a regular herringbone, so the curtains fill one crown.
        val count = 26
        for (i in 0 until count) {
            val t = 0.3f + 0.68f * (i + rnd.nextFloat()) / count
            val side = if (rnd.nextBoolean()) 1f else -1f
            val at = trunkAt(t)
            val profile = sin((PI * ((t - 0.2f) / 0.85f).coerceIn(0f, 1f)).toFloat()).pow(0.7f)
            val start = (rnd.f(0.25f, 1.15f) - 0.3f * (t - 0.3f)).coerceAtLeast(0.15f)
            val reach = crown * (0.25f + 0.85f * profile * rnd.f(0.45f, 1.1f)) / sin(start + 0.3f)
            val w0 = max(0.6f * u, trunk.w[at] * rnd.f(0.24f, 0.38f))
            junctions += floatArrayOf(trunk.x[at], trunk.y[at], side, trunk.w[at])
            val primary = limb(trunk.x[at], trunk.y[at], w0, side, start, reach, 22, false)
            for (k in listOf(5, 9, 13, 17)) {
                if (rnd.nextFloat() < 0.35f) continue
                val back = if (rnd.nextFloat() < 0.5f) -1f else 1f
                limb(primary.x[k], primary.y[k], primary.w[k] * 0.6f, side, (start + back * rnd.f(0.3f, 0.6f)).coerceIn(0.15f, 1.5f),
                    reach * rnd.f(0.3f, 0.5f), 12, true)
            }
        }
        // The leader fans out into the top of the dome.
        for (a in listOf(-0.95f, -0.45f, 0.1f, 0.55f, 1f)) {
            val side = if (a < 0) -1f else 1f
            limb(trunk.x[tn], trunk.y[tn], 0.8f * u, side, abs(a) + 0.7f, crown * rnd.f(0.3f, 0.5f), 12, true)
        }
        // Curtains straight off the upper trunk fill the middle, where the real tree has no gap.
        for (k in (tn * 0.45f).toInt()..tn) {
            val high = sm(0.45f, 0.8f, k / tn.toFloat())
            if (rnd.nextFloat() < 0.5f + 0.4f * high) curtain(trunk.x[k] + rnd.f(-2f, 2f) * u, trunk.y[k], rnd.f(-0.4f, 0.4f), hang(trunk.y[k]), front = true)
            repeat(2) { if (rnd.nextFloat() < 0.4f + 0.5f * high) curtain(trunk.x[k] + rnd.f(-1.5f, 1.5f) * u, trunk.y[k], rnd.f(-1f, 1f), treeH * rnd.f(0.03f, 0.1f), 8, front = true) }
        }

        // Roots: two shallow laterals, two diagonals and a heart root, each branching three times.
        val roots = mutableListOf<Limb>()
        val depth = h - ground
        fun root(x0: Float, y0: Float, a0: Float, w0: Float, length: Float, level: Int) {
            val n = if (level < 3) 14 else 7
            val limb = Limb(FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1))
            var a = a0
            var x = x0
            var y = y0
            val seg = length / n
            for (k in 0..n) {
                limb.x[k] = x
                limb.y[k] = y
                limb.w[k] = w0 * (1 - 0.75f * k / n) + 0.18f * u
                a += rnd.f(-0.2f, 0.2f) - a * 0.03f * (level + 1)
                x += sin(a) * seg
                y += cos(a) * seg
                if (y < ground + 2f * u) { y = ground + 2f * u; a *= 0.8f }
                if (y > ground + depth * 0.86f) { y = ground + depth * 0.86f; a = a * 1.2f + (if (a >= 0) 0.3f else -0.3f) }
                if (x < left + u || x > left + tw - u) { x = x.coerceIn(left + u, left + tw - u); a = -a * 0.5f }
            }
            roots += limb
            if (level >= 4) return
            for (k in listOf(3, 6, 9, 12)) {
                if (k > n || rnd.nextFloat() < 0.35f) continue
                val turn = (if (rnd.nextBoolean()) 1 else -1) * rnd.f(0.45f, 0.95f)
                root(limb.x[k], limb.y[k], a0 + turn, limb.w[k] * 0.62f, length * rnd.f(0.35f, 0.55f) * (1 - k / (n + 4f)), level + 1)
            }
        }

        // The large view's roots, for the birch and its neighbours alike: wide and shallow rather than
        // radiating down like an upside-down tree. Laterals run out near the surface, each wandering
        // about a depth of its own; sinkers drop from them to anchor the tree; fine feeder roots crowd
        // the top of the soil.
        fun feeder(x0: Float, y0: Float, into: MutableList<Limb>, r: Random) {
            val n = 3
            val limb = Limb(FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1))
            var a = r.f(0f, 6.3f)
            // Mostly sideways, as feeders follow the moist top layer.
            a = atan2(sin(a) * 0.45f, cos(a))
            val seg = r.f(1.2f, 3.5f) * u / n
            var x = x0
            var y = y0
            for (k in 0..n) {
                limb.x[k] = x
                limb.y[k] = max(ground + 1.2f * u, y)
                limb.w[k] = 0.16f * u
                a += r.f(-0.5f, 0.5f)
                x += cos(a) * seg
                y += sin(a) * seg
            }
            into += limb
        }
        fun sinker(x0: Float, y0: Float, w0: Float, length: Float, lean: Float, into: MutableList<Limb>, r: Random) {
            val n = 12
            val limb = Limb(FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1))
            var a = lean
            var x = x0
            var y = y0
            val seg = length / n
            for (k in 0..n) {
                limb.x[k] = x
                limb.y[k] = y
                limb.w[k] = w0 * (1 - 0.85f * k / n) + 0.14f * u
                a += r.f(-0.15f, 0.15f) - a * 0.08f
                x += sin(a) * seg
                y += cos(a) * seg
                y = min(y, ground + depth * 0.86f)
            }
            into += limb
            for (k in 3..n step 2) repeat(if (k > n / 2) 2 else 1) {
                if (r.nextFloat() < 0.6f) feeder(limb.x[k], limb.y[k], into, r)
            }
            // A thick sinker forks lower down, so the deeper soil holds some roots too.
            if (w0 > 0.8f * u) for (k in listOf(n / 2, 3 * n / 4)) if (r.nextFloat() < 0.6f) {
                val side = if (r.nextBoolean()) 1f else -1f
                sinker(limb.x[k], limb.y[k], limb.w[k] * 0.6f, length * r.f(0.25f, 0.45f), lean + side * r.f(0.5f, 1f), into, r)
            }
        }
        fun lateral(x0: Float, y0: Float, side: Float, length: Float, w0: Float, keep: Float, sink: Float, level: Int,
                    into: MutableList<Limb>, r: Random, xMin: Float, xMax: Float) {
            val n = when (level) { 1 -> 22; 2 -> 12; else -> 7 }
            val limb = Limb(FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1))
            // Angle below the level, steered back towards the root's own depth as it wanders.
            var a = r.f(0.05f, 0.35f)
            var x = x0
            var y = y0
            val seg = length / n
            for (k in 0..n) {
                limb.x[k] = x
                limb.y[k] = y
                limb.w[k] = w0 * (1 - 0.8f * k / n).pow(1.2f) + 0.15f * u
                // It sinks a little as it goes, round the depth it keeps, and wanders about that.
                val target = ground + keep + depth * 0.06f * k / n
                a += r.f(-0.24f, 0.24f) + ((target - y) / (depth * 0.08f)).coerceIn(-1f, 1f) * 0.07f
                a = a.coerceIn(-0.45f, 0.9f)
                x += side * cos(a) * seg
                y += sin(a) * seg
                if (y < ground + 1.5f * u) { y = ground + 1.5f * u; a = abs(a) * 0.5f }
                x = x.coerceIn(xMin, xMax)
            }
            into += limb
            for (k in 1..n) {
                val shallow = 1 - sm(0.06f, 0.3f, (limb.y[k] - ground) / depth)
                repeat(2) { if (r.nextFloat() < 0.2f + 0.6f * shallow) feeder(limb.x[k], limb.y[k], into, r) }
            }
            if (level >= 3) return
            for (k in listOf(n / 6, n / 3, n / 2, 2 * n / 3, 5 * n / 6)) {
                if (r.nextFloat() < sink * (if (level == 1) 1f else 0.4f)) sinker(limb.x[k], limb.y[k], limb.w[k] * 0.7f, depth * r.f(0.12f, 0.45f) * (1.1f - k / (n + 3f)), r.f(-0.35f, 0.35f), into, r)
            }
            for (k in listOf(n / 3, 2 * n / 3)) {
                if (r.nextFloat() < 0.3f) continue
                val way = if (r.nextFloat() < 0.75f) side else -side
                lateral(limb.x[k], limb.y[k], way, length * r.f(0.3f, 0.5f), limb.w[k] * 0.6f, keep * r.f(0.5f, 1.3f), sink, level + 1, into, r, xMin, xMax)
            }
        }

        val rootBase = ground + 1.5f * u
        if (w > tw) {
            // The birch: laterals out to one and a half crown-widths, unequal side to side, a short heart
            // root and two obliques under the trunk.
            val wr = Random(4021)
            val (xMin, xMax) = max(u, cx - tw * 1.75f) to min(w - u, cx + tw * 1.75f)
            for ((side, length, keep, wf) in listOf(
                floatArrayOf(-1f, 1.55f, 0.05f, 0.34f), floatArrayOf(-1f, 0.95f, 0.13f, 0.3f), floatArrayOf(-1f, 0.5f, 0.22f, 0.24f),
                floatArrayOf(1f, 1.25f, 0.07f, 0.34f), floatArrayOf(1f, 0.8f, 0.16f, 0.27f), floatArrayOf(1f, 1.05f, 0.035f, 0.22f),
            )) lateral(cx + side * baseW * 0.35f, rootBase, side, tw * length, baseW * wf * 1.15f, depth * keep, 0.75f, 1, roots, wr, xMin, xMax)
            sinker(cx - baseW * 0.1f, rootBase + u, baseW * 0.36f, depth * 0.76f, 0.1f, roots, wr)
            sinker(cx - baseW * 0.3f, rootBase, baseW * 0.26f, depth * 0.58f, -0.55f, roots, wr)
            sinker(cx + baseW * 0.3f, rootBase, baseW * 0.24f, depth * 0.62f, 0.5f, roots, wr)
        } else {
            root(cx - baseW * 0.4f, rootBase, -1.38f, baseW * 0.3f, tw * 0.55f, 1)
            root(cx + baseW * 0.4f, rootBase, 1.36f, baseW * 0.3f, tw * 0.55f, 1)
            root(cx - baseW * 0.3f, rootBase, -0.75f, baseW * 0.34f, depth * 0.5f, 1)
            root(cx + baseW * 0.3f, rootBase, 0.8f, baseW * 0.34f, depth * 0.5f, 1)
            root(cx, rootBase + u, 0.05f, baseW * 0.3f, depth * 0.62f, 1)
        }

        // Water collects in hollows beside the roots, the shallowest first.
        val pockets = mutableListOf<Pocket>()
        val candidates = roots.flatMap { r -> (0 until r.size).map { r.x[it] to r.y[it] } }
            .filter { it.second > ground + depth * 0.18f && it.second < ground + depth * 0.8f }
        repeat(8) { i ->
            if (candidates.isEmpty()) return@repeat
            val (px, py) = candidates[rnd.nextInt(candidates.size)]
            pockets += Pocket(px + rnd.f(-2f, 2f) * u, py + rnd.f(0.5f, 2f) * u, rnd.f(0.4f, 1f),
                0.3f + 0.07f * i + 0.15f * ((py - ground) / depth))
        }
        val stones = (0 until 13).map {
            val n = 11
            Stone(left + rnd.f(4f * u, tw - 4f * u), ground + depth * rnd.f(0.14f, 0.84f), rnd.f(1.6f, 6f) * u,
                FloatArray(n) { rnd.f(0.78f, 1.12f) }, rnd.nextFloat())
        }
        // Fine white mycelium threads near the root tips: birch lives with its fungi.
        val tips = roots.filter { it.size <= 8 }
        val threads = (0 until 22).mapNotNull {
            if (tips.isEmpty()) return@mapNotNull null
            val r = tips[rnd.nextInt(tips.size)]
            val k = rnd.nextInt(r.size)
            var x = r.x[k]
            var y = r.y[k]
            var a = rnd.f(0f, 6.3f)
            val pts = FloatArray(16)
            for (j in 0 until 8) {
                pts[j * 2] = x
                pts[j * 2 + 1] = y
                a += rnd.f(-0.8f, 0.8f)
                x += cos(a) * u * 1.1f
                y += sin(a) * u * 0.8f
            }
            pts
        }
        // The wider landscape of the large view has its own stones, beside the tree's.
        val far = Random(1911)
        val flank = (13 * (w - tw) / tw).toInt()
        val allStones = stones + (0 until flank).map {
            val x = far.f(4f * u, w - tw - 4f * u)
            Stone(if (x < left) x else x + tw, ground + depth * far.f(0.14f, 0.84f), far.f(1.6f, 6f) * u, FloatArray(11) { far.f(0.78f, 1.12f) }, far.nextFloat())
        }

        // The large view's landscape, when there is room beside the birch: Scots pines framing it and a
        // low Japanese maple beside it, all a little behind. Their roots share the soil with the birch's,
        // and fungal threads join them.
        val room = (w - tw) / 2
        var land: Land? = null
        val pines = mutableListOf<Pine>()
        var maple: Maple? = null
        val neighbourRoots = mutableListOf<Limb>()
        var pineRootCount = 0
        val links = mutableListOf<FloatArray>()
        if (room >= 0.6f * tw) {
            land = vista(Random(2030), w, ground, u, cx, tw)
            val land = Random(2026)
            // Unbalanced on purpose: on the left two pines further back on the hill, small and hazy,
            // with the lake between them; on the right one tall near pine at the edge, its crown high
            // over the maple, which stands clear of the birch's curtains. The furthest first.
            for ((x, height, back) in listOf(
                Triple(cx - tw * 0.5f - room * 0.6f, treeH * 0.4f, 1f),
                Triple(cx - tw * 0.5f - room * 0.95f, treeH * 0.5f, 0.7f),
                Triple(cx + tw * 0.5f + room * 0.9f, treeH * 0.74f, 0.05f),
            )) pines += pine(land, x, ground - (2f + 10f * back) * u, height, u, back)
            val m = maple(land, cx + tw * 1.02f, ground - 2.5f * u, treeH * 0.24f, u)
            maple = m
            val base = ground + 1.5f * u
            fun bounds(x: Float) = max(u, x - tw * 1.1f) to min(w - u, x + tw * 1.1f)
            // A pine: a smaller system of fine laterals, the longest reaching in towards the birch, with a
            // taproot and a few sinkers to anchor it.
            for (p in pines) {
                val x = p.trunk.x[0]
                val bw = p.trunk.w[0]
                val inward = if (cx > x) 1f else -1f
                val scale = p.height / (treeH * 0.55f)
                val (a, b) = bounds(x)
                for ((side, length, keep) in listOf(Triple(inward, 0.85f, 0.04f), Triple(inward, 0.5f, 0.11f), Triple(-inward, 0.45f, 0.05f), Triple(-inward, 0.3f, 0.13f)))
                    lateral(x + side * bw * 0.3f, base, side, tw * length * scale, bw * 0.3f, depth * keep, 0.5f, 2, neighbourRoots, land, a, b)
                sinker(x, base + u, bw * 0.34f, depth * 0.42f * scale, land.f(-0.1f, 0.1f), neighbourRoots, land)
            }
            pineRootCount = neighbourRoots.size
            // The maple: a shallow mat of fine roots, spreading furthest under the edge of the birch's.
            val (a, b) = bounds(m.x)
            val mw = m.limbs.maxOf { it.w[0] }
            for ((side, length, keep) in listOf(Triple(-1f, 0.8f, 0.03f), Triple(-1f, 0.5f, 0.07f), Triple(1f, 0.45f, 0.04f), Triple(1f, 0.3f, 0.08f), Triple(-1f, 0.3f, 0.12f)))
                lateral(m.x, base, side, tw * length, mw * 0.4f, depth * keep, 0.2f, 2, neighbourRoots, land, a, b)
            sinker(m.x - mw * 0.25f, base, mw * 0.3f, depth * 0.35f, -0.4f, neighbourRoots, land)
            sinker(m.x + mw * 0.2f, base, mw * 0.25f, depth * 0.29f, 0.35f, neighbourRoots, land)
            val birchTips = roots.filter { it.size <= 8 }
            val theirTips = neighbourRoots.filter { it.size <= 8 }
            // Threads from a neighbour's root tips to the nearest tip of another tree's, as the fungi do.
            repeat(14) {
                if (theirTips.isEmpty()) return@repeat
                val from = theirTips[land.nextInt(theirTips.size)]
                val ax = from.x[from.size - 1]
                val ay = from.y[from.size - 1]
                val to = (birchTips + theirTips).filter { abs(it.x[0] - from.x[0]) > tw * 0.25f }
                    .minByOrNull { (it.x[it.size - 1] - ax).pow(2) + (it.y[it.size - 1] - ay).pow(2) } ?: return@repeat
                val bx = to.x[to.size - 1]
                val by = to.y[to.size - 1]
                if (sqrt((bx - ax).pow(2) + (by - ay).pow(2)) > tw * 0.8f) return@repeat
                val n = 14
                val bend = land.f(-1f, 1f) * 3f * u
                links += FloatArray(n * 2) { i ->
                    val f = (i / 2) / (n - 1f)
                    if (i % 2 == 0) ax + (bx - ax) * f + land.f(-0.5f, 0.5f) * u
                    else ay + (by - ay) * f + sin(f * PI.toFloat()) * bend + land.f(-0.5f, 0.5f) * u
                }
            }
            // And their own short tufts round the tips, as round the birch's.
            repeat(16) {
                if (theirTips.isEmpty()) return@repeat
                val r = theirTips[land.nextInt(theirTips.size)]
                val k = land.nextInt(r.size)
                var x = r.x[k]
                var y = r.y[k]
                var angle = land.f(0f, 6.3f)
                links += FloatArray(16).also { pts ->
                    for (j in 0 until 8) {
                        pts[j * 2] = x
                        pts[j * 2 + 1] = y
                        angle += land.f(-0.8f, 0.8f)
                        x += cos(angle) * u * 1.1f
                        y += sin(angle) * u * 0.8f
                    }
                }
            }
        }
        return Tree(w, h, u, ground, top, sky, baseW, trunk, branches, curtains, leaves, roots, pockets, allStones, threads, junctions, tw,
            pines, maple, neighbourRoots, pineRootCount, links, land)
    }

    /**
     * The land beyond the birch in the large view. We stand on a hilltop: its rough grass runs back to
     * a brow, over which show the tops of the trees further down the slope; below lies a lake in a
     * forested valley, and behind it rolling forested hills (åser), each range paler with the air
     * between. Treetops are many small overlapping spires of random height and spacing, so the
     * skylines are ragged like real forest, not drawn.
     */
    private fun vista(rnd: Random, w: Float, ground: Float, u: Float, cx: Float, tw: Float): Land {
        val horizon = ground - 40f * u
        val step = 0.5f * u
        val n = (w / step).toInt() + 2
        fun skyline(base: (Float) -> Float, tall: Float, spacing: Float, sharp: Float, presence: (Float) -> Float): FloatArray {
            val ys = FloatArray(n) { base(it * step) }
            var x = -spacing
            while (x < w + spacing) {
                val p = presence(x)
                if (p > 0.05f) {
                    val h = tall * rnd.f(0.45f, 1f) * p
                    val half = h / sharp
                    for (i in ((x - half) / step).toInt().coerceAtLeast(0)..((x + half) / step).toInt().coerceAtMost(n - 1)) {
                        val y = base(i * step) - (h - abs(i * step - x) * sharp)
                        if (y < ys[i]) ys[i] = y
                    }
                }
                x += spacing * rnd.f(0.5f, 1.5f)
            }
            return ys
        }
        val shore = ground - 29f * u
        val far = skyline({ x -> horizon - u * (13f + 10f * sin(x / w * 2.1f + 0.3f) + 5f * sin(x / w * 4.7f + 2.2f) + 2f * sin(x / w * 11f)) }, 1.3f * u, 0.7f * u, 2.2f) { 1f }
        val near = skyline({ x -> shore - u * max(1f, 9f + 8f * sin(x / w * 3.1f + 1.4f) + 3.5f * sin(x / w * 7.3f + 0.2f)) }, 2.2f * u, 0.9f * u, 2.4f) { 1f }
        val browAt = { x: Float -> ground - u * (17f + 2.5f * sin(x / w * 3.7f + 0.9f) + 1.2f * sin(x / w * 11f + 2f)) }
        val brow = FloatArray(n) { browAt(it * step) }
        // The slope's trees stand in groups, with gaps where the lake shows over the brow.
        fun wood(x: Float) = sm(-0.1f, 0.35f, sin(x / w * 6.1f + 2.6f) + 0.5f * sin(x / w * 13.7f))
        // Norway spruce: narrow dark spires, closer and so more distinct than on the far hills.
        val slope = skyline({ x -> browAt(x) + 4f * u }, 12f * u, 1.4f * u, 3f) { wood(it) }
        // Scots pines among them, their bare stems and flat crowns standing above the spruce.
        val slopePines = mutableListOf<FloatArray>()
        var px = rnd.f(0f, 10f) * u
        while (px < w) {
            if (wood(px) > 0.3f) slopePines += floatArrayOf(px, browAt(px) + 3f * u, browAt(px) - rnd.f(12f, 17f) * u, rnd.nextInt(1000).toFloat())
            px += rnd.f(10f, 26f) * u
        }
        val broadleaves = mutableListOf<FloatArray>()
        var x = 0f
        while (x < w) {
            val p = wood(x)
            if (p > 0.5f && rnd.nextFloat() < 0.35f) {
                val group = rnd.f(4f, 9f) * u
                val tall = rnd.f(6f, 9f) * u * p
                repeat((group / u * 3).toInt()) {
                    val gx = x + rnd.f(0f, group)
                    val foot = browAt(gx) + 3f * u
                    val top = foot - tall * (0.75f + 0.25f * sin((gx - x) / group * PI.toFloat()))
                    val r = rnd.f(0.9f, 1.8f) * u
                    broadleaves += floatArrayOf(gx, top + rnd.f(0f, 1f) * (foot - top - r), r)
                }
                x += group
            }
            x += rnd.f(2f, 5f) * u
        }
        // Granite breaks through the thin soil of the hilltop here and there, with juniper and heather.
        fun spot(): FloatArray {
            var sx: Float
            do sx = rnd.f(0f, w) while (abs(sx - cx) < tw * 0.35f)
            val f = rnd.f(0.1f, 0.8f)
            return floatArrayOf(sx, browAt(sx) + (ground - browAt(sx)) * f, f, rnd.nextInt(1000).toFloat())
        }
        val rocks = List(7) { spot() }
        val shrubs = List(10) { spot() }
        return Land(horizon, step, far, near, shore, floatArrayOf(w * 0.1f, w * 0.78f), slope, brow, broadleaves, slopePines, rocks, shrubs)
    }

    /**
     * A Scots pine (furu): a straight trunk, grey-brown and plated low down, orange high up, bare
     * below but for a few dead stubs, its needles in flat dark pads on short level boughs and a
     * rounded, flattish top.
     */
    private fun pine(rnd: Random, x0: Float, base: Float, height: Float, u: Float, back: Float): Pine {
        val seed = rnd.nextInt()
        val n = 24
        val trunk = Limb(FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1), FloatArray(n + 1))
        val lean = rnd.f(-0.04f, 0.04f)
        val phase = rnd.f(0f, 6.3f)
        val w0 = height * 0.04f
        for (i in 0..n) {
            val t = i / n.toFloat()
            trunk.y[i] = base - t * height
            trunk.x[i] = x0 + lean * t * height + sin(t * 4f + phase) * height * 0.012f
            trunk.w[i] = w0 * (1 - 0.8f * t) + 0.25f * u
        }
        val boughs = mutableListOf<Limb>()
        val pads = mutableListOf<Pad>()
        // A clump of needles: a lumpy mass of overlapping ovals, not one smooth disc.
        fun pad(x: Float, y: Float, rx: Float) {
            val ry = rx * rnd.f(0.5f, 0.7f)
            val k = 14
            val blobs = FloatArray(k * 3)
            for (j in 0 until k) {
                val a = rnd.f(0f, 6.3f)
                val r = sqrt(rnd.nextFloat())
                blobs[j * 3] = cos(a) * r * rx * 0.8f
                blobs[j * 3 + 1] = sin(a) * r * ry * 0.7f
                blobs[j * 3 + 2] = rnd.f(0.2f, 0.4f) * rx
            }
            pads += Pad(x, y, rx, ry, blobs)
        }
        val half = height * rnd.f(0.17f, 0.21f)
        val crownBase = rnd.f(0.56f, 0.64f)
        var side = if (rnd.nextBoolean()) 1f else -1f
        val count = 16
        for (i in 0 until count) {
            val t = crownBase + (0.94f - crownBase) * (i + rnd.f(0.2f, 0.8f)) / count
            val at = (t * n).toInt().coerceIn(0, n)
            // Broadest a little above the crown's middle, narrowing to a flattish top rather than a point.
            val f = (t - crownBase) / (1 - crownBase)
            val profile = 0.35f + 0.65f * sin(PI.toFloat() * (0.12f + 0.7f * f)).pow(0.6f)
            val reach = half * profile * rnd.f(0.35f, 1.15f)
            val m = 6
            val bough = Limb(FloatArray(m + 1), FloatArray(m + 1), FloatArray(m + 1), FloatArray(m + 1))
            var a = rnd.f(0.35f, 0.7f)
            var bx = trunk.x[at]
            var by = trunk.y[at]
            for (k in 0..m) {
                bough.x[k] = bx
                bough.y[k] = by
                bough.w[k] = trunk.w[at] * 0.4f * (1 - 0.7f * k / m) + 0.2f * u
                bx += cos(a) * side * reach / m
                by -= sin(a) * reach / m
                a -= 0.12f
            }
            boughs += bough
            // Clumps along the bough's outer half, the end one largest and turned a little up.
            pad(bough.x[m], bough.y[m] - height * 0.012f, height * rnd.f(0.06f, 0.085f) * (0.65f + 0.35f * profile))
            if (reach > half * 0.45f) pad(bough.x[m * 2 / 3], bough.y[m * 2 / 3] + rnd.f(-0.5f, 0.5f) * u, height * rnd.f(0.04f, 0.06f))
            if (reach > half * 0.75f && rnd.nextFloat() < 0.6f) pad(bough.x[m / 3], bough.y[m / 3] - 0.5f * u, height * rnd.f(0.03f, 0.045f))
            if (rnd.nextFloat() < 0.75f) side = -side
        }
        pad(trunk.x[n], trunk.y[n] + height * 0.025f, height * 0.085f)
        pad(trunk.x[n] + rnd.f(-0.05f, 0.05f) * height, trunk.y[n] + height * 0.06f, height * 0.07f)
        // The lower trunk has shed its shaded branches; a few dead stubs remain.
        val stubs = (0 until 5).map {
            val at = (rnd.f(0.22f, crownBase - 0.04f) * n).toInt()
            val s = if (rnd.nextBoolean()) 1f else -1f
            floatArrayOf(trunk.x[at], trunk.y[at], trunk.x[at] + s * height * rnd.f(0.02f, 0.045f), trunk.y[at] - height * rnd.f(-0.005f, 0.015f))
        }
        return Pine(trunk, boughs, stubs, pads, height, back, seed)
    }

    /**
     * A Japanese maple (Acer palmatum): low and wider than tall, forking near the ground into a few
     * stems whose branches spread nearly level, so the foliage lies in tiers of flat sprays with
     * shade and branches showing between them. A leaf's `outer` is how sunlit it is: the top of
     * each spray and the outside of the crown, where the autumn colour is deepest.
     */
    private fun maple(rnd: Random, x0: Float, base: Float, height: Float, u: Float): Maple {
        val limbs = mutableListOf<Limb>()
        val twigs = mutableListOf<FloatArray>()
        val leaves = mutableListOf<MapleLeaf>()
        val cy = base - height * 0.55f
        val rx = height * 0.78f
        val ry = height * 0.45f
        fun inside(x: Float, y: Float) = ((x - x0) / rx).pow(2) + ((y - cy) / ry).pow(2)
        /** A flat spray of leaves held level over the end of a twig. */
        fun spray(x: Float, y: Float, size: Float) {
            val sx = size * rnd.f(0.8f, 1.2f)
            val sy = size * rnd.f(0.22f, 0.32f)
            val crown = sqrt(inside(x, y)).coerceIn(0f, 1f)
            // Fine twigs fanning level out of the spray, which carry its leaves.
            repeat(5) {
                val tx = x + rnd.f(-0.6f, 0.6f) * sx
                val ty = y + rnd.f(-0.3f, 0.3f) * sy
                val reach = rnd.f(0.3f, 0.6f) * sx
                twigs += floatArrayOf(tx, ty, tx + reach * (if (tx >= x) 1f else -1f), ty - rnd.f(-0.25f, 0.35f) * reach)
            }
            repeat((sx / u * 7f).toInt().coerceIn(10, 90)) {
                val fx = rnd.nextGaussian().toFloat() * 0.45f
                val fy = rnd.nextGaussian().toFloat() * 0.45f
                if (abs(fx) > 1.1f) return@repeat
                val lx = x + fx * sx
                // The spray droops a little at its ends.
                val ly = y - sy * 0.3f + fy * sy + fx * fx * sy * 0.6f
                val top = (0.5f - fy).coerceIn(0f, 1f)
                val outer = (0.55f * top + 0.45f * crown + 0.2f * ((cy - ly) / ry)).coerceIn(0f, 1f)
                leaves += MapleLeaf(lx, ly, rnd.f(0f, 6.3f), rnd.f(0.8f, 1.2f), 0.6f * rnd.nextFloat() + 0.4f * (1 - outer), rnd.nextFloat(), outer, rnd.nextFloat())
            }
        }
        // `a` is the angle from upright, positive to the right; branches are pulled towards level.
        fun branch(xs: Float, ys: Float, a0: Float, length: Float, w0: Float, level: Int) {
            val n = 6
            val bx = FloatArray(n + 1)
            val by = FloatArray(n + 1)
            val bw = FloatArray(n + 1)
            var a = a0
            var x = xs
            var y = ys
            var last = n
            val level0 = if (a0 >= 0) 1.25f else -1.25f
            for (k in 0..n) {
                bx[k] = x
                by[k] = y
                bw[k] = w0 * (1 - 0.4f * k / n) + 0.12f * u
                if (level > 1 && inside(x, y) > 1f) { last = k; break }
                if (level >= 1) a += (level0 - a) * 0.12f
                a += rnd.f(-0.1f, 0.1f)
                x += sin(a) * length / n
                y -= cos(a) * length / n
            }
            if (last < 2) return
            val limb = Limb(bx.copyOf(last + 1), by.copyOf(last + 1), bw.copyOf(last + 1), FloatArray(last + 1))
            limbs += limb
            val ex = limb.x[last]
            val ey = limb.y[last]
            if (level >= 2) for (k in (last / 3)..last step 2) spray(limb.x[k], limb.y[k], (7f - level) * 2f * u)
            if (level >= 5 || last < n) { spray(ex, ey, 7f * u); return }
            val spread = if (level == 0) listOf(-0.75f, -0.2f, 0.35f, 0.85f) else listOf(-0.35f, 0.3f)
            for (turn in spread) {
                // Each fork rises a little, then is drawn level again: the tiers.
                val rise = if (level == 0) 0f else -0.35f * sign(a)
                branch(ex, ey, a + turn + rise + rnd.f(-0.15f, 0.15f), length * rnd.f(0.68f, 0.8f), w0 * 0.62f, level + 1)
            }
        }
        // Several stems from low down, leaning out.
        for (lean in listOf(-0.55f, 0.12f, 0.6f)) branch(x0 + lean * 0.8f * u, base + u, lean + rnd.f(-0.1f, 0.1f), height * rnd.f(0.3f, 0.38f), height * 0.032f, 1)
        branch(x0, base + u, rnd.f(-0.05f, 0.05f), height * 0.2f, height * 0.045f, 0)
        return Maple(x0, cy, rx, limbs, twigs, leaves)
    }

    // ---------------------------------------------------------------- light

    private class Light(s: TreeScene) {
        val e = s.sunElevation.toFloat()
        val cloud = sm(0.3f, 0.95f, s.cloud.toFloat())
        val rain = (1 - exp(-(s.rain + s.snowfall * 0.6) / 1.2)).toFloat()
        val night = 1 - sm(-12f, -2f, e)
        val day = sm(-1f, 14f, e)
        /** Direct sun: what casts shadows and lights one side of the trunk. */
        val sun = sm(-1f, 8f, e) * (1 - cloud * 0.9f) * (1 - rain * 0.5f)
        val golden = sm(-3f, 2f, e) * (1 - sm(5f, 14f, e)) * (1 - cloud)
        /** Low sun shining straight in under the crown: it catches the white bark and the sides of the twigs. */
        val lowSun = golden * sm(-1.5f, 2.5f, e) * (1 - rain * 0.7f)
        /**
         * Sunrise and sunset under an open sky: the warmth gathers in a band low on the sun's side
         * ([sky] paints it there) while the upper sky deepens to a cool blue.
         */
        val dusk = sm(-9f, -1.5f, e) * (1 - sm(3f, 11f, e)) * (1 - cloud) * (1 - rain)
        /** The colour of that band, deepening from gold to a dull rose as the sun goes further down. */
        val glow: Int = ramp(e, floatArrayOf(-8f, -4.5f, -1.5f, 1.5f, 6f),
            if (s.morning) intArrayOf(0xFF6F6280.toInt(), 0xFFB27A86.toInt(), 0xFFE8A27E.toInt(), 0xFFF0BE8E.toInt(), 0xFFF0D8B4.toInt())
            else intArrayOf(0xFF6E5A70.toInt(), 0xFFB46A64.toInt(), 0xFFE58F55.toInt(), 0xFFEDB070.toInt(), 0xFFEFD0A0.toInt()))
        val moonVis = if (s.moonElevation > -2 && night > 0.2f) (1 - sm(0.55f, 0.95f, s.cloud.toFloat())) * Sky.illuminated(s.moonPhase).toFloat() else 0f
        /** Overcast nights: low cloud lit from below by the town, a faint light from the whole sky. */
        val skyglow = night * cloud * (1 - 0.4f * rain)
        /**
         * How strongly the night picks out the trunk and limbs against the sky: a little from starlight
         * alone, more from the moon on clear nights or the skyglow on cloudy ones.
         */
        val rim = night * (0.3f + 0.55f * moonVis + 0.35f * skyglow).coerceAtMost(1f)
        /** A clear night well below freezing. */
        val cold = night * sm(0f, -12f, s.temp.toFloat()) * (1 - cloud)
        /** A mild night after rain, the air heavy with it. */
        val damp = night * sm(1f, 7f, s.temp.toFloat()) * max(sm(0.3f, 0.7f, s.soil.toFloat()), rain)
        val skyTop: Int
        val skyHorizon: Int
        val r: Float
        val g: Float
        val b: Float
        val sat: Float

        init {
            val m = s.morning
            val clearTop = ramp(e, floatArrayOf(-16f, -9f, -4f, 1f, 6f, 18f),
                intArrayOf(0xFF0B1322.toInt(), 0xFF141E33.toInt(), 0xFF2F3D5A.toInt(), 0xFF56698A.toInt(), 0xFF7D94AE.toInt(), 0xFF7A99B4.toInt()))
            val clearHorizon = ramp(e, floatArrayOf(-16f, -9f, -4f, 1f, 6f, 18f),
                intArrayOf(0xFF18233A.toInt(), 0xFF2A3552.toInt(), if (m) 0xFF7F86A6.toInt() else 0xFF9A8394.toInt(),
                    if (m) 0xFFD8AC9C.toInt() else 0xFFDCA67E.toInt(), if (m) 0xFFE3D3BF.toInt() else 0xFFE2C6A0.toInt(), 0xFFC9D8DE.toInt()))
            val greyTop = ramp(e, floatArrayOf(-14f, -4f, 4f, 14f), intArrayOf(0xFF1E222B.toInt(), 0xFF3E434C.toInt(), 0xFF747B83.toInt(), 0xFF8E969D.toInt()))
            // Overcast nights glow faintly orange from the town.
            val greyHorizon = ramp(e, floatArrayOf(-14f, -4f, 4f, 14f), intArrayOf(0xFF433D40.toInt(), 0xFF5D5B60.toInt(), 0xFF9EA2A4.toInt(), 0xFFB8BDBF.toInt()))
            val dim = 1 - 0.22f * rain - if (s.thunder) 0.15f else 0f
            var top = shade(mix(clearTop, greyTop, cloud), dim)
            var horizon = shade(mix(clearHorizon, greyHorizon, cloud), dim)
            // In twilight the warmth is local, so the sky as a whole cools: a deeper blue overhead and a
            // lilac-grey horizon away from the sun. The picture grows no brighter, only deeper.
            top = mix(top, if (m) 0xFF35507E.toInt() else 0xFF30497A.toInt(), 0.4f * dusk)
            horizon = mix(horizon, if (m) 0xFF8A8CAC.toInt() else 0xFF7F86A6.toInt(), 0.55f * dusk)
            // The night's mood: a cold clear night is crisp and pale blue-black; a mild damp one is
            // hazier, its gradient softer and its colour a muted brown-blue.
            top = mix(top, 0xFF0E1A30.toInt(), 0.5f * cold)
            horizon = mix(horizon, 0xFF27344E.toInt(), 0.45f * cold)
            horizon = mix(horizon, 0xFF3B3842.toInt(), 0.35f * damp)
            top = mix(top, mix(top, horizon, 0.5f), 0.35f * damp)
            if (s.fog > 0) {
                val fog = ramp(e, floatArrayOf(-10f, 0f, 10f), intArrayOf(0xFF22262B.toInt(), 0xFF7E8386.toInt(), 0xFFB4B8B7.toInt()))
                top = mix(top, fog, 0.55f * s.fog.toFloat())
                horizon = mix(horizon, fog, 0.8f * s.fog.toFloat())
            }
            skyTop = top
            skyHorizon = horizon
            // Ambient colour of everything lit by the sky: night blue, blue hour, sunrise, golden, day.
            val stops = floatArrayOf(-12f, -4f, 1f, 6f, 16f)
            var ar = ramp1(e, stops, floatArrayOf(0.34f, if (m) 0.5f else 0.53f, if (m) 0.86f else 0.9f, 1.02f, 1f))
            var ag = ramp1(e, stops, floatArrayOf(0.38f, if (m) 0.53f else 0.5f, if (m) 0.78f else 0.76f, 0.94f, 1f))
            var ab = ramp1(e, stops, floatArrayOf(0.52f, if (m) 0.68f else 0.63f, if (m) 0.8f else 0.66f, 0.82f, 1f))
            val avg = (ar + ag + ab) / 3
            val grey = cloud * 0.8f
            ar += (avg * 0.96f - ar) * grey
            ag += (avg * 0.98f - ag) * grey
            ab += (avg * 1.03f - ab) * grey
            val bright = (1 - 0.1f * cloud - 0.14f * rain) * (if (s.thunder) 0.85f else 1f)
            val warmth = s.season.warmth.toFloat() * 0.02f * day
            // Moonlight is a cool blue-grey, skyglow a duller warm grey; both stay well below dusk.
            r = ar * bright * (1 + warmth) + 0.05f * moonVis + 0.045f * skyglow + 0.02f * night
            g = ag * bright + 0.065f * moonVis + 0.045f * skyglow + 0.025f * night
            b = ab * bright * (1 - warmth) + 0.1f * moonVis + 0.04f * skyglow + 0.03f * night
            sat = (1 - 0.25f * cloud) * (1 - 0.15f * rain) * (1 - 0.6f * night)
        }

        /** The colour filter for a layer; underground gets half the effect, like a lit cutaway. */
        fun filter(strength: Float): ColorMatrixColorFilter {
            val matrix = ColorMatrix().apply { setSaturation(1 + (sat - 1) * strength) }
            val contrast = 1 - 0.14f * cloud * strength
            val rr = 1 + (r - 1) * strength
            val gg = 1 + (g - 1) * strength
            val bb = 1 + (b - 1) * strength
            matrix.postConcat(ColorMatrix(floatArrayOf(
                rr * contrast, 0f, 0f, 0f, (1 - contrast) * 110 * rr,
                0f, gg * contrast, 0f, 0f, (1 - contrast) * 110 * gg,
                0f, 0f, bb * contrast, 0f, (1 - contrast) * 110 * bb,
                0f, 0f, 0f, 1f, 0f)))
            return ColorMatrixColorFilter(matrix)
        }

        /** A plain colour as this light shows it, for things drawn outside the filtered layers. */
        fun lit(c: Int): Int = Color.argb(Color.alpha(c), (Color.red(c) * r).toInt().coerceIn(0, 255),
            (Color.green(c) * g).toInt().coerceIn(0, 255), (Color.blue(c) * b).toInt().coerceIn(0, 255))
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

    // ---------------------------------------------------------------- painting

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var grain: Bitmap? = null

    /**
     * One frame. Frame 1 is the same moment a few seconds on: twigs swung the other way, rain and
     * snow further down, falling leaves lower. `cornerRadius` is in pixels.
     */
    @Synchronized
    fun paint(scene: TreeScene, width: Int, height: Int, frame: Int, cornerRadius: Float, nowMs: Long, treeWidth: Int = width): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val t = tree(width, height, treeWidth.coerceAtMost(width))
        val light = Light(scene)
        val u = t.u
        val lean = sm(2f, 14f, scene.wind.toFloat())
        val swing = if (scene.animated) (frame * 2 - 1).toFloat() else 0f
        val ws = scene.windSide.toFloat()
        // From a strong gale on, the long curtains stream out sideways, nearly level in a hurricane, and
        // the whole crown leans with them. A twig hangs steeper near its branch and flies out towards
        // its tip, so in a storm each curtain curves rather than slanting straight.
        val storm = sm(9f, 25f, scene.wind.toFloat())
        val sway = ws * (lean * 2f + storm * 4f) * u + swing * (0.3f + 1.1f * lean + 1.2f * storm) * u
        val stream = ws * (0.025f + 0.16f * lean) + swing * (0.01f + 0.035f * lean)
        val gale = ws * 0.62f * storm + swing * 0.07f * storm

        sky(canvas, t, scene, light, nowMs, frame)
        val rect = RectF(0f, 0f, t.w, t.h)
        // The cutaway is lit like a diagram by day, but by night it darkens nearly as much as the world above.
        canvas.saveLayer(rect, Paint().apply { colorFilter = light.filter(0.5f + 0.38f * light.night) })
        underground(canvas, t, scene)
        canvas.restore()
        // By night the deep layers sink into the dark first, so the eye stays with the tree and its roots.
        if (light.night > 0.01f) {
            val dark = 0xFF07080C.toInt()
            fill.with(LinearGradient(0f, t.ground + t.depth * 0.3f, 0f, t.h, alpha(dark, 0f), alpha(dark, 0.55f * light.night), Shader.TileMode.CLAMP))
            canvas.drawRect(0f, t.ground, t.w, t.h, fill)
            fill.shader = null
        }
        canvas.saveLayer(rect, Paint().apply { colorFilter = light.filter(1f) })
        val dx = { _: Float, y: Float, hang: Float ->
            sway * ((t.ground - y) / t.height).coerceIn(0f, 1f).pow(2) + stream * hang * (1 - 0.25f * hang / t.height) +
                gale * hang * (0.3f + 0.9f * sm(0f, 0.4f * t.height, hang))
        }
        landscape(canvas, t, scene, light, dx)
        groundBack(canvas, t, scene, light)
        curtains(canvas, t, scene, light, dx, front = false)
        trunk(canvas, t, scene, light)
        branches(canvas, t, scene, light, dx)
        sunRim(canvas, t, scene, light, dx)
        nightRim(canvas, t, scene, light, dx)
        curtains(canvas, t, scene, light, dx, front = true)
        nightLimbs(canvas, t, scene, light, dx)
        perchedBird(canvas, t, scene, dx)
        groundFront(canvas, t, scene, light, frame)
        fallingLeaves(canvas, t, scene, frame)
        canvas.restore()
        weather(canvas, t, scene, light, frame)
        finish(canvas, t, scene, light, cornerRadius)
        return bitmap
    }

    private fun sky(canvas: Canvas, t: Tree, s: TreeScene, light: Light, nowMs: Long, frame: Int) {
        val u = t.u
        fill.with(LinearGradient(0f, 0f, 0f, t.ground, intArrayOf(light.skyTop, mix(light.skyTop, light.skyHorizon, 0.55f), light.skyHorizon),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP))
        canvas.drawRect(0f, 0f, t.w, t.ground + 4 * u, fill)
        fill.shader = null
        // By night the sky grows paler towards the horizon: faintly on clear nights, more under cloud,
        // whose base the town lights from below. Seen through the gaps in the curtains, it gives the
        // crown something to stand against. A plain gradient, no halo.
        val low = light.night * (0.2f + 0.8f * light.cloud) * (1 - 0.6f * s.fog.toFloat())
        if (low > 0.02f) {
            val pale = mix(light.skyHorizon, 0xFF77706C.toInt(), 0.35f + 0.25f * light.cloud)
            fill.with(LinearGradient(0f, t.sky, 0f, t.ground, intArrayOf(alpha(pale, 0f), alpha(pale, 0.3f * low), alpha(pale, 0.6f * low)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP))
            canvas.drawRect(0f, t.sky, t.w, t.ground + 4 * u, fill)
            fill.shader = null
        }

        dusk(canvas, t, s, light)

        // Stars, when it is dark enough and the sky is clear enough.
        val stars = light.night * (1 - sm(0.2f, 0.65f, s.cloud.toFloat())) * (1 - 0.6f * light.moonVis)
        if (stars > 0.02f) {
            val rnd = Random(77)
            val twinkle = Random(s.variation + frame)
            repeat(t.spread(85)) {
                val x = rnd.nextFloat() * t.w
                val y = t.sky + rnd.nextFloat().pow(1.3f) * t.skyHeight * 0.72f
                val size = rnd.f(0.18f, 0.5f) * u
                // Cold dry air shows them a little sharper.
                val a = stars * rnd.f(0.35f, 1f) * twinkle.f(0.75f, 1f) * (1 + 0.3f * light.cold)
                fill.color = alpha(0xFFE6EBF5.toInt(), a)
                canvas.drawCircle(x, y, max(size, 0.55f), fill)
            }
        }
        if (light.moonVis > 0.02f) moon(canvas, t, s, light)
        aurora(canvas, t, s, frame)
        if (s.sights.meteors > 0 && (frame == 1 || !s.animated)) meteors(canvas, t, s)

        // The sun itself stays out of the picture; its light gathers in the sky on its side.
        if (s.sunElevation > -7) {
            val sx = t.cx + s.sunSide.toFloat() * t.w * 0.45f
            val sy = t.ground - t.skyHeight * (0.04f + 0.9f * ((s.sunElevation.toFloat() + 2) / 45f).coerceIn(0f, 1f))
            val warm = mix(0xFFFFF0D8.toInt(), if (s.morning) 0xFFF3C9A2.toInt() else 0xFFF2BE7C.toInt(), light.golden + (1 - light.day) * 0.7f)
            // In twilight the band low on the sun's side carries the warmth instead of this wide wash.
            val a = (0.5f * (1 - light.cloud * 0.8f) * sm(-7f, 1f, s.sunElevation.toFloat())) * (1 + 0.04f * (frame * 2 - 1)) * (1 - 0.6f * light.dusk)
            fill.with(RadialGradient(sx, sy, t.w * 1.2f, intArrayOf(alpha(warm, a), alpha(warm, a * 0.35f), alpha(warm, 0f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP))
            canvas.drawRect(0f, 0f, t.w, t.ground + 4 * u, fill)
            fill.shader = null
        }
        // A low sun shows itself near the horizon, between the curtains.
        if (s.sunElevation in -1.0..7.0) {
            val vis = (1 - sm(0.3f, 0.85f, s.cloud.toFloat())) * sm(-1f, 0.5f, s.sunElevation.toFloat())
            if (vis > 0.02f) {
                val sx = t.cx + s.sunSide.toFloat() * t.w * 0.36f
                val sy = t.skyline - (4f + 2.2f * s.sunElevation.toFloat()) * u
                fill.with(RadialGradient(sx, sy, 14 * u, intArrayOf(alpha(0xFFFFE2A8.toInt(), 0.75f * vis), alpha(0xFFF5B86A.toInt(), 0.3f * vis), alpha(0xFFF5B86A.toInt(), 0f)), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP))
                canvas.drawCircle(sx, sy, 14 * u, fill)
                fill.shader = null
                fill.color = alpha(0xFFFFF1D2.toInt(), 0.95f * vis)
                canvas.drawCircle(sx, sy, 2.6f * u, fill)
            }
        }
        clouds(canvas, t, s, light, nowMs, frame)
        flight(canvas, t, s, light, frame)
        val land = t.land
        if (land != null) vista(canvas, t, s, light, land) else hills(canvas, t, s, light)
    }

    /**
     * How much of the grass is dead straw: it greens up in spring and dies back in late autumn on the
     * tree's own year, so a warm spring greens it sooner.
     */
    private fun strawGrass(season: Season): Float =
        if (season.dayOfYear >= 200) Season.smooth(285.0, 325.0, season.autumnDay).toFloat() else 1 - Season.smooth(95.0, 128.0, season.springDay).toFloat()

    /** A summer dry spell, only while the grass is growing. */
    private fun parched(s: TreeScene): Float {
        val day = s.season.dayOfYear.toFloat()
        return s.dry.toFloat() * sm(130f, 160f, day) * (1 - sm(250f, 275f, day))
    }

    /** Hoar frost where the ground is not under snow. */
    private fun rime(s: TreeScene): Float = s.frost.toFloat() * (1 - sm(0.3f, 1.5f, s.snow.toFloat()))

    /** Where the sun's light gathers across the picture. */
    private fun sunX(t: Tree, s: TreeScene) = t.cx + s.sunSide.toFloat() * t.w * 0.4f

    /**
     * Twilight's depth: a warm band lying low over the horizon on the sun's side, fading upwards
     * into the cooled sky and away along the horizon. In the large view's wider sky the other side
     * shows the earth's shadow, a dusky blue band with a faint rose above it.
     */
    private fun dusk(canvas: Canvas, t: Tree, s: TreeScene, light: Light) {
        val d = light.dusk
        if (d < 0.02f) return
        val e = s.sunElevation.toFloat()
        val sx = sunX(t, s)
        val y = t.skyline
        // The band hugs the horizon: lower and tighter as the sun sinks, wider across in the large view.
        val rx = max(t.w * 0.6f, t.tw * 1.1f)
        val ry = t.skyHeight * (0.16f + 0.14f * sm(-6f, 4f, e))
        fill.with(RadialGradient(sx, y, rx, intArrayOf(alpha(light.glow, 0.8f * d), alpha(light.glow, 0.42f * d), alpha(light.glow, 0f)),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP))
        canvas.save()
        canvas.scale(1f, ry / rx, sx, y)
        canvas.drawCircle(sx, y, rx, fill)
        canvas.restore()
        // Brightest just where the sun is or has just gone.
        val core = mix(light.glow, 0xFFFFE1A8.toInt(), 0.4f)
        val cr = rx * 0.35f
        fill.with(RadialGradient(sx, y, cr, intArrayOf(alpha(core, 0.55f * d * sm(-5f, 0f, e)), alpha(core, 0f)), null, Shader.TileMode.CLAMP))
        canvas.save()
        canvas.scale(1f, 0.32f, sx, y)
        canvas.drawCircle(sx, y, cr, fill)
        canvas.restore()
        if (t.land != null && e < 2f) {
            val shadowSide = t.cx - s.sunSide.toFloat().let { if (abs(it) < 0.2f) 0.2f else it } * t.w * 0.45f
            val belt = d * (1 - sm(-1f, 2f, e)) * sm(-8f, -2.5f, e).let { 0.4f + 0.6f * it }
            val band = t.skyHeight * 0.1f
            for ((offset, c, a) in listOf(Triple(0.2f, 0xFF4C5675.toInt(), 0.22f), Triple(1.4f, 0xFFB98D9C.toInt(), 0.14f))) {
                val by = y - band * offset
                fill.with(RadialGradient(shadowSide, by, t.w * 0.5f, intArrayOf(alpha(c, a * belt), alpha(c, 0f)), null, Shader.TileMode.CLAMP))
                canvas.save()
                canvas.scale(1f, band / (t.w * 0.5f), shadowSide, by)
                canvas.drawCircle(shadowSide, by, t.w * 0.5f, fill)
                canvas.restore()
            }
        }
        fill.shader = null
    }

    /** The large view's distance, as [vista] grew it, in the season's colours and softened by the air. */
    private fun vista(canvas: Canvas, t: Tree, s: TreeScene, light: Light, land: Land) {
        val u = t.u
        val season = s.season
        val fog = s.fog.toFloat()
        val snow = sm(0.5f, 4f, s.snow.toFloat())
        fun hazed(c: Int, k: Float) = mix(light.lit(c), light.skyHorizon, (k + 0.7f * fog * (1 - k)).coerceAtMost(0.96f))
        val white = 0xFFE4E9EE.toInt()
        // Spruce forest is near black-green all year; under snow the ground between shows pale.
        val forest = mix(0xFF2E3F34.toInt(), white, 0.18f * snow)
        fun skyline(ys: FloatArray, bottom: Float, top: Float, foot: Float, color: Int) {
            val path = Path()
            path.moveTo(0f, bottom)
            for (i in ys.indices) path.lineTo(i * land.step, ys[i])
            path.lineTo(t.w, bottom)
            path.close()
            // Paler at its foot, where the air near the ground is thicker.
            fill.with(LinearGradient(0f, ys.minOrNull() ?: bottom, 0f, bottom, hazed(color, top), hazed(color, foot), Shader.TileMode.CLAMP))
            canvas.drawPath(path, fill)
            fill.shader = null
        }
        skyline(land.far, land.shore + 2 * u, 0.7f, 0.82f, forest)
        skyline(land.near, land.shore + 2 * u, 0.46f, 0.6f, forest)
        // The valley floor, forested, and the lake in it.
        fill.color = hazed(forest, 0.5f)
        canvas.drawRect(0f, land.shore, t.w, land.brow.maxOrNull()!! + 6 * u, fill)
        val (l, r) = land.lake[0] to land.lake[1]
        val lowest = land.brow.maxOrNull()!! + 6 * u
        val water = Path()
        water.moveTo(l, lowest)
        var x = l
        while (x <= r) {
            // Its ends bend away out of sight behind the slope.
            val e = ((x - l) / (r - l) * 2 - 1)
            water.lineTo(x, land.shore + u * (0.4f * sin(x / u * 0.3f) + 14f * e.pow(8)))
            x += u
        }
        water.lineTo(r, lowest)
        water.close()
        // It mirrors the sky: the paler horizon at the far shore, the sky overhead nearer.
        val frozen = sm(-2f, -8f, s.temp.toFloat()) * sm(0.3f, 2f, s.snow.toFloat()) * (if (season.dayOfYear in 100..300) 0f else 1f)
        var far = mix(light.skyHorizon, light.skyTop, 0.15f)
        var nearTone = shade(mix(light.skyTop, light.skyHorizon, 0.35f), 0.88f)
        far = mix(far, light.lit(0xFFD8DEE4.toInt()), frozen * 0.8f)
        nearTone = mix(nearTone, light.lit(0xFFC8D0D8.toInt()), frozen * 0.8f)
        fill.with(LinearGradient(0f, land.shore, 0f, land.shore + 12 * u, mix(far, light.skyHorizon, fog), mix(nearTone, light.skyHorizon, fog), Shader.TileMode.CLAMP))
        canvas.drawPath(water, fill)
        fill.shader = null
        if (frozen < 0.5f) {
            canvas.save()
            canvas.clipPath(water)
            // The far shore's forest mirrored in a dark band along it.
            fill.with(LinearGradient(0f, land.shore, 0f, land.shore + 3.5f * u, alpha(hazed(forest, 0.5f), 0.7f), alpha(hazed(forest, 0.5f), 0f), Shader.TileMode.CLAMP))
            canvas.drawRect(l, land.shore, r, land.shore + 3.5f * u, fill)
            fill.shader = null
            // The twilight band on the water, under the sun's side.
            if (light.dusk > 0.02f) {
                val sx = sunX(t, s)
                fill.with(RadialGradient(sx, land.shore, t.w * 0.3f, intArrayOf(alpha(light.glow, 0.45f * light.dusk), alpha(light.glow, 0f)), null, Shader.TileMode.CLAMP))
                canvas.save()
                canvas.scale(1f, 0.08f, sx, land.shore)
                canvas.drawCircle(sx, land.shore, t.w * 0.3f, fill)
                canvas.restore()
                fill.shader = null
            }
            // Wind lines on the water catching the light.
            val glint = Random(59)
            stroke.strokeWidth = max(0.5f, 0.2f * u)
            stroke.color = alpha(mix(light.skyHorizon, 0xFFFFFFFF.toInt(), 0.5f), (0.12f + 0.1f * sm(2f, 8f, s.wind.toFloat())) * (1 - 0.6f * light.night))
            repeat(7) {
                val gx = glint.f(l, r)
                val gy = land.shore + glint.f(3f, 11f) * u
                canvas.drawLine(gx, gy, gx + glint.f(3f, 14f) * u, gy, stroke)
            }
            canvas.restore()
        }
        // The trees down our own slope, their tops showing over the brow: pines rising above the spruce.
        for (p in land.pines) {
            val rr = Random(p[3].toLong())
            val tall = p[1] - p[2]
            stroke.strokeWidth = max(0.5f, tall * 0.022f)
            stroke.color = hazed(0xFF6A4A3A.toInt(), 0.3f)
            canvas.drawLine(p[0], p[1], p[0] + rr.f(-0.5f, 0.5f) * u, p[2] + tall * 0.2f, stroke)
            // An uneven crown of a few merged clumps, wider than tall.
            fill.color = hazed(shade(forest, 1.05f), 0.3f)
            repeat(7) {
                val cx = p[0] + rr.f(-2.6f, 2.6f) * u
                val cy = p[2] + rr.f(0f, 0.28f) * tall
                val rx = rr.f(1f, 2f) * u
                canvas.drawOval(cx - rx, cy - rx * 0.6f, cx + rx, cy + rx * 0.6f, fill)
            }
        }
        skyline(land.slope, lowest, 0.2f, 0.3f, forest)
        val leaf = mix(mix(0xFF7E9A4E.toInt(), 0xFF5D7443.toInt(), 1 - season.fresh.toFloat()), 0xFFC49A3E.toInt(), season.turn.toFloat())
        val bare = mix(0xFF6E6463.toInt(), white, 0.25f * snow)
        val muted = mix(mix(bare, leaf, sm(0f, 0.5f, season.leaves.toFloat())), forest, 0.4f)
        val lobes = Random(83)
        for (b in land.broadleaves) {
            fill.color = hazed(shade(muted, lobes.f(0.78f, 1.05f)), 0.24f)
            canvas.drawCircle(b[0], b[1], b[2], fill)
        }
        // The hilltop: rough grass, tawny in autumn and winter, and snow on it.
        val day = season.dayOfYear
        val dead = strawGrass(season)
        var meadow = mix(mix(0xFF8AA05E.toInt(), 0xFF6B7F45.toInt(), 1 - season.fresh.toFloat()), 0xFF9C9262.toInt(), season.turn.toFloat() * 0.6f)
        meadow = mix(meadow, 0xFF8E8466.toInt(), dead)
        // A dry summer bleaches the thin soil of the hilltop first; hoar frost greys it on cold mornings.
        meadow = mix(meadow, 0xFFB8A46A.toInt(), 0.8f * parched(s))
        meadow = mix(meadow, 0xFFC9D2D8.toInt(), 0.45f * rime(s))
        meadow = shade(mix(meadow, white, snow), 1 - 0.12f * s.surface.toFloat())
        val hill = Path()
        hill.moveTo(0f, t.ground + 4 * u)
        for (i in land.brow.indices) hill.lineTo(i * land.step, land.brow[i])
        hill.lineTo(t.w, t.ground + 4 * u)
        hill.close()
        val browTop = land.brow.minOrNull()!!
        fill.with(LinearGradient(0f, browTop, 0f, t.ground, hazed(meadow, 0.16f), hazed(meadow, 0.02f), Shader.TileMode.CLAMP))
        canvas.drawPath(hill, fill)
        fill.shader = null
        // Thin snow melts in patches, leaving drifts in the hollows and lee of the rocks; a first dusting
        // lies the same way.
        val patches = sm(0.2f, 1f, s.snow.toFloat()) * (1 - sm(3f, 6f, s.snow.toFloat()))
        if (patches > 0.02f) {
            val pr = Random(87)
            fill.color = hazed(white, 0.08f)
            repeat(t.spread(26)) {
                val px = pr.f(0f, t.w)
                val f = pr.nextFloat()
                val py = land.brow[(px / land.step).toInt().coerceIn(0, land.brow.size - 1)] + (t.ground - land.brow[(px / land.step).toInt().coerceIn(0, land.brow.size - 1)]) * f
                val rx = pr.f(2f, 7f) * u * (0.4f + 0.6f * f)
                if (pr.nextFloat() > patches) return@repeat
                canvas.drawOval(px - rx, py - rx * 0.12f, px + rx, py + rx * 0.12f, fill)
            }
        }
        // Granite outcrops, rounded by the ice, lit on top.
        for (rock in land.rocks) {
            val size = (3.5f + 9f * rock[2]) * u
            val rr = Random(rock[3].toLong())
            val path = Path()
            val k = 9
            for (i in 0..k) {
                val f = i / k.toFloat()
                val px = rock[0] - size + 2 * size * f
                val py = rock[1] - size * 0.42f * sin(PI.toFloat() * f).pow(0.7f) * rr.f(0.8f, 1.15f)
                if (i == 0) path.moveTo(px, rock[1]) else path.lineTo(px, py)
            }
            path.lineTo(rock[0] + size, rock[1])
            path.close()
            val k2 = 0.16f * (1 - rock[2])
            val stone = mix(0xFF8C8A84.toInt(), white, 0.5f * snow)
            fill.with(LinearGradient(0f, rock[1] - size * 0.45f, 0f, rock[1], hazed(shade(stone, 1.08f), k2), hazed(shade(stone, 0.7f), k2), Shader.TileMode.CLAMP))
            canvas.drawPath(path, fill)
            fill.shader = null
        }
        // Juniper and heather in low dark clumps; heather browns in autumn.
        for (sh in land.shrubs) {
            val size = (0.8f + 2.6f * sh[2]) * u * (1 + 0.3f * Season.smooth(212.0, 222.0, day.toDouble()).toFloat() * (1 - Season.smooth(245.0, 258.0, day.toDouble()).toFloat()))
            val rr = Random(sh[3].toLong())
            val juniper = rr.nextFloat() < 0.4f
            // Heather flowers a dusky purple in August.
            val heather = Season.smooth(212.0, 222.0, day.toDouble()).toFloat() * (1 - Season.smooth(245.0, 258.0, day.toDouble()).toFloat())
            val c = if (juniper) 0xFF35463A.toInt() else mix(mix(0xFF5E6046.toInt(), 0xFF655248.toInt(), season.turn.toFloat() * 0.6f), 0xFF9A5C92.toInt(), 0.8f * heather)
            fill.color = hazed(mix(c, white, 0.6f * snow), 0.16f * (1 - sh[2]))
            repeat(if (juniper) 5 else 3) {
                canvas.drawOval(sh[0] + rr.f(-1f, 1f) * size - size * 0.6f, sh[1] - size * rr.f(0.6f, if (juniper) 1.6f else 0.9f),
                    sh[0] + rr.f(-1f, 1f) * size + size * 0.6f, sh[1] + 0.2f * size, fill)
            }
        }
    }

    /**
     * The northern lights, when NOAA's outlook, the dark and the cloud allow it: a low green arc with
     * soft rays above it, as southern Norway sees them, behind the crown and the clouds. Only a strong
     * display reaches high and takes a faint red top. Dim and blurred, like the real thing seen from a
     * suburban garden, not neon. The rays shift a little between the two frames.
     */
    private fun aurora(canvas: Canvas, t: Tree, s: TreeScene, frame: Int) {
        val shown = s.sights.aurora.toFloat()
        if (shown < 0.03f) return
        // A moderate display should catch the eye at a glance; a strong one stays short of neon.
        val a = shown.pow(0.6f)
        val u = t.u
        // The arc lies low over the northern horizon, behind the crown, and shows through the gaps
        // between the curtains as a backdrop; only the rays rise above the treetops, higher the
        // stronger the display.
        val base = t.top + (t.ground - t.top) * 0.4f
        val behind = base - t.top
        val above = (t.top - t.sky).coerceAtLeast(t.skyHeight * 0.15f) * (0.2f + 0.6f * a)
        val green = 0xFF86E2AE.toInt()
        val red = 0xFFC77E96.toInt()
        fun arc(x: Float) = base + sin(x / t.w * 3.6f + 1.1f) * 3f * u
        val glowTop = t.top - above * 0.8f
        fill.with(LinearGradient(0f, glowTop, 0f, base + 30 * u, intArrayOf(alpha(green, 0f), alpha(green, 0.26f * a), alpha(green, 0.34f * a), alpha(green, 0f)),
            floatArrayOf(0f, 0.4f, 0.8f, 1f), Shader.TileMode.CLAMP))
        canvas.drawRect(0f, glowTop, t.w, base + 30 * u, fill)
        val rnd = Random(97)
        fill.maskFilter = BlurMaskFilter(max(1f, 1.4f * u), BlurMaskFilter.Blur.NORMAL)
        val rays = t.spread(24)
        repeat(rays) { i ->
            val drift = (frame * 2 - 1) * rnd.f(0.5f, 1.8f) * u
            val x = (i + rnd.nextFloat()) / rays * (t.w + 10 * u) - 5 * u + drift
            val y = arc(x)
            val h = (behind * rnd.f(0.75f, 1f) + above * rnd.f(0.25f, 1f)) * (0.9f + 0.1f * (frame * 2 - 1) * sin(i * 2.1f))
            val k = rnd.f(0.3f, 1f) * a
            val crown = if (a > 0.55f) alpha(red, 0.4f * k * sm(0.55f, 0.9f, a)) else alpha(green, 0f)
            val rw = rnd.f(1.2f, 2.6f) * u
            fill.with(LinearGradient(0f, y + 2 * u, 0f, y - h, intArrayOf(alpha(green, 0f), alpha(green, 0.62f * k), alpha(green, 0.4f * k), crown, alpha(crown, 0f)),
                floatArrayOf(0f, 0.1f, 0.55f, 0.85f, 1f), Shader.TileMode.CLAMP))
            canvas.drawRect(x - rw / 2, y - h, x + rw / 2, y + 2 * u, fill)
        }
        fill.maskFilter = null
        fill.shader = null
    }

    /** Meteors of the shower running tonight, in the open sky, each streaking away from the radiant. */
    private fun meteors(canvas: Canvas, t: Tree, s: TreeScene) {
        val u = t.u
        val sights = s.sights
        val rnd = Random(s.variation * 17 + 3)
        // Where the radiant sits in the picture: to its side, higher in the sky the higher it stands.
        val rx = t.cx + sights.radiantSide.toFloat() * t.w * 0.7f
        val ry = t.sky + t.skyHeight * (0.5f - 0.5f * (sights.radiantElevation.toFloat() / 90f).coerceIn(0f, 1f)) - t.skyHeight * 0.2f
        val room = (t.top - t.sky).coerceAtLeast(t.skyHeight * 0.25f)
        repeat(sights.meteors) {
            val x = rnd.f(0.15f, 0.85f) * t.w
            val y = t.sky + rnd.f(0.05f, 0.75f) * room
            var dx = x - rx
            var dy = y - ry
            val len = max(0.001f, sqrt(dx * dx + dy * dy))
            dx /= len
            dy /= len
            val streak = rnd.f(9f, 16f) * u
            val hx = x + dx * streak
            val hy = y + dy * streak
            stroke.color = 0xFFFFFFFF.toInt()
            stroke.strokeWidth = max(0.8f, 0.3f * u)
            stroke.shader = LinearGradient(x, y, hx, hy, intArrayOf(alpha(0xFFFFFFFF.toInt(), 0f), alpha(0xFFF4F7FF.toInt(), 0.85f)), null, Shader.TileMode.CLAMP)
            canvas.drawLine(x, y, hx, hy, stroke)
            stroke.shader = null
            fill.color = alpha(0xFFFFFFFF.toInt(), 0.9f)
            canvas.drawCircle(hx, hy, max(0.7f, 0.35f * u), fill)
        }
    }

    private fun moon(canvas: Canvas, t: Tree, s: TreeScene, light: Light) {
        val u = t.u
        // The expanded landscape uses the widget's narrow tree frame; scale the disc gently so it
        // still reads as a moon in the wider sky, while keeping the widget's established size.
        val r = 2.3f * u * (1f + 0.65f * sm(1f, 3f, t.wide))
        val mx = t.cx + s.moonSide.toFloat() * t.w * 0.3f
        val my = t.h * 0.045f + (1 - (s.moonElevation.toFloat() / 40f).coerceIn(0f, 1f)) * t.h * 0.17f
        val vis = light.moonVis
        fill.with(RadialGradient(mx, my, 16 * u, intArrayOf(alpha(0xFFC9D3E2.toInt(), 0.22f * vis), alpha(0xFFC9D3E2.toInt(), 0f)), null, Shader.TileMode.CLAMP))
        canvas.drawCircle(mx, my, 16 * u, fill)
        fill.shader = null
        fill.color = alpha(0xFF3A4458.toInt(), 0.35f * vis)
        canvas.drawCircle(mx, my, r, fill)
        // The lit part: one half of the disc and the terminator, a half ellipse.
        val phase = s.moonPhase.toFloat()
        val waxing = phase < 0.5f
        val k = r * cos(2 * PI.toFloat() * phase)
        val path = Path()
        path.moveTo(mx, my - r)
        path.arcTo(RectF(mx - r, my - r, mx + r, my + r), -90f, if (waxing) 180f else -180f, false)
        val crescent = k > 0
        val terminator = RectF(mx - abs(k), my - r, mx + abs(k), my + r)
        path.arcTo(terminator, 90f, if (waxing == crescent) -180f else 180f, false)
        path.close()
        fill.color = alpha(0xFFECE8DC.toInt(), 0.9f * min(1f, vis * 1.6f))
        canvas.drawPath(path, fill)
    }

    private fun clouds(canvas: Canvas, t: Tree, s: TreeScene, light: Light, nowMs: Long, frame: Int) {
        val u = t.u
        val cloud = s.cloud.toFloat()
        if (cloud < 0.04f) return
        val rnd = Random(311)
        val span = t.w * 2.2f
        val drift = ((nowMs / 3_600_000.0 * (2.0 + s.wind * 0.9) * u + frame * 1.2 * u) % span).toFloat()
        val rain = light.rain
        // Cloud greys darken with the light: by night clouds are dim shapes, not bright puffs.
        val dim = 0.22f + 0.78f * (1 - light.night)
        var litTone = mix(mix(light.skyHorizon, 0xFFFFFFFF.toInt(), (0.5f * light.day + 0.12f) * dim), shade(0xFF8A9098.toInt(), dim), rain * 0.6f)
        var baseTone = mix(mix(light.skyTop, shade(0xFF59616B.toInt(), dim), 0.45f), shade(0xFF4E545C.toInt(), dim), rain * 0.5f + if (s.thunder) 0.3f else 0f)
        // By night a cloud's lit side is a dull warm grey where the town lights it, silver where the
        // moon does, and its shadowed side darker than the sky behind it.
        val night = light.night
        litTone = mix(litTone, mix(mix(light.skyHorizon, 0xFF8C8580.toInt(), 0.4f), 0xFFB4BDCC.toInt(), 0.45f * light.moonVis), night)
        baseTone = mix(baseTone, shade(mix(light.skyTop, 0xFF22252B.toInt(), 0.4f), 0.85f - 0.15f * rain), night)
        // Lit from above by the moon, from below by the town under a cloudy sky.
        val fromBelow = night * light.skyglow / (light.skyglow + light.moonVis + 0.15f)
        // Under a closed deck the puffs give way to its layers.
        val deck = night * sm(0.45f, 0.9f, cloud)
        // The large view's wider sky has fewer, larger clouds, not a scatter of the widget's small ones.
        val big = sqrt(t.wide)
        repeat((t.spread(16) / big).toInt()) {
            val x0 = rnd.nextFloat() * span
            val y = t.sky - 3 * u + rnd.nextFloat().pow(1.4f) * t.skyHeight * 0.6f
            val rank = rnd.nextFloat()
            val scale = rnd.f(7f, 20f) * u * big
            val lobes = 5 + rnd.nextInt(4)
            val shown = ((cloud * 1.15f - rank) * 3f).coerceIn(0f, 1f) * (0.45f + 0.25f * cloud) * (1 - 0.6f * deck)
            val x = ((x0 + drift * (0.7f + 0.6f * rank)) % span) - t.w * 0.6f
            repeat(lobes) {
                val lx = x + rnd.f(-1.3f, 1.3f) * scale
                val ly = y + rnd.f(-0.35f, 0.2f) * scale
                val lr = rnd.f(0.45f, 0.85f) * scale
                if (shown <= 0.01f) return@repeat
                val under = ((ly - y) / scale + 0.35f) * 1.2f
                val tone = mix(litTone, baseTone, under + (1 - 2 * under) * fromBelow)
                fill.with(RadialGradient(lx, ly, lr, intArrayOf(alpha(tone, shown), alpha(tone, shown * 0.5f), alpha(tone, 0f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP))
                // Flattened, as clouds seen from the ground are.
                canvas.save()
                canvas.scale(1f, 0.6f, lx, ly)
                canvas.drawCircle(lx, ly, lr, fill)
                canvas.restore()
            }
        }
        // Overcast: long soft bands of stratus give the grey some texture.
        if (cloud > 0.7f) {
            val a = (cloud - 0.7f) / 0.3f * 0.22f
            repeat(6) {
                val y = t.sky + rnd.nextFloat() * t.skyHeight * 0.75f
                val x = ((rnd.nextFloat() * span + drift) % span) - t.w * 0.6f
                val rx = t.w * rnd.f(0.6f, 1.1f)
                val ry = rnd.f(2f, 5f) * u * sqrt(t.wide)
                fill.with(RadialGradient(x, y, rx, intArrayOf(alpha(baseTone, a), alpha(baseTone, 0f)), null, Shader.TileMode.CLAMP))
                canvas.save()
                canvas.scale(1f, ry / rx, x, y)
                canvas.drawCircle(x, y, rx, fill)
                canvas.restore()
            }
        }
        fill.shader = null
        if (deck > 0.03f) cloudDeck(canvas, t, s, light, litTone, baseTone, deck, nowMs, frame)
    }

    /**
     * A cloudy night's sky: not puffs but a closed deck seen from below, in two layers. The far one is
     * paler, lit by the town, and drifts slowly; the near one is darker and moves faster, and in rain
     * it hangs lower and heavier. Patches high in the picture are overhead and look larger; those
     * lower down are further off and flatter.
     */
    private fun cloudDeck(canvas: Canvas, t: Tree, s: TreeScene, light: Light, lit: Int, base: Int, deck: Float, nowMs: Long, frame: Int) {
        val u = t.u
        val rain = light.rain
        val rnd = Random(523)
        val span = t.w * 3f
        val hours = nowMs / 3_600_000.0
        val bottom = t.top + (t.ground - t.top) * 0.25f
        for (near in listOf(false, true)) {
            val speed = (if (near) 1.8 else 0.9) * (1.5 + s.wind * 0.8)
            val drift = ((hours * speed * u + frame * (if (near) 1.8f else 0.9f) * u) % span).toFloat()
            val tone = if (near) shade(base, 1 - 0.2f * rain) else lit
            val big = sqrt(t.wide)
            val count = (t.spread(if (near) 7 + (5 * rain).toInt() else 10) / big).toInt()
            repeat(count) {
                val f = if (near) rnd.f(0.05f, 0.75f + 0.25f * rain) else rnd.f(-0.05f, 0.9f)
                val y = f * bottom
                val far = (y / bottom).coerceIn(0f, 1f)
                val rx = rnd.f(22f, 50f) * u * (1.2f - 0.5f * far) * big
                val ry = rx * rnd.f(0.2f, 0.34f) * (1.1f - 0.5f * far) * (if (near) 1f + 0.4f * rain else 1f)
                val x = ((rnd.nextFloat() * span + drift) % span) - t.w
                val a = deck * rnd.f(0.55f, 1f) * (if (near) 0.6f + 0.25f * rain else 0.5f)
                fill.with(RadialGradient(x, y, rx, intArrayOf(alpha(tone, a), alpha(tone, a * 0.55f), alpha(tone, 0f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP))
                canvas.save()
                canvas.scale(1f, ry / rx, x, y)
                canvas.drawCircle(x, y, rx, fill)
                canvas.restore()
            }
        }
        fill.shader = null
    }

    /** Far hills with spruce, softened by the air between: the landscape the birch stands in. */
    private fun hills(canvas: Canvas, t: Tree, s: TreeScene, light: Light) {
        val u = t.u
        val haze = 0.32f * (1 - s.fog.toFloat() * 0.8f)
        var tone = mix(light.skyHorizon, shade(0xFF4C5852.toInt(), 0.3f + 0.7f * light.day), haze)
        if (s.snow > 2) tone = mix(tone, mix(light.skyHorizon, 0xFFFFFFFF.toInt(), 0.3f), 0.35f)
        fun ridge(x: Float) = t.ground - u * (4.5f + 2.6f * sin(x / t.w * 3.1f + 0.7f) + 1.3f * sin(x / t.w * 8f + 2f))
        val path = Path()
        path.moveTo(0f, t.ground + 2 * u)
        var x = 0f
        while (x <= t.w) { path.lineTo(x, ridge(x)); x += u }
        path.lineTo(t.w, t.ground + 2 * u)
        path.close()
        fill.color = tone
        canvas.drawPath(path, fill)
        val rnd = Random(53)
        val spruce = mix(tone, shade(0xFF3A4742.toInt(), 0.3f + 0.7f * light.day), 0.4f * (1 - s.fog.toFloat() * 0.7f))
        repeat(t.spread(11)) {
            val sx = rnd.nextFloat() * t.w
            val sh = rnd.f(3.5f, 10f) * u
            val sw = sh * rnd.f(0.26f, 0.34f)
            val base = ridge(sx) + u
            val tree = Path()
            tree.moveTo(sx, base - sh)
            // Tiers, wider towards the ground, as a spruce silhouette reads from afar.
            val tiers = 5
            for (i in 1..tiers) {
                val ty = base - sh + sh * i / tiers
                val tw = sw * i / tiers
                tree.lineTo(sx + tw, ty)
                tree.lineTo(sx + tw * 0.45f, ty - sh * 0.04f)
            }
            for (i in tiers downTo 1) {
                val ty = base - sh + sh * i / tiers
                val tw = sw * i / tiers
                tree.lineTo(sx - tw * 0.45f, ty - sh * 0.04f)
                tree.lineTo(sx - tw, ty)
            }
            tree.close()
            fill.color = alpha(spruce, rnd.f(0.6f, 1f))
            canvas.drawPath(tree, fill)
        }
    }

    // ---------------------------------------------------------------- underground

    private fun underground(canvas: Canvas, t: Tree, s: TreeScene) {
        val u = t.u
        val d = t.depth
        val soil = s.soil.toFloat()
        fun boundary(f: Float, p: Float): Path {
            val path = Path()
            path.moveTo(0f, t.h)
            var x = 0f
            while (x <= t.w) { path.lineTo(x, t.ground + f * d + u * (1.3f * sin(x / t.w * 5f + p) + 0.6f * sin(x / t.w * 13f + p * 2))); x += u }
            path.lineTo(t.w, t.h)
            path.close()
            return path
        }
        // Humus, topsoil, brown subsoil, grey glacial till and the bedrock under it all.
        val top = Path()
        top.moveTo(0f, t.h)
        var x = 0f
        while (x <= t.w) { top.lineTo(x, t.surface(x)); x += u }
        top.lineTo(t.w, t.h)
        top.close()
        val layers = listOf(
            Triple(top, 0xFF3A2D24.toInt(), 0xFF45362A.toInt()),
            Triple(boundary(0.07f, 1f), 0xFF5A4535.toInt(), 0xFF67503D.toInt()),
            Triple(boundary(0.36f, 2.3f), 0xFF7A6047.toInt(), 0xFF836A4F.toInt()),
            Triple(boundary(0.72f, 4.1f), 0xFF8A7C66.toInt(), 0xFF7F735F.toInt()),
        )
        for ((path, upper, lower) in layers) {
            fill.with(LinearGradient(0f, t.ground, 0f, t.h, upper, lower, Shader.TileMode.CLAMP))
            canvas.drawPath(path, fill)
        }
        fill.shader = null
        // Soft seams between the layers.
        for ((i, f) in listOf(0.07f, 0.36f, 0.72f).withIndex()) {
            stroke.strokeWidth = 1.6f * u
            stroke.color = alpha(0xFF2E241C.toInt(), 0.12f)
            canvas.drawPath(boundary(f, listOf(1f, 2.3f, 4.1f)[i]).also { it.offset(0f, -0.4f * u) }, stroke)
        }
        grainSpecks(canvas, t)

        // Bedrock: grey gneiss with a quartz vein and cracks.
        val rockTop = t.ground + 0.9f * d
        val rock = Path()
        rock.moveTo(0f, t.h)
        val rr = Random(19)
        x = 0f
        while (x <= t.w) { rock.lineTo(x, rockTop + u * (1.8f * sin(x / t.w * 4f + 1.3f) + rr.f(-0.6f, 0.6f))); x += 2.5f * u }
        rock.lineTo(t.w, t.h)
        rock.close()
        fill.with(LinearGradient(0f, rockTop - 2 * u, 0f, t.h, 0xFF77746E.toInt(), 0xFF5B5955.toInt(), Shader.TileMode.CLAMP))
        canvas.drawPath(rock, fill)
        fill.shader = null
        stroke.strokeWidth = max(0.6f, 0.22f * u)
        stroke.color = alpha(0xFF3C3A37.toInt(), 0.6f)
        repeat(t.spread(5)) {
            val cx = rr.f(0.1f, 0.9f) * t.w
            val cy = rockTop + rr.f(2f, 6f) * u
            val crack = Path()
            crack.moveTo(cx, cy)
            crack.lineTo(cx + rr.f(-3f, 3f) * u, cy + rr.f(2f, 5f) * u)
            crack.lineTo(cx + rr.f(-4f, 4f) * u, t.h)
            canvas.drawPath(crack, stroke)
        }
        stroke.strokeWidth = 0.5f * u
        stroke.color = alpha(0xFFCFCBC2.toInt(), 0.35f)
        canvas.drawLine(0f, rockTop + 7 * u, t.w, rockTop + 4.5f * u, stroke)
        // Water that soaked through gathers on the rock as a thin glinting seam.
        if (soil > 0.5f) {
            stroke.strokeWidth = 0.6f * u
            stroke.color = alpha(0xFF9FB2B8.toInt(), sm(0.5f, 0.9f, soil) * 0.6f)
            canvas.drawPath(Path().apply {
                moveTo(0f, rockTop + 0.8f * u)
                var px = 0f
                while (px <= t.w) { lineTo(px, rockTop + u * (1.8f * sin(px / t.w * 4f + 1.3f)) - 0.3f * u); px += u }
            }, stroke)
        }

        // Wet soil belongs behind the roots so the fine branches remain legible after rain.
        soak(canvas, t, s, top)
        stones(canvas, t)
        neighbourRoots(canvas, t, s)
        roots(canvas, t, s)
        pockets(canvas, t, soil)
        // Threads of mycelium, plainer when the soil is dry.
        stroke.strokeWidth = max(0.5f, 0.14f * u)
        stroke.color = alpha(0xFFE9E2CF.toInt(), 0.16f + 0.14f * soil)
        for (pts in t.threads + t.links) {
            val path = Path()
            path.moveTo(pts[0], pts[1])
            for (i in 1 until pts.size / 2) path.lineTo(pts[i * 2], pts[i * 2 + 1])
            canvas.drawPath(path, stroke)
        }
        worm(canvas, t, s)
    }

    /**
     * An illustrative moisture gradient inferred from recent rain and melt. Three saved reservoirs
     * move the dark band down over hours and let the surface lighten first during dry weather.
     */
    private fun soak(canvas: Canvas, t: Tree, s: TreeScene, top: Path) {
        val u = t.u
        val topWet = sm(0.15f, 0.95f, s.topsoil.toFloat())
        val rootWet = sm(0.15f, 0.95f, s.soil.toFloat())
        val deepWet = sm(0.15f, 0.95f, s.deepSoil.toFloat())
        if (max(topWet, max(rootWet, deepWet)) < 0.01f) return
        val d = t.depth
        val dark = 0xFF1E140D.toInt()
        canvas.saveLayer(0f, t.ground - 4 * u, t.w, t.h, null)
        canvas.save()
        canvas.clipPath(top)
        fun band(y0: Float, y1: Float, strength: Float) {
            if (strength < 0.01f) return
            fill.with(LinearGradient(0f, t.ground + y0 * d, 0f, t.ground + y1 * d,
                intArrayOf(alpha(dark, 0f), alpha(dark, 0.48f * strength), alpha(dark, 0.42f * strength), alpha(dark, 0f)),
                floatArrayOf(0f, 0.12f, 0.72f, 1f), Shader.TileMode.CLAMP))
            canvas.drawRect(0f, t.ground + y0 * d, t.w, t.ground + y1 * d, fill)
        }
        band(-0.015f, 0.19f, topWet)
        band(0.08f, 0.61f, rootWet)
        band(0.38f, 0.9f, deepWet)
        canvas.restore()
        if (t.pines.isNotEmpty()) {
            val n = 48
            val keep = IntArray(n) { i ->
                val x = i / (n - 1f) * t.w
                var k = 1f
                for (p in t.pines) k -= 0.4f * exp(-((x - p.trunk.x[0]) / (p.height * 0.24f)).pow(2))
                alpha(0xFF000000.toInt(), k)
            }
            fill.with(LinearGradient(0f, 0f, t.w, 0f, keep, null, Shader.TileMode.CLAMP))
            fill.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawRect(0f, t.ground - 4 * u, t.w, t.h, fill)
            fill.xfermode = null
        }
        fill.shader = null
        canvas.restore()
    }

    /** The pines' and the maple's roots: paler and finer than the birch's, behind them. */
    private fun neighbourRoots(canvas: Canvas, t: Tree, s: TreeScene) {
        if (t.neighbourRoots.isEmpty()) return
        val u = t.u
        val wet = s.soil.toFloat()
        for ((index, root) in t.neighbourRoots.withIndex()) for (k in 0 until root.size - 1) {
            val w = root.w[k]
            stroke.strokeWidth = max(0.5f, w * 0.75f)
            val pine = index < t.pineRootCount
            val thick = shade(if (pine) 0xFF78614B.toInt() else 0xFF765244.toInt(), 1 - 0.1f * wet)
            val thin = shade(if (pine) 0xFFB6A083.toInt() else 0xFFC19A7B.toInt(), 1 - 0.1f * wet)
            stroke.color = alpha(mix(thin, thick, sm(0.4f * u, 2.5f * u, w)), if (pine) 0.68f else 0.78f)
            canvas.drawLine(root.x[k], root.y[k], root.x[k + 1], root.y[k + 1], stroke)
        }
    }

    private fun grainSpecks(canvas: Canvas, t: Tree) {
        val u = t.u
        val rnd = Random(8)
        val tones = intArrayOf(0xFF2A1F17.toInt(), 0xFF4D3B2D.toInt(), 0xFF8E7A63.toInt(), 0xFFA8977E.toInt(), 0xFF6B5A49.toInt(), 0xFFBCB2A2.toInt())
        val n = (t.w * t.depth / (u * u) * 0.32f).toInt()
        val buckets = Array(tones.size) { FloatArray(n / tones.size * 2 + 2) }
        val fillCount = IntArray(tones.size)
        repeat(n) {
            val b = rnd.nextInt(tones.size)
            if (fillCount[b] + 2 > buckets[b].size) return@repeat
            buckets[b][fillCount[b]++] = rnd.nextFloat() * t.w
            buckets[b][fillCount[b]++] = t.ground + 1.2f * u + rnd.nextFloat() * (t.depth - 1.2f * u)
        }
        stroke.strokeCap = Paint.Cap.ROUND
        for (b in tones.indices) {
            stroke.color = alpha(tones[b], if (b >= 2) 0.32f else 0.45f)
            stroke.strokeWidth = max(0.7f, u * (if (b == 5) 0.55f else 0.38f))
            canvas.drawPoints(buckets[b], 0, fillCount[b], stroke)
        }
        // Small pebbles in the till and subsoil.
        repeat(t.spread(55)) {
            val px = rnd.nextFloat() * t.w
            val py = t.ground + t.depth * rnd.f(0.3f, 0.9f)
            val pr = rnd.f(0.35f, 1f) * u
            fill.color = alpha(mix(0xFF7F786E.toInt(), 0xFFA9A294.toInt(), rnd.nextFloat()), 0.85f)
            canvas.drawOval(px - pr * 1.3f, py - pr, px + pr * 1.3f, py + pr, fill)
        }
    }

    private fun stones(canvas: Canvas, t: Tree) {
        val u = t.u
        for (stone in t.stones) {
            val path = Path()
            val n = stone.shape.size
            for (i in 0..n) {
                val a = (2 * PI * i / n).toFloat()
                val r = stone.r * stone.shape[i % n]
                val px = stone.x + cos(a) * r * 1.35f
                val py = stone.y + sin(a) * r * 0.85f
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            val light = mix(0xFF9C9486.toInt(), 0xFF8F8B83.toInt(), stone.tone)
            val dark = mix(0xFF5C564E.toInt(), 0xFF4F4F4C.toInt(), stone.tone)
            fill.with(LinearGradient(stone.x - stone.r, stone.y - stone.r, stone.x + stone.r, stone.y + stone.r, light, dark, Shader.TileMode.CLAMP))
            canvas.drawPath(path, fill)
            fill.shader = null
            stroke.strokeWidth = max(0.5f, 0.2f * u)
            stroke.color = alpha(0xFF2E2620.toInt(), 0.35f)
            canvas.drawPath(path, stroke)
            fill.color = alpha(0xFFFFFFFF.toInt(), 0.08f)
            canvas.drawCircle(stone.x - stone.r * 0.45f, stone.y - stone.r * 0.35f, stone.r * 0.22f, fill)
        }
    }

    private fun roots(canvas: Canvas, t: Tree, s: TreeScene) {
        val u = t.u
        val wet = s.soil.toFloat()
        // The root collar: the trunk widening into its roots just under the surface.
        val collar = Path()
        val bw = t.baseW * 0.62f
        collar.moveTo(t.cx - bw, t.ground - u)
        collar.cubicTo(t.cx - bw, t.ground + 2 * u, t.cx - bw * 1.7f, t.ground + 3 * u, t.cx - bw * 1.9f, t.ground + 5 * u)
        collar.lineTo(t.cx + bw * 1.9f, t.ground + 5 * u)
        collar.cubicTo(t.cx + bw * 1.7f, t.ground + 3 * u, t.cx + bw, t.ground + 2 * u, t.cx + bw, t.ground - u)
        collar.close()
        fill.with(LinearGradient(0f, t.ground - u, 0f, t.ground + 5 * u, 0xFF4A403A.toInt(), 0xFF5E4838.toInt(), Shader.TileMode.CLAMP))
        canvas.drawPath(collar, fill)
        fill.shader = null
        val thick = shade(0xFF70533D.toInt(), 1 - 0.1f * wet)
        val thin = shade(0xFFB49A77.toInt(), 1 - 0.1f * wet)
        for (root in t.roots) for (k in 0 until root.size - 1) {
            val w = root.w[k]
            stroke.strokeWidth = max(0.6f, w)
            stroke.color = mix(thin, thick, sm(0.4f * u, 3f * u, w))
            canvas.drawLine(root.x[k], root.y[k], root.x[k + 1], root.y[k + 1], stroke)
        }
        // A light upper edge on the thicker roots gives them their roundness.
        stroke.color = alpha(0xFF9B8169.toInt(), 0.55f)
        for (root in t.roots) for (k in 0 until root.size - 1) {
            val w = root.w[k]
            if (w < 1.2f * u) continue
            stroke.strokeWidth = w * 0.28f
            canvas.drawLine(root.x[k], root.y[k] - w * 0.26f, root.x[k + 1], root.y[k + 1] - root.w[k + 1] * 0.26f, stroke)
        }
        // Fine root hairs on the tips.
        stroke.strokeWidth = max(0.5f, 0.12f * u)
        stroke.color = alpha(0xFFA48C70.toInt(), 0.55f)
        val rnd = Random(4)
        for (root in t.roots) {
            if (root.size > 8) continue
            val k = root.size - 1
            repeat(1) {
                val a = rnd.f(0f, 6.3f)
                canvas.drawLine(root.x[k], root.y[k], root.x[k] + cos(a) * 1.5f * u, root.y[k] + sin(a) * 1.5f * u, stroke)
            }
        }
    }

    private fun pockets(canvas: Canvas, t: Tree, soil: Float) {
        val u = t.u
        for (p in t.pockets) {
            val a = sm(p.threshold, p.threshold + 0.18f, soil)
            if (a <= 0.01f) continue
            val r = (1.8f + 4f * p.size) * u * (0.55f + 0.45f * sm(p.threshold, 1f, soil))
            fill.with(RadialGradient(p.x, p.y, r * 2.4f, intArrayOf(alpha(0xFF24180F.toInt(), 0.3f * a), alpha(0xFF24180F.toInt(), 0f)), null, Shader.TileMode.CLAMP))
            canvas.drawCircle(p.x, p.y, r * 2.4f, fill)
            val oval = RectF(p.x - r * 1.45f, p.y - r * 0.75f, p.x + r * 1.45f, p.y + r * 0.75f)
            fill.with(RadialGradient(p.x, p.y - r * 0.3f, r * 1.5f, intArrayOf(alpha(0xFF8AA4AC.toInt(), 0.85f * a), alpha(0xFF4C636A.toInt(), 0.85f * a)), null, Shader.TileMode.CLAMP))
            canvas.drawOval(oval, fill)
            fill.shader = null
            stroke.strokeWidth = max(0.5f, 0.2f * u)
            stroke.color = alpha(0xFF2B2E2C.toInt(), 0.35f * a)
            canvas.drawOval(oval, stroke)
            // The water's flat top and a glint on it: water, not a pebble.
            stroke.color = alpha(0xFFDDEBEF.toInt(), 0.6f * a)
            stroke.strokeWidth = max(0.5f, 0.22f * u)
            canvas.drawLine(oval.left + r * 0.5f, p.y - r * 0.3f, oval.right - r * 0.5f, p.y - r * 0.3f, stroke)
            fill.color = alpha(0xFFFFFFFF.toInt(), 0.7f * a)
            canvas.drawCircle(p.x - r * 0.5f, p.y - r * 0.3f, max(0.6f, 0.3f * u), fill)
        }
    }

    /** Now and then, in mild damp soil, an earthworm near the surface. */
    private fun worm(canvas: Canvas, t: Tree, s: TreeScene) {
        val day = s.season.dayOfYear
        if (s.soil < 0.35 || s.temp < 3 || day !in 90..300 || s.variation % 3 != 0L) return
        val u = t.u
        val x = t.cx + t.tw * 0.22f
        val y = t.ground + t.depth * 0.17f
        val path = Path()
        path.moveTo(x - 3.5f * u, y + 0.6f * u)
        path.cubicTo(x - 1.5f * u, y - 1.2f * u, x + 0.5f * u, y + 1.6f * u, x + 3f * u, y - 0.2f * u)
        stroke.strokeWidth = 0.85f * u
        stroke.color = 0xFF9A6E61.toInt()
        canvas.drawPath(path, stroke)
        stroke.strokeWidth = max(0.5f, 0.15f * u)
        stroke.color = alpha(0xFFD4B2A4.toInt(), 0.6f)
        path.offset(0f, -0.2f * u)
        canvas.drawPath(path, stroke)
    }

    // ---------------------------------------------------------------- above ground

    /**
     * The large view's pines and maple, a little behind the birch and softened by the air between.
     * They are drawn in the lit layer, so the light, the season's colour and the night reach them
     * as they reach the birch.
     */
    private fun landscape(canvas: Canvas, t: Tree, s: TreeScene, light: Light, dx: (Float, Float, Float) -> Float) {
        if (t.pines.isEmpty() && t.maple == null) return
        val haze = mix(0xFFB0BAC3.toInt(), 0xFF7E8794.toInt(), light.night)
        for (p in t.pines) pine(canvas, t, s, light, p, dx, haze)
        t.maple?.let {
            mapleLitter(canvas, t, s, it)
            maple(canvas, t, s, light, it, dx, haze)
        }
    }

    private fun pine(canvas: Canvas, t: Tree, s: TreeScene, light: Light, p: Pine, dx: (Float, Float, Float) -> Float, hazeTone: Int) {
        val u = t.u
        val amount = 0.08f + 0.3f * p.back + 0.45f * s.fog.toFloat()
        fun h(c: Int) = mix(c, hazeTone, amount)
        val wet = s.surface.toFloat()
        // Stiffer than the birch's long twigs: a pine's top sways a little.
        fun sway(x: Float, y: Float) = dx(x, y, 0f) * 0.6f
        val side = s.sunSide.toFloat().let { if (abs(it) < 0.2f) 0.3f else it }
        val tr = p.trunk
        val n = tr.size
        for (i in 0 until n - 1) {
            val f = i / (n - 1f)
            // Grey-brown plates low down, the thin orange bark of the upper trunk above.
            val bark = shade(mix(0xFF5A4B40.toInt(), 0xFFB86C42.toInt(), sm(0.35f, 0.72f, f)), 1 - 0.28f * wet)
            val x0 = tr.x[i] + sway(tr.x[i], tr.y[i])
            val x1 = tr.x[i + 1] + sway(tr.x[i + 1], tr.y[i + 1])
            val w0 = tr.w[i] / 2
            val w1 = tr.w[i + 1] / 2
            val litBark = mix(shade(bark, 1f + 0.18f * light.sun), 0xFFE49A5E.toInt(), 0.35f * light.lowSun * sm(0.3f, 0.7f, f))
            fill.with(LinearGradient(x0 - w0 * side, 0f, x0 + w0 * side, 0f, h(shade(bark, 0.68f - 0.08f * light.lowSun)), h(litBark), Shader.TileMode.CLAMP))
            canvas.drawPath(Path().apply {
                moveTo(x0 - w0, tr.y[i] + 0.5f)
                lineTo(x0 + w0, tr.y[i] + 0.5f)
                lineTo(x1 + w1, tr.y[i + 1] - 0.5f)
                lineTo(x1 - w1, tr.y[i + 1] - 0.5f)
                close()
            }, fill)
        }
        fill.shader = null
        val rnd = Random(p.seed.toLong())
        stroke.strokeWidth = max(0.5f, 0.22f * u)
        stroke.color = h(alpha(0xFF2E241E.toInt(), 0.6f))
        repeat(12) {
            val i = (rnd.f(0.02f, 0.4f) * (n - 1)).toInt()
            val half = tr.w[i] * rnd.f(0.15f, 0.4f)
            val x = tr.x[i] + rnd.f(-0.25f, 0.25f) * tr.w[i]
            canvas.drawLine(x - half, tr.y[i], x + half, tr.y[i] + rnd.f(-0.3f, 0.3f) * u, stroke)
        }
        stroke.color = h(0xFF4A3A30.toInt())
        for (stub in p.stubs) {
            stroke.strokeWidth = max(0.5f, 0.35f * u)
            canvas.drawLine(stub[0], stub[1], stub[2], stub[3], stroke)
        }
        for (b in p.boughs) for (k in 0 until b.size - 1) {
            stroke.strokeWidth = max(0.6f, b.w[k])
            stroke.color = h(shade(mix(0xFF4F3A2E.toInt(), 0xFF9A5C3A.toInt(), 0.3f), 1 - 0.2f * wet))
            canvas.drawLine(b.x[k] + sway(b.x[k], b.y[k]), b.y[k], b.x[k + 1] + sway(b.x[k + 1], b.y[k + 1]), b.y[k + 1], stroke)
        }
        // The needle clumps: each tuft within one lit from above and from the sun's side, those
        // beneath and inside in shade, so a clump has texture rather than a smooth fill.
        val dark = h(0xFF1C2A21.toInt())
        val tone = Random(p.seed.toLong() + 1)
        val season = s.season
        // Through a cold winter the needles go a duller, yellower green, greening again in spring.
        val winter = sm(3f, -8f, s.temp.toFloat()) * (if (season.dayOfYear in 110..290) 0f else 1f)
        // In September and October the oldest needles, deep inside the crown, yellow before they drop.
        val shedding = (Season.smooth(245.0, 265.0, season.autumnDay) * (1 - Season.smooth(292.0, 310.0, season.autumnDay))).toFloat()
        for (pad in p.pads) {
            val ox = sway(pad.x, pad.y)
            val toward = (side * (pad.x - tr.x[0]) / (p.height * 0.2f)).coerceIn(-1f, 1f)
            val green = mix(mix(0xFF3B553E.toInt(), 0xFF64804F.toInt(), 0.2f + 0.25f * light.sun * (1 + toward) + 0.1f * light.day), 0xFF6E7448.toInt(), 0.35f * winter)
            val lit = h(green)
            val inner = abs(pad.x - tr.x[0]) < p.height * 0.07f
            fill.color = dark
            canvas.drawOval(pad.x + ox - pad.rx * 0.8f, pad.y - pad.ry * 0.5f, pad.x + ox + pad.rx * 0.8f, pad.y + pad.ry * 0.6f, fill)
            for (j in 0 until pad.blobs.size / 3) {
                val bx = pad.x + ox + pad.blobs[j * 3]
                val by = pad.y + pad.blobs[j * 3 + 1]
                val r = pad.blobs[j * 3 + 2]
                var c = mix(dark, lit, (0.55f - 0.7f * pad.blobs[j * 3 + 1] / pad.ry + tone.f(-0.2f, 0.2f)).coerceIn(0f, 1f))
                if (shedding > 0.02f && inner && pad.blobs[j * 3 + 1] > 0f) c = mix(c, h(0xFFB59A48.toInt()), 0.4f * shedding)
                fill.color = c
                canvas.drawOval(bx - r, by - r * 0.72f, bx + r, by + r * 0.72f, fill)
            }
        }
        // Needles: short strokes fanning up and out over each clump's upper side, darker ones hanging
        // under it, so the clumps read as pine and not as cloud.
        stroke.strokeWidth = max(0.5f, 0.16f * u)
        val needleLit = h(alpha(mix(0xFF5F7C4C.toInt(), 0xFF7F9A60.toInt(), light.sun), 0.55f + 0.25f * light.day))
        val needleDark = h(alpha(0xFF18241C.toInt(), 0.55f))
        for (pad in p.pads) {
            val ox = sway(pad.x, pad.y)
            repeat((pad.rx / u * 2.2f).toInt().coerceIn(8, 40)) {
                val f = rnd.f(-1f, 1f)
                val v = rnd.f(-1f, 0.2f)
                val x = pad.x + ox + f * pad.rx * 0.95f
                val y = pad.y + v * pad.ry * 0.8f * sqrt(1 - f * f)
                val len = rnd.f(0.7f, 1.4f) * u
                stroke.color = needleLit
                canvas.drawLine(x, y, x + f * len * 0.7f, y - len * (0.5f + 0.5f * (1 - abs(f))), stroke)
            }
            repeat((pad.rx / u).toInt().coerceIn(4, 16)) {
                val f = rnd.f(-0.9f, 0.9f)
                val x = pad.x + ox + f * pad.rx
                val y = pad.y + pad.ry * 0.3f * sqrt(1 - f * f)
                stroke.color = needleDark
                canvas.drawLine(x, y, x + f * 0.5f * u, y + rnd.f(0.4f, 1f) * u, stroke)
            }
        }
        // New shoots in late spring: pale "candles" standing up from the clumps, greening as they lengthen.
        val candles = (Season.smooth(128.0, 142.0, season.springDay) * (1 - Season.smooth(170.0, 195.0, season.springDay))).toFloat()
        if (candles > 0.02f) {
            val grow = Season.smooth(128.0, 160.0, season.springDay).toFloat()
            stroke.strokeWidth = max(1f, 0.5f * u)
            stroke.color = h(alpha(mix(0xFFE2E0A6.toInt(), 0xFF9DB46E.toInt(), sm(0.7f, 1f, grow)), candles))
            val cr = Random(p.seed.toLong() + 3)
            for (pad in p.pads) {
                val ox = sway(pad.x, pad.y)
                repeat(2) {
                    val cx = pad.x + ox + cr.f(-0.5f, 0.5f) * pad.rx
                    val cy = pad.y - pad.ry * 0.4f
                    canvas.drawLine(cx, cy, cx + cr.f(-0.2f, 0.2f) * u, cy - (0.8f + 2f * grow) * u, stroke)
                }
            }
        }
        // A few cones hanging under the clumps, all year.
        fill.color = h(0xFF6A5039.toInt())
        for ((i, pad) in p.pads.withIndex()) {
            if (i % 4 != 1) continue
            val cx = pad.x + sway(pad.x, pad.y) + pad.blobs[0] * 0.5f
            val cy = pad.y + pad.ry * 0.55f
            canvas.drawOval(cx - 0.35f * u, cy, cx + 0.35f * u, cy + 0.9f * u, fill)
        }
        // Snow settles on the flat pads and stays longer than on the birch's twigs.
        val snow = s.branchSnow.toFloat()
        if (snow > 0.03f) {
            // On the upper tufts of each clump, in lumps, the dark needles showing between.
            fill.color = alpha(0xFFEFF2F5.toInt(), 0.92f * min(1f, snow * 2f))
            for (pad in p.pads) {
                val ox = sway(pad.x, pad.y)
                for (j in 0 until pad.blobs.size / 3) {
                    if (pad.blobs[j * 3 + 1] > pad.ry * 0.1f) continue
                    val bx = pad.x + ox + pad.blobs[j * 3]
                    val r = pad.blobs[j * 3 + 2]
                    val by = pad.y + pad.blobs[j * 3 + 1] - r * 0.55f
                    canvas.drawOval(bx - r * 0.8f, by - r * 0.2f, bx + r * 0.8f, by + r * (0.12f + 0.3f * snow), fill)
                }
            }
        }
    }

    private fun mapleColor(m: MapleSeason, leaf: MapleLeaf): Int {
        // New leaves unfold bronze-red and green over a few weeks; summer green is fresh and light.
        val fresh = m.fresh.toFloat()
        var green = mix(mix(0xFF6E9440.toInt(), 0xFF9CBF5C.toInt(), fresh), 0xFF557A34.toInt(), leaf.tint * 0.5f + 0.35f * (1 - leaf.outer))
        // The newest leaves at the sprays' tips keep a red-bronze edge for their first week or two.
        green = mix(green, 0xFFB4744C.toInt(), 0.4f * sm(0.75f, 1f, fresh) * leaf.outer * leaf.tint)
        val turn = (m.turn.toFloat() * 1.5f - leaf.early * 0.5f).coerceIn(0f, 1f)
        if (turn <= 0f) return green
        // Sunlit leaves go crimson; shaded ones inside stop at orange and amber.
        val peak = mix(mix(0xFFD9922F.toInt(), 0xFFD8652B.toInt(), leaf.tint), mix(0xFFC2352A.toInt(), 0xFFA82530.toInt(), leaf.tint), leaf.outer)
        return if (turn < 0.4f) mix(green, 0xFFB49A3E.toInt(), turn / 0.4f) else mix(0xFFB49A3E.toInt(), peak, (turn - 0.4f) / 0.6f)
    }

    private fun maple(canvas: Canvas, t: Tree, s: TreeScene, light: Light, m: Maple, dx: (Float, Float, Float) -> Float, hazeTone: Int) {
        val u = t.u
        val season = s.season.maple
        val amount = 0.05f + 0.4f * s.fog.toFloat()
        fun h(c: Int) = mix(c, hazeTone, amount)
        val wet = s.surface.toFloat()
        fun sway(x: Float, y: Float) = dx(x, y, 0f) * 0.8f
        // Smooth grey-brown stems, the young twigs reddish.
        val bark = shade(0xFF6E665E.toInt(), 1 - 0.3f * wet)
        val twig = shade(0xFF6A3E36.toInt(), 1 - 0.2f * wet)
        for (limb in m.limbs) for (k in 0 until limb.size - 1) {
            val w = limb.w[k]
            stroke.strokeWidth = max(0.5f, w)
            stroke.color = h(mix(twig, bark, sm(0.6f * u, 2.5f * u, w)))
            canvas.drawLine(limb.x[k] + sway(limb.x[k], limb.y[k]), limb.y[k], limb.x[k + 1] + sway(limb.x[k + 1], limb.y[k + 1]), limb.y[k + 1], stroke)
        }
        stroke.strokeWidth = max(0.5f, 0.14f * u)
        stroke.color = h(alpha(twig, 0.75f))
        for (tw in m.twigs) canvas.drawLine(tw[0] + sway(tw[0], tw[1]), tw[1], tw[2] + sway(tw[2], tw[3]), tw[3], stroke)
        val snow = s.branchSnow.toFloat()
        if (snow > 0.03f && season.leaves < 0.3f) {
            stroke.color = alpha(0xFFF3F5F7.toInt(), 0.9f * min(1f, snow * 2f))
            for (limb in m.limbs) for (k in 0 until limb.size - 1) {
                val ddx = limb.x[k + 1] - limb.x[k]
                val ddy = limb.y[k + 1] - limb.y[k]
                val flat = abs(ddx) / max(0.001f, sqrt(ddx * ddx + ddy * ddy))
                if (flat < 0.45f) continue
                val thick = (0.3f + 0.6f * snow) * u * flat
                stroke.strokeWidth = max(0.5f, thick)
                val off = limb.w[k] / 2 + thick * 0.3f
                canvas.drawLine(limb.x[k] + sway(limb.x[k], limb.y[k]), limb.y[k] - off, limb.x[k + 1] + sway(limb.x[k + 1], limb.y[k + 1]), limb.y[k + 1] - off, stroke)
            }
        }
        if (season.leaves < 0.01) return
        val paths = HashMap<Int, Path>()
        val size = 1.25f * u * season.leafSize.toFloat()
        val side = s.sunSide.toFloat()
        for (leaf in m.leaves) {
            if (leaf.rank >= season.leaves) continue
            val bx = leaf.x + sway(leaf.x, leaf.y)
            val toward = (side * (leaf.x - m.x) / m.rx).coerceIn(-1f, 1f)
            // The undersides of the sprays and the inside of the crown are in their own shade.
            val lit = 1 + 0.14f * light.sun * toward - 0.3f * (1 - leaf.outer) - 0.05f * leaf.tint
            var color = mapleColor(season, leaf)
            if (light.golden > 0.01f) color = mix(color, if (season.turn > 0.4) 0xFFF09A4A.toInt() else 0xFFD3DA74.toInt(), light.golden * 0.6f * sm(-0.2f, 0.9f, toward) * leaf.outer)
            if (light.night > 0.01f) color = mix(color, 0xFF8C94A8.toInt(), light.night * (0.3f + 0.2f * light.moonVis))
            color = h(shade(color, lit))
            val key = (Color.red(color) / 10) shl 16 or ((Color.green(color) / 10) shl 8) or (Color.blue(color) / 10)
            val path = paths.getOrPut(key) { Path() }
            // Palmate, held flat: a broad little shape rather than the birch's narrow one.
            val len = size * leaf.size
            val ca = cos(leaf.angle)
            val sa = sin(leaf.angle)
            val wide = len * 0.75f
            path.moveTo(bx, leaf.y)
            path.quadTo(bx + ca * len * 0.4f - sa * wide, leaf.y + sa * len * 0.4f + ca * wide, bx + ca * len, leaf.y + sa * len)
            path.quadTo(bx + ca * len * 0.4f + sa * wide, leaf.y + sa * len * 0.4f - ca * wide, bx, leaf.y)
            path.close()
        }
        for ((key, path) in paths) {
            fill.color = Color.rgb(((key shr 16) and 0xFF) * 10 + 5, ((key shr 8) and 0xFF) * 10 + 5, (key and 0xFF) * 10 + 5)
            canvas.drawPath(path, fill)
        }
    }

    /** The maple's fallen leaves under it, red and orange, browning and flattened through the winter. */
    private fun mapleLitter(canvas: Canvas, t: Tree, s: TreeScene, m: Maple) {
        val season = s.season.maple
        if (season.litter < 0.02) return
        val u = t.u
        val rnd = Random(64)
        val wetDark = 1 - 0.22f * s.surface.toFloat()
        repeat(70) {
            val rank = rnd.nextFloat()
            val x = m.x + rnd.nextGaussian().toFloat() * m.rx * 0.5f
            val tone = rnd.nextFloat()
            val angle = rnd.f(-0.4f, 0.4f)
            val len = rnd.f(0.9f, 1.3f) * u
            val lift = rnd.f(0f, 0.6f) * u
            if (rank >= season.litter) return@repeat
            val c = when {
                tone < 0.5f -> 0xFFB0302C.toInt()
                tone < 0.8f -> 0xFFC85A2C.toInt()
                else -> 0xFFC9913A.toInt()
            }
            fill.color = shade(mix(c, 0xFF6A4A3A.toInt(), season.litterAge.toFloat() * 0.8f), wetDark)
            canvas.save()
            canvas.rotate(angle * 57.3f, x, t.surface(x) - lift)
            canvas.drawOval(x - len, t.surface(x) - lift - 0.4f * u, x + len, t.surface(x) - lift + 0.4f * u, fill)
            canvas.restore()
        }
    }

    /** The ground surface behind the tree: shadows on it and the leaves lying on it. */
    private fun groundBack(canvas: Canvas, t: Tree, s: TreeScene, light: Light) {
        val u = t.u
        // A strip of earth from the soil's top up to the surface line, under the grass.
        val shadowLength = min(42f * u, 7f * u / max(0.06f, sin(max(2.0, s.sunElevation) * PI / 180).toFloat()))
        if (light.sun > 0.02f) {
            val sx = t.cx - s.sunSide.toFloat().let { if (abs(it) < 0.15f) 0.15f else it } * shadowLength * 0.55f
            fill.with(RadialGradient(sx, t.ground, shadowLength * 0.6f, intArrayOf(alpha(0xFF1A140F.toInt(), 0.32f * light.sun), alpha(0xFF1A140F.toInt(), 0f)), null, Shader.TileMode.CLAMP))
            canvas.save()
            canvas.scale(1f, 0.09f, sx, t.ground)
            canvas.drawCircle(sx, t.ground, shadowLength * 0.6f, fill)
            canvas.restore()
            fill.shader = null
        }
        litter(canvas, t, s, back = true)
        carpet(canvas, t, s)
    }

    /**
     * In the large view, the fallen leaves seen lying on the ground behind the foot of each tree, in
     * perspective: smaller and closer together further back, drifted a little downwind, browning
     * through the winter and frosted on cold mornings. Under the pines, their shed needles.
     */
    private fun carpet(canvas: Canvas, t: Tree, s: TreeScene) {
        if (t.land == null || s.snow > 2) return
        val u = t.u
        val season = s.season
        val wetDark = 1 - 0.22f * s.surface.toFloat()
        val frosted = rime(s)
        val hide = 1 - sm(0.5f, 2f, s.snow.toFloat())
        fun spread(seed: Long, x0: Float, reach: Float, amount: Float, count: Int, color: (Random) -> Int) {
            if (amount < 0.02f) return
            val rnd = Random(seed)
            repeat(count) {
                val back = rnd.nextFloat().pow(1.6f)
                val x = x0 + rnd.nextGaussian().toFloat() * reach * (1 - 0.4f * back) + s.windSide.toFloat() * reach * 0.15f
                val y = t.ground - back * 6f * u + 0.6f * u
                val len = rnd.f(1f, 1.6f) * u * (1 - 0.45f * back)
                val c = color(rnd)
                if (rnd.nextFloat() >= amount * hide) return@repeat
                fill.color = shade(mix(c, 0xFFD6DEE4.toInt(), 0.45f * frosted), wetDark * (1 - 0.15f * back))
                canvas.drawOval(x - len, y - len * 0.28f, x + len, y + len * 0.28f, fill)
            }
        }
        val age = season.litterAge.toFloat()
        spread(91, t.cx, t.tw * 0.45f, season.litter.toFloat(), 420) { r ->
            mix(intArrayOf(0xFFC29A46.toInt(), 0xFFB07F3B.toInt(), 0xFF9A6735.toInt(), 0xFFD0AC50.toInt())[r.nextInt(4)], 0xFF6A5444.toInt(), age * 0.75f)
        }
        t.maple?.let { m ->
            val ms = season.maple
            spread(92, m.x, m.rx * 0.6f, ms.litter.toFloat(), 200) { r ->
                mix(intArrayOf(0xFFB0302C.toInt(), 0xFFC85A2C.toInt(), 0xFFC9913A.toInt())[r.nextInt(3)], 0xFF6A4A3A.toInt(), ms.litterAge.toFloat() * 0.8f)
            }
        }
        // Scots pines shed their oldest needles in September and October: a rusty mat under them,
        // fresh after the shed and duller by spring.
        val shed = Season.smooth(262.0, 300.0, season.autumnDay).toFloat().let { if (season.dayOfYear < 200) 1f - 0.5f * Season.smooth(90.0, 180.0, season.springDay).toFloat() else 0.5f + 0.5f * it }
        for (p in t.pines) {
            stroke.strokeWidth = max(0.5f, 0.14f * u)
            val rnd = Random(p.seed.toLong() + 5)
            val x0 = p.trunk.x[0]
            val needle = mix(0xFF6E5440.toInt(), 0xFFA8643A.toInt(), shed - 0.5f)
            repeat((90 * hide).toInt()) {
                val back = rnd.nextFloat().pow(1.5f)
                val x = x0 + rnd.nextGaussian().toFloat() * p.height * 0.16f
                val y = p.trunk.y[0] + 1.5f * u - back * 4f * u
                val a = rnd.f(-0.6f, 0.6f)
                val len = rnd.f(0.5f, 0.9f) * u
                stroke.color = alpha(shade(mix(needle, 0xFFD6DEE4.toInt(), 0.4f * frosted), wetDark), 0.75f)
                canvas.drawLine(x, y, x + cos(a) * len, y + sin(a) * len * 0.3f, stroke)
            }
        }
    }

    private fun litter(canvas: Canvas, t: Tree, s: TreeScene, back: Boolean) {
        val u = t.u
        val season = s.season
        val amount = season.litter.toFloat()
        val rnd = Random(if (back) 61 else 62)
        val wetDark = 1 - 0.22f * s.surface.toFloat()
        val frosted = rime(s)
        repeat(t.spread(90)) {
            val rank = rnd.nextFloat()
            val near = rnd.nextFloat() < 0.55f / t.wide
            val x = if (near) t.cx + (rnd.nextGaussian().toFloat() * 0.2f * t.tw) else rnd.nextFloat() * t.w
            val tone = rnd.nextFloat()
            val angle = rnd.f(-0.4f, 0.4f)
            val len = rnd.f(1.5f, 2.2f) * u
            val lift = rnd.f(0f, 0.7f) * u
            if (rank >= amount) return@repeat
            var c = when {
                tone < 0.35f -> 0xFFC29A46.toInt()
                tone < 0.65f -> 0xFFB07F3B.toInt()
                tone < 0.85f -> 0xFF9A6735.toInt()
                else -> 0xFF7E5C3D.toInt()
            }
            c = shade(mix(c, 0xFF6A5444.toInt(), season.litterAge.toFloat() * 0.75f), wetDark)
            fill.color = mix(c, 0xFFD6DEE4.toInt(), 0.45f * frosted)
            canvas.save()
            canvas.rotate(angle * 57.3f, x, t.surface(x) - lift)
            canvas.drawOval(x - len, t.surface(x) - lift - 0.32f * u, x + len, t.surface(x) - lift + 0.32f * u, fill)
            canvas.restore()
        }
    }

    private fun trunk(canvas: Canvas, t: Tree, s: TreeScene, light: Light) {
        val u = t.u
        val tr = t.trunk
        val n = tr.size
        val outline = Path()
        outline.moveTo(tr.x[0] - tr.w[0] / 2, tr.y[0] + u)
        for (i in 0 until n) outline.lineTo(tr.x[i] - tr.w[i] / 2, tr.y[i])
        for (i in n - 1 downTo 0) outline.lineTo(tr.x[i] + tr.w[i] / 2, tr.y[i])
        outline.lineTo(tr.x[0] + tr.w[0] / 2, tr.y[0] + u)
        outline.close()
        val wet = s.surface.toFloat()
        // Lit from the sun's side, or by night from the moon's, a cool blue-grey on the white bark.
        val moonlit = light.night * light.moonVis
        val side = (if (moonlit > 0.2f) s.moonSide else s.sunSide).toFloat().let { if (abs(it) < 0.2f) 0.3f else it }
        val low = light.lowSun * (1 - moonlit)
        val contrast = 0.12f + 0.3f * light.sun + 0.2f * low + 0.16f * moonlit
        val bark = mix(mix(0xFFEDE8DC.toInt(), 0xFF9B978F.toInt(), wet * 0.35f), 0xFFE2E8F2.toInt(), light.rim * 0.6f)
        // A low sun lights the white bark warm on its side; the far side takes the cool of the open sky.
        val bright = mix(bark, 0xFFFFDDAA.toInt(), 0.55f * low)
        val dim = shade(mix(bark, 0xFF96A0B4.toInt(), 0.3f * low), 1 - contrast - 0.12f)
        canvas.save()
        canvas.clipPath(outline)
        // Each band of the trunk shaded across, lit on the sun's side.
        for (i in 0 until n - 1) {
            val x = (tr.x[i] + tr.x[i + 1]) / 2
            val w = tr.w[i] / 2 + u
            fill.with(LinearGradient(x - w * side, 0f, x + w * side, 0f, intArrayOf(dim, bright, shade(bright, 1 - contrast * 0.3f)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP))
            canvas.drawRect(x - w - u, tr.y[i + 1] - 1f, x + w + u, tr.y[i] + 1f + (if (i == 0) 2 * u else 0f), fill)
        }
        fill.shader = null
        // Lenticels: the birch's dark horizontal marks, more crowded low on the trunk.
        val rnd = Random(29)
        var y = t.ground - t.height * 0.08f
        while (y > t.top + t.height * 0.1f) {
            val f = (t.ground - y) / (t.height * 0.95f)
            val i = (f * (n - 1)).toInt().coerceIn(0, n - 1)
            val w = tr.w[i]
            if (rnd.nextFloat() < 0.6f) {
                val len = w * rnd.f(0.25f, 0.75f)
                val mx = tr.x[i] + rnd.f(-0.35f, 0.35f) * w
                stroke.strokeWidth = max(0.6f, rnd.f(0.2f, 0.6f) * u * (0.6f + 0.6f * w / t.baseW))
                stroke.color = alpha(0xFF2D2824.toInt(), rnd.f(0.55f, 0.85f))
                canvas.drawLine(mx - len / 2, y, mx + len / 2, y + rnd.f(-0.2f, 0.2f) * u, stroke)
            }
            y -= rnd.f(1.1f, 2.4f) * u * (0.6f + 0.8f * f)
        }
        // Dark chevrons under the branch collars.
        fill.color = alpha(0xFF3A332E.toInt(), 0.8f)
        for (j in t.junctions) {
            val (jx, jy, js, jw) = listOf(j[0], j[1], j[2], j[3])
            val p = Path()
            p.moveTo(jx + js * jw * 0.1f, jy - 0.4f * u)
            p.lineTo(jx + js * jw * 0.55f, jy - 0.2f * u)
            p.lineTo(jx - js * jw * 0.2f, jy + jw * 0.7f)
            p.close()
            canvas.drawPath(p, fill)
        }
        // The old base of a silver birch is dark and deeply fissured.
        val baseTop = t.ground - t.height * 0.14f
        val base = Path()
        base.moveTo(t.cx - t.baseW * 2, t.ground + 2 * u)
        var x = t.cx - t.baseW * 2
        while (x <= t.cx + t.baseW * 2) {
            base.lineTo(x, baseTop + (t.ground - baseTop) * (0.3f + 0.22f * sin(x * 0.35f / u + 1f) + 0.12f * rnd.nextFloat()))
            x += 1.6f * u
        }
        base.lineTo(t.cx + t.baseW * 2, t.ground + 2 * u)
        base.close()
        fill.with(LinearGradient(0f, baseTop, 0f, t.ground, alpha(0xFF4C443D.toInt(), 0.85f), 0xFF3E3630.toInt(), Shader.TileMode.CLAMP))
        canvas.drawPath(base, fill)
        fill.shader = null
        stroke.strokeWidth = max(0.6f, 0.3f * u)
        repeat(14) {
            val fx = t.cx + rnd.f(-0.5f, 0.5f) * t.baseW * 1.2f
            val fy = t.ground - rnd.f(0f, 0.6f) * (t.ground - baseTop)
            stroke.color = alpha(if (rnd.nextBoolean()) 0xFF231E1A.toInt() else 0xFF71665A.toInt(), 0.7f)
            canvas.drawLine(fx, fy, fx + rnd.f(-0.4f, 0.4f) * u, fy - rnd.f(2f, 6f) * u, stroke)
        }
        // A little moss on the shady side of the base after wet weather.
        if (s.soil > 0.3) {
            fill.color = alpha(0xFF5E6B3A.toInt(), 0.45f * sm(0.3f, 0.7f, s.soil.toFloat()))
            canvas.drawOval(tr.x[0] - side * t.baseW * 0.55f - 1.6f * u, t.ground - 4 * u, tr.x[0] - side * t.baseW * 0.55f + 1.6f * u, t.ground, fill)
        }
        canvas.restore()
        stroke.strokeWidth = max(0.5f, 0.2f * u)
        stroke.color = alpha(0xFF6F6A62.toInt(), 0.5f)
        canvas.drawPath(outline, stroke)
    }

    private fun branches(canvas: Canvas, t: Tree, s: TreeScene, light: Light, dx: (Float, Float, Float) -> Float) {
        val u = t.u
        val snow = s.branchSnow.toFloat()
        for (limb in t.branches) for (k in 0 until limb.size - 1) {
            val w = limb.w[k]
            stroke.strokeWidth = max(0.6f, w)
            stroke.color = limbColor(t, s, light, w)
            canvas.drawLine(limb.x[k] + dx(limb.x[k], limb.y[k], 0f), limb.y[k], limb.x[k + 1] + dx(limb.x[k + 1], limb.y[k + 1], 0f), limb.y[k + 1], stroke)
        }
        if (snow < 0.03f) return
        // Snow lies along the upper side of the branches that are flat enough to hold it.
        stroke.color = alpha(0xFFF3F5F7.toInt(), 0.92f * min(1f, snow * 2f))
        for (limb in t.branches) for (k in 0 until limb.size - 1) {
            val ddx = limb.x[k + 1] - limb.x[k]
            val ddy = limb.y[k + 1] - limb.y[k]
            val flat = abs(ddx) / max(0.001f, sqrt(ddx * ddx + ddy * ddy))
            if (flat < 0.45f) continue
            val thick = (0.3f + 0.7f * snow) * u * flat
            stroke.strokeWidth = max(0.6f, thick)
            val off = limb.w[k] / 2 + thick * 0.3f
            canvas.drawLine(limb.x[k] + dx(limb.x[k], limb.y[k], 0f), limb.y[k] - off, limb.x[k + 1] + dx(limb.x[k + 1], limb.y[k + 1], 0f), limb.y[k + 1] - off, stroke)
        }
    }

    /**
     * Birds passing far overhead, small against the sky: a skein of geese in a loose V, crows and
     * jackdaws straggling to their roost, or a few small birds. They move on a little between frames.
     */
    private fun flight(canvas: Canvas, t: Tree, s: TreeScene, light: Light, frame: Int) {
        val kind = s.flight ?: return
        val u = t.u
        val rnd = Random(s.variation / 6 * 7919 + 11)
        val way = if (rnd.nextBoolean()) 1f else -1f
        val x0 = t.w * rnd.f(0.25f, 0.75f) + way * frame * 2.5f * u
        val y0 = t.sky + t.skyHeight * rnd.f(0.06f, 0.26f)
        // Dark against the sky whatever its colour, a touch softened by the distance.
        val ink = mix(0xFF1A1C20.toInt(), light.skyTop, 0.12f + 0.1f * light.day)
        stroke.color = ink
        fun bird(x: Float, y: Float, span: Float, up: Float) {
            stroke.strokeWidth = max(1f, span * 0.14f)
            canvas.drawLine(x - span / 2, y - span * up, x, y, stroke)
            canvas.drawLine(x, y, x + span / 2, y - span * up, stroke)
        }
        when (kind) {
            Flight.GEESE -> {
                // One arm longer than the other, as skeins usually fly.
                val count = 7 + rnd.nextInt(5)
                for (i in 0 until count) {
                    val rank = (i + 1) / 2
                    val arm = if (i % 2 == 0) 1f else -1f
                    val x = x0 - way * rank * 3.6f * u + rnd.f(-0.4f, 0.4f) * u
                    val y = y0 + arm * rank * (if (arm > 0) 1.8f else 1.4f) * u + rnd.f(-0.3f, 0.3f) * u
                    bird(x, y, 2.8f * u, rnd.f(0.15f, 0.35f))
                }
            }
            Flight.CORVIDS -> repeat(5 + rnd.nextInt(5)) {
                val x = x0 + rnd.f(-18f, 18f) * u
                val y = y0 + rnd.f(-5f, 6f) * u
                // Wings caught at different beats: raised, level or just past the downstroke.
                bird(x, y, rnd.f(2.8f, 3.6f) * u, rnd.f(-0.12f, 0.4f))
            }
            Flight.SMALL_BIRDS -> repeat(3 + rnd.nextInt(3)) { i ->
                val x = x0 - way * i * 3.2f * u
                val y = y0 + sin(i * 1.7f + frame) * 1.2f * u
                bird(x, y, 1.6f * u, rnd.f(0f, 0.35f))
            }
        }
    }

    /**
     * A small visitor in the birch, held still across both animation frames: the crow and the wood
     * pigeon on a strong limb high up, the blackbird lower in the crown, and the great tit on one of
     * the hanging twigs, on a new one at each refresh as it works its way through the tree.
     */
    private fun perchedBird(canvas: Canvas, t: Tree, s: TreeScene, dx: (Float, Float, Float) -> Float) {
        val visitor = s.bird ?: return
        val u = t.u
        if (visitor == PerchedBird.GREAT_TIT) {
            greatTit(canvas, t, s, dx)
            return
        }
        val blackbird = visitor == PerchedBird.BLACKBIRD
        val side = if (blackbird && (s.variation / 6) % 2 == 0L) -1f else 1f
        val targetX = t.cx + side * t.tw * (if (blackbird) 0.2f else 0.27f)
        val targetY = t.top + t.height * (if (blackbird) 0.56f else 0.43f)
        val perch = t.branches.flatMap { limb -> (2 until limb.size - 2).map { limb to it } }
            .filter { (limb, k) -> limb.w[k] > 0.5f * u && abs(limb.y[k + 1] - limb.y[k]) < 2.5f * u }
            .minByOrNull { (limb, k) -> abs(limb.x[k] - targetX) + abs(limb.y[k] - targetY) * 0.7f }
            ?: return
        val (limb, k) = perch
        val x = limb.x[k] + dx(limb.x[k], limb.y[k], 0f)
        val y = limb.y[k] - limb.w[k] * 0.4f
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(1.4f, 1.4f)
        if (limb.x[k] < t.cx) canvas.scale(-1f, 1f)
        if (blackbird) {
            blackbird(canvas, u)
            canvas.restore()
            return
        }
        if (visitor == PerchedBird.WOOD_PIGEON) {
            woodPigeon(canvas, u)
            canvas.restore()
            return
        }
        val body = 0xFF24282B.toInt()
        val wing = 0xFF15191C.toInt()
        val head = 0xFF1C2023.toInt()
        stroke.strokeWidth = max(0.5f, 0.18f * u)
        stroke.color = wing
        canvas.drawLine(-1.2f * u, -0.5f * u, -1.4f * u, 0.6f * u, stroke)
        canvas.drawLine(0.7f * u, -0.6f * u, 0.5f * u, 0.6f * u, stroke)
        fill.color = wing
        canvas.drawPath(Path().apply {
            moveTo(-1.2f * u, -1.8f * u)
            lineTo(-5.4f * u, -3.4f * u)
            lineTo(-4.4f * u, -1.6f * u)
            lineTo(-2.2f * u, -0.8f * u)
            close()
        }, fill)
        fill.color = body
        canvas.drawOval(-2.8f * u, -3.9f * u, 2.5f * u, -0.5f * u, fill)
        fill.color = wing
        canvas.drawOval(-1.9f * u, -3.2f * u, 1.3f * u, -1.1f * u, fill)
        fill.color = head
        canvas.drawCircle(2.1f * u, -3.6f * u, 1.35f * u, fill)
        fill.color = 0xFF77716A.toInt()
        canvas.drawPath(Path().apply {
            moveTo(3.1f * u, -3.65f * u)
            lineTo(4.7f * u, -3.35f * u)
            lineTo(3.1f * u, -3.05f * u)
            close()
        }, fill)
        fill.color = 0xFFB9B7AB.toInt()
        canvas.drawCircle(2.45f * u, -4.0f * u, max(0.5f, 0.17f * u), fill)
        canvas.restore()
    }

    /** Turdus merula, a male: all black, long tail held a little down, orange-yellow bill and eye-ring. */
    private fun blackbird(canvas: Canvas, u: Float) {
        // About half a crow's length; the caller scales by 1.4 as for the crow.
        canvas.scale(0.85f, 0.85f)
        stroke.strokeWidth = max(0.5f, 0.2f * u)
        stroke.color = 0xFF3A2E28.toInt()
        canvas.drawLine(-0.6f * u, -0.8f * u, -0.8f * u, 0.4f * u, stroke)
        canvas.drawLine(0.5f * u, -0.8f * u, 0.4f * u, 0.4f * u, stroke)
        fill.color = 0xFF131314.toInt()
        canvas.drawPath(Path().apply {
            moveTo(-1.4f * u, -2.1f * u)
            lineTo(-5.0f * u, -0.9f * u)
            lineTo(-4.8f * u, -0.1f * u)
            lineTo(-1.5f * u, -1.1f * u)
            close()
        }, fill)
        fill.color = 0xFF1A1A1C.toInt()
        canvas.drawOval(-2.4f * u, -3.6f * u, 1.8f * u, -0.6f * u, fill)
        fill.color = 0xFF252321.toInt()
        canvas.drawOval(-2.0f * u, -3.2f * u, 0.9f * u, -1.3f * u, fill)
        fill.color = 0xFF161617.toInt()
        canvas.drawCircle(1.7f * u, -3.6f * u, 1.1f * u, fill)
        fill.color = 0xFFE8A01E.toInt()
        canvas.drawPath(Path().apply {
            moveTo(2.5f * u, -3.8f * u)
            lineTo(3.9f * u, -3.5f * u)
            lineTo(2.5f * u, -3.2f * u)
            close()
        }, fill)
        fill.color = 0xFFDDA62C.toInt()
        canvas.drawCircle(2.0f * u, -3.85f * u, max(0.5f, 0.3f * u), fill)
        fill.color = 0xFF0C0C0C.toInt()
        canvas.drawCircle(2.0f * u, -3.85f * u, max(0.3f, 0.15f * u), fill)
    }

    /**
     * Parus major on a hanging twig, head up as it clings: black head with a white cheek, yellow breast
     * with its black stripe, olive back and blue-grey wings. Moves to another twig at each refresh.
     */
    private fun greatTit(canvas: Canvas, t: Tree, s: TreeScene, dx: (Float, Float, Float) -> Float) {
        val u = t.u
        val twigs = t.curtains.filter {
            it.front && it.size > 10 && abs(it.x[0] - t.cx) < t.tw * 0.42f &&
                it.y[0] > t.top + t.height * 0.25f && it.y[0] < t.top + t.height * 0.6f
        }
        if (twigs.isEmpty()) return
        val pick = Random(s.variation * 2654435761L)
        val c = twigs[pick.nextInt(twigs.size)]
        val k = (c.size * pick.f(0.3f, 0.6f)).toInt()
        val x = c.x[k] + dx(c.x[k], c.y[k], c.hang[k])
        val y = c.y[k] - lift(t, dx, c.hang[k])
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(1.6f, 1.6f)
        if (pick.nextBoolean()) canvas.scale(-1f, 1f)
        canvas.rotate(-40f)
        stroke.strokeWidth = max(0.5f, 0.14f * u)
        stroke.color = 0xFF5E6A78.toInt()
        canvas.drawLine(-0.2f * u, -0.5f * u, 0f, 0.15f * u, stroke)
        canvas.drawLine(0.35f * u, -0.5f * u, 0.4f * u, 0.15f * u, stroke)
        fill.color = 0xFF56646F.toInt()
        canvas.drawPath(Path().apply {
            moveTo(-1.0f * u, -1.3f * u)
            lineTo(-2.7f * u, -0.6f * u)
            lineTo(-2.6f * u, -0.2f * u)
            lineTo(-1.0f * u, -0.8f * u)
            close()
        }, fill)
        fill.color = 0xFFE2C63C.toInt()
        canvas.drawOval(-1.3f * u, -2.3f * u, 1.0f * u, -0.35f * u, fill)
        fill.color = 0xFF7C8A47.toInt()
        canvas.drawOval(-1.4f * u, -2.4f * u, 0.3f * u, -1.15f * u, fill)
        fill.color = 0xFF5F7080.toInt()
        canvas.drawOval(-1.3f * u, -1.9f * u, -0.1f * u, -1.05f * u, fill)
        stroke.strokeWidth = max(0.5f, 0.28f * u)
        stroke.color = 0xFF161616.toInt()
        canvas.drawLine(0.55f * u, -1.7f * u, 0.25f * u, -0.55f * u, stroke)
        fill.color = 0xFF151515.toInt()
        canvas.drawCircle(0.8f * u, -2.4f * u, 0.75f * u, fill)
        fill.color = 0xFFF1F1EC.toInt()
        canvas.drawOval(0.7f * u, -2.5f * u, 1.4f * u, -1.98f * u, fill)
        fill.color = 0xFF2A2A2A.toInt()
        canvas.drawPath(Path().apply {
            moveTo(1.45f * u, -2.55f * u)
            lineTo(1.95f * u, -2.45f * u)
            lineTo(1.45f * u, -2.3f * u)
            close()
        }, fill)
        canvas.restore()
    }

    /** Columba palumbus: grey-blue head and back, rosy breast, pale neck patch and folded-wing edge. */
    private fun woodPigeon(canvas: Canvas, u: Float) {
        stroke.strokeWidth = max(0.6f, 0.2f * u)
        stroke.color = 0xFF9B6568.toInt()
        for (foot in listOf(-1.15f, 0.7f)) {
            canvas.drawLine(foot * u, -0.7f * u, (foot - 0.1f) * u, 0.45f * u, stroke)
            canvas.drawLine((foot - 0.1f) * u, 0.45f * u, (foot + 0.45f) * u, 0.55f * u, stroke)
        }
        fill.color = 0xFF555D65.toInt()
        canvas.drawPath(Path().apply {
            moveTo(-2.1f * u, -2.55f * u)
            lineTo(-5.15f * u, -2.9f * u)
            lineTo(-5.35f * u, -1.65f * u)
            lineTo(-2.55f * u, -1.15f * u)
            close()
        }, fill)
        // The darker terminal band is visible even when the tail is folded.
        stroke.strokeWidth = max(0.7f, 0.45f * u)
        stroke.color = 0xFF34383E.toInt()
        canvas.drawLine(-5.1f * u, -2.75f * u, -5.25f * u, -1.75f * u, stroke)
        fill.color = 0xFF7A8085.toInt()
        canvas.drawPath(Path().apply {
            moveTo(-2.6f * u, -2.75f * u)
            cubicTo(-2.1f * u, -4.0f * u, -0.3f * u, -4.5f * u, 1.25f * u, -3.8f * u)
            cubicTo(2.65f * u, -3.2f * u, 2.8f * u, -1.55f * u, 1.35f * u, -0.8f * u)
            cubicTo(-0.05f * u, -0.2f * u, -2.5f * u, -0.95f * u, -2.6f * u, -2.75f * u)
            close()
        }, fill)
        fill.color = 0xFFB68D92.toInt()
        canvas.drawOval(0.2f * u, -3.55f * u, 2.55f * u, -1.0f * u, fill)
        fill.color = 0xFFAAA9A4.toInt()
        canvas.drawOval(0.15f * u, -1.55f * u, 1.75f * u, -0.65f * u, fill)
        fill.color = 0xFF5D666F.toInt()
        canvas.drawPath(Path().apply {
            moveTo(-2.3f * u, -2.6f * u)
            cubicTo(-2.05f * u, -3.6f * u, -0.8f * u, -3.65f * u, 0.45f * u, -3.05f * u)
            cubicTo(0.65f * u, -2.4f * u, -0.65f * u, -1.3f * u, -2.25f * u, -1.25f * u)
            close()
        }, fill)
        stroke.strokeWidth = max(0.65f, 0.34f * u)
        stroke.color = 0xFFD5D1C5.toInt()
        canvas.drawLine(-1.9f * u, -1.85f * u, -0.25f * u, -2.15f * u, stroke)
        fill.color = 0xFF7C8D80.toInt()
        canvas.drawOval(0.95f * u, -3.95f * u, 2.0f * u, -2.85f * u, fill)
        fill.color = 0xFF8C939A.toInt()
        canvas.drawOval(1.35f * u, -4.75f * u, 3.45f * u, -2.75f * u, fill)
        fill.color = 0xFFDAD7C9.toInt()
        canvas.drawOval(1.05f * u, -3.75f * u, 1.75f * u, -2.85f * u, fill)
        fill.color = 0xFFAA6D62.toInt()
        canvas.drawPath(Path().apply {
            moveTo(3.3f * u, -3.65f * u)
            lineTo(4.05f * u, -3.55f * u)
            lineTo(3.3f * u, -3.25f * u)
            close()
        }, fill)
        fill.color = 0xFFD9AD65.toInt()
        canvas.drawCircle(2.65f * u, -4.05f * u, max(0.55f, 0.24f * u), fill)
        fill.color = 0xFF282725.toInt()
        canvas.drawCircle(2.68f * u, -4.05f * u, max(0.3f, 0.11f * u), fill)
    }

    /**
     * By night a faint blue-grey edge along the larger limbs, on the side the light comes from: the
     * moon's side on clear nights, the top of each limb under a glowing overcast. It is drawn in the
     * lit layer, so the night's own colour keeps it dim. The white trunk needs no edge; [trunk] turns
     * its light to the moon instead.
     */
    private fun nightRim(canvas: Canvas, t: Tree, s: TreeScene, light: Light, dx: (Float, Float, Float) -> Float,
                         limbs: List<Limb> = t.branches, strength: Float = 1f, until: (Limb) -> Int = { it.size - 1 }) {
        val a = light.rim * strength
        if (a < 0.02f) return
        // Where the light comes from: up and towards the moon when it shows, straight down from the cloud otherwise.
        val moon = light.moonVis / (light.moonVis + 0.6f * light.skyglow + 0.15f)
        val lx = s.moonSide.toFloat().coerceIn(-1f, 1f).let { if (abs(it) < 0.3f) (if (it < 0) -0.3f else 0.3f) else it } * 0.9f * moon
        rim(canvas, t, dx, limbs, lx, -1f, 0xFFC6D0E0.toInt(), 0.6f * a, 0.7f, until)
    }

    /**
     * By day the sun picks out the side of each limb that faces it: faintly when it is high, and when
     * it is low and shining in under the crown, a warm line along the white bark and the dark twigs
     * alike. Bare in winter the whole frame of the tree shows it.
     */
    private fun sunRim(canvas: Canvas, t: Tree, s: TreeScene, light: Light, dx: (Float, Float, Float) -> Float) {
        val a = light.sun * 0.25f + light.lowSun * 0.75f
        if (a < 0.02f) return
        val e = s.sunElevation.toFloat()
        val lx = s.sunSide.toFloat().coerceIn(-1f, 1f).let { if (abs(it) < 0.3f) (if (it < 0) -0.3f else 0.3f) else it }
        val ly = -max(0.2f, sin(e.coerceIn(0f, 60f) * PI.toFloat() / 180))
        rim(canvas, t, dx, t.branches, lx, ly, mix(0xFFFFF3DE.toInt(), 0xFFFFD08E.toInt(), light.lowSun), 0.8f * a, 0.45f)
    }

    /** A light edge on the side of each limb facing (`lx`, `ly`), for limbs at least `minW` u wide. */
    private fun rim(canvas: Canvas, t: Tree, dx: (Float, Float, Float) -> Float, limbs: List<Limb>, lx0: Float, ly0: Float,
                    edge: Int, a: Float, minW: Float, until: (Limb) -> Int = { it.size - 1 }) {
        val u = t.u
        val norm = sqrt(lx0 * lx0 + ly0 * ly0)
        val lx = lx0 / norm
        val ly = ly0 / norm
        for (limb in limbs) for (k in 0 until until(limb)) {
            val w = limb.w[k]
            if (w < minW * u) continue
            val x1 = limb.x[k] + dx(limb.x[k], limb.y[k], 0f)
            val x2 = limb.x[k + 1] + dx(limb.x[k + 1], limb.y[k + 1], 0f)
            val ddx = x2 - x1
            val ddy = limb.y[k + 1] - limb.y[k]
            val len = max(0.001f, sqrt(ddx * ddx + ddy * ddy))
            var nx = -ddy / len
            var ny = ddx / len
            var facing = nx * lx + ny * ly
            if (facing < 0) { nx = -nx; ny = -ny; facing = -facing }
            if (facing < 0.15f) continue
            val off = w * 0.32f
            stroke.strokeWidth = max(0.5f, w * 0.3f)
            stroke.color = alpha(edge, a * facing * sm(minW * u, (minW + 0.9f) * u, w))
            canvas.drawLine(x1 + nx * off, limb.y[k] + ny * off, x2 + nx * limb.w[k + 1] * 0.32f, limb.y[k + 1] + ny * limb.w[k + 1] * 0.32f, stroke)
        }
    }

    /** The bark of a limb `w` wide: white on the thick parts, dark on the thin, greyed by the night. */
    private fun limbColor(t: Tree, s: TreeScene, light: Light, w: Float): Int {
        val wet = s.surface.toFloat()
        val whiteBark = mix(0xFFDAD4C7.toInt(), 0xFF8E8A83.toInt(), wet * 0.35f)
        val twig = mix(shade(0xFF3F3936.toInt(), 1 - 0.15f * wet), 0xFF8C95AA.toInt(), light.night * 0.35f + 0.25f * light.rim)
        return mix(twig, whiteBark, sm(2f * t.u, 4f * t.u, w))
    }

    /**
     * By night, with the tree in leaf, the inner parts of the three strongest limbs show through the
     * curtains with their faint edge, as the eye picks out a tree's frame in the dark. By day the
     * leaves hide them as before.
     */
    private fun nightLimbs(canvas: Canvas, t: Tree, s: TreeScene, light: Light, dx: (Float, Float, Float) -> Float) {
        val a = light.night * sm(0.15f, 0.6f, s.season.leaves.toFloat())
        if (a < 0.02f) return
        val u = t.u
        val strong = t.branches.sortedByDescending { it.w[0] }.take(3)
        fun until(limb: Limb) = (limb.size * 0.7f).toInt()
        for (limb in strong) for (k in 0 until until(limb)) {
            val w = limb.w[k]
            val fade = 1 - sm(0.45f, 1f, k / (limb.size * 0.7f))
            stroke.strokeWidth = max(0.7f, w)
            stroke.color = alpha(limbColor(t, s, light, w), 0.9f * a * fade)
            canvas.drawLine(limb.x[k] + dx(limb.x[k], limb.y[k], 0f), limb.y[k], limb.x[k + 1] + dx(limb.x[k + 1], limb.y[k + 1], 0f), limb.y[k + 1], stroke)
        }
        nightRim(canvas, t, s, light, dx, strong, a) { until(it) }
    }

    private fun leafColor(season: Season, leaf: Leaf): Int {
        val green = mix(mix(0xFF9DB46B.toInt(), 0xFF5D7443.toInt(), 1 - season.fresh.toFloat()), 0xFF7A8A4C.toInt(), leaf.tint * 0.35f)
        val turn = ((season.turn.toFloat() * 1.4f - leaf.early * 0.45f)).coerceIn(0f, 1f)
        if (turn <= 0f) return green
        val gold = mix(0xFFE0B941.toInt(), 0xFFE8C64E.toInt(), leaf.tint)
        val late = if (leaf.tint > 0.7f) 0xFFA86F35.toInt() else 0xFFB88A3C.toInt()
        return when {
            turn < 0.35f -> mix(green, 0xFFB3A250.toInt(), turn / 0.35f)
            turn < 0.7f -> mix(0xFFB3A250.toInt(), gold, (turn - 0.35f) / 0.35f)
            else -> mix(gold, late, (turn - 0.7f) / 0.3f * 0.8f * (1 - season.leaves.toFloat()))
        }
    }

    /**
     * How far a point `hang` along a curtain rises as the wind swings it out: a little in a breeze, and
     * as a pendulum would in a storm, when the curtain streams out towards level.
     */
    private fun lift(t: Tree, dx: (Float, Float, Float) -> Float, hang: Float): Float {
        val out = abs(dx(0f, t.ground, hang))
        if (hang <= 0f) return 0f
        val s = (out / hang).coerceAtMost(0.95f)
        return max(out * 0.12f, hang * (1 - sqrt(1 - s * s)))
    }

    /** The hanging twigs, their leaves, buds and catkins: half behind the trunk, half in front. */
    private fun curtains(canvas: Canvas, t: Tree, s: TreeScene, light: Light, dx: (Float, Float, Float) -> Float, front: Boolean) {
        val u = t.u
        val season = s.season
        val depthShade = if (front) 1f else 0.82f
        // By night the bare twigs catch a little moonlight, so the tracery stays against the sky.
        val twig = mix(shade(0xFF5A4840.toInt(), depthShade * (1 - 0.15f * s.surface.toFloat())), 0xFF9AA3B8.toInt(), light.night * (0.45f + 0.25f * light.moonVis))
        val px = FloatArray(40)
        val py = FloatArray(40)
        // Bare, the curtains are a fine purple-brown veil; in leaf they are mostly hidden anyway.
        val veil = alpha(mix(twig, shade(0xFF7A625A.toInt(), depthShade), (1 - season.leaves.toFloat()) * (1 - light.night)), 0.5f + 0.35f * season.leaves.toFloat())
        stroke.color = veil
        // A low sun catches the curtains on its side, copper on the purple-brown twigs; those on the far
        // side hang in the crown's own shade.
        val catch = light.lowSun
        val copper = alpha(0xFFDDA676.toInt(), Color.alpha(veil) / 255f)
        for (c in t.curtains) {
            if (c.front != front) continue
            if (catch > 0.01f) {
                val toward = (s.sunSide.toFloat() * ((c.x[c.size / 2] - t.cx) / (t.tw / 2))).coerceIn(-1f, 1f)
                stroke.color = shade(mix(veil, copper, 0.6f * catch * sm(-0.3f, 0.9f, toward)), 1 - 0.18f * catch * sm(0.1f, 0.9f, -toward))
            }
            val path = Path()
            for (k in 0 until c.size) {
                px[k] = c.x[k] + dx(c.x[k], c.y[k], c.hang[k])
                py[k] = c.y[k] - lift(t, dx, c.hang[k])
                if (k == 0) path.moveTo(px[k], py[k]) else path.lineTo(px[k], py[k])
            }
            stroke.strokeWidth = max(0.6f, c.w[c.size / 2])
            canvas.drawPath(path, stroke)
        }
        // Buds swelling along the twigs before the leaves, and catkins hanging from them.
        if (season.buds > 0.05) {
            fill.color = alpha(mix(0xFF6B4A3A.toInt(), 0xFF7E8C4E.toInt(), season.buds.toFloat()), depthShade)
            val r = max(0.55f, (0.2f + 0.3f * season.buds.toFloat()) * u)
            for (c in t.curtains) {
                if (c.front != front) continue
                for (k in 3 until c.size step 3) canvas.drawCircle(c.x[k] + dx(c.x[k], c.y[k], c.hang[k]), c.y[k], r, fill)
            }
        }
        if (season.catkins > 0.05) {
            stroke.strokeWidth = 0.55f * u
            stroke.color = alpha(0xFFAD9459.toInt(), season.catkins.toFloat() * depthShade)
            for ((i, c) in t.curtains.withIndex()) {
                if (c.front != front || i % 3 != 0) continue
                val k = (c.size * 0.4f).toInt() + i % 5
                if (k >= c.size) continue
                val x = c.x[k] + dx(c.x[k], c.y[k], c.hang[k])
                canvas.drawLine(x, c.y[k], x + 0.3f * u, c.y[k] + 2.8f * u * season.catkins.toFloat(), stroke)
            }
        }
        if (season.leaves < 0.01) return
        // By night the leaves of each curtain run together into one soft hanging strand, as the eye
        // sees them in the dark, instead of thousands of separate specks; the gaps between stay sky.
        val mass = light.night * sm(0.15f, 0.7f, season.leaves.toFloat())
        val nightGrey = light.night * (0.3f + 0.2f * light.moonVis - 0.08f * light.skyglow) * 0.85f
        val massColor = mix(leafColor(season, Leaf(0, 0, 0f, 1f, 0f, 0.5f, 0.5f)), 0xFF8C94A8.toInt(), nightGrey)
        if (mass > 0.02f) {
            stroke.strokeWidth = 1.15f * u * season.leafSize.toFloat()
            stroke.color = alpha(0xFFFFFFFF.toInt(), 0.5f * mass)
            for (c in t.curtains) {
                if (c.front != front || c.size < 3) continue
                val path = Path()
                for (k in 1 until c.size) {
                    val x = c.x[k] + dx(c.x[k], c.y[k], c.hang[k])
                    val y = c.y[k] - lift(t, dx, c.hang[k])
                    if (k == 1) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val mid = c.size / 2
                val towardMoon = (s.moonSide.toFloat() * ((c.x[mid] - t.cx) / (t.tw / 2))).coerceIn(-1f, 1f)
                val lit = 0.85f * depthShade * (1 + 0.3f * light.moonVis * light.night * towardMoon)
                // Lit from above under a glowing overcast: lighter where the strand starts high in the crown.
                fun at(y: Float) = shade(massColor, lit * (1 + light.skyglow * (0.22f * (1 - ((y - t.top) / t.height).coerceIn(0f, 1f)) - 0.12f)))
                stroke.shader = LinearGradient(0f, c.y[1], 0f, c.y[c.size - 1], at(c.y[1]), at(c.y[c.size - 1]), Shader.TileMode.CLAMP)
                canvas.drawPath(path, stroke)
            }
            stroke.shader = null
        }
        // Leaves go into one path per colour, so thousands draw in a few calls.
        val paths = HashMap<Int, Path>()
        val size = 1.9f * u * season.leafSize.toFloat()
        val sun = light.sun
        val glow = light.golden * (if (front) 0.85f else 1f)
        for (leaf in t.leaves) {
            if (leaf.rank >= season.leaves) continue
            val c = t.curtains[leaf.curtain]
            if (c.front != front) continue
            val k = leaf.point
            val bx = c.x[k] + dx(c.x[k], c.y[k], c.hang[k])
            val by = c.y[k] - lift(t, dx, c.hang[k])
            val toward = (s.sunSide.toFloat() * ((bx - t.cx) / (t.tw / 2))).coerceIn(-1f, 1f)
            val towardMoon = (s.moonSide.toFloat() * ((bx - t.cx) / (t.tw / 2))).coerceIn(-1f, 1f)
            // By night the moon lights the crown on its side; under a glowing overcast the light comes from above.
            val high = 1 - ((by - t.top) / t.height).coerceIn(0f, 1f)
            val lit = 1 + (0.14f * sun + 0.16f * light.lowSun) * toward - 0.06f * leaf.tint + 0.3f * light.moonVis * light.night * towardMoon + light.skyglow * (0.22f * high - 0.12f)
            var color = leafColor(season, leaf)
            // Low sun through the leaves: the curtains on its side glow, lit from behind.
            if (glow > 0.01f) color = mix(color, if (season.turn > 0.4) 0xFFF4B04C.toInt() else 0xFFD3DA74.toInt(), glow * sm(-0.2f, 0.9f, toward) * (0.55f + 0.45f * leaf.tint))
            // Moonlight greys the leaves a little so the crown keeps its shape at night.
            if (light.night > 0.01f) color = mix(color, 0xFF8C94A8.toInt(), light.night * (0.3f + 0.2f * light.moonVis - 0.08f * light.skyglow) * (0.7f + 0.3f * leaf.tint))
            // ...and the single leaves sink into their strand, so the crown is not a field of specks.
            if (mass > 0.02f) color = mix(color, massColor, 0.4f * mass)
            color = shade(color, depthShade * lit)
            val key = (Color.red(color) / 10) shl 16 or ((Color.green(color) / 10) shl 8) or (Color.blue(color) / 10)
            val path = paths.getOrPut(key) { Path() }
            val len = size * leaf.size
            val ca = cos(leaf.angle)
            val sa = sin(leaf.angle)
            val tipX = bx + ca * len
            val tipY = by + sa * len
            val side = len * 0.42f
            path.moveTo(bx, by)
            path.quadTo(bx + ca * len * 0.45f - sa * side, by + sa * len * 0.45f + ca * side, tipX, tipY)
            path.quadTo(bx + ca * len * 0.45f + sa * side, by + sa * len * 0.45f - ca * side, bx, by)
            path.close()
        }
        for ((key, path) in paths) {
            fill.color = Color.rgb(((key shr 16) and 0xFF) * 10 + 5, ((key shr 8) and 0xFF) * 10 + 5, (key and 0xFF) * 10 + 5)
            canvas.drawPath(path, fill)
        }
    }

    /** Grass, the flowers and fungi of the season, snow and puddles: everything in front at the base. */
    private fun groundFront(canvas: Canvas, t: Tree, s: TreeScene, light: Light, frame: Int) {
        val u = t.u
        val season = s.season
        val day = season.dayOfYear
        litter(canvas, t, s, back = false)
        val snowCm = s.snow.toFloat()
        val snowH = min(18f * u, snowCm * 0.85f * u) * sm(0.2f, 1.2f, snowCm)
        val dead = strawGrass(season)
        val greenGrass = mix(mix(0xFF87A05C.toInt(), 0xFF5E713E.toInt(), 1 - season.fresh.toFloat()), 0xFFB5A26A.toInt(), 0.75f * parched(s))
        val autumnGrass = mix(greenGrass, 0xFF968C5C.toInt(), season.turn.toFloat() * 0.6f)
        // Blade by blade, not one averaged colour: new green shoots come up through last year's straw.
        val straw = 0xFF8C8062.toInt()
        val frosted = rime(s)
        val height = (0.8f + 0.4f * (1 - dead) * (1 - season.fresh.toFloat() * 0.3f))
        // Seed heads stand over the grass from July into September.
        val seeds = (Season.smooth(185.0, 200.0, day.toDouble()) * (1 - Season.smooth(262.0, 285.0, day.toDouble()))).toFloat()
        val blade = Random(18)
        val bend = s.windSide.toFloat() * sm(3f, 14f, s.wind.toFloat()) * 1.6f * u + (if (s.animated) (frame * 2 - 1) * 0.25f * u else 0f)
        val wetDark = 1 - 0.18f * s.surface.toFloat()

        // Snow first, so taller grass pokes through it.
        if (snowH > 0.3f) {
            val path = Path()
            path.moveTo(0f, t.surface(0f) + 0.6f * u)
            var x = 0f
            while (x <= t.w) {
                val hollow = 1 - 0.55f * exp(-((x - t.cx) / (t.baseW * 1.1f)).pow(2))
                path.lineTo(x, t.surface(x) - snowH * hollow * (1 + 0.14f * sin(x / t.w * 7f + 1f)))
                x += u
            }
            path.lineTo(t.w, t.surface(t.w) + 0.6f * u)
            path.close()
            val patchy = sm(0.2f, 2.5f, snowCm)
            fill.with(LinearGradient(0f, t.ground - snowH, 0f, t.ground + u, alpha(0xFFF4F6F8.toInt(), 0.6f + 0.4f * patchy), alpha(0xFFD7DFE8.toInt(), 0.6f + 0.4f * patchy), Shader.TileMode.CLAMP))
            canvas.drawPath(path, fill)
            fill.shader = null
        }
        val rnd = Random(17)
        val tufts = t.spread(74)
        for (i in 0 until tufts) {
            val x = (i + rnd.nextFloat()) / tufts * t.w
            val blades = 3 + rnd.nextInt(3)
            for (b in 0 until blades) {
                val h = rnd.f(1.4f, 4.6f) * u * height
                val lean = rnd.f(-0.5f, 0.5f)
                val bx = x + rnd.f(-0.6f, 0.6f) * u
                val tone = rnd.nextFloat()
                val dies = blade.nextFloat()
                val seeded = blade.nextFloat() < 0.16f * seeds
                val stand = h - snowH
                if (stand <= 0.3f * u) continue
                val by = t.surface(bx) - min(snowH, h) * 0.9f
                val path = Path()
                val tipX = bx + lean * stand * 0.8f + bend * stand / (4f * u)
                val tipY = by - stand
                path.moveTo(bx, by + 0.3f * u)
                path.quadTo(bx + lean * stand * 0.3f, by - stand * 0.6f, tipX, tipY)
                stroke.strokeWidth = max(0.6f, 0.32f * u)
                var grass = if (dies < dead) mix(straw, 0xFF9C8F6A.toInt(), tone) else mix(autumnGrass, shade(autumnGrass, 0.75f), tone)
                grass = mix(grass, 0xFFDCE4EA.toInt(), 0.6f * frosted)
                stroke.color = shade(grass, wetDark)
                canvas.drawPath(path, stroke)
                if (seeded && stand > 2.5f * u) {
                    stroke.strokeWidth = max(0.7f, 0.55f * u)
                    stroke.color = alpha(shade(mix(0xFFB8A57C.toInt(), 0xFFDCE4EA.toInt(), 0.5f * frosted), wetDark), seeds)
                    canvas.drawLine(tipX, tipY, tipX + (tipX - bx) * 0.08f, tipY - 1.1f * u, stroke)
                }
            }
        }
        // Wood anemones (hvitveis) under the birch in late April and May.
        val anemones = (Season.smooth(104.0, 114.0, season.springDay) * (1 - Season.smooth(134.0, 146.0, season.springDay))).toFloat()
        if (anemones > 0.05f && snowH < u) {
            val fr = Random(23)
            repeat(t.spread(13)) {
                val x = fr.nextFloat() * t.w
                if (abs(x - t.cx) < t.baseW) return@repeat
                val y = t.surface(x) - fr.f(1.2f, 2.4f) * u
                stroke.strokeWidth = max(0.5f, 0.2f * u)
                stroke.color = 0xFF6C8048.toInt()
                canvas.drawLine(x, t.surface(x), x + 0.2f * u, y, stroke)
                fill.color = alpha(0xFFF1EFE8.toInt(), anemones)
                canvas.drawCircle(x + 0.2f * u, y, 0.6f * u * anemones, fill)
                fill.color = alpha(0xFFD8C060.toInt(), anemones)
                canvas.drawCircle(x + 0.2f * u, y, 0.18f * u, fill)
            }
        }
        // Brown birch boletes (brunskrubb) at the foot of the tree after late-summer rain.
        val boletes = (Season.smooth(222.0, 236.0, day.toDouble()) * (1 - Season.smooth(286.0, 300.0, day.toDouble())) * Season.smooth(0.22, 0.38, s.soil)).toFloat()
        if (boletes > 0.1f && snowH < u) {
            for ((x, scale) in listOf(t.cx - t.baseW * 1.5f to 1f, t.cx + t.baseW * 1.9f to 0.75f, t.cx + t.baseW * 2.5f to 0.55f)) {
                val k = scale * boletes * u
                val y = t.surface(x)
                fill.color = 0xFFD9D0C1.toInt()
                canvas.drawRoundRect(x - 0.5f * k, y - 2.4f * k, x + 0.5f * k, y + 0.2f * k, 0.4f * k, 0.4f * k, fill)
                fill.color = alpha(0xFF3A322C.toInt(), 0.6f)
                for (j in 0 until 4) canvas.drawCircle(x + (if (j % 2 == 0) -0.2f else 0.2f) * k, y - (0.4f + 0.45f * j) * k, max(0.4f, 0.1f * k), fill)
                fill.color = 0xFF84603F.toInt()
                canvas.drawOval(x - 1.7f * k, y - 3.4f * k, x + 1.7f * k, y - 1.9f * k, fill)
                fill.color = alpha(0xFFFFFFFF.toInt(), 0.15f)
                canvas.drawOval(x - 1.1f * k, y - 3.3f * k, x + 0.3f * k, y - 2.8f * k, fill)
            }
        }
        // Puddles that hold the sky for a few hours after rain; frozen over, they hold nothing.
        val ice = sm(0.2f, 0.55f, max(s.surface.toFloat(), 0.6f * sm(0.45f, 0.8f, s.soil.toFloat()))) * (if (s.temp < -0.5 && snowH <= u) 1f else 0f)
        if (ice > 0.02f) {
            // Frozen over: grey-white ice with a crack, holding no sky.
            for ((x, w) in listOf(t.cx - t.tw * 0.28f to 0.13f, t.cx + t.tw * 0.3f to 0.1f)) {
                val pw = t.tw * w * 0.8f
                val y = t.surface(x) + 0.2f * u
                fill.color = alpha(0xFFC7D0D6.toInt(), 0.8f * ice)
                canvas.drawOval(x - pw, y - 0.5f * u, x + pw, y + 0.5f * u, fill)
                stroke.strokeWidth = max(0.5f, 0.12f * u)
                stroke.color = alpha(0xFF7D8890.toInt(), 0.6f * ice)
                canvas.drawLine(x - pw * 0.4f, y + 0.1f * u, x + pw * 0.1f, y - 0.25f * u, stroke)
                canvas.drawLine(x + pw * 0.1f, y - 0.25f * u, x + pw * 0.5f, y + 0.15f * u, stroke)
            }
        }
        val puddle = sm(0.2f, 0.55f, s.surface.toFloat()) * (if (s.temp < -0.5 || snowH > u) 0f else 1f)
        if (puddle > 0.02f) {
            for ((x, w) in listOf(t.cx - t.tw * 0.28f to 0.13f, t.cx + t.tw * 0.3f to 0.1f)) {
                val pw = t.tw * w * (0.6f + 0.4f * s.surface.toFloat())
                val y = t.surface(x) + 0.2f * u
                fill.color = alpha(shade(mix(light.skyHorizon, light.skyTop, 0.3f), 0.85f), 0.85f * puddle)
                canvas.drawOval(x - pw, y - 0.55f * u, x + pw, y + 0.55f * u, fill)
                stroke.strokeWidth = max(0.5f, 0.18f * u)
                stroke.color = alpha(0xFFFFFFFF.toInt(), 0.35f * puddle)
                canvas.drawLine(x - pw * 0.5f, y - 0.2f * u, x + pw * 0.3f, y - 0.2f * u, stroke)
            }
        }
    }

    private fun fallingLeaves(canvas: Canvas, t: Tree, s: TreeScene, frame: Int) {
        val n = s.fallingLeaves
        if (n == 0) return
        val u = t.u
        // A few of the maple's too, while it has leaves to let go of.
        val maple = s.season.maple
        val m = t.maple
        if (m != null && maple.turn > 0.5 && maple.leaves in 0.02..0.97) {
            val mr = Random(s.variation * 37)
            repeat((n + 1) / 2) {
                val x = m.x + mr.f(-0.8f, 0.8f) * m.rx + frame * s.windSide.toFloat() * (1.5f + s.wind.toFloat() * 0.3f) * u
                val y = m.crownY + mr.f(0f, 1f) * (t.ground - m.crownY) * 0.85f + frame * 4f * u
                fill.color = mapleColor(maple, MapleLeaf(0f, 0f, 0f, 1f, 0f, mr.nextFloat(), mr.nextFloat(), mr.nextFloat()))
                canvas.save()
                canvas.rotate(mr.f(0f, 360f) + frame * 40f, x, y)
                canvas.drawOval(x - 1.1f * u, y - 0.7f * u, x + 1.1f * u, y + 0.7f * u, fill)
                canvas.restore()
            }
        }
        val rnd = Random(s.variation * 31)
        val drop = frame * 5f * u
        val drift = frame * s.windSide.toFloat() * (1.5f + s.wind.toFloat() * 0.3f) * u
        repeat(n) {
            val x = t.cx + rnd.f(-0.42f, 0.42f) * t.tw + drift
            val y = t.top + t.height * rnd.f(0.35f, 0.9f) + drop
            val angle = rnd.f(0f, 360f) + frame * 40f
            val early = rnd.nextFloat()
            fill.color = leafColor(s.season, Leaf(0, 0, 0f, 1f, 0f, early, rnd.nextFloat()))
            canvas.save()
            canvas.rotate(angle, x, y)
            canvas.drawOval(x - 1.2f * u, y - 0.55f * u, x + 1.2f * u, y + 0.55f * u, fill)
            canvas.restore()
        }
    }

    // ---------------------------------------------------------------- weather and finish

    private fun weather(canvas: Canvas, t: Tree, s: TreeScene, light: Light, frame: Int) {
        val u = t.u
        val rnd = Random(s.variation * 13 + frame)
        // By night the rain and snow catch the town's light under the cloud, so they still show.
        val bright = 0.35f + 0.65f * (1 - light.night) + 0.3f * light.skyglow
        mist(canvas, t, s, light, frame)
        if (s.fog > 0) {
            val fog = mix(light.skyHorizon, 0xFFC9CCCB.toInt(), 0.3f * light.day)
            fill.with(LinearGradient(0f, t.sky, 0f, t.ground + 2 * u, alpha(fog, 0.12f * s.fog.toFloat()), alpha(fog, 0.62f * s.fog.toFloat()), Shader.TileMode.CLAMP))
            canvas.drawRect(0f, 0f, t.w, t.ground + 2 * u, fill)
            fill.shader = null
        }
        val rain = (1 - exp(-s.rain / 1.2)).toFloat()
        if (s.rain > 0.05) {
            val slant = s.windSide.toFloat() * min(0.45f, s.wind.toFloat() * 0.035f)
            // Heavier rain hangs in soft grey curtains under the cloud, a few across the picture.
            val heavy = sm(0.8f, 4f, s.rain.toFloat())
            if (heavy > 0.02f) {
                val veil = shade(mix(light.skyHorizon, 0xFFB9C0C6.toInt(), 0.35f), bright)
                val curtains = Random(s.variation * 7 + 1)
                repeat(t.spread(3)) {
                    val cx = curtains.f(0.1f, 0.9f) * t.w + (frame * 2 - 1) * 1.5f * u
                    val half = curtains.f(10f, 22f) * u
                    val top = t.sky + curtains.f(0f, 0.2f) * t.skyHeight
                    val a = heavy * curtains.f(0.1f, 0.18f)
                    fill.with(LinearGradient(cx - half, 0f, cx + half, 0f, intArrayOf(alpha(veil, 0f), alpha(veil, a), alpha(veil, 0f)), null, Shader.TileMode.CLAMP))
                    // Slanted with the rain, about the curtain's middle.
                    val mid = (top + t.ground) / 2
                    canvas.save()
                    canvas.translate(0f, mid)
                    canvas.skew(-sin(slant), 0f)
                    canvas.translate(0f, -mid)
                    canvas.drawRect(cx - half, top, cx + half, t.ground, fill)
                    canvas.restore()
                }
                fill.shader = null
            }
            stroke.strokeWidth = max(0.6f, 0.2f * u)
            repeat(t.spread((18 + 150 * rain).toInt())) {
                val x = rnd.nextFloat() * (t.w + 10 * u) - 5 * u
                val y = rnd.nextFloat() * t.ground
                val len = rnd.f(2.5f, 6.5f) * u * (0.7f + 0.5f * rain)
                stroke.color = alpha(shade(0xFFDDE3E8.toInt(), bright), rnd.f(0.22f, 0.45f))
                canvas.drawLine(x, y, x - sin(slant) * len, y + cos(slant) * len, stroke)
            }
            // Splashes on the ground.
            stroke.strokeWidth = max(0.5f, 0.15f * u)
            repeat(t.spread((5 + 28 * rain).toInt())) {
                val x = rnd.nextFloat() * t.w
                val y = t.surface(x) - 0.3f * u
                stroke.color = alpha(shade(0xFFE4E9EC.toInt(), bright), 0.4f)
                canvas.drawLine(x, y, x - 0.6f * u, y - 0.8f * u, stroke)
                canvas.drawLine(x, y, x + 0.6f * u, y - 0.8f * u, stroke)
            }
        }
        if (s.snowfall > 0.05) {
            val falling = (1 - exp(-s.snowfall / 1.0)).toFloat()
            val drift = s.windSide.toFloat() * frame * (0.5f + s.wind.toFloat() * 0.25f) * u
            repeat(t.spread((14 + 110 * falling).toInt())) {
                val x = rnd.nextFloat() * t.w + drift
                val y = rnd.nextFloat() * t.ground + frame * 2.5f * u
                val r = rnd.f(0.25f, 0.75f) * u
                fill.color = alpha(shade(0xFFF7F8FA.toInt(), bright), rnd.f(0.55f, 0.95f))
                canvas.drawCircle(x, y, max(0.6f, r), fill)
            }
        }
        // Shafts of low sun slipping through the gaps in the crown.
        if (light.golden > 0.05f && s.cloud < 0.6) {
            val sx = t.cx + s.sunSide.toFloat() * t.w * 0.36f
            val sy = t.skyline - (4f + 2.2f * s.sunElevation.toFloat()) * u
            val shafts = Random(41)
            fill.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            repeat(5) {
                val a = (PI.toFloat() + s.sunSide.toFloat() * shafts.f(0.25f, 1.1f) * -1f) + (if (s.sunSide >= 0) PI.toFloat() else 0f)
                val len = t.w * 1.6f
                val spread = shafts.f(0.015f, 0.04f)
                val ex = sx + cos(a) * len
                val ey = sy - abs(sin(a)) * len * 0.5f
                val beam = Path()
                beam.moveTo(sx, sy)
                beam.lineTo(ex + spread * len, ey - spread * len)
                beam.lineTo(ex - spread * len, ey + spread * len)
                beam.close()
                val strength = 0.1f * light.golden * (1 - s.cloud.toFloat() / 0.6f) * (1 + 0.15f * (frame * 2 - 1))
                fill.with(LinearGradient(sx, sy, ex, ey, alpha(0xFFF6C886.toInt(), strength), alpha(0xFFF6C886.toInt(), 0f), Shader.TileMode.CLAMP))
                canvas.drawPath(beam, fill)
            }
            fill.shader = null
            fill.xfermode = null
        }
        // Low sun spills a warm haze over everything above ground.
        if (light.golden > 0.05f) {
            val sx = t.cx + s.sunSide.toFloat() * t.w * 0.5f
            fill.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            fill.with(RadialGradient(sx, t.ground - t.height * 0.15f, t.h * 0.6f, intArrayOf(alpha(0xFFB5895A.toInt(), 0.25f * light.golden), alpha(0xFFB5895A.toInt(), 0f)), null, Shader.TileMode.CLAMP))
            canvas.drawRect(0f, 0f, t.w, t.ground, fill)
            fill.shader = null
            fill.xfermode = null
        }
    }

    /**
     * Low mist on still, damp nights and the morning after: it forms after rain, more readily under a
     * clear sky, lies in a band just above the grass round the foot of the tree, and keeps the
     * foreground apart from the dark behind it. A wind clears it.
     */
    private fun mist(canvas: Canvas, t: Tree, s: TreeScene, light: Light, frame: Int) {
        val hour = max(light.night, if (s.morning) 1 - sm(1f, 8f, light.e) else 0f)
        val calm = 1 - sm(2.5f, 7f, s.wind.toFloat())
        val damp = max(sm(0.35f, 0.75f, s.soil.toFloat()) * sm(-3f, 1f, s.temp.toFloat()), 0.7f * light.rain)
        val m = hour * calm * damp * (0.6f + 0.4f * (1 - light.cloud)) * (1 - s.fog.toFloat())
        if (m < 0.03f) return
        val u = t.u
        val tone = mix(light.skyHorizon, light.lit(0xFFD3D8DD.toInt()), 0.55f)
        val top = t.ground - t.height * 0.13f
        fill.with(LinearGradient(0f, top, 0f, t.ground + 2 * u, intArrayOf(alpha(tone, 0f), alpha(tone, 0.42f * m), alpha(tone, 0.28f * m)),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP))
        canvas.drawRect(0f, top, t.w, t.ground + 2 * u, fill)
        // A few slow wisps, thickest beside the trunk.
        val rnd = Random(71)
        repeat(t.spread(4)) {
            val x = t.cx + rnd.f(-0.5f, 0.5f) * t.w + (frame * 2 - 1) * rnd.f(0.8f, 2f) * u
            val y = t.ground - t.height * rnd.f(0.015f, 0.08f)
            val rx = rnd.f(25f, 45f) * u
            val ry = rnd.f(1.6f, 3.2f) * u
            fill.with(RadialGradient(x, y, rx, intArrayOf(alpha(tone, 0.22f * m), alpha(tone, 0f)), null, Shader.TileMode.CLAMP))
            canvas.save()
            canvas.scale(1f, ry / rx, x, y)
            canvas.drawCircle(x, y, rx, fill)
            canvas.restore()
        }
        fill.shader = null
    }

    private fun finish(canvas: Canvas, t: Tree, s: TreeScene, light: Light, radius: Float) {
        // Paper grain, as in a printed field guide.
        val noise = grain ?: Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b ->
            val rnd = Random(5)
            val px = IntArray(96 * 96) { if (rnd.nextBoolean()) Color.argb(rnd.nextInt(16), 255, 255, 255) else Color.argb(rnd.nextInt(20), 0, 0, 0) }
            b.setPixels(px, 0, 96, 0, 0, 96, 96)
            grain = b
        }
        fill.with(BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
        canvas.drawRect(0f, 0f, t.w, t.h, fill)
        // A soft vignette keeps the eye inside the picture.
        fill.with(RadialGradient(t.cx, t.h * 0.48f, t.h * 0.62f, intArrayOf(0x00000000, 0x00000000, alpha(0xFF000000.toInt(), 0.2f + 0.1f * light.night)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP))
        canvas.drawRect(0f, 0f, t.w, t.h, fill)
        fill.shader = null
        if (radius > 0) {
            val corners = Path().apply { addRoundRect(RectF(0f, 0f, t.w, t.h), radius, radius, Path.Direction.CW); fillType = Path.FillType.INVERSE_WINDING }
            fill.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            canvas.drawPath(corners, fill)
            fill.xfermode = null
        }
    }
}
