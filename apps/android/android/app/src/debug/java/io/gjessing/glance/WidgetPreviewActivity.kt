package io.gjessing.glance

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Hosts DepartureWidget through AppWidgetHost, the same path a launcher uses.
 *
 *   adb shell appwidget grantbind --package io.gjessing.glance
 *   adb shell am start -n io.gjessing.glance/.WidgetPreviewActivity --ei width 406 --ei height 433 --ei count 5
 *
 * Add `--es provider commute` to host CommuteWidget instead, `smart`, `agenda` or `weather` for
 * the section widgets, `tree` for the Living Tree or `world` for Tiny Living World (`--ez worldGallery true`
 * paints that in twenty scenes instead, see [WorldGallery]). `--ez gallery true` instead paints the Living
 * Tree in a dozen seasons and weathers side by side (see [TreeGallery]), `--ez treeLarge true` as the
 * tap-to-enlarge view shows them, `--ez catalog true` every kind of picture as single PNGs (see [TreeCatalog]); with `tree`, `--ez motion
 * true` shows it in rain and wind, to check the two cross-fading frames. `--ez fixture true` fills the agenda and weather caches with PreviewFixtures
 * (times relative to now), so those render without Nodecal or MET. `--es preset` and `--es profile`
 * set the Glance widget's arrangement and agenda profile.
 *
 * `--ez pin true` instead asks the launcher to place the chosen provider on the home screen, to check
 * a change in the real host (One UI and Pixel inflate widgets their own way).
 *
 * `width`/`height` are the dp the widget is drawn at; `reportedWidth`/`reportedHeight` override
 * what the options bundle claims, to mimic launchers that report a different size; with `fixture`,
 * `transportAgeMinutes` back-dates the transport check and `--ez emptyAgenda true` leaves the agenda without events,
 * `--ei agendaOffsetMinutes N` moves its events later, `--ei timedEvents N` keeps N of them, `--ez track true` tracks the next journey and
 * `--es eveningEnd HH:MM` moves the end of commute hours (it stays saved; pass 18:30 to restore).
 */
