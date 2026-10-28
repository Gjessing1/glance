package io.gjessing.glance

import android.app.Activity
import android.graphics.Bitmap
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every kind of picture the Living Tree can show, as single PNGs at the owner's phone's pixel count
 * (3.5 px/dp): the 59×494dp widget ("collapsed") and the tap-to-enlarge view ("expanded", ~380×779dp
 * capped at 1.1 Mpx as [TreeActivity] paints it). Animated scenes save both cross-fading frames,
 * since meteors only show in the second. Written to `files/tree_catalog/` with a `manifest.json`;
 * pull with `adb exec-out run-as io.gjessing.glance tar c files/tree_catalog > catalog.tar`.
 *
 *   adb shell am start -n io.gjessing.glance/.WidgetPreviewActivity --ez catalog true
 */
object TreeCatalog {
    private const val PHONE_DENSITY = 3.5f

    private class Entry(val group: String, val label: String, val scene: TreeScene, val widthDp: Int = 59, val heightDp: Int = 494, val expanded: Boolean = true)

    private fun scene(
        day: Int, sun: Double, morning: Boolean = true, sunSide: Double = if (morning) -0.8 else 0.8,
        moon: Double = -10.0, moonSide: Double = 0.4, phase: Double = 0.4,
        cloud: Double = 0.2, rain: Double = 0.0, snowfall: Double = 0.0, sleet: Boolean = false,
        wind: Double = 2.0, windSide: Double = -1.0, fog: Double = 0.0, thunder: Boolean = false,
        soil: Double = 0.2, surface: Double = 0.0, snow: Double = 0.0, branchSnow: Double = 0.0,
        temp: Double = 8.0, variation: Long = day.toLong() * 3 + 1, windLoss: Double = 0.0, sights: NightSights = NightSights(),
    ) = TreeScene(Season.at(day, 59.85).afterWind(windLoss), sun, sunSide, morning, moon, moonSide, phase,
        cloud, rain, snowfall, sleet, wind, windSide, fog, thunder, soil, soil, soil * 0.7, surface, snow, branchSnow, temp, 12.0, variation, sights)

