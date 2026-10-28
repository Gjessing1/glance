package io.gjessing.glance

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The journey timeline as a native sheet over the home screen. It renders the widget's cached board
 * at once, so a tap on a departure never waits for the WebView, then refreshes it from Entur. A
 * departure widget's board is read as it is; a commute hop (Smart Glance's local buses) is read
 * through [CommuteRepository.hopBoard] and a leg of a commute journey through
 * [CommuteRepository.legBoard], which give them the same shape.
 */
class JourneyActivity : Activity() {
    private var widgetId = -1
    private var groupId = ""
    private var hop = ""
    /** The commute direction a tapped leg belongs to, or "" when the sheet is not for a leg. */
    private var legDirection = ""
    private var legJourney = ""
    private var journeyId = ""
    private var departureTime = ""
    private var board: JSONObject? = null
    private var showPrevious = false
    private var showNext = false
    private var closing = false
    private lateinit var scrim: FrameLayout
    private lateinit var sheet: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(120, 10, 12, 40))
            setOnClickListener { dismiss() }
        }
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                val r = dp(24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            isClickable = true // Taps inside the sheet must not reach the scrim and close it.
        }
        val height = (resources.displayMetrics.heightPixels * 0.88).toInt()
        scrim.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, height, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(scrim) { _, insets ->
            sheet.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
        setContentView(scrim)
        scrim.alpha = 0f
        sheet.translationY = height.toFloat()
        scrim.animate().alpha(1f).setDuration(160).start()
        sheet.animate().translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    @Deprecated("Activity has no OnBackPressedDispatcher")
    override fun onBackPressed() = dismiss()

    private fun dismiss() {
        if (closing) return
        closing = true
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(180).start()
        scrim.animate().alpha(0f).setDuration(180).withEndAction {
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }.start()
    }

    private fun load(intent: Intent) {
        widgetId = intent.getIntExtra("widgetId", -1)
        hop = intent.getStringExtra("hop") ?: ""
        legDirection = intent.getStringExtra("legDirection") ?: ""
        legJourney = intent.getStringExtra("legJourney") ?: ""
        groupId = when {
            hop.isNotEmpty() -> hop
            legDirection.isNotEmpty() -> CommuteRepository.LEG_GROUP
            else -> intent.getStringExtra("groupId") ?: ""
        }
        journeyId = intent.getStringExtra("journeyId") ?: ""
        departureTime = intent.getStringExtra("departureTime") ?: ""
        showPrevious = false
        showNext = false
        board = when {
            hop.isNotEmpty() -> CommuteRepository.cached(this, widgetId)?.let { CommuteRepository.hopBoard(it, hop, journeyId) }
            legDirection.isNotEmpty() -> CommuteRepository.cached(this, widgetId)?.let { CommuteRepository.legBoard(it, legDirection, journeyId, legJourney) }
            else -> BoardRepository.cached(this, widgetId)
        }
        render()
        val age = CommuteRepository.millis(board?.optString("checkedAt"))?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
        if (age < 60_000) return
        val id = widgetId
        val hopId = hop
        val direction = legDirection
        val key = journeyId
        val journey = legJourney
        thread {
            val fresh = try { refresh(id, hopId, direction, key, journey) } catch (_: Exception) { null } ?: return@thread
            runOnUiThread {
                // Keep the old board when the chosen departure has already left the fresh one.
                if (!isFinishing && widgetId == id && selected(fresh).second != null) {
                    board = fresh
                    render()
                }
            }
        }
    }

    /** Fetches the board again and re-renders the widget the tap came from. */
    private fun refresh(id: Int, hopId: String, direction: String, key: String, journey: String): JSONObject? {
        val context = applicationContext
        if (hopId.isEmpty() && direction.isEmpty()) return BoardRepository.refresh(context, id).also { DepartureWidget.render(context, id) }
        val commute = CommuteRepository.refresh(context, id)
        Widgets.render(context, id)
        return if (direction.isNotEmpty()) CommuteRepository.legBoard(commute, direction, key, journey) else CommuteRepository.hopBoard(commute, hopId, key)
    }

    private fun selected(source: JSONObject?): Pair<JSONObject?, JSONObject?> {
        val group = source?.optJSONArray("groups").objects().firstOrNull { it.optString("id") == groupId }
        val departures = group?.optJSONArray("departures").objects()
        val departure = departures.firstOrNull { it.optString("journeyId") == journeyId && it.optString("scheduled") == departureTime }
            ?: departures.firstOrNull { it.optString("journeyId") == journeyId }
        return group to departure
    }

    private fun render() {
        sheet.removeAllViews()
        sheet.addView(View(this).apply { background = rounded(HANDLE, dp(2)) },
            LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        val (group, departure) = selected(board)
        if (group == null || departure == null) {
            header("Journey unavailable", "Refresh the widget and choose a current departure.")
            return
        }
        val accent = if (departure.optString("mode") == "rail") RAIL else BUS
        val updated = board?.optString("checkedAt")?.let { " · Updated ${DepartureWidget.clock(it)}" } ?: ""
        header("${departure.optString("line")} ${departure.optString("headsign")}".trim(), group.optString("name") + updated)
        sheet.addView(chips(group.optJSONArray("departures").objects(), departure))
        sheet.addView(summary(departure))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(16)) }
        timeline(list, departure, accent)
        sheet.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        sheet.addView(actions())
    }

    private fun header(title: String, subtitle: String) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(8), dp(8), 0) }
        row.addView(label(title, 24f, NAVY, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(label("✕", 22f, NAVY).apply {
            gravity = Gravity.CENTER
            contentDescription = "Close"
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        sheet.addView(row)
        sheet.addView(label(subtitle, 14f, MUTED).apply { setPadding(dp(20), 0, dp(20), dp(12)) })
    }

    private fun chips(departures: List<JSONObject>, current: JSONObject): View {
        val row = LinearLayout(this).apply { setPadding(dp(20), 0, dp(12), dp(12)) }
        var active: View? = null
        // Where lines mix (the 71 and the 280 to Asker), a bare time does not say which bus it is.
        val mixed = departures.map { it.optString("line") }.distinct().size > 1
        for (item in departures) {
            val isCurrent = item.optString("journeyId") == current.optString("journeyId") && item.optString("scheduled") == current.optString("scheduled")
            val late = item.optBoolean("cancelled") || DepartureWidget.minutesLate(item.optString("scheduled"), item.optString("expected")) > 0
            val time = DepartureWidget.clock(item.optString("expected"))
            val text = if (mixed) "${item.optString("line")}  $time" else time
            val chip = label(if (isCurrent) "✓ $text" else text, 16f, if (late) LATE else NAVY, bold = isCurrent).apply {
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = if (isCurrent) rounded(SELECTED, dp(20)) else rounded(Color.WHITE, dp(20), CHIP_BORDER)
                if (item.optBoolean("cancelled")) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                contentDescription = "${item.optString("line")} at $time"
                setOnClickListener {
                    journeyId = item.optString("journeyId")
                    departureTime = item.optString("scheduled")
                    showPrevious = false
                    showNext = false
                    render()
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
            if (isCurrent) active = chip
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            post { active?.let { scrollTo(maxOf(0, it.left - dp(20)), 0) } }
        }
    }

    /** Departure and arrival side by side: the arrival is what Entur's own view leaves out. */
    private fun summary(departure: JSONObject): View {
        val stops = departure.optJSONArray("stops").objects()
        val origin = stops.getOrNull(departure.optInt("originIndex"))
        val destination = stops.getOrNull(departure.optInt("destinationIndex"))
        val leaves = departure.optString("expected")
        val arrives = destination?.let { time(it, arrival = true).first }.orEmpty().ifEmpty { departure.optString("arrival") }
        val minutes = DepartureWidget.minutesLate(leaves, arrives)
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(SURFACE, dp(14))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        box.addView(endpoint("Departs", leaves, origin?.optString("name").orEmpty(), Gravity.START), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        box.addView(label(if (minutes > 0) "$minutes min\n→" else "→", 13f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        })
        box.addView(endpoint("Arrives", arrives, destination?.optString("name").orEmpty(), Gravity.END), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return FrameLayout(this).apply {
            setPadding(dp(20), 0, dp(20), dp(4))
            addView(box)
        }
    }

    private fun endpoint(caption: String, iso: String, name: String, side: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for (text in listOf(
            label(caption, 12f, MUTED),
            label(DepartureWidget.clock(iso), 22f, NAVY, bold = true),
            label(name, 13f, NAVY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
        )) addView(text.apply { gravity = side }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun timeline(list: LinearLayout, departure: JSONObject, accent: Int) {
        val stops = departure.optJSONArray("stops").objects()
        val origin = departure.optInt("originIndex")
        val destination = departure.optInt("destinationIndex")
        if (stops.isEmpty()) return
        val first = if (showPrevious) 0 else origin
        val last = if (showNext) stops.lastIndex else destination
        if (origin > 0) list.addView(toggle(if (showPrevious) "Hide previous stops" else "Show $origin previous stops") {
            showPrevious = !showPrevious
            render()
        })
        for (i in first..last) {
            val stop = stops[i]
            val inside = i in origin..destination
            val (expected, scheduled) = time(stop, arrival = i == destination)
            val delay = DepartureWidget.minutesLate(scheduled, expected)
            val row = LinearLayout(this).apply {
                setPadding(dp(20), 0, dp(20), 0)
                minimumHeight = dp(if (i == origin || i == destination) 64 else 46)
            }
            val times = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(3), 0, 0) }
            times.addView(label(DepartureWidget.clock(expected), 15f, if (delay > 0) LATE else if (inside) NAVY else MUTED, bold = true))
            if (delay > 0) times.addView(label(DepartureWidget.clock(scheduled), 12f, MUTED).apply { paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG })
            row.addView(times, LinearLayout.LayoutParams(dp(52), WRAP_CONTENT))
            // Lines leading out of view stay grey, so hidden stops read as a continuation.
            val above = when { i == 0 -> null; i - 1 >= origin && i <= destination -> accent; else -> LINE_MUTED }
            val below = when { i == stops.lastIndex -> null; i >= origin && i + 1 <= destination -> accent; else -> LINE_MUTED }
            row.addView(TimelineMarker(this, above, below, if (inside) accent else LINE_MUTED, big = i == origin || i == destination),
                LinearLayout.LayoutParams(dp(28), MATCH_PARENT))
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(1), 0, dp(10)) }
            text.addView(label(stop.optString("name"), 16f, if (inside) NAVY else MUTED, bold = inside))
            detail(departure, stop, i, origin, destination, delay)?.let { (line, color) -> text.addView(label(line, 13f, color)) }
            row.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            list.addView(row)
        }
        if (destination < stops.lastIndex) list.addView(toggle(if (showNext) "Hide later stops" else "Show ${stops.lastIndex - destination} later stops") {
            showNext = !showNext
            render()
        })
    }

    private fun detail(departure: JSONObject, stop: JSONObject, index: Int, origin: Int, destination: Int, delay: Long): Pair<String, Int>? {
        val rail = departure.optString("mode") == "rail"
        val platform = stop.optString("platform").takeIf { it.isNotEmpty() }?.let { if (rail) "Track $it" else "Stop $it" }
        if (stop.optBoolean("cancelled")) return "Cancelled at this stop" to LATE
        return when (index) {
            origin -> {
                val status = when {
                    departure.optBoolean("cancelled") -> "Cancelled"
                    delay > 0 -> "$delay min late"
                    departure.optBoolean("realtime") -> "On time"
                    else -> "Scheduled time"
                }
                listOfNotNull("Board here", platform, status).joinToString(" · ") to if (delay > 0 || departure.optBoolean("cancelled")) LATE else ON_TIME
            }
            destination -> listOfNotNull("Get off here", platform).joinToString(" · ") to NAVY
            else -> null
        }
    }

    private fun toggle(text: String, onClick: () -> Unit) = FrameLayout(this).apply {
        setPadding(dp(20), dp(4), dp(20), dp(10))
        addView(label("⇅  $text", 15f, NAVY).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(Color.WHITE, dp(8), CHIP_BORDER)
            setOnClickListener { onClick() }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    private fun actions(): View {
        val row = LinearLayout(this).apply { setPadding(dp(16), dp(10), dp(16), dp(12)) }
        if (legDirection.isNotEmpty()) {
            trackButton()?.let { row.addView(it, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(10) }) }
            row.addView(button("Whole commute", NAVY, Color.WHITE) { openApp("commute") }, LinearLayout.LayoutParams(0, dp(52), 1f))
            return row
        }
        // The app's commute screen has no map, so a hop offers only the journey there.
        if (hop.isEmpty()) row.addView(button("See in map", SELECTED, NAVY) { openApp("map") }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(10) })
        row.addView(button("Open in app", NAVY, Color.WHITE) { openApp("detail") }, LinearLayout.LayoutParams(0, dp(52), 1f))
        return row
    }

    /**
     * Tracks the journey the shown ride belongs to, or stops tracking it. The widget then holds that
     * journey, ringed in green, until it arrives. Null when the ride's journey is not known.
     */
    private fun trackButton(): View? {
        val key = selected(board).second?.optString("journeyKey").orEmpty().ifEmpty { return null }
        val tracking = CommuteRepository.tracked(this, widgetId)?.takeIf { it.optString("direction") == legDirection }
            ?.getJSONObject("journey")?.optString("key") == key
        return button(if (tracking) "Stop tracking" else "Track journey", if (tracking) Color.WHITE else ON_TIME,
            if (tracking) ON_TIME else Color.WHITE, if (tracking) ON_TIME else null) {
            val journey = if (tracking) null else CommuteRepository.findJourney(this, widgetId, legDirection, key) ?: return@button
            CommuteRepository.track(this, widgetId, legDirection, journey)
            Widgets.render(applicationContext, widgetId)
            // Tracking keeps transport fresh outside the commute windows and ends on arrival; both ride on the alarm.
            BoundaryAlarm.arm(applicationContext)
            render()
        }
    }

    private fun button(text: String, fill: Int, color: Int, stroke: Int? = null, onClick: () -> Unit) = label(text, 16f, color).apply {
        gravity = Gravity.CENTER
        background = rounded(fill, dp(12), stroke)
        setOnClickListener { onClick() }
    }

    private fun openApp(view: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("widgetId", widgetId)
            if (legDirection.isNotEmpty()) {
                // The journey the chosen ride belongs to, so the app opens on the whole commute.
                putExtra("commuteMode", legDirection)
                putExtra("journeyKey", selected(board).second?.optString("journeyKey").orEmpty())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                return@apply
            }
            if (hop.isNotEmpty()) {
                putExtra("commuteMode", CommuteRepository.VIA)
                putExtra("journeyKey", journeyId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                return@apply
            }
            putExtra("groupId", groupId)
            putExtra("journeyId", journeyId)
            putExtra("departureTime", departureTime)
            putExtra("view", view)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    /** Expected and scheduled time at a stop: arrival at the destination, departure elsewhere. */
    private fun time(stop: JSONObject, arrival: Boolean): Pair<String, String> {
        val order = if (arrival) listOf("Arrival", "Departure") else listOf("Departure", "Arrival")
        val scheduled = order.map { stop.optString("scheduled$it") }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val expected = order.map { stop.optString("expected$it") }.firstOrNull { it.isNotEmpty() } ?: scheduled
        return expected to scheduled
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private class TimelineMarker(context: Context, private val above: Int?, private val below: Int?, private val dot: Int, private val big: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = context.resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            val x = width / 2f
            val y = 14 * density // Centre of the stop name's first line.
            paint.strokeWidth = 4 * density
            above?.let { paint.color = it; canvas.drawLine(x, 0f, x, y, paint) }
            below?.let { paint.color = it; canvas.drawLine(x, y, x, height.toFloat(), paint) }
            paint.color = dot
            canvas.drawCircle(x, y, (if (big) 9 else 6) * density, paint)
            if (big) {
                paint.color = Color.WHITE
                canvas.drawCircle(x, y, 4 * density, paint)
            }
        }
    }

    companion object {
        /** A tap on one of a commute hop's departures: this sheet, opened on that journey. */
        fun hopIntent(context: Context, widgetId: Int, hopId: String, journey: JSONObject, code: Int): PendingIntent {
            val intent = Intent(context, JourneyActivity::class.java).apply {
                data = Uri.parse("glance://hop/$widgetId/$hopId/${Uri.encode(journey.optString("key"))}")
                putExtra("widgetId", widgetId)
                putExtra("hop", hopId)
                putExtra("journeyId", journey.optString("key"))
                putExtra("departureTime", CommuteRepository.rides(journey).firstOrNull()?.optString("aimedStart").orEmpty())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return WidgetBlocks.activity(context, code, intent)
        }

        /** A tap on one vehicle of a commute journey (Smart Glance's next connection): this sheet, opened on that ride. */
        fun legIntent(context: Context, widgetId: Int, direction: String, journey: JSONObject, ride: JSONObject, code: Int): PendingIntent {
            val key = CommuteRepository.rideKey(ride)
            val intent = Intent(context, JourneyActivity::class.java).apply {
                data = Uri.parse("glance://leg/$widgetId/$direction/${Uri.encode(key)}")
                putExtra("widgetId", widgetId)
                putExtra("legDirection", direction)
                putExtra("legJourney", journey.optString("key"))
                putExtra("journeyId", key)
                putExtra("departureTime", ride.optString("aimedStart"))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return WidgetBlocks.activity(context, code, intent)
        }

        private val NAVY = Color.rgb(24, 28, 86)
        private val MUTED = Color.rgb(107, 111, 143)
        private val LATE = Color.rgb(211, 31, 42)
        private val ON_TIME = Color.rgb(28, 128, 84)
        private val BUS = Color.rgb(197, 4, 78)
        private val RAIL = Color.rgb(11, 60, 122)
        private val SURFACE = Color.rgb(242, 242, 247)
        private val SELECTED = Color.rgb(209, 212, 240)
        private val CHIP_BORDER = Color.rgb(185, 184, 211)
        private val LINE_MUTED = Color.rgb(205, 208, 222)
        private val HANDLE = Color.rgb(205, 208, 222)
    }
}
