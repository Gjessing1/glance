package io.gjessing.glance

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.ImageView
import java.io.File

/**
 * The Living Tree in twenty-four fixed scenes, painted side by side at the widget's size in dp, shown on
 * screen and saved to `files/tree_gallery.png` (`adb exec-out run-as io.gjessing.glance cat
 * files/tree_gallery.png > gallery.png`). Each frame 1 is saved too, as `tree_gallery_b.png`.
 * [showLarge] paints a dozen of them as the tap-to-enlarge view shows them, to `tree_gallery_large.png`.
 */
object TreeGallery {
    private fun scene(day: Int, sun: Double, morning: Boolean = true, cloud: Double = 0.2, rain: Double = 0.0, snowfall: Double = 0.0,
                      wind: Double = 2.0, fog: Double = 0.0, soil: Double = 0.2, surface: Double = 0.0, snow: Double = 0.0,
                      branchSnow: Double = 0.0, temp: Double = 8.0, moon: Double = 30.0, phase: Double = 0.4, windLoss: Double = 0.0,
                      sights: NightSights = NightSights(), bird: PerchedBird? = null, flight: Flight? = null, frost: Double = 0.0, dry: Double = 0.0) =
        TreeScene(Season.at(day, 59.85).afterWind(windLoss), sun, if (morning) -0.8 else 0.8, morning, moon, 0.4, phase,
            cloud, rain, snowfall, false, wind, -1.0, fog, false, soil, soil, soil * 0.7, surface, snow, branchSnow, temp, 12.0, day.toLong(), sights, bird,
            flight, frost, dry)

