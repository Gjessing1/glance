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
import kotlin.math.sqrt

/**
 * The Living Tree shown large over the home screen, from a tap on the widget, as [WorldActivity]
 * shows the world: the same moment and the same tree, standing on a hilltop in a card nearly the size
 * of the screen, with pines and a Japanese maple beside it and a lake and forested hills beyond. A tap anywhere or
 * Back closes it.
 */
class TreeActivity : Activity() {
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
        val id = intent.getIntExtra("widgetId", 0)
        // Clear of the status and navigation bars; never wider than tall, nor taller than the widget's
        // own shape, so a short widget opens short.
        val height = (metrics.heightPixels - 112 * metrics.density).toInt()
        val width = min((metrics.widthPixels - 32 * metrics.density).toInt(), height)
        val size = WidgetSize.of(this, id, 80, 420)
        val shown = min(height, (width * size.height.toFloat() / size.width).toInt().coerceAtLeast(width))
        // Painted smaller than shown, as the widget is; the style is soft enough to scale up.
        val k = min(1.0, sqrt(MAX_PIXELS.toDouble() / (width.toLong() * shown)))
        val paintW = (width * k).toInt()
        val paintH = (shown * k).toInt()
        val tree = treeFrame(paintW, paintH, size.width.toFloat() / size.height)
        val nowMs = System.currentTimeMillis()
        val scene = TreeWidget.scene(this, id, nowMs, remember = false)
        val radius = 28 * metrics.density * paintW / width
        val stack = FrameLayout(this).apply {
            elevation = 12 * metrics.density
            contentDescription = scene.describe()
        }
        val first = ImageView(this).apply { setImageBitmap(TreePainter.paint(scene, paintW, paintH, 0, radius, nowMs, tree)); scaleType = ImageView.ScaleType.FIT_XY }
        stack.addView(first, FrameLayout.LayoutParams(width, shown))
        scrim.addView(stack, FrameLayout.LayoutParams(width, shown, Gravity.CENTER))
        scrim.alpha = 0f
        stack.scaleX = 0.92f
        stack.scaleY = 0.92f
        scrim.animate().alpha(1f).setDuration(180).start()
        stack.animate().scaleX(1f).scaleY(1f).setDuration(240).start()
        // The tree takes a while to paint this large, so the second frame follows once it is ready.
        if (scene.animated) Thread {
            val bitmap = TreePainter.paint(scene, paintW, paintH, 1, radius, nowMs, tree)
            runOnUiThread {
                if (closing || isDestroyed) return@runOnUiThread
                val second = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_XY
                    alpha = 0f
                }
                stack.addView(second, FrameLayout.LayoutParams(width, shown))
                breathing = ObjectAnimator.ofFloat(second, "alpha", 0f, 1f).apply {
                    duration = 3500
                    startDelay = 1500
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }
        }.start()
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
        /** About a full-HD card; the painter's grain and curtains take a while beyond that. */
        private const val MAX_PIXELS = 1_100_000

        /**
         * The width of the tree's own frame in a picture `width` × `height`: the widget's shape, so the
         * birch stands at the same size against the card's height as in the widget, with open sky over
         * it, and only the land around it widens.
         */
        fun treeFrame(width: Int, height: Int, widgetAspect: Float): Int = min(width, (height * widgetAspect).toInt())

        fun intent(context: Context, widgetId: Int) = Intent(context, TreeActivity::class.java).apply {
            putExtra("widgetId", widgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
