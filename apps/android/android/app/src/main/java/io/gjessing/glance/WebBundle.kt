package io.gjessing.glance

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import com.getcapacitor.plugin.WebView as CapWebView

/**
 * Over-the-air web bundles: the React screens can change without a new APK, the widgets and other
 * Kotlin code cannot. Capacitor's bridge serves files from the persisted `serverBasePath` instead of
 * the APK's assets, and clears that path itself when a new APK is installed, so a bundle never
 * outlives the native code it was built against.
 *
 * A bundle stays pending until its JavaScript calls `webReady`. One that never does is dropped on the
 * next launch (or after [CONFIRM_MS]) and not offered again, so a broken bundle cannot brick the app.
 */
object WebBundle {
    const val CONFIRM_MS = 15_000L
    private const val PREFS = "glance_web"
    private const val PENDING = "pending"
    private const val REJECTED = "rejected"
    private const val STAMP = "web-build.json"

    private fun capPrefs(context: Context) = context.getSharedPreferences(CapWebView.WEBVIEW_PREFS_NAME, Context.MODE_PRIVATE)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun root(context: Context) = File(context.filesDir, "web")

    private fun stampId(text: String) = runCatching { JSONObject(text).optString("id") }.getOrDefault("")

    fun servedPath(context: Context): String? =
        capPrefs(context).getString(CapWebView.CAP_SERVER_PATH, null)?.takeIf { it.isNotEmpty() && File(it).isDirectory }

    /** The bundle on screen: an applied download, or the one built into the APK. */
    fun currentId(context: Context): String = servedPath(context)?.let { path ->
        runCatching { stampId(File(path, STAMP).readText()) }.getOrDefault("")
    } ?: runCatching { stampId(context.assets.open("public/$STAMP").bufferedReader().use { it.readText() }) }.getOrDefault("")

    /** The manifest's bundle, if it targets this APK and is neither on screen nor known to be broken. */
    fun offered(context: Context, web: JSONObject?, origin: String): JSONObject? {
        if (web == null || web.optInt("nativeCode") != BuildConfig.VERSION_CODE) return null
        val id = web.optString("id")
        if (id.isEmpty() || !web.optString("url").startsWith(origin) || web.optString("sha256").isEmpty()) return null
        if (id == currentId(context) || id == prefs(context).getString(REJECTED, null)) return null
        return web
    }

    /** Downloads, verifies and unpacks [web]; returns the directory to serve. Blocking. */
    fun install(context: Context, web: JSONObject): File {
        val id = web.getString("id")
        require(id.matches(Regex("[0-9a-f]{6,64}"))) { "Invalid bundle id" }
        val dir = File(root(context), id)
        val staging = File(root(context), "$id.tmp").apply { deleteRecursively(); mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        val zip = File(context.cacheDir, "glance-web.zip")
        val connection = URL(web.getString("url")).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 15000
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        try {
            if (connection.responseCode != 200) error("Download failed (${connection.responseCode})")
            DigestInputStream(connection.inputStream, digest).use { input -> zip.outputStream().use { input.copyTo(it) } }
        } finally { connection.disconnect() }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != web.getString("sha256")) { zip.delete(); error("Download corrupted") }
        val base = staging.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { entries ->
            while (true) {
                val entry = entries.nextEntry ?: break
                val target = File(staging, entry.name)
                // Rejects entries that would land outside the bundle directory.
                if (!target.canonicalPath.startsWith(base)) error("Invalid bundle entry")
                if (entry.isDirectory) target.mkdirs()
                else { target.parentFile?.mkdirs(); target.outputStream().use { entries.copyTo(it) } }
            }
        }
        zip.delete()
        if (!File(staging, "index.html").isFile || stampId(File(staging, STAMP).readText()) != id) {
            staging.deleteRecursively(); error("Incomplete bundle")
        }
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) error("Could not install bundle")
        return dir
    }

    /** Persists [dir] as the served bundle, pending confirmation. The caller points the bridge at it. */
    fun activate(context: Context, dir: File) {
        prefs(context).edit().putString(PENDING, dir.path).apply()
        capPrefs(context).edit().putString(CapWebView.CAP_SERVER_PATH, dir.path).commit()
    }

    /** Called by the bundle once it has rendered; also removes bundles nothing serves any more. */
    fun confirm(context: Context) {
        val served = servedPath(context)
        if (prefs(context).getString(PENDING, null) == served) prefs(context).edit().remove(PENDING).apply()
        root(context).listFiles()?.filter { it.path != served }?.forEach { it.deleteRecursively() }
    }

    /** Falls back to the APK's bundle if the pending one never confirmed. Returns true if it did. */
    fun revertUnconfirmed(context: Context): Boolean {
        val pending = prefs(context).getString(PENDING, null) ?: return false
        prefs(context).edit().remove(PENDING).putString(REJECTED, File(pending).name).apply()
        if (capPrefs(context).getString(CapWebView.CAP_SERVER_PATH, null) != pending) return false
        capPrefs(context).edit().putString(CapWebView.CAP_SERVER_PATH, "").commit()
        return true
    }
}
