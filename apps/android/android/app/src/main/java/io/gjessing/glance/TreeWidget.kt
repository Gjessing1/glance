package io.gjessing.glance

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import org.json.JSONObject
import java.time.Instant

/**
 * Living Tree: a 1×5 picture of a weeping birch over its roots that follows the weather, the light
 * and the seasons where the commute starts (home). Its weather is the forecast and nowcast the
 * other widgets already cache; it adds no weather requests of its own beyond those for its place.
 * The northern lights come from NOAA ([SpaceWeather]), shared with Tiny Living World. A tap shows it large.
 *
 * Home screens cannot run animation code, so the picture is redrawn on each refresh and every ten
 * to thirty minutes as the light moves. When something in it moves (rain, snow, wind, falling
 * leaves), two frames go into a ViewFlipper that cross-fades slowly between them while the home
 * screen is on view, and stops by itself when it is not.
 */
class TreeWidget : AppWidgetProvider() {
    override fun onEnabled(context: Context) { DepartureWidget.schedule(context) }
    override fun onDisabled(context: Context) { DepartureWidget.cancelIfUnused(context) }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, id)
        DepartureWidget.refresh(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { render(context, id) }
    override fun onDeleted(context: Context, ids: IntArray) { for (id in ids) CommuteRepository.remove(context, id) }

    companion object {
        private const val prefsName = "glance_tree"
        /** Above this many pixels a frame is drawn smaller and scaled up; the picture is soft enough. */
        private const val MAX_PIXELS = 520_000

        /** The tree stands at home: the commute's first stop, whatever the hour. */
        fun place(context: Context, id: Int, network: Boolean = false): Place? = WeatherRepository.places(context, id, network).firstOrNull()

        fun render(context: Context, id: Int) {
            val size = WidgetSize.of(context, id, 80, 420)
            val density = context.resources.displayMetrics.density
            var width = (size.width * density).toInt().coerceAtLeast(40)
            var height = (size.height * density).toInt().coerceAtLeast(120)
            val pixels = width.toLong() * height
            if (pixels > MAX_PIXELS) {
                val k = Math.sqrt(MAX_PIXELS.toDouble() / pixels)
                width = (width * k).toInt()
                height = (height * k).toInt()
            }
            val radius = cornerRadius(context) * width / (size.width * density)
            val nowMs = System.currentTimeMillis()
            val scene = scene(context, id, nowMs, remember = true)
            val views = frames(context, scene, width, height, radius, nowMs)
            views.setContentDescription(R.id.tree_root, scene.describe())
            views.setOnClickPendingIntent(R.id.tree_root, WidgetBlocks.activity(context, id * 100 + 70, TreeActivity.intent(context, id)))
            AppWidgetManager.getInstance(context).updateAppWidget(id, views)
        }

        /** One still, or two frames cross-fading (A, B, then A again, so the loop has no jump). */
        fun frames(context: Context, scene: TreeScene, width: Int, height: Int, radius: Float, nowMs: Long): RemoteViews {
            val first = TreePainter.paint(scene, width, height, 0, radius, nowMs)
            if (!scene.animated) return RemoteViews(context.packageName, R.layout.widget_tree).apply { setImageViewBitmap(R.id.tree_frame_a, first) }
            val second = TreePainter.paint(scene, width, height, 1, radius, nowMs)
            return RemoteViews(context.packageName, R.layout.widget_tree_animated).apply {
                setImageViewBitmap(R.id.tree_frame_a, first)
                setImageViewBitmap(R.id.tree_frame_b, second)
                setImageViewBitmap(R.id.tree_frame_c, first)
            }
        }

        /**
         * The scene now, with the memory carried forward. `remember` saves it; a preview passes false
         * so it does not disturb the real record.
         */
        fun scene(context: Context, id: Int, nowMs: Long, remember: Boolean): TreeScene {
            val place = place(context, id)
            val lat = place?.lat ?: 59.85
            val lon = place?.lon ?: 10.45
            val key = "memory|${place?.key ?: "default"}"
            val prefs = context.getSharedPreferences(prefsName, 0)
            val saved = TreeMemory.fromJson(prefs.getString(key, null)?.let { JSONObject(it) }) ?: TreeMemory(nowMs)
            val zone = GlanceTime.zone()
            val hours = place?.let { WeatherRepository.hourRows(context, it) }.orEmpty()
            val nowcast = place?.let { WeatherRepository.nowcast(context, it) }
            val weatherAt = { ms: Long -> weatherAt(hours, nowcast, ms, nowMs) }
            val memory = saved.step(nowMs, Instant.ofEpochMilli(nowMs).atZone(zone).dayOfYear, weatherAt)
            if (remember) prefs.edit().putString(key, memory.toJson().toString()).apply()
            return TreeScene.of(nowMs, zone, lat, lon, memory, weatherAt(nowMs), place?.let { SpaceWeather.signal(context, it, nowMs) })
        }

        /** The nowcast for the last hour, else the cached forecast's own hour, else nothing. */
        internal fun weatherAt(hours: List<JSONObject>, nowcast: JSONObject?, ms: Long, nowMs: Long): TreeWeather? {
            if (nowcast != null && nowcast.has("temp") && ms >= nowMs - 60 * 60_000L) {
                val hour = hourAt(hours, ms)
                // Nowcast has no cloud cover; the hour it falls in does.
                if (hour != null) for (key in listOf("cloud", "cloudLow", "cloudMedium", "cloudHigh")) {
                    if (!nowcast.has(key) && hour.has(key)) nowcast.put(key, hour.optDouble(key))
                }
                return TreeWeather.of(nowcast)
            }
            return hourAt(hours, ms)?.let { TreeWeather.of(it) }
        }

        private fun hourAt(hours: List<JSONObject>, ms: Long): JSONObject? = hours.firstOrNull {
            val start = CommuteRepository.millis(it.optString("t")) ?: return@firstOrNull false
            ms >= start && ms < start + 3_600_000L
        }

        /** Light changes fastest around sunrise and sunset; the picture follows it there more often. */
        fun nextBoundary(context: Context, id: Int): Instant {
            val place = place(context, id)
            val sun = Sky.sun(System.currentTimeMillis(), place?.lat ?: 59.85, place?.lon ?: 10.45)
            return Instant.now().plusSeconds(TreeScene.refreshAfterMinutes(sun.elevation) * 60)
        }

        /** The launcher's own corner radius on Android 12+, so the picture's corners match the others. */
        internal fun cornerRadius(context: Context): Float {
            val res = context.resources
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { return res.getDimension(android.R.dimen.system_app_widget_background_radius) } catch (_: Exception) { }
            }
            return 18 * res.displayMetrics.density
        }
    }
}
