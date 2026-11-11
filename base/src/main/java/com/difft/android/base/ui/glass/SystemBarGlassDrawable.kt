package com.difft.android.base.ui.glass

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.difft.android.base.R







class SystemBarGlassDrawable(
    private val host: View,
    private val edge: SystemBarEdge,
    private val enabled: () -> Boolean = { true },
    private val tintColor: Int? = null,
) : Drawable(), AutoCloseable {
    private val painter = SystemBarGlassPainter()
    private val binding = GlassSurfaceBinding(host)
    private val drawBackdrop: (Canvas) -> Boolean = { binding.draw(it, strip) }
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)
    private val strip = RectF()
    private var drawableAlpha = 255
    private var closed = false

    override fun draw(canvas: Canvas) {
        val insets = ViewCompat.getRootWindowInsets(host)
        if (closed || !enabled() || !host.isShown || insets == null ||
            (edge == SystemBarEdge.BOTTOM && insets.isVisible(WindowInsetsCompat.Type.ime()))
        ) {
            binding.release()
            return
        }
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val inset = if (edge == SystemBarEdge.TOP) bars.top else bars.bottom
        if (inset == 0 || host.width <= 0) {
            binding.release()
            return
        }
        host.getLocationInWindow(location)
        val extent = inset + GlassTuning.SYSTEM_BAR_FADE_DP * host.resources.displayMetrics.density
        if (edge == SystemBarEdge.TOP) {
            strip.set(0f, -location[1].toFloat(), host.width.toFloat(), extent - location[1])
        } else {
            val root = host.rootView
            root.getLocationInWindow(rootLocation)
            val bottom = (rootLocation[1] + root.height - location[1]).toFloat()
            strip.set(0f, bottom - extent, host.width.toFloat(), bottom)
        }
        val dark = host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val color = tintColor ?: ContextCompat.getColor(host.context, if (dark) R.color.bg3 else R.color.bg1)
        val save = if (drawableAlpha < 255) canvas.saveLayerAlpha(strip, drawableAlpha) else canvas.save()
        painter.draw(canvas, strip, edge, inset.toFloat(), color, drawBackdrop)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
    override fun getAlpha(): Int = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun close() {
        if (closed) return
        closed = true
        binding.close()
    }
}
