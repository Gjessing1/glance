package io.gjessing.glance

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.WindowCompat
import kotlin.math.min

/**
 * Tiny Living World shown large over the home screen, from a tap on the widget: the same moment,
 * painted at the size of the screen, with its two frames breathing into each other for as long as it
 * is open. Nothing to do here; a tap anywhere or Back closes it.
 */
class WorldActivity : Activity() {
    private var closing = false
    private lateinit var scrim: FrameLayout
    private var breathing: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(170, 8, 10, 24))
            setOnClickListener { dismiss() }
        }
        setContentView(scrim)
        val metrics = resources.displayMetrics
        val side = (min(metrics.widthPixels, metrics.heightPixels) - 32 * metrics.density).toInt()
        // Painted a little smaller than shown; the style is soft enough to scale up.
        val paint = min(side, 900)
        val id = intent.getIntExtra("widgetId", 0)
        val scene = WorldWidget.scene(this, id, System.currentTimeMillis(), remember = false)
        val radius = 28 * metrics.density * paint / side
        val stack = FrameLayout(this).apply {
            elevation = 12 * metrics.density
            contentDescription = scene.describe()
        }
        val first = ImageView(this).apply { setImageBitmap(WorldPainter.paint(scene, paint, paint, 0, radius)); scaleType = ImageView.ScaleType.FIT_XY }
        stack.addView(first, FrameLayout.LayoutParams(side, side))
        if (scene.animated) {
            val second = ImageView(this).apply {
                setImageBitmap(WorldPainter.paint(scene, paint, paint, 1, radius))
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = 0f
            }
            stack.addView(second, FrameLayout.LayoutParams(side, side))
            breathing = ObjectAnimator.ofFloat(second, "alpha", 0f, 1f).apply {
                duration = 3500
                startDelay = 1500
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        scrim.addView(stack, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        scrim.alpha = 0f
        stack.scaleX = 0.92f
        stack.scaleY = 0.92f
        scrim.animate().alpha(1f).setDuration(180).start()
        stack.animate().scaleX(1f).scaleY(1f).setDuration(240).withEndAction { breathing?.start() }.start()
    }

    @Deprecated("Activity has no OnBackPressedDispatcher")
    override fun onBackPressed() = dismiss()

    override fun onDestroy() {
        breathing?.cancel()
        super.onDestroy()
    }

    private fun dismiss() {
        if (closing) return
        closing = true
        breathing?.cancel()
        scrim.animate().alpha(0f).setDuration(160).withEndAction {
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }.start()
    }

    companion object {
        fun intent(context: Context, widgetId: Int) = Intent(context, WorldActivity::class.java).apply {
            putExtra("widgetId", widgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
