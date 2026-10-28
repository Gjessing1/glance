package io.gjessing.glance

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import org.json.JSONObject
import java.time.Instant
import kotlin.math.min

/**
 * Tiny Living World: a 1×1 diorama of a clearing by a mountain lake that lives with the weather, the
 * light and the seasons at home. It reads the forecast and nowcast the other widgets already cache,
 * keeps its own record of what the weather left behind (puddles, snow, ice, mushrooms, tracks), and
 * now and then has a visitor. A tap shows it large.
 *
 * Like the Living Tree it is redrawn on each refresh and every ten to twenty minutes; when something
 * in it moves, two frames cross-fade slowly while the home screen is on view.
 */
class WorldWidget : AppWidgetProvider() {
    override fun onEnabled(context: Context) { DepartureWidget.schedule(context) }
    override fun onDisabled(context: Context) { DepartureWidget.cancelIfUnused(context) }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, id)
        DepartureWidget.refresh(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { render(context, id) }
    override fun onDeleted(context: Context, ids: IntArray) { for (id in ids) CommuteRepository.remove(context, id) }

    companion object {
        private const val prefsName = "glance_world"
        /** Plenty for a 1×1 cell; the activity paints its own large copy. */
        private const val MAX_PIXELS = 160_000
        /** Up to this height-to-width ratio the widget is taken to be one cell and drawn square. */
        private const val SQUARE_UP_TO = 1.5f

        /** The world lies at home: the commute's first stop. */
        fun place(context: Context, id: Int, network: Boolean = false): Place? = WeatherRepository.places(context, id, network).firstOrNull()

        fun render(context: Context, id: Int) {
            val size = WidgetSize.of(context, id, 80, 80)
            val density = context.resources.displayMetrics.density
            var width = (size.width * density).toInt().coerceAtLeast(40)
            var height = (size.height * density).toInt().coerceAtLeast(40)
            // One UI's cells are taller than wide, room for an icon's label. In one cell the world is
            // a true square centred in the cell, level with the icons and folders beside it (One UI
            // centres them, measured on the phone); resized larger, it fills the widget as before.
            val square = height > width && height <= width * SQUARE_UP_TO
            if (square) height = width
            val pixels = width.toLong() * height
            if (pixels > MAX_PIXELS) {
                val k = Math.sqrt(MAX_PIXELS.toDouble() / pixels)
                width = (width * k).toInt()
                height = (height * k).toInt()
            }
            var radius = TreeWidget.cornerRadius(context) * width / (size.width * density)
            // Rounded like the app icons beside it, not into a circle on a small square.
            if (square) radius = min(radius, width * 0.3f)
            val nowMs = System.currentTimeMillis()
            val scene = scene(context, id, nowMs, remember = true)
            val views = frames(context, scene, width, height, radius, square)
            views.setContentDescription(R.id.tree_root, scene.describe())
            views.setOnClickPendingIntent(R.id.tree_root, WidgetBlocks.activity(context, id * 100 + 71, WorldActivity.intent(context, id)))
            AppWidgetManager.getInstance(context).updateAppWidget(id, views)
        }

        /**
         * One still, or two frames cross-fading, in the Living Tree's picture layouts; `square` uses
         * the variants that keep the picture's shape centred in the cell instead of stretching it.
         */
        fun frames(context: Context, scene: WorldScene, width: Int, height: Int, radius: Float, square: Boolean = false): RemoteViews {
            val first = WorldPainter.paint(scene, width, height, 0, radius)
            if (!scene.animated) return RemoteViews(context.packageName, if (square) R.layout.widget_world else R.layout.widget_tree).apply { setImageViewBitmap(R.id.tree_frame_a, first) }
            val second = WorldPainter.paint(scene, width, height, 1, radius)
            return RemoteViews(context.packageName, if (square) R.layout.widget_world_animated else R.layout.widget_tree_animated).apply {
                setImageViewBitmap(R.id.tree_frame_a, first)
                setImageViewBitmap(R.id.tree_frame_b, second)
                setImageViewBitmap(R.id.tree_frame_c, first)
            }
        }

        /** The world now, with its memory carried forward; `remember` saves it (the large view and previews pass false). */
        fun scene(context: Context, id: Int, nowMs: Long, remember: Boolean): WorldScene {
            val place = place(context, id)
            val lat = place?.lat ?: 59.83
            val lon = place?.lon ?: 10.43
            val key = "memory|${place?.key ?: "default"}"
            val prefs = context.getSharedPreferences(prefsName, 0)
            val saved = WorldMemory.fromJson(prefs.getString(key, null)?.let { JSONObject(it) }) ?: WorldMemory(TreeMemory(nowMs))
            val zone = GlanceTime.zone()
            val hours = place?.let { WeatherRepository.hourRows(context, it) }.orEmpty()
            val nowcast = place?.let { WeatherRepository.nowcast(context, it) }
            val weatherAt = { ms: Long -> TreeWidget.weatherAt(hours, nowcast, ms, nowMs) }
            val memory = saved.step(nowMs, Instant.ofEpochMilli(nowMs).atZone(zone).dayOfYear, weatherAt)
            if (remember) prefs.edit().putString(key, memory.toJson().toString()).apply()
            return WorldScene.of(nowMs, zone, lat, lon, memory, weatherAt(nowMs), place?.let { SpaceWeather.signal(context, it, nowMs) })
        }

        fun nextBoundary(context: Context, id: Int): Instant {
            val place = place(context, id)
            val sun = Sky.sun(System.currentTimeMillis(), place?.lat ?: 59.83, place?.lon ?: 10.43)
            return Instant.now().plusSeconds(WorldScene.refreshAfterMinutes(sun.elevation) * 60)
        }
    }
}