    private val entries: List<Entry> = buildList {
        fun add(group: String, label: String, s: TreeScene) = add(Entry(group, label, s))

        var g = "1 Through the year (midday)"
        add(g, "Jan 15 · snow cover, low sun", scene(15, 8.0, sunSide = 0.1, cloud = 0.2, snow = 18.0, branchSnow = 0.3, temp = -6.0))
        add(g, "Feb 20 · bright snow day", scene(51, 17.0, sunSide = 0.1, cloud = 0.05, snow = 25.0, branchSnow = 0.1, temp = -4.0))
        add(g, "Mar 20 · thaw, patchy snow", scene(79, 29.0, sunSide = 0.1, cloud = 0.4, snow = 2.0, soil = 0.6, surface = 0.4, temp = 4.0))
        add(g, "Apr 10 · bare, spring", scene(100, 38.0, sunSide = 0.1, cloud = 0.3, soil = 0.45, temp = 7.0))
        add(g, "Apr 26 · buds and catkins", scene(116, 43.0, sunSide = 0.1, cloud = 0.25, soil = 0.4, temp = 10.0))
        add(g, "May 8 · leaf burst", scene(128, 46.0, sunSide = 0.1, cloud = 0.3, soil = 0.35, temp = 13.0))
        add(g, "May 22 · fresh new leaves", scene(142, 50.0, sunSide = 0.1, cloud = 0.25, soil = 0.3, temp = 16.0))
        add(g, "Jun 21 · midsummer", scene(172, 53.0, sunSide = 0.1, cloud = 0.15, temp = 21.0))
        add(g, "Jul 15 · full summer", scene(196, 51.0, sunSide = 0.1, cloud = 0.1, temp = 23.0))
        add(g, "Aug 15 · late summer", scene(227, 44.0, sunSide = 0.1, cloud = 0.3, temp = 19.0))
        add(g, "Sep 5 · first yellow", scene(248, 36.0, sunSide = 0.1, cloud = 0.3, temp = 15.0))
        add(g, "Sep 20 · turning", scene(263, 30.0, sunSide = 0.1, cloud = 0.3, soil = 0.35, temp = 12.0))
        add(g, "Oct 5 · golden", scene(278, 24.0, sunSide = 0.1, cloud = 0.25, soil = 0.35, temp = 10.0))
        add(g, "Oct 20 · peak gold", scene(293, 18.0, sunSide = 0.1, cloud = 0.2, soil = 0.35, temp = 8.0))
        add(g, "Nov 1 · leaves falling", scene(305, 14.0, sunSide = 0.1, cloud = 0.4, soil = 0.45, temp = 6.0))
        add(g, "Nov 12 · thinning", scene(316, 11.0, sunSide = 0.1, cloud = 0.5, soil = 0.5, temp = 4.0))
        add(g, "Nov 25 · bare, leaf litter", scene(329, 8.0, sunSide = 0.1, cloud = 0.5, soil = 0.5, temp = 2.0))
        add(g, "Dec 10 · bare December", scene(344, 7.0, sunSide = 0.1, cloud = 0.4, temp = 0.0))

        g = "2 Times of day"
        add(g, "Jun 21 · 02:30 white night", scene(172, -6.5, sunSide = -0.2, cloud = 0.1, temp = 12.0))
        add(g, "Jun 21 · 04:00 sunrise", scene(172, 1.5, cloud = 0.15, temp = 12.0))
        add(g, "Jun 21 · 08:00 morning", scene(172, 25.0, cloud = 0.15, temp = 16.0))
        add(g, "Jun 21 · 13:30 noon", scene(172, 53.0, sunSide = 0.05, cloud = 0.15, temp = 21.0))
        add(g, "Jun 21 · 19:00 evening", scene(172, 20.0, morning = false, cloud = 0.15, temp = 19.0))
        add(g, "Jun 21 · 22:30 sunset", scene(172, 1.5, morning = false, cloud = 0.15, temp = 15.0))
        add(g, "Oct 20 · 07:00 blue hour", scene(293, -5.0, cloud = 0.2, temp = 3.0))
        add(g, "Oct 20 · 08:00 sunrise", scene(293, 1.0, cloud = 0.2, temp = 3.0))
        add(g, "Oct 20 · 10:00 morning", scene(293, 10.0, cloud = 0.2, temp = 6.0))
        add(g, "Oct 20 · 13:00 noon", scene(293, 19.0, sunSide = 0.05, cloud = 0.2, temp = 9.0))
        add(g, "Oct 20 · 16:30 golden hour", scene(293, 4.0, morning = false, cloud = 0.2, temp = 8.0))
        add(g, "Oct 20 · 18:00 dusk", scene(293, -3.0, morning = false, cloud = 0.2, temp = 6.0))
        add(g, "Oct 20 · 19:00 late twilight", scene(293, -9.0, morning = false, cloud = 0.2, temp = 5.0))
        add(g, "Oct 20 · 23:00 night", scene(293, -32.0, cloud = 0.2, temp = 4.0))
        add(g, "Jan 10 · 12:30 low winter sun", scene(10, 7.0, sunSide = 0.05, cloud = 0.15, snow = 12.0, temp = -5.0))
        add(g, "Jan 10 · 15:00 winter sunset", scene(10, 0.5, morning = false, cloud = 0.15, snow = 12.0, temp = -7.0))
        add(g, "Apr 26 · 06:00 spring dawn", scene(116, -2.0, cloud = 0.2, soil = 0.4, temp = 3.0))

        g = "3 Summer weather"
        add(g, "Jul 15 · clear", scene(196, 45.0, cloud = 0.0, temp = 24.0))
        add(g, "Jul 15 · fair", scene(196, 45.0, cloud = 0.25, temp = 22.0))
        add(g, "Jul 15 · partly cloudy", scene(196, 45.0, cloud = 0.55, temp = 20.0))
        add(g, "Jul 15 · overcast", scene(196, 45.0, cloud = 0.95, temp = 17.0))
        add(g, "Jul 15 · morning fog", scene(196, 10.0, cloud = 1.0, fog = 1.0, soil = 0.4, temp = 12.0))
        add(g, "Jul 15 · drizzle", scene(196, 40.0, cloud = 1.0, rain = 0.4, soil = 0.5, surface = 0.6, temp = 15.0))
        add(g, "Jul 15 · rain", scene(196, 40.0, cloud = 1.0, rain = 1.8, wind = 4.0, soil = 0.75, surface = 1.0, temp = 14.0))
        add(g, "Jul 15 · downpour", scene(196, 40.0, cloud = 1.0, rain = 6.0, wind = 6.0, soil = 0.95, surface = 1.0, temp = 15.0))
        add(g, "Jul 15 · thunderstorm", scene(196, 35.0, cloud = 1.0, rain = 4.0, wind = 9.0, thunder = true, soil = 0.9, surface = 1.0, temp = 18.0))
        add(g, "Jul 16 · sun after rain, puddles", scene(197, 40.0, cloud = 0.35, soil = 0.8, surface = 0.5, temp = 18.0))
        add(g, "Jul 17 · damp soil, worm", scene(198, 40.0, cloud = 0.4, soil = 0.65, surface = 0.1, temp = 16.0, variation = 600))
        add(g, "Aug 5 · dry spell", scene(217, 46.0, cloud = 0.05, soil = 0.02, temp = 27.0))

        g = "4 Autumn weather"
        add(g, "Oct 15 · clear gold", scene(288, 20.0, cloud = 0.0, soil = 0.3, temp = 9.0))
        add(g, "Oct 15 · overcast", scene(288, 18.0, cloud = 0.95, soil = 0.45, temp = 8.0))
        add(g, "Oct 15 · fog", scene(288, 8.0, cloud = 1.0, fog = 1.0, soil = 0.55, surface = 0.3, temp = 5.0))
        add(g, "Oct 15 · drizzle", scene(288, 16.0, cloud = 1.0, rain = 0.5, soil = 0.65, surface = 0.7, temp = 7.0))
        add(g, "Oct 15 · rain", scene(288, 16.0, cloud = 1.0, rain = 2.2, wind = 5.0, soil = 0.85, surface = 1.0, temp = 8.0))
        add(g, "Oct 28 · sleet", scene(301, 12.0, cloud = 1.0, rain = 1.0, snowfall = 0.6, sleet = true, wind = 5.0, soil = 0.7, surface = 0.7, temp = 1.0))
        add(g, "Oct 30 · first snow on gold leaves", scene(303, 10.0, cloud = 0.6, snow = 3.0, branchSnow = 0.5, soil = 0.5, temp = -1.0))
        add(g, "Nov 5 · after the rain", scene(309, 10.0, cloud = 0.45, soil = 0.9, surface = 0.6, temp = 6.0))

        g = "5 Wind"
        add(g, "Jul 15 · calm", scene(196, 45.0, cloud = 0.3, wind = 0.0, temp = 20.0))
        add(g, "Jul 15 · breeze 5 m/s", scene(196, 45.0, cloud = 0.3, wind = 5.0, temp = 20.0))
        add(g, "Jul 15 · fresh 8 m/s", scene(196, 45.0, cloud = 0.4, wind = 8.0, temp = 19.0))
        add(g, "Jul 15 · strong 11 m/s", scene(196, 45.0, cloud = 0.5, wind = 11.0, temp = 18.0))
        add(g, "Jul 15 · gale 15 m/s", scene(196, 40.0, cloud = 0.7, wind = 15.0, temp = 17.0))
        add(g, "Jul 15 · storm 22 m/s, rain", scene(196, 35.0, cloud = 1.0, rain = 2.5, wind = 22.0, soil = 0.85, surface = 1.0, temp = 15.0))
        add(g, "Jul 15 · storm, wind from the left", scene(196, 35.0, cloud = 1.0, rain = 2.5, wind = 22.0, windSide = 1.0, soil = 0.85, surface = 1.0, temp = 15.0))
        add(g, "Oct 10 · autumn gale, leaves stripped", scene(283, 18.0, cloud = 0.7, wind = 14.0, soil = 0.45, temp = 9.0, windLoss = 0.25))
        add(g, "Nov 3 · autumn storm", scene(307, 6.0, cloud = 0.9, rain = 1.0, wind = 19.0, soil = 0.8, surface = 0.8, temp = 7.0, windLoss = 0.35))
        add(g, "Jan 12 · winter storm, blowing snow", scene(12, 4.0, cloud = 0.95, snowfall = 1.0, wind = 24.0, snow = 6.0, branchSnow = 0.1, temp = -3.0))

        g = "6 Winter"
        add(g, "Dec 2 · first snowfall", scene(336, 6.0, cloud = 1.0, snowfall = 0.5, snow = 1.0, branchSnow = 0.3, temp = -0.5))
        add(g, "Jan 20 · heavy snowfall", scene(20, 8.0, cloud = 1.0, snowfall = 3.0, snow = 15.0, branchSnow = 0.9, temp = -3.0))
        add(g, "Jan 21 · fresh snow on branches, sun", scene(21, 9.0, cloud = 0.1, snow = 18.0, branchSnow = 1.0, temp = -6.0))
        add(g, "Feb 5 · deep snow 45 cm", scene(36, 12.0, cloud = 0.4, snow = 45.0, branchSnow = 0.2, temp = -5.0))
        add(g, "Feb 8 · hard frost, frozen ground", scene(39, 13.0, cloud = 0.0, snow = 10.0, branchSnow = 0.1, temp = -14.0))
        add(g, "Feb 26 · fog, thaw", scene(57, 10.0, cloud = 1.0, fog = 1.0, snow = 2.0, soil = 0.7, surface = 0.4, temp = 1.0))
        add(g, "Mar 5 · rain on snow", scene(64, 18.0, cloud = 1.0, rain = 1.5, snow = 8.0, soil = 0.75, surface = 0.8, temp = 3.0))
        add(g, "Mar 10 · sleet", scene(69, 20.0, cloud = 1.0, rain = 0.8, snowfall = 0.8, sleet = true, snow = 3.0, soil = 0.6, temp = 0.5))
        add(g, "Dec 20 · snowy dusk", scene(354, -3.0, morning = false, cloud = 0.6, snow = 20.0, branchSnow = 0.5, temp = -8.0))
        add(g, "Dec 21 · snowfall at night", scene(355, -50.0, cloud = 1.0, snowfall = 1.5, snow = 12.0, branchSnow = 0.6, temp = -4.0))

        g = "7 Moon"
        val m = { label: String, phase: Double, moon: Double, side: Double, cloud: Double ->
            add(g, label, scene(296, -30.0, moon = moon, moonSide = side, phase = phase, cloud = cloud, soil = 0.3, temp = 3.0))
        }
        m("Oct 23 · new moon", 0.0, 25.0, 0.3, 0.05)
        m("Oct 26 · waxing crescent", 0.12, 18.0, 0.6, 0.05)
        m("Oct 30 · first quarter", 0.25, 25.0, 0.3, 0.05)
        m("Nov 3 · waxing gibbous", 0.38, 30.0, 0.0, 0.05)
        m("Nov 6 · full moon, high", 0.5, 40.0, 0.0, 0.05)
        m("Nov 6 · full moon rising, low", 0.5, 3.0, -0.8, 0.05)
        m("Nov 10 · waning gibbous", 0.62, 25.0, -0.4, 0.05)
        m("Nov 13 · last quarter", 0.75, 25.0, -0.5, 0.05)
        m("Nov 17 · waning crescent", 0.88, 10.0, -0.7, 0.05)
        m("Nov 6 · full moon, thin cloud", 0.5, 30.0, 0.2, 0.45)
        m("Nov 6 · full moon, behind overcast", 0.5, 30.0, 0.2, 0.92)

        g = "8 Night moods"
        add(g, "Jan 30 · cold clear night −14°", scene(30, -40.0, cloud = 0.0, snow = 10.0, branchSnow = 0.2, temp = -14.0))
        add(g, "Jan 31 · moonlit snow", scene(31, -40.0, moon = 35.0, phase = 0.5, cloud = 0.05, snow = 15.0, branchSnow = 0.4, temp = -10.0))
        add(g, "Oct 25 · partly cloudy night", scene(298, -14.0, moon = 20.0, phase = 0.5, cloud = 0.55, soil = 0.3, temp = 5.0))
        add(g, "Oct 27 · overcast, town skyglow", scene(300, -25.0, cloud = 1.0, soil = 0.4, temp = 6.0))
        add(g, "Oct 28 · mild damp night", scene(301, -28.0, cloud = 0.6, wind = 1.0, soil = 0.75, surface = 0.4, temp = 8.0))
        add(g, "Oct 29 · still night, ground mist", scene(302, -28.0, cloud = 0.3, wind = 0.5, soil = 0.85, surface = 0.3, temp = 6.0))
        add(g, "Nov 8 · foggy night", scene(312, -30.0, cloud = 1.0, fog = 1.0, soil = 0.6, surface = 0.4, temp = 4.0))
        add(g, "Oct 25 · rainy night", scene(298, -22.0, cloud = 1.0, rain = 1.2, soil = 0.7, surface = 0.6, temp = 7.0))
        add(g, "Sep 10 · heavy rain at night", scene(253, -25.0, cloud = 1.0, rain = 5.0, wind = 7.0, soil = 0.9, surface = 1.0, temp = 12.0))
        add(g, "Aug 3 · thunder at night", scene(215, -15.0, cloud = 1.0, rain = 3.5, wind = 10.0, thunder = true, soil = 0.9, surface = 1.0, temp = 17.0))
        add(g, "Jul 20 · summer night", scene(201, -10.0, moon = 12.0, phase = 0.45, cloud = 0.2, temp = 14.0))
        add(g, "Nov 20 · stormy night", scene(324, -35.0, cloud = 1.0, rain = 1.5, wind = 20.0, soil = 0.85, surface = 0.9, temp = 6.0))
        add(g, "Apr 26 · spring night, catkins", scene(116, -20.0, moon = 20.0, phase = 0.3, cloud = 0.1, soil = 0.4, temp = 2.0))

        g = "9 Northern lights and meteors"
        add(g, "Oct 30 · faint aurora low in the north", scene(303, -30.0, cloud = 0.1, soil = 0.4, temp = 3.0, sights = NightSights(aurora = 0.15)))
        add(g, "Oct 30 · northern lights", scene(303, -30.0, cloud = 0.15, soil = 0.4, temp = 3.0, sights = NightSights(aurora = 0.45)))
        add(g, "Nov 11 · strong aurora", scene(315, -35.0, cloud = 0.05, soil = 0.4, temp = -2.0, sights = NightSights(aurora = 0.8)))
        add(g, "Nov 11 · aurora with the moon up", scene(315, -35.0, moon = 20.0, phase = 0.3, cloud = 0.05, soil = 0.4, temp = -2.0, sights = NightSights(aurora = 0.5)))
        add(g, "Feb 10 · aurora over snow", scene(41, -35.0, cloud = 0.0, snow = 20.0, branchSnow = 0.3, temp = -12.0, sights = NightSights(aurora = 0.7)))
        add(g, "Sep 15 · aurora over the summer crown", scene(258, -25.0, cloud = 0.2, temp = 8.0, sights = NightSights(aurora = 0.55)))
        add(g, "Jan 3 · Quadrantids", scene(3, -40.0, cloud = 0.05, snow = 12.0, branchSnow = 0.2, temp = -9.0,
            sights = NightSights(meteors = 3, radiantSide = -0.6, radiantElevation = 60.0, shower = "Quadrantids")))
        add(g, "Oct 21 · Orionids", scene(294, -30.0, cloud = 0.05, soil = 0.35, temp = 4.0,
            sights = NightSights(meteors = 1, radiantSide = -0.5, radiantElevation = 30.0, shower = "Orionids")))
        add(g, "Nov 17 · Leonids", scene(321, -35.0, cloud = 0.05, soil = 0.5, temp = 1.0,
            sights = NightSights(meteors = 2, radiantSide = -0.7, radiantElevation = 35.0, shower = "Leonids")))
        add(g, "Dec 14 · Geminids", scene(348, -45.0, cloud = 0.05, snow = 6.0, branchSnow = 0.3, temp = -6.0,
            sights = NightSights(meteors = 3, radiantSide = 0.3, radiantElevation = 55.0, shower = "Geminids")))
        add(g, "Dec 14 · Geminids under the aurora", scene(348, -45.0, cloud = 0.05, snow = 6.0, branchSnow = 0.3, temp = -6.0,
            sights = NightSights(aurora = 0.5, meteors = 2, radiantSide = 0.3, radiantElevation = 55.0, shower = "Geminids")))

        // The widget at other shapes it may be given; the large view follows the widget's shape, so only the widget.
        g = "10 Widget sizes"
        val day = scene(293, 18.0, sunSide = 0.1, cloud = 0.2, soil = 0.35, temp = 8.0)
        val night = scene(303, -30.0, cloud = 0.15, soil = 0.4, temp = 3.0, sights = NightSights(aurora = 0.45))
        for ((label, w, h) in listOf(Triple("1×5 owner's phone 59×494", 59, 494), Triple("1×4 59×395", 59, 395), Triple("1×3 59×296", 59, 296),
            Triple("default 86×430", 86, 430), Triple("2×5 140×494", 140, 494), Triple("2×3 140×296", 140, 296))) {
            add(Entry(g, "$label · day", day, w, h, expanded = false))
            add(Entry(g, "$label · night", night, w, h, expanded = false))
        }
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun run(activity: Activity) {
        val status = TextView(activity).apply { textSize = 20f; text = "Painting the catalogue…" }
        activity.setContentView(status)
        Thread {
            val root = File(activity.filesDir, "tree_catalog").apply { deleteRecursively(); mkdirs() }
            val manifest = JSONArray()
            val now = System.currentTimeMillis()
            val started = System.nanoTime()
            for ((i, e) in entries.withIndex()) {
                val name = "%03d-%s".format(i + 1, slug(e.label))
                val frames = if (e.scene.animated) listOf(0, 1) else listOf(0)
                val item = JSONObject().put("group", e.group).put("label", e.label).put("name", name)
                    .put("animated", e.scene.animated).put("describe", e.scene.describe()).put("widthDp", e.widthDp).put("heightDp", e.heightDp)
                // Collapsed: the widget at the phone's pixels, under the widget's own pixel cap.
                var w = (e.widthDp * PHONE_DENSITY).toInt()
                var h = (e.heightDp * PHONE_DENSITY).toInt()
                if (w.toLong() * h > 520_000) {
                    val k = Math.sqrt(520_000.0 / (w.toLong() * h))
                    w = (w * k).toInt(); h = (h * k).toInt()
                }
                val radius = 18 * PHONE_DENSITY * w / (e.widthDp * PHONE_DENSITY)
                for (f in frames) save(TreePainter.paint(e.scene, w, h, f, radius, now), File(root, "collapsed/$name${suffix(f, frames)}.png"))
                if (e.expanded) {
                    val k = minOf(1.0, Math.sqrt(1_100_000.0 / (380 * PHONE_DENSITY * 779 * PHONE_DENSITY))).toFloat()
                    val lw = (380 * PHONE_DENSITY * k).toInt()
                    val lh = (779 * PHONE_DENSITY * k).toInt()
                    val tree = TreeActivity.treeFrame(lw, lh, e.widthDp.toFloat() / e.heightDp)
                    for (f in frames) save(TreePainter.paint(e.scene, lw, lh, f, 28 * PHONE_DENSITY * k, now, tree), File(root, "expanded/$name${suffix(f, frames)}.png"))
                }
                item.put("expanded", e.expanded)
                manifest.put(item)
                activity.runOnUiThread { status.text = "Painted ${i + 1} of ${entries.size}: ${e.label}" }
            }
            File(root, "manifest.json").writeText(manifest.toString(1))
            val seconds = (System.nanoTime() - started) / 1_000_000_000
            android.util.Log.i("TreeCatalog", "done: ${entries.size} scenes in $seconds s")
            activity.runOnUiThread { status.text = "Done: ${entries.size} scenes in $seconds s" }
        }.start()
    }

    private fun suffix(frame: Int, frames: List<Int>) = if (frames.size == 1) "" else if (frame == 0) "-a" else "-b"

    private fun save(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