    val scenes = listOf(
        "Apr 24 dawn, buds" to scene(114, 1.5, cloud = 0.15, temp = 3.0),
        "May 28 day, new leaves" to scene(148, 35.0, cloud = 0.4, soil = 0.3, temp = 15.0),
        "Jul 12 noon, clear" to scene(193, 52.0, cloud = 0.05, temp = 22.0, bird = PerchedBird.CROW),
        "Jul 20 rain" to scene(201, 30.0, cloud = 1.0, rain = 2.5, wind = 5.0, soil = 0.85, surface = 1.0, temp = 15.0),
        "Aug 30 after rain" to scene(242, 25.0, cloud = 0.5, soil = 0.6, surface = 0.3, temp = 16.0),
        "Sep 24 dusk" to scene(267, 2.0, morning = false, cloud = 0.3, temp = 10.0),
        "Oct 8 gale" to scene(281, 18.0, cloud = 0.6, wind = 12.0, soil = 0.4, temp = 9.0, windLoss = 0.2),
        "Oct 25 overcast" to scene(298, 12.0, cloud = 0.9, soil = 0.45, surface = 0.2, temp = 6.0),
        "Nov 20 drizzle dusk" to scene(324, -3.0, morning = false, cloud = 1.0, rain = 0.6, soil = 0.75, surface = 0.8, temp = 4.0),
        "Dec 18 night, snow" to scene(352, -40.0, cloud = 0.1, snow = 14.0, branchSnow = 0.8, temp = -7.0, moon = 30.0, phase = 0.45),
        "Jan 20 snowfall" to scene(20, 8.0, cloud = 1.0, snowfall = 1.5, snow = 8.0, branchSnow = 0.6, temp = -3.0),
        "Oct 25 night, partly cloudy" to scene(298, -14.0, cloud = 0.55, soil = 0.3, temp = 5.0, moon = 20.0, phase = 0.5),
        "Oct 26 night, clear, moon" to scene(299, -25.0, cloud = 0.05, soil = 0.3, temp = 2.0, moon = 25.0, phase = 0.3),
        "Oct 27 night, overcast" to scene(300, -25.0, cloud = 1.0, soil = 0.4, temp = 6.0, moon = 25.0, phase = 0.35),
        "Oct 30 night, northern lights" to scene(303, -30.0, cloud = 0.15, soil = 0.4, temp = 3.0, moon = -10.0, sights = NightSights(aurora = 0.45)),
        "Nov 11 night, strong aurora" to scene(315, -35.0, cloud = 0.05, soil = 0.4, temp = -2.0, moon = -10.0, sights = NightSights(aurora = 0.8)),
        "Dec 14 night, Geminids" to scene(348, -45.0, cloud = 0.05, snow = 6.0, branchSnow = 0.3, temp = -6.0, moon = -10.0,
            sights = NightSights(meteors = 3, radiantSide = 0.3, radiantElevation = 55.0, shower = "Geminids")),
        "Oct 25 night, rain" to scene(298, -22.0, cloud = 1.0, rain = 1.2, soil = 0.7, surface = 0.6, temp = 7.0, moon = 25.0, phase = 0.35),
        "Oct 28 night, still after rain" to scene(301, -28.0, cloud = 0.35, wind = 1.0, soil = 0.8, surface = 0.3, temp = 6.0, moon = -10.0),
        "Jan 30 night, hard frost" to scene(30, -40.0, cloud = 0.0, snow = 10.0, branchSnow = 0.2, temp = -14.0, moon = -10.0),
        "Feb 26 fog, thaw" to scene(57, 10.0, cloud = 1.0, fog = 1.0, snow = 2.0, soil = 0.7, surface = 0.4, temp = 1.0),
        "Aug 14 storm" to scene(226, 20.0, cloud = 0.95, rain = 2.5, wind = 22.0, soil = 0.85, surface = 1.0, temp = 14.0),
        "Nov 3 storm" to scene(307, 6.0, cloud = 0.9, rain = 1.0, wind = 18.0, soil = 0.8, surface = 0.8, temp = 7.0, windLoss = 0.3),
        "Jan 12 storm, bare" to scene(12, 4.0, cloud = 0.95, snowfall = 0.8, wind = 24.0, snow = 4.0, branchSnow = 0.1, temp = -2.0),
        "Oct 25 low sun, sunset" to scene(298, 1.5, morning = false, cloud = 0.1, soil = 0.3, temp = 7.0),
        "Oct 25 after sunset" to scene(298, -3.5, morning = false, cloud = 0.05, soil = 0.3, temp = 5.0, moon = -10.0),
        "Jan 16 low winter sun" to scene(16, 5.0, morning = false, cloud = 0.05, snow = 10.0, temp = -5.0),
        "Jun 10 blackbird" to scene(161, 40.0, cloud = 0.3, temp = 17.0, bird = PerchedBird.BLACKBIRD),
        "Feb 5 great tit" to scene(36, 12.0, cloud = 0.3, snow = 6.0, temp = -3.0, bird = PerchedBird.GREAT_TIT),
        "Oct 2 geese" to scene(275, 20.0, cloud = 0.3, soil = 0.3, temp = 10.0, flight = Flight.GEESE),
        "Nov 18 crows to roost" to scene(322, -1.5, morning = false, cloud = 0.3, soil = 0.5, temp = 2.0, moon = -10.0, flight = Flight.CORVIDS),
        "Nov 2 frosty morning" to scene(306, 4.0, cloud = 0.05, soil = 0.4, surface = 0.4, temp = -3.0, frost = 1.0),
        "Jul 28 dry spell" to scene(209, 45.0, cloud = 0.05, temp = 26.0, dry = 1.0),
        "May 26 pine candles" to scene(146, 45.0, cloud = 0.2, soil = 0.3, temp = 16.0),
        "Aug 12 heather, seed heads" to scene(224, 40.0, cloud = 0.25, temp = 19.0),
        "Mar 25 thaw, patchy snow" to scene(84, 25.0, cloud = 0.3, snow = 1.2, soil = 0.6, surface = 0.4, temp = 5.0),
    )

    /** Seasons, weathers and nights for the large view, where the landscape around the birch shows. */
    private val large = listOf(1, 2, 7, 9, 10, 20, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35)

