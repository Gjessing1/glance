package io.gjessing.glance

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Nodecal's `/api/glance/agenda`, fetched with the integration token and cached once for every
 * widget: profiles are filtered locally, so all widgets share one response.
 */
object AgendaRepository {
    private const val prefsName = "glance_agenda"
    /** A week ahead, so "Nothing scheduled" can still name the next event's day. */
    private const val HOURS = 168

    private fun prefs(context: Context) = context.getSharedPreferences(prefsName, 0)

    fun url(context: Context): String = GlanceSettings.get(context).optString("nodecalUrl").trim().trimEnd('/')
    fun configured(context: Context) = url(context).startsWith("http") && GlanceSettings.nodecalToken(context).isNotEmpty()

    /** `{agenda, checkedAt, stale, error}`, or null before the first answer. */
    fun cached(context: Context): JSONObject? = prefs(context).getString("agenda", null)?.let { JSONObject(it) }

    fun refresh(context: Context): JSONObject {
        val agenda = fetch(url(context), GlanceSettings.nodecalToken(context))
        val entry = JSONObject().put("agenda", agenda).put("checkedAt", Instant.now().toString()).put("stale", false).put("error", "")
        prefs(context).edit().putString("agenda", entry.toString()).apply()
        return entry
    }

    /** A failed refresh keeps the last agenda and says why, so the widget can mark it old. */
    fun markStale(context: Context, error: String) {
        val entry = cached(context) ?: JSONObject()
        prefs(context).edit().putString("agenda", entry.put("stale", true).put("error", error).toString()).apply()
    }

    fun clear(context: Context) { prefs(context).edit().remove("agenda").apply() }

    fun fetch(base: String, token: String): JSONObject {
        require(base.startsWith("http")) { "Set the Nodecal address first" }
        require(token.isNotEmpty()) { "Add an integration token from Nodecal" }
        val connection = URL("$base/api/glance/agenda?hours=$HOURS").openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 10000
        connection.readTimeout = 20000
        // SSO answers a native client with a login redirect; following it would hand back an HTML page.
        connection.instanceFollowRedirects = false
        try {
            when (val code = connection.responseCode) {
                200 -> return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                // Nodecal's own refusal names the Bearer scheme; a bare 401 is the SSO in front of it.
                401 -> throw IllegalStateException(
                    if (connection.getHeaderField("WWW-Authenticate")?.startsWith("Bearer") == true) "Nodecal rejected the token"
                    else "Blocked by the login in front of Nodecal; /api/glance/ must skip SSO")
                301, 302, 303, 307, 308 -> throw IllegalStateException("Nodecal answered with a login page; /api/glance/ must skip SSO")
                else -> throw IllegalStateException("Nodecal answered HTTP $code")
            }
        } finally { connection.disconnect() }
    }
}