class WidgetPreviewActivity : Activity() {
    private lateinit var host: AppWidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val width = intent.getIntExtra("width", 406)
        val height = intent.getIntExtra("height", 433)
        val reportedWidth = intent.getIntExtra("reportedWidth", width)
        val reportedHeight = intent.getIntExtra("reportedHeight", height)
        val manager = AppWidgetManager.getInstance(this)
        if (intent.getBooleanExtra("pin", false)) {
            val pinned = when (intent.getStringExtra("provider")) {
                "agenda" -> AgendaWidget::class.java
                "weather" -> WeatherWidget::class.java
                "commute" -> CommuteWidget::class.java
                "tree" -> TreeWidget::class.java
                "world" -> WorldWidget::class.java
                else -> SmartWidget::class.java
            }
            manager.requestPinAppWidget(ComponentName(this, pinned), null, null)
            finish()
            return
        }
        if (intent.getBooleanExtra("catalog", false)) {
            TreeCatalog.run(this)
            return
        }
        if (intent.getBooleanExtra("birdGallery", false)) {
            TreeGallery.exportBirds(this)
            return
        }
        if (intent.getBooleanExtra("treeLarge", false)) {
            // The tap-to-enlarge view on the owner's phone, around the 59×494dp widget's tree.
            TreeGallery.showLarge(this, intent.getIntExtra("width", 380), intent.getIntExtra("height", 779), 59f / 494f)
            return
        }
        if (intent.getBooleanExtra("gallery", false)) {
            // The default is the 1×5 tree on the owner's phone (WQHD, five columns): narrow, about 8:1.
            TreeGallery.show(this, intent.getIntExtra("width", 59), intent.getIntExtra("height", 494))
            return
        }
        if (intent.getBooleanExtra("worldGallery", false)) {
            WorldGallery.show(this, intent.getIntExtra("width", 80), intent.getIntExtra("height", 80), intent.getIntExtra("scale", 1))
            return
        }
        host = AppWidgetHost(this, 4242)
        host.deleteHost()
        host.startListening()
        val id = host.allocateAppWidgetId()
        val kind = intent.getStringExtra("provider") ?: "departures"
        val commute = kind == "commute"
        val provider = ComponentName(this, when (kind) {
            "commute" -> CommuteWidget::class.java
            "smart" -> SmartWidget::class.java
            "agenda" -> AgendaWidget::class.java
            "weather" -> WeatherWidget::class.java
            "tree" -> TreeWidget::class.java
            "world" -> WorldWidget::class.java
            else -> DepartureWidget::class.java
        })
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, reportedWidth)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, reportedWidth)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, reportedHeight)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, reportedHeight)
        }
        check(manager.bindAppWidgetIdIfAllowed(id, provider, options)) { "Run: adb shell appwidget grantbind --package $packageName" }
        val view = host.createView(this, id, manager.getAppWidgetInfo(id))
        // One UI draws the widget edge to edge in its cell; drop AOSP's default host padding to match.
        view.setPadding(0, 0, 0, 0)
        // Launchers inflate widgets off the main thread; do the same so async-only behaviour shows up.
        if (intent.getBooleanExtra("async", true)) view.setExecutor(Executors.newSingleThreadExecutor())
        val density = resources.displayMetrics.density
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(30, 34, 60)) }
        frame.addView(view, FrameLayout.LayoutParams((width * density).toInt(), (height * density).toInt()).apply { leftMargin = (27 * density).toInt(); topMargin = (80 * density).toInt() })
        setContentView(frame)

        val count = intent.getIntExtra("count", 0)
        Executors.newSingleThreadExecutor().execute {
            if (kind in setOf("smart", "agenda", "weather")) {
                val type = if (kind == "smart") SectionConfigs.SMART else kind
                val config = SectionConfigs.config(this, id, type)
                intent.getStringExtra("preset")?.let { config.put("preset", it) }
                intent.getStringExtra("profile")?.let { config.put("profile", it) }
                SectionConfigs.saveConfig(this, id, config)
                // Ends commute hours early (or restores them) so evening states can be checked at any hour.
                intent.getStringExtra("eveningEnd")?.let { end ->
                    val settings = GlanceSettings.get(this)
                    GlanceSettings.put(this, settings.put("time", (settings.optJSONObject("time") ?: TimeRules().toJson()).put("eveningEnd", end)))
                }
                if (intent.getBooleanExtra("fixture", false)) {
                    // Binding queued a live refresh, which would overwrite the fixtures with today's data.
                    androidx.work.WorkManager.getInstance(this).cancelAllWork()
                    PreviewFixtures.install(this, id, intent.getIntExtra("transportAgeMinutes", 0).toLong(), intent.getBooleanExtra("emptyAgenda", false),
                        intent.getIntExtra("agendaOffsetMinutes", 0).toLong(), intent.getBooleanExtra("track", false), intent.getIntExtra("timedEvents", -1))
                    Widgets.renderAll(this)
                    Thread.sleep(6000)
                    PreviewFixtures.install(this, id, intent.getIntExtra("transportAgeMinutes", 0).toLong(), intent.getBooleanExtra("emptyAgenda", false),
                        intent.getIntExtra("agendaOffsetMinutes", 0).toLong(), intent.getBooleanExtra("track", false), intent.getIntExtra("timedEvents", -1))
                }
                // Live data: the coordinator refreshes every placed widget, this one included.
                else try { RefreshCoordinator.run(this) } catch (_: Exception) { }
                Widgets.renderAll(this)
                return@execute
            }
            if (kind == "tree") {
                try { RefreshCoordinator.run(this) } catch (_: Exception) { }
                TreeWidget.render(this, id)
                // `--ez motion true` swaps in a rainy, windy version of now, to check the cross-fading frames.
                if (intent.getBooleanExtra("motion", false)) {
                    val now = System.currentTimeMillis()
                    val scene = TreeWidget.scene(this, id, now, remember = false).copy(rain = 1.5, wind = 8.0, cloud = 1.0)
                    val px = (width * resources.displayMetrics.density).toInt() to (height * resources.displayMetrics.density).toInt()
                    manager.updateAppWidget(id, TreeWidget.frames(this, scene, px.first, px.second, 18 * resources.displayMetrics.density, now))
                }
                return@execute
            }
            if (kind == "world") {
                try { RefreshCoordinator.run(this) } catch (_: Exception) { }
                WorldWidget.render(this, id)
                return@execute
            }
            if (commute) {
                try { CommuteRepository.refresh(this, id) } catch (_: Exception) { }
                CommuteWidget.render(this, id)
                return@execute
            }
            if (count > 0) {
                val config = BoardRepository.config(this, id)
                val groups = config.getJSONArray("groups")
                for (i in 0 until groups.length()) groups.getJSONObject(i).put("count", count)
                BoardRepository.saveConfig(this, id, JSONObject(config.toString()))
            }
            try { BoardRepository.refresh(this, id) } catch (_: Exception) { }
            DepartureWidget.render(this, id)
        }
    }

    override fun onDestroy() {
        if (::host.isInitialized) host.stopListening()
        super.onDestroy()
    }
}
