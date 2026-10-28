package io.gjessing.glance

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

/** Shared provider plumbing for the section widgets: one refresh job, renders from cache. */
abstract class SectionWidget(private val type: String) : AppWidgetProvider() {
    abstract fun render(context: Context, id: Int)
    override fun onEnabled(context: Context) { DepartureWidget.schedule(context) }
    override fun onDisabled(context: Context) { DepartureWidget.cancelIfUnused(context) }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            SectionConfigs.config(context, id, type)
            render(context, id)
        }
        DepartureWidget.refresh(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { render(context, id) }
    override fun onReceive(context: Context, intent: android.content.Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) DepartureWidget.refreshByHand(context, intent.data?.lastPathSegment?.toIntOrNull())
        if (intent.action == AgendaPager.ACTION) {
            val id = intent.getIntExtra("widgetId", -1)
            if (id < 0) return
            AgendaPager.set(context, id, intent.getIntExtra("page", 0))
            render(context, id)
        }
    }
    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) {
            SectionConfigs.remove(context, id)
            CommuteRepository.remove(context, id)
            AgendaPager.remove(context, id)
        }
    }

    companion object {
        const val ACTION_REFRESH = "io.gjessing.glance.SECTION_REFRESH"

        fun sectionContext(context: Context, id: Int, type: String) = SectionContext(context, id, SectionConfigs.config(context, id, type),
            Instant.now(), GlanceTime.zone(), GlanceTime.rules(context), context.resources.configuration.fontScale)
    }
}

/**
 * The combined Glance (roadmap phase 4): a header with the date, then commute, agenda and
 * weather in the order the chosen preset gives. Sections step down in size when the widget runs
 * short of room, least urgent first, and never reorder.
 */
class SmartWidget : SectionWidget(SectionConfigs.SMART) {
    override fun render(context: Context, id: Int) = Companion.render(context, id)

    companion object {
        /** Root padding plus the header row. */
        private const val CHROME = 44

        fun render(context: Context, id: Int) {
            val sc = sectionContext(context, id, SectionConfigs.SMART)
            val size = WidgetSize.of(context, id, 300, 400)
            val root = RemoteViews(context.packageName, R.layout.widget_glance_root)
            val plan = plan(sc, size.height - sc.dp(CHROME))
            // The forecast card carries the temperature range; only the commute's rain check leaves it to the header.
            header(sc, root, size.width, plan.none { it.first === WeatherSection && WeatherSection.advice(sc).kind == Advice.Kind.FORECAST })
            root.removeAllViews(R.id.content)
            for ((section, sizeClass) in plan) root.addView(R.id.content, section.render(sc, sizeClass))
            AppWidgetManager.getInstance(context).updateAppWidget(id, root)
        }

        /**
         * "WEEKEND" and "Sunday, 25 October" ("Sun, 25 Oct" where that would squeeze the label);
         * the status bar already has the clock. The temperature here joins them when no forecast card
         * below shows one.
         */
        private fun header(sc: SectionContext, root: RemoteViews, width: Int, temperature: Boolean) {
            val local = sc.local
            root.setTextViewText(R.id.header_label, GlanceTime.contextLabel(local, sc.rules).uppercase(Locale.ENGLISH))
            val style = if (width >= 300 * sc.scale) TextStyle.FULL else TextStyle.SHORT
            root.setTextViewText(R.id.header_day, local.dayOfWeek.getDisplayName(style, Locale.ENGLISH) + ", " +
                local.dayOfMonth + " " + local.month.getDisplayName(style, Locale.ENGLISH))
            val now = if (temperature) WeatherSection.here(sc)?.let { WeatherRepository.now(sc.context, it) } else null
            if (now != null && now.has("temp")) {
                root.setTextViewText(R.id.header_weather, "${WeatherRepository.emoji(now.optString("symbol"))} ${WeatherRepository.temperature(now.optDouble("temp"))}".trim())
                root.setViewVisibility(R.id.header_weather, View.VISIBLE)
            } else root.setViewVisibility(R.id.header_weather, View.GONE)
            root.setOnClickPendingIntent(R.id.header, WidgetBlocks.activity(sc.context, sc.widgetId * 100 + 60, Sections.openApp(sc.context, sc.widgetId)))
            WidgetBlocks.refreshButton(sc.context, root, R.id.header_refresh, R.id.header_refresh_count, SmartWidget::class.java, sc.widgetId)
        }

        /**
         * Each section at the size it asks for; while that is too tall, the least urgent section that
         * can shrink steps down, the later one on a tie. If nothing can shrink, the last sections go.
         * A step that turns out to free more than was missing is taken back where the rest still fits.
         * Height left over lets a full agenda show more events per page.
         */
        internal fun plan(sc: SectionContext, available: Int): List<Pair<GlanceSection, SizeClass>> {
            val ids = SectionConfigs.sections(sc.config)
            val single = ids.size == 1
            // Outside commute focus the commute's place goes to the local buses (to the change and back).
            val chosen = ids.map { if (it == "commute" && LocalSection.replacesCommute(sc)) LocalSection else Sections.of(it) }.mapNotNull { section ->
                val wanted = if (single) SizeClass.FULL else section.wanted(sc)
                wanted?.let { section to it }
            }.toMutableList()
            if (chosen.isEmpty()) return listOf(Sections.of(ids.first()) to SizeClass.LINE)
            val urgency = chosen.associate { it.first.id to it.first.urgency(sc) }
            fun total() = chosen.sumOf { it.first.height(sc, it.second) }
            // Every step down with the size it left, so the room a step turns out not to need can be taken back.
            val steps = mutableListOf<Pair<Int, SizeClass>>()
            fun shrink(index: Int, to: SizeClass) {
                steps += index to chosen[index].second
                chosen[index] = chosen[index].first to to
            }
            // The later departures are a bonus: they go before anything else does.
            if (total() > available) chosen.indexOfFirst { it.first === CommuteSection && it.second == SizeClass.FULL }.takeIf { it >= 0 }?.let { shrink(it, SizeClass.COMPACT) }
            while (total() > available) {
                val shrinkable = chosen.withIndex().filter { it.value.first.smaller(it.value.second) != null }
                if (shrinkable.isEmpty()) {
                    if (chosen.size == 1) break
                    chosen.removeAt(chosen.lastIndex)
                    continue
                }
                // The agenda gives way last on a tie: two whole events are worth more than a section's title row.
                val pick = shrinkable.minWith(compareBy<IndexedValue<Pair<GlanceSection, SizeClass>>> { urgency.getValue(it.value.first.id) }
                    .thenBy { it.value.first === AgendaSection }.thenByDescending { it.index })
                shrink(pick.index, pick.value.first.smaller(pick.value.second)!!)
            }
            // One step can free more than was missing: the steps are taken back, last first, wherever they still fit.
            for ((index, before) in steps.asReversed()) {
                val step = chosen.getOrNull(index) ?: continue
                if (step.first.smaller(before) != step.second) continue
                chosen[index] = step.first to before
                if (total() > available) chosen[index] = step
            }
            // Room to spare goes to a full agenda, as more events on each page.
            if (chosen.any { it.first === AgendaSection && it.second == SizeClass.FULL }) AgendaSection.fill(sc) { total() <= available }
            return chosen
        }

        /** The next instant this widget's text changes from cache alone. */
        fun nextBoundary(context: Context, id: Int): Instant? {
            val sc = sectionContext(context, id, SectionConfigs.SMART)
            return SectionConfigs.sections(sc.config).map { if (it == "commute" && LocalSection.replacesCommute(sc)) "local" else it }
                .mapNotNull { Sections.of(it).nextBoundary(sc) }.minOrNull()
        }
    }
}

