package io.gjessing.glance

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * Settings of the section widgets, one JSON per widget id: the combined Glance (`smart`), the
 * standalone agenda and the standalone weather widget. A Glance widget's commute stops live in
 * CommuteRepository under the same widget id, so the commute screens work on it unchanged.
 */
object SectionConfigs {
    private const val prefsName = "glance_sections"
    const val SMART = "smart"
    const val AGENDA = "agenda"
    const val WEATHER = "weather"

    /** Preset arrangements, top to bottom. There is no free-form editor. */
    val presets = mapOf(
        "full" to listOf("commute", "agenda", "weather"),
        "commute-agenda" to listOf("commute", "agenda"),
        "agenda-weather" to listOf("agenda", "weather"),
    )

    fun defaults(type: String): JSONObject {
        val config = JSONObject().put("id", UUID.randomUUID().toString()).put("type", type).put("version", 1)
        when (type) {
            SMART -> config.put("preset", "full").put("single", "agenda").put("profile", AgendaModel.AUTO).put("weatherAdviceOnly", false)
            AGENDA -> config.put("profile", AgendaModel.AUTO)
        }
        return config
    }

    private fun prefs(context: Context) = context.getSharedPreferences(prefsName, 0)

    /** Widget id -1 is the default setup per type: what a newly placed widget of that type starts from. */
    private fun key(widgetId: Int, type: String) = if (widgetId < 0) "config_${type}_template" else "config_$widgetId"

    fun config(context: Context, widgetId: Int, type: String): JSONObject {
        prefs(context).getString(key(widgetId, type), null)?.let { return JSONObject(it) }
        val template = if (widgetId >= 0) prefs(context).getString(key(-1, type), null) else null
        val fresh = template?.let { JSONObject(it).put("id", UUID.randomUUID().toString()) } ?: defaults(type)
        if (widgetId >= 0) prefs(context).edit().putString(key(widgetId, type), fresh.toString()).apply()
        return fresh
    }

    fun saveConfig(context: Context, widgetId: Int, value: JSONObject) {
        val type = value.optString("type")
        require(type in setOf(SMART, AGENDA, WEATHER)) { "Unknown widget type" }
        if (type == SMART) {
            require(value.optString("preset") in presets.keys + "single") { "Unknown arrangement" }
            require(value.optString("single", "agenda") in setOf("commute", "agenda", "weather"))
        }
        if (type != WEATHER) require(value.optString("profile", AgendaModel.AUTO) in setOf(AgendaModel.AUTO, "personal", "work", "combined")) { "Unknown profile" }
        prefs(context).edit().putString(key(widgetId, type), value.toString()).apply()
    }

    fun remove(context: Context, widgetId: Int) { prefs(context).edit().remove("config_$widgetId").apply() }

    /** The sections a Glance widget shows, in order. */
    fun sections(config: JSONObject): List<String> =
        if (config.optString("preset") == "single") listOf(config.optString("single", "agenda"))
        else presets[config.optString("preset")] ?: presets.getValue("full")
}
