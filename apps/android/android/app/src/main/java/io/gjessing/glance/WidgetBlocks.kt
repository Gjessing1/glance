package io.gjessing.glance

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes

/**
 * A text colour from the widget palette. On Android 12+ the host resolves the resource itself, so
 * the widget follows a light/dark switch without waiting for the next update.
 */
fun RemoteViews.textColor(context: Context, viewId: Int, @ColorRes color: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setColor(viewId, "setTextColor", color)
    else setTextColor(viewId, context.getColor(color))
}

/** Normal, warning or critical: the three tones a status line can take. */
enum class Tone(@ColorRes val color: Int, @DrawableRes val icon: Int) {
    NORMAL(R.color.glance_muted, R.drawable.ic_check),
    OK(R.color.glance_ok, R.drawable.ic_check),
    WARNING(R.color.glance_warn, R.drawable.ic_warning),
    CRITICAL(R.color.glance_late, R.drawable.ic_error),
}

/** The widget's size in dp, as the launcher reports it for the current orientation. */
data class WidgetSize(val width: Int, val height: Int) {
    companion object {
        fun of(context: Context, id: Int, fallbackWidth: Int, fallbackHeight: Int): WidgetSize {
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
            // Launchers report min width/max height in portrait and the reverse in landscape.
            val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val width = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            val height = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            return WidgetSize(width.takeIf { it > 0 } ?: fallbackWidth, height.takeIf { it > 0 } ?: fallbackHeight)
        }
    }
}

/**
 * The pieces every Glance section is built from (roadmap F6), so the commute, agenda and weather
 * sections look like one family and like the departure cards: white rounded cards, a bold title row,
 * line badges in operator colours, a time with its countdown, a colour bar and a toned status line.
 */
object WidgetBlocks {
    /** A card with a title row: icon, title, and a muted label on the right. Rows go into `section_body`. */
    fun section(context: Context, @DrawableRes icon: Int, title: String, right: String): RemoteViews {
        val card = RemoteViews(context.packageName, R.layout.widget_glance_section)
        card.removeAllViews(R.id.section_body)
        title(card, icon, title, right)
        return card
    }

    /** The title row alone, for a card the widget's own layout holds (Smart Glance's scrolling agenda). */
    fun header(context: Context, @DrawableRes icon: Int, title: String, right: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_glance_section_header).also { title(it, icon, title, right) }

    private fun title(views: RemoteViews, @DrawableRes icon: Int, title: String, right: String) {
        views.setImageViewResource(R.id.section_icon, icon)
        views.setTextViewText(R.id.section_title, title)
        views.setTextViewText(R.id.section_right, right)
        views.setViewVisibility(R.id.section_right, if (right.isEmpty()) View.GONE else View.VISIBLE)
    }

    /** Fills the badge views (`line_badge`, `mode_icon`, `line_text`) that a layout includes. */
    fun badge(views: RemoteViews, line: String, mode: String) {
        val rail = mode != "bus" && mode != "coach"
        views.setTextViewText(R.id.line_text, line.ifEmpty { "–" })
        views.setImageViewResource(R.id.mode_icon, if (rail) R.drawable.ic_train else R.drawable.ic_bus)
        views.setInt(R.id.line_badge, "setBackgroundResource", if (rail) R.drawable.badge_rail else R.drawable.badge_bus)
    }

    /** A time and its countdown beside it; late times turn red like on Entur. */
    fun timeWithCountdown(context: Context, views: RemoteViews, timeId: Int, countId: Int, time: String, countdown: String, late: Boolean) {
        views.setTextViewText(timeId, time)
        views.setTextViewText(countId, countdown)
        views.setViewVisibility(countId, if (countdown.isEmpty()) View.GONE else View.VISIBLE)
        views.textColor(context, timeId, if (late) R.color.glance_late else R.color.glance_ink)
    }

    /** One line with a tone icon: a transfer verdict, a stale note, a notice. */
    fun status(context: Context, text: String, tone: Tone): RemoteViews {
        val line = RemoteViews(context.packageName, R.layout.widget_status_line)
        line.setImageViewResource(R.id.status_icon, tone.icon)
        // A plain fact needs no icon; only a verdict does.
        if (tone == Tone.NORMAL) line.setViewVisibility(R.id.status_icon, View.GONE)
        line.setTextViewText(R.id.status_text, text)
        line.textColor(context, R.id.status_text, if (tone == Tone.NORMAL) R.color.glance_muted else tone.color)
        return line
    }

    /** Wires a small refresh button, which counts down while a tapped refresh runs (see [refreshState]). */
    fun refreshButton(context: Context, views: RemoteViews, viewId: Int, countId: Int, provider: Class<*>, widgetId: Int) {
        val intent = android.content.Intent(context, provider).setAction(SectionWidget.ACTION_REFRESH)
            .setData(android.net.Uri.parse("glance://refresh/$widgetId"))
        views.setOnClickPendingIntent(viewId, PendingIntent.getBroadcast(context, widgetId * 100 + 99, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        refreshState(context, views, viewId, countId, widgetId)
    }

    /**
     * A refresh icon, or in its place while a tapped refresh runs the seconds it should still take, so
     * the tap visibly did something and the icon coming back says the data is in.
     */
    fun refreshState(context: Context, views: RemoteViews, iconId: Int, countId: Int, widgetId: Int) {
        val left = RefreshCoordinator.countdown(context, widgetId)
        views.setViewVisibility(iconId, if (left == null) View.VISIBLE else View.GONE)
        views.setViewVisibility(countId, if (left == null) View.GONE else View.VISIBLE)
        if (left != null) {
            views.setTextViewText(countId, left.toString())
            views.setContentDescription(countId, "Refreshing")
        }
    }

    fun divider(context: Context) = RemoteViews(context.packageName, R.layout.widget_divider)

    /** A calendar colour from Nodecal ("#ba6e03"), or the ink colour when it cannot be read. */
    fun parseColor(context: Context, value: String): Int =
        try { Color.parseColor(value) } catch (_: Exception) { context.getColor(R.color.glance_ink) }

    fun activity(context: Context, code: Int, intent: android.content.Intent): PendingIntent =
        PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
