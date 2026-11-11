package com.difft.android.base.ui.topchrome

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.difft.android.base.R
import com.difft.android.base.ui.glass.GlassSurfaceBinding
import kotlin.math.roundToInt











class TopChromeGlassDrawable(
    private val host: View,
    private val style: TopChromeStyle,
    @ColorRes private val tintRes: Int = R.color.bg1,
    private val solidBottom: () -> Float,
) : Drawable(), AutoCloseable {
    private val binding = GlassSurfaceBinding(host)
    private val rect = RectF()
    private val tint = Paint()
    private val fallback = Paint()
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val drawBackdrop: (Canvas) -> Boolean = { binding.draw(it, rect) }
    private var cachedSolid = Float.NaN
    private var cachedColor = 0
    private var drawableAlpha = 255
    private var closed = false

    
    fun extent(): Float = solidBottom() + if (style == TopChromeStyle.Feather) FEATHER_FADE_DP * host.resources.displayMetrics.density else 0f

    override fun draw(canvas: Canvas) {
        if (closed || !host.isShown || host.width <= 0) {
            binding.release()
            return
        }
        val solid = solidBottom()
        if (solid <= 0f) {
            binding.release()
            return
        }
        rect.set(0f, 0f, host.width.toFloat(), extent())
        updatePaints(solid, color())
        val save = if (drawableAlpha < 255) canvas.saveLayerAlpha(rect, drawableAlpha) else canvas.save()
        canvas.clipRect(rect)
        if (style == TopChromeStyle.Band) {
            if (drawBackdrop(canvas)) canvas.drawRect(rect, tint) else canvas.drawRect(rect, fallback)
        } else {
            val layer = canvas.saveLayer(rect, null)
            if (drawBackdrop(canvas)) {
                canvas.drawRect(rect, tint)
                
                canvas.drawRect(rect, mask)
            } else {
                canvas.drawRect(rect, fallback)
            }
            canvas.restoreToCount(layer)
        }
        canvas.restoreToCount(save)
    }

    private fun color(): Int = ContextCompat.getColor(host.context, tintRes)

    private fun dark(): Boolean =
        host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun updatePaints(solid: Float, color: Int) {
        if (solid == cachedSolid && color == cachedColor) return
        cachedSolid = solid
        cachedColor = color
        if (style == TopChromeStyle.Band) {
            val alpha = if (dark()) BAND_ALPHA_DARK else BAND_ALPHA_LIGHT
            tint.color = ColorUtils.setAlphaComponent(color, (255 * alpha).roundToInt())
            fallback.color = ColorUtils.setAlphaComponent(color, 255)
            return
        }
        val end = rect.bottom
        val stop = if (end > 0f) (solid / end).coerceIn(0f, 1f) else 1f
        tint.color = ColorUtils.setAlphaComponent(color, (255 * FEATHER_ALPHA).roundToInt())
        mask.shader = LinearGradient(0f, 0f, 0f, end, intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, stop, 1f), Shader.TileMode.CLAMP)
        val head = ColorUtils.setAlphaComponent(color, (255 * FEATHER_FALLBACK_ALPHA).roundToInt())
        fallback.shader = LinearGradient(0f, 0f, 0f, end, intArrayOf(head, head, Color.TRANSPARENT),
            floatArrayOf(0f, stop, 1f), Shader.TileMode.CLAMP)
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

    companion object {
        
        const val BAND_ALPHA_LIGHT = 0.50f
        const val BAND_ALPHA_DARK = 0.45f
        const val FEATHER_ALPHA = 0.45f
        const val FEATHER_FALLBACK_ALPHA = 0.85f
        const val FEATHER_FADE_DP = 48f
    }
}
