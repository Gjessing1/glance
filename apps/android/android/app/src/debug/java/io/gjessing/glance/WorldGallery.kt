package io.gjessing.glance

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.ImageView
import java.io.File

/**
 * Tiny Living World in twenty fixed scenes, painted in a grid at the widget's size in dp, shown on
 * screen and saved to `files/world_gallery.png` (`adb exec-out run-as io.gjessing.glance cat
 * files/world_gallery.png > gallery.png`); frame 1 goes to `world_gallery_b.png`. `--ei scale N`
 * paints them N times larger to look at the detail.
 */
object WorldGallery {
    private fun scene(day: Int, sun: Double, morning: Boolean = true, cloud: Double = 0.2, rain: Double = 0.0, snowfall: Double = 0.0,
                      wind: Double = 2.0, fog: Double = 0.0, puddle: Double = 0.0, snow: Double = 0.0, treeSnow: Double = 0.0,
                      temp: Double = 8.0, moon: Double = 30.0, phase: Double = 0.4, fungi: Double = 0.0, bloom: Double = -1.0,
                      ice: Boolean = false, tracks: Tracks? = null, visitor: Visitor? = null, flock: Boolean = false, fireflies: Boolean = false,
                      sights: NightSights = NightSights()) =
        WorldScene(Season.at(day, 59.83), sun, if (morning) -0.7 else 0.7, morning, moon, 0.45, phase, cloud, rain, snowfall, wind, -1.0, fog, false,
            puddle, temp < -0.5, ice, snow, treeSnow, fungi, if (bloom < 0) WorldMemory.flowerSeason(day) else bloom, tracks, 4.0, temp, day.toLong() * 3,
            visitor, flock, fireflies, sights)

    val scenes = listOf(
        "Apr 28 morning, buds, birds" to scene(118, 6.0, cloud = 0.2, temp = 6.0, flock = true),
        "May 20 day, butterfly" to scene(140, 40.0, cloud = 0.35, temp = 17.0, visitor = Visitor.BUTTERFLY),
        "Jul 10 noon, clear" to scene(191, 52.0, cloud = 0.05, temp = 24.0, visitor = Visitor.BIRD),
        "Jul 18 rain" to scene(199, 30.0, cloud = 1.0, rain = 2.0, wind = 5.0, puddle = 1.0, temp = 15.0),
        "Jul 22 night, fireflies" to scene(203, -12.0, cloud = 0.1, temp = 16.0, moon = 20.0, phase = 0.3, fireflies = true),
        "Aug 16 after rain, squirrel" to scene(228, 28.0, cloud = 0.45, puddle = 0.6, fungi = 0.8, temp = 17.0, visitor = Visitor.SQUIRREL),
        "Sep 20 sunset, fox" to scene(263, 1.0, morning = false, cloud = 0.25, temp = 11.0, visitor = Visitor.FOX),
        "Oct 6 gale" to scene(279, 16.0, cloud = 0.6, wind = 13.0, temp = 9.0),
        "Oct 25 overcast" to scene(298, 12.0, cloud = 0.9, puddle = 0.4, fungi = 0.6, temp = 7.0),
        "Oct 25 night, owl" to scene(298, -20.0, cloud = 0.15, temp = 3.0, moon = 30.0, phase = 0.5, visitor = Visitor.OWL),
        "Nov 15 dusk drizzle" to scene(319, -3.0, morning = false, cloud = 1.0, rain = 0.6, puddle = 0.8, temp = 4.0),
        "Dec 10 night, shooting star" to scene(344, -40.0, cloud = 0.05, snow = 6.0, treeSnow = 0.5, temp = -6.0, moon = 25.0, phase = 0.2, visitor = Visitor.SHOOTING_STAR),
        "Jan 14 snowfall" to scene(14, 6.0, cloud = 1.0, snowfall = 1.5, snow = 12.0, treeSnow = 0.8, temp = -4.0, ice = true),
        "Feb 8 clear, hare, tracks" to scene(39, 14.0, cloud = 0.1, snow = 25.0, treeSnow = 0.3, temp = -9.0, ice = true, tracks = Tracks.HARE, visitor = Visitor.RABBIT),
        "Mar 20 fog, thaw" to scene(79, 12.0, cloud = 1.0, fog = 1.0, snow = 2.0, puddle = 0.7, temp = 2.0, ice = true),
        "Oct 30 night, northern lights" to scene(303, -30.0, cloud = 0.15, temp = 3.0, moon = -10.0, sights = NightSights(aurora = 0.45)),
        "Nov 11 night, strong aurora" to scene(315, -35.0, cloud = 0.05, temp = -2.0, moon = -10.0, sights = NightSights(aurora = 0.8)),
        "Dec 14 night, Geminids" to scene(348, -45.0, cloud = 0.05, snow = 6.0, treeSnow = 0.3, temp = -6.0, moon = -10.0,
            sights = NightSights(meteors = 3, radiantSide = 0.3, radiantElevation = 55.0, shower = "Geminids")),
        "Jan 4 night, Quadrantids, aurora" to scene(4, -40.0, cloud = 0.1, snow = 15.0, treeSnow = 0.2, temp = -10.0, ice = true, moon = -10.0,
            sights = NightSights(aurora = 0.3, meteors = 2, radiantSide = -0.6, radiantElevation = 40.0, shower = "Quadrantids")),
        "Jun 21 midnight sun dusk" to scene(172, -4.0, morning = false, cloud = 0.3, temp = 14.0, fireflies = false),
    )

    fun show(activity: Activity, widthDp: Int, heightDp: Int, scale: Int) {
        val density = activity.resources.displayMetrics.density * scale
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()
        val gap = (6 * density / scale).toInt()
        val columns = 4
        val rows = (scenes.size + columns - 1) / columns
        for ((suffix, frame) in listOf("" to 0, "_b" to 1)) {
            val sheet = Bitmap.createBitmap(columns * (w + gap) + gap, rows * (h + gap) + gap, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(40, 42, 46))
            val started = System.nanoTime()
            for ((i, entry) in scenes.withIndex()) {
                val picture = WorldPainter.paint(entry.second, w, h, frame, 18 * density)
                canvas.drawBitmap(picture, (gap + i % columns * (w + gap)).toFloat(), (gap + i / columns * (h + gap)).toFloat(), null)
            }
            android.util.Log.i("WorldGallery", "frame $frame: ${scenes.size} scenes in ${(System.nanoTime() - started) / 1_000_000} ms at ${w}x$h")
            File(activity.filesDir, "world_gallery$suffix.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (frame == 0) activity.setContentView(ImageView(activity).apply { setImageBitmap(sheet); scaleType = ImageView.ScaleType.FIT_CENTER })
        }
    }
}
