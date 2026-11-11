package com.difft.android.base.ui.glass

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver


class GlassSurfaceBinding(private val view: View) : View.OnAttachStateChangeListener, AutoCloseable {
    private var source: GlassBackdrop? = null
    private val location = IntArray(2)
    private val redraw: () -> Unit = { view.postInvalidateOnAnimation() }
    private val sampleBounds = RectF()
    private val requestBounds = RectF()
    private val lastRequest = RectF()
    private var hasRequest = false
    private val transform = Matrix()
    private val inverse = Matrix()
    private val values = FloatArray(9)
    private var invertible = true
    private var observer: ViewTreeObserver? = null
    private val visibility = ViewTreeObserver.OnPreDrawListener {
        if (!view.isShown && hasRequest) {
            source?.updateRegion(redraw, null)
            hasRequest = false
        }
        true
    }
    private val drawSource: (Canvas) -> Boolean = { canvas ->
        val save = canvas.save()
        if (invertible) canvas.concat(inverse)
        val drawn = source?.draw(canvas, location[0].toFloat(), location[1].toFloat()) == true
        canvas.restoreToCount(save)
        drawn
    }

    init {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    fun draw(canvas: Canvas, bounds: RectF? = null): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !view.isAttachedToWindow || !view.isShown || !canvas.isHardwareAccelerated) return false
        val nearest = view.findGlassBackdrop()
        if (source !== nearest) {
            source?.removeListener(redraw)
            source = nearest
            nearest?.addListener(redraw)
            hasRequest = false
        }
        view.getLocationInWindow(location)
        
        
        transform.reset()
        view.transformMatrixToGlobal(transform)
        transform.getValues(values)
        values[Matrix.MTRANS_X] = 0f
        values[Matrix.MTRANS_Y] = 0f
        transform.setValues(values)
        invertible = transform.invert(inverse)
        sampleBounds.set(bounds ?: run {
            requestBounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
            requestBounds
        })
        transform.mapRect(sampleBounds)
        requestBounds.set(sampleBounds)
        requestBounds.offset(location[0].toFloat(), location[1].toFloat())
        if (!invertible) {
            if (hasRequest) source?.updateRegion(redraw, null)
            hasRequest = false
        } else if (!hasRequest || lastRequest != requestBounds) {
            source?.updateRegion(redraw, requestBounds)
            lastRequest.set(requestBounds)
            hasRequest = source != null
        }
        if (!invertible) return false
        return drawSource(canvas)
    }

    override fun onViewAttachedToWindow(v: View) {
        if (observer == null) observer = view.viewTreeObserver.also { it.addOnPreDrawListener(visibility) }
    }

    override fun onViewDetachedFromWindow(v: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(visibility)
        observer = null
        release()
    }

    
    fun release() {
        source?.removeListener(redraw)
        source = null
        hasRequest = false
    }

    override fun close() {
        onViewDetachedFromWindow(view)
        view.removeOnAttachStateChangeListener(this)
    }
}