    /** Individual bird examples for reviewing both the full landscape and the narrow widget. */
    fun exportBirds(activity: Activity) {
        val density = activity.resources.displayMetrics.density
        val largeScale = minOf(1.0, Math.sqrt(1_100_000.0 / (380 * density * 779 * density))).toFloat()
        val largeW = (380 * density * largeScale).toInt()
        val largeH = (779 * density * largeScale).toInt()
        val narrowW = (59 * density).toInt()
        val narrowH = (494 * density).toInt()
        val examples = listOf(
            "crow-summer" to scene(193, 52.0, cloud = 0.05, temp = 22.0, bird = PerchedBird.CROW),
            "dove-autumn" to scene(298, 25.0, cloud = 0.25, soil = 0.35, temp = 10.0, bird = PerchedBird.WOOD_PIGEON),
            "blackbird-summer" to scene(161, 40.0, cloud = 0.3, temp = 17.0, bird = PerchedBird.BLACKBIRD),
            "tit-winter" to scene(36, 12.0, cloud = 0.3, snow = 6.0, temp = -3.0, bird = PerchedBird.GREAT_TIT),
            "geese-autumn" to scene(275, 20.0, cloud = 0.3, soil = 0.3, temp = 10.0, flight = Flight.GEESE),
        )
        val output = File(activity.filesDir, "tree_birds").apply { mkdirs() }
        val now = System.currentTimeMillis()
        for ((name, example) in examples) {
            for ((view, w, h, treeWidth, radius) in listOf(
                BirdSize("expanded", largeW, largeH, TreeActivity.treeFrame(largeW, largeH, 59f / 494f), 28 * density * largeScale),
                BirdSize("widget", narrowW, narrowH, narrowW, 18 * density),
            )) {
                val picture = TreePainter.paint(example, w, h, 0, radius, now, treeWidth)
                File(output, "$name-$view.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                picture.recycle()
            }
        }
        android.util.Log.i("TreeGallery", "bird examples: ${examples.size * 2} images in ${output.absolutePath}")
        activity.setContentView(ImageView(activity).apply {
            setImageBitmap(TreePainter.paint(examples.first().second, largeW, largeH, 0, 28 * density * largeScale, now,
                TreeActivity.treeFrame(largeW, largeH, 59f / 494f)))
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
    }

    private data class BirdSize(val view: String, val width: Int, val height: Int, val treeWidth: Int, val radius: Float)

    /**
     * The large view on the owner's phone: a card ~380dp wide and ~779dp tall (the S24 Ultra's 891dp
     * less the bars), the tree framed as [TreeActivity.treeFrame] does for the 59×494dp widget, painted
     * at most 1.1 Mpx as the activity does. Four across.
     */
    fun showLarge(activity: Activity, widthDp: Int, heightDp: Int, treeAspect: Float) {
        val density = activity.resources.displayMetrics.density
        val k = minOf(1.0, Math.sqrt(1_100_000.0 / (widthDp * density * heightDp * density))).toFloat()
        val w = (widthDp * density * k).toInt()
        val h = (heightDp * density * k).toInt()
        val tree = TreeActivity.treeFrame(w, h, treeAspect)
        val gap = (6 * density).toInt()
        val columns = 4
        val rows = (large.size + columns - 1) / columns
        val sheet = Bitmap.createBitmap(columns * (w + gap) + gap, rows * (h + gap) + gap, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(40, 42, 46))
        val now = System.currentTimeMillis()
        val started = System.nanoTime()
        for ((i, index) in large.withIndex()) {
            val picture = TreePainter.paint(scenes[index].second, w, h, 0, 28 * density * k, now, tree)
            canvas.drawBitmap(picture, (gap + i % columns * (w + gap)).toFloat(), (gap + i / columns * (h + gap)).toFloat(), null)
        }
        android.util.Log.i("TreeGallery", "large: ${large.size} scenes in ${(System.nanoTime() - started) / 1_000_000} ms at ${w}x$h, tree $tree")
        File(activity.filesDir, "tree_gallery_large.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        activity.setContentView(ImageView(activity).apply { setImageBitmap(sheet); scaleType = ImageView.ScaleType.FIT_CENTER })
    }

    fun show(activity: Activity, widthDp: Int, heightDp: Int) {
        val density = activity.resources.displayMetrics.density
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()
        val gap = (6 * density).toInt()
        val now = System.currentTimeMillis()
        for ((suffix, frame) in listOf("" to 0, "_b" to 1)) {
            val sheet = Bitmap.createBitmap(scenes.size * (w + gap) + gap, h + 2 * gap, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(40, 42, 46))
            val started = System.nanoTime()
            for ((i, entry) in scenes.withIndex()) {
                val picture = TreePainter.paint(entry.second, w, h, frame, 18 * density, now)
                canvas.drawBitmap(picture, (gap + i * (w + gap)).toFloat(), gap.toFloat(), null)
            }
            android.util.Log.i("TreeGallery", "frame $frame: ${scenes.size} scenes in ${(System.nanoTime() - started) / 1_000_000} ms at ${w}x$h")
            File(activity.filesDir, "tree_gallery$suffix.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (frame == 0) activity.setContentView(ImageView(activity).apply { setImageBitmap(sheet); scaleType = ImageView.ScaleType.FIT_CENTER })
        }
    }
}
