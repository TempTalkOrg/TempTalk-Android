package com.difft.android.base.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt


class SystemBarGlassPainter {
    private val tint = Paint()
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val fallback = Paint()
    private var cachedTop = Float.NaN
    private var cachedBottom = Float.NaN
    private var cachedInset = Float.NaN
    private var cachedColor = 0
    private var cachedEdge: SystemBarEdge? = null

    



    fun draw(
        canvas: Canvas,
        bounds: RectF,
        edge: SystemBarEdge,
        insetPx: Float,
        tintColor: Int,
        drawBackdrop: (Canvas) -> Boolean,
    ) {
        if (bounds.isEmpty || insetPx <= 0f) return
        updateShaders(bounds, edge, insetPx, tintColor)
        val clipSave = canvas.save()
        canvas.clipRect(bounds)
        val layerSave = canvas.saveLayer(bounds, null)
        if (drawBackdrop(canvas)) {
            canvas.drawRect(bounds, tint)
            
            canvas.drawRect(bounds, mask)
        } else {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawRect(bounds, fallback)
        }
        canvas.restoreToCount(layerSave)
        canvas.restoreToCount(clipSave)
    }

    private fun updateShaders(bounds: RectF, edge: SystemBarEdge, insetPx: Float, color: Int) {
        if (cachedTop == bounds.top && cachedBottom == bounds.bottom && cachedInset == insetPx &&
            cachedColor == color && cachedEdge == edge
        ) return
        cachedTop = bounds.top
        cachedBottom = bounds.bottom
        cachedInset = insetPx
        cachedColor = color
        cachedEdge = edge
        val top = edge == SystemBarEdge.TOP
        tint.color = ColorUtils.setAlphaComponent(color, (Color.alpha(color) * GlassTuning.STRIP_TINT_TOP).roundToInt())
        val edgeAlpha = (255 * GlassStripMath.ramp(0f, bounds.height())).roundToInt()
        val clearAlpha = (255 * GlassStripMath.ramp(bounds.height(), bounds.height())).roundToInt()
        val opaque = ColorUtils.setAlphaComponent(Color.WHITE, edgeAlpha)
        val clear = ColorUtils.setAlphaComponent(Color.WHITE, clearAlpha)
        mask.shader = LinearGradient(0f, bounds.top, 0f, bounds.bottom,
            if (top) opaque else clear, if (top) clear else opaque, Shader.TileMode.CLAMP)
        val protection = ColorUtils.setAlphaComponent(color, (Color.alpha(color) * GlassTuning.SYSTEM_BAR_PROTECTION_ALPHA).roundToInt())
        val fraction = GlassStripMath.insetFraction(insetPx, bounds.height())
        fallback.shader = LinearGradient(0f, bounds.top, 0f, bounds.bottom,
            if (top) intArrayOf(protection, protection, Color.TRANSPARENT) else intArrayOf(Color.TRANSPARENT, protection, protection),
            if (top) floatArrayOf(0f, fraction, 1f) else floatArrayOf(0f, 1f - fraction, 1f), Shader.TileMode.CLAMP)
    }
}
