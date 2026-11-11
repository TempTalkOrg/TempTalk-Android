package com.difft.android.base.ui.glass

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates


internal class ComposeGlassGeometry {
    val sampleBounds = RectF()
    val windowBounds = RectF()
    val inverse = Matrix()
    private val transform = Matrix()
    private val values = FloatArray(9).apply { this[8] = 1f }
    private var xx = Float.NaN
    private var xy = Float.NaN
    private var yx = Float.NaN
    private var yy = Float.NaN
    var originX = 0f
        private set
    var originY = 0f
        private set
    private var matrixInvertible = false
    val invertible: Boolean get() = matrixInvertible && originX.isFinite() && originY.isFinite()

    fun update(coordinates: LayoutCoordinates, bounds: RectF, padding: Float = 0f) {
        val origin = coordinates.localToWindow(Offset.Zero)
        val x = coordinates.localToWindow(Offset(1f, 0f)) - origin
        val y = coordinates.localToWindow(Offset(0f, 1f)) - origin
        originX = origin.x
        originY = origin.y
        if (x.x != xx || x.y != xy || y.x != yx || y.y != yy) {
            xx = x.x; xy = x.y; yx = y.x; yy = y.y
            values[0] = xx; values[1] = yx
            values[3] = xy; values[4] = yy
            transform.setValues(values)
            matrixInvertible = transform.invert(inverse)
        }
        if (!invertible) {
            sampleBounds.setEmpty()
            windowBounds.setEmpty()
            return
        }
        sampleBounds.set(bounds)
        sampleBounds.inset(-padding, -padding)
        transform.mapRect(sampleBounds)
        windowBounds.set(sampleBounds)
        windowBounds.offset(originX, originY)
    }

    fun draw(canvas: Canvas, source: GlassBackdrop?): Boolean {
        if (!invertible) return false
        val save = canvas.save()
        return try {
            canvas.concat(inverse)
            source?.draw(canvas, originX, originY) == true
        } finally {
            canvas.restoreToCount(save)
        }
    }
}