/** The agenda on its own: the next event when small, two or as many as fit plus the all-day row from medium up. */
class AgendaWidget : SectionWidget(SectionConfigs.AGENDA) {
    override fun render(context: Context, id: Int) = Companion.render(context, id)

    companion object {
        fun render(context: Context, id: Int) {
            val sc = sectionContext(context, id, SectionConfigs.AGENDA)
            val size = WidgetSize.of(context, id, 300, 180)
            val root = RemoteViews(context.packageName, R.layout.widget_section_root)
            root.removeAllViews(R.id.content)
            val sizeClass = if (size.height < sc.dp(170)) SizeClass.COMPACT else SizeClass.FULL
            // Less the root's top and bottom padding.
            if (sizeClass == SizeClass.FULL) AgendaSection.fill(sc) { AgendaSection.height(sc, sizeClass) <= size.height - sc.dp(14) }
            val card = AgendaSection.render(sc, sizeClass)
            WidgetBlocks.refreshButton(context, card, R.id.section_refresh, R.id.section_refresh_count, AgendaWidget::class.java, id)
            root.addView(R.id.content, card)
            AppWidgetManager.getInstance(context).updateAppWidget(id, root)
        }

        fun nextBoundary(context: Context, id: Int) = AgendaSection.nextBoundary(sectionContext(context, id, SectionConfigs.AGENDA))
    }
}

/** The rain advice on its own, with the temperature now, and the next hours from medium up. */
class WeatherWidget : SectionWidget(SectionConfigs.WEATHER) {
    override fun render(context: Context, id: Int) = Companion.render(context, id)

    companion object {
        fun render(context: Context, id: Int) {
            val sc = sectionContext(context, id, SectionConfigs.WEATHER)
            val size = WidgetSize.of(context, id, 250, 110)
            val root = RemoteViews(context.packageName, R.layout.widget_section_root)
            root.removeAllViews(R.id.content)
            val card = WeatherSection.render(sc, SizeClass.LINE)
            WidgetBlocks.refreshButton(context, card, R.id.weather_refresh, R.id.weather_refresh_count, WeatherWidget::class.java, id)
            val here = WeatherSection.here(sc)
            val now = here?.let { WeatherRepository.now(context, it) }
            if (now != null && now.has("temp")) {
                card.setTextViewText(R.id.weather_now, "${WeatherRepository.emoji(now.optString("symbol"))} ${WeatherRepository.temperature(now.optDouble("temp"))}".trim())
                card.setViewVisibility(R.id.weather_now, View.VISIBLE)
            }
            if (here != null && size.height >= sc.dp(140)) WeatherSection.strip(sc, card, here, ((size.width - 44) / 44).coerceIn(3, 8))
            else card.setViewVisibility(R.id.weather_hours, View.GONE)
            root.addView(R.id.content, card)
            AppWidgetManager.getInstance(context).updateAppWidget(id, root)
        }
    }
}
