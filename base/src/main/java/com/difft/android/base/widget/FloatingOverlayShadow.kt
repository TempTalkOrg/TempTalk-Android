package com.difft.android.base.widget

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.graphics.ColorUtils
import com.difft.android.base.R
import kotlin.math.min






internal class FloatingOverlayShadow private constructor(
    private val card: View,
    private val cornerRadiusPx: Float,
) : Drawable(), ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private val density = card.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val outline = Outline()
    private val outlineRect = Rect()
    private val transform = Matrix()
    private val path = Path()
    private var host: ViewGroup? = null
    private var observer: ViewTreeObserver? = null
    private var state = 0

    override fun onViewAttachedToWindow(v: View) {
        val parent = card.parent as? ViewGroup ?: return
        host = parent
        parent.overlay.add(this)
        observer = card.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        sync()
    }

    override fun onViewDetachedFromWindow(v: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
        host?.overlay?.remove(this)
        host = null
    }

    override fun onPreDraw(): Boolean {
        sync()
        return true
    }

    private fun sync() {
        val parent = host ?: return
        
        
        val next = listOf(card.left, card.top, card.right, card.bottom, card.translationX, card.translationY,
            card.scaleX, card.scaleY, card.pivotX, card.pivotY, card.rotation,
            card.isShown, card.alpha, card.paddingBottom, parent.width, parent.height).hashCode()
        if (next == state) return
        state = next
        setBounds(0, 0, parent.width, parent.height)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (!card.isShown || card.alpha <= 0f || card.width == 0 || card.height == 0) return
        
        outline.setEmpty()
        card.outlineProvider?.getOutline(card, outline)
        val radius = if (outline.getRect(outlineRect)) {
            rect.set(outlineRect)
            outline.radius
        } else {
            rect.set(0f, 0f, card.width.toFloat(), card.height.toFloat())
            cornerRadiusPx
        }.let { min(it, min(rect.width(), rect.height()) / 2f) }
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        
        transform.set(card.matrix)
        transform.postTranslate(card.left.toFloat(), card.top.toFloat())
        path.transform(transform)
        val color = ColorUtils.setAlphaComponent(0, (FloatingShadow.ALPHA * card.alpha).toInt())
        paint.color = color
        paint.setShadowLayer(FloatingShadow.BLUR_DP * density, 0f, FloatingShadow.DY_DP * density, color)
        val save = canvas.save()
        canvas.clipOutPath(path)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        
        fun install(card: View, cornerRadiusPx: Float) {
            if (card.getTag(R.id.floating_overlay_shadow) != null) return
            val shadow = FloatingOverlayShadow(card, cornerRadiusPx)
            card.setTag(R.id.floating_overlay_shadow, shadow)
            card.addOnAttachStateChangeListener(shadow)
            if (card.isAttachedToWindow) shadow.onViewAttachedToWindow(card)
        }
    }
}


internal object FloatingShadow {
    const val ALPHA = 0x20
    const val BLUR_DP = 2.67f
    const val DY_DP = 0.85f
}
