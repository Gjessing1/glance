package io.gjessing.glance

import android.content.Intent
import android.os.Bundle
import com.getcapacitor.BridgeActivity
import org.json.JSONObject

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(GlancePlugin::class.java)
        // Before the bridge reads the served path: a bundle that never confirmed last time is dropped.
        WebBundle.revertUnconfirmed(this)
        super.onCreate(savedInstanceState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = launchTarget(intent) ?: return
        bridge?.webView?.post {
            bridge?.webView?.evaluateJavascript("window.dispatchEvent(new CustomEvent('glance:open', {detail: $target}))", null)
        }
    }
    companion object {
        fun launchTarget(intent: Intent?): JSONObject? {
            // A Glance widget's header, agenda setup hint or weather line: open that widget's screen.
            if (intent?.hasExtra("widgetId") == true && !intent.hasExtra("commuteMode") && !intent.hasExtra("journeyId")) {
                return JSONObject().put("kind", "glance").put("widgetId", intent.getIntExtra("widgetId", -1)).put("screen", intent.getStringExtra("screen") ?: "")
            }
            if (intent?.hasExtra("commuteMode") == true) return JSONObject().put("kind", "commute")
                .put("widgetId", intent.getIntExtra("widgetId", -1))
                .put("mode", intent.getStringExtra("commuteMode"))
                .put("journeyKey", intent.getStringExtra("journeyKey") ?: "")
            if (intent?.hasExtra("journeyId") != true) return null
            return JSONObject().put("widgetId", intent.getIntExtra("widgetId", -1))
                .put("groupId", intent.getStringExtra("groupId"))
                .put("journeyId", intent.getStringExtra("journeyId"))
                .put("departureTime", intent.getStringExtra("departureTime"))
                .put("view", intent.getStringExtra("view") ?: "detail")
        }
    }
}
