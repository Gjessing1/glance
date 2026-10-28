package io.gjessing.glance

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Today's all-day events as a short sheet over the home screen, opened from the agenda's all-day row
 * when it has more than one. A tap on an event opens it in Nodecal. The events come in the intent,
 * as the widget last rendered them, so the sheet shows at once and needs no network.
 */
class AllDayActivity : Activity() {
    private var closing = false
    private lateinit var scrim: FrameLayout
    private lateinit var sheet: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !night
        }
        scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(120, 10, 12, 40))
            setOnClickListener { dismiss() }
        }
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(getColor(R.color.glance_card))
                val r = dp(24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            isClickable = true // Taps inside the sheet must not reach the scrim and close it.
        }
        scrim.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(scrim) { _, insets ->
            sheet.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
        setContentView(scrim)
        render(intent)
        scrim.alpha = 0f
        sheet.visibility = View.INVISIBLE
        // The sheet wraps its content, so it slides in from its own height once laid out.
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setDuration(160).start()
            sheet.animate().translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
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

    private fun render(intent: Intent) {
        val ids = intent.getStringArrayExtra("ids").orEmpty()
        val titles = intent.getStringArrayExtra("titles").orEmpty()
        val calendars = intent.getStringArrayExtra("calendars").orEmpty()
        val colors = intent.getStringArrayExtra("colors").orEmpty()
        sheet.removeAllViews()
        sheet.addView(View(this).apply { background = rounded(getColor(R.color.glance_soft), dp(2)) },
            LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(8), dp(8), 0) }
        header.addView(label("All day", 22f, R.color.glance_ink, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(label("✕", 22f, R.color.glance_ink).apply {
            gravity = Gravity.CENTER
            contentDescription = "Close"
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        sheet.addView(header)
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))
        sheet.addView(label("$today · ${ids.size} events", 14f, R.color.glance_muted).apply { setPadding(dp(20), 0, dp(20), dp(8)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
        for (i in ids.indices) {
            if (i > 0) list.addView(View(this).apply { setBackgroundColor(getColor(R.color.glance_divider)) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { marginStart = dp(37); marginEnd = dp(20) })
            list.addView(event(ids[i], titles.getOrElse(i) { "" }, calendars.getOrElse(i) { "" }, colors.getOrElse(i) { "" }))
        }
        // A long list scrolls rather than pushing the sheet past the top of the screen.
        val most = (resources.displayMetrics.heightPixels * 0.6).toInt()
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(most, View.MeasureSpec.AT_MOST))
        }
        scroll.addView(list)
        sheet.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** One event: its calendar's colour bar, title and calendar; a tap opens it in Nodecal. */
    private fun event(id: String, title: String, calendar: String, color: String): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(16), dp(12))
            minimumHeight = dp(60)
            background = TypedValue().let { value ->
                theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                getDrawable(value.resourceId)
            }
            contentDescription = listOf(title, calendar).filter { it.isNotEmpty() }.joinToString(", ")
            setOnClickListener {
                startActivity(AgendaSection.nodecalIntent(this@AllDayActivity, id))
                finish()
                @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            }
        }
        row.addView(View(this).apply { background = rounded(WidgetBlocks.parseColor(context, color), dp(2)) },
            LinearLayout.LayoutParams(dp(4), dp(36)).apply { marginEnd = dp(13) })
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(title, 16f, R.color.glance_ink, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (calendar.isNotEmpty()) text.addView(label(calendar, 13f, R.color.glance_muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        row.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(label("›", 22f, R.color.glance_muted).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; setPadding(dp(8), 0, 0, dp(2)) })
        return row
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(getColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** The sheet for today's all-day events, as the widget renders them now. */
        fun intent(context: Context, items: List<AllDayItem>) = Intent(context, AllDayActivity::class.java).apply {
            putExtra("ids", items.map { it.event.id }.toTypedArray())
            putExtra("titles", items.map { it.event.title }.toTypedArray())
            putExtra("calendars", items.map { it.calendar }.toTypedArray())
            putExtra("colors", items.map { it.color }.toTypedArray())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
