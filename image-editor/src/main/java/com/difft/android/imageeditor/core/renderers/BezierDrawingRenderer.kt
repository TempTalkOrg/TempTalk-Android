package com.difft.android.imageeditor.core.renderers

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import com.difft.android.imageeditor.core.ColorableRenderer
import com.difft.android.imageeditor.core.RendererContext

/**
 * Renders a [AutomaticControlPointBezierLine] with [thickness], [color] and [cap] end type.
 */
@Parcelize
class BezierDrawingRenderer private constructor(
    private var colorValue: Int,
    private var thickness: Float,
    private val cap: Paint.Cap,
    private val bezierLine: AutomaticControlPointBezierLine,
    private val clipRect: RectF?
) : InvalidateableRenderer(), ColorableRenderer {

    @IgnoredOnParcel
    private val paint = Paint()

    init {
        updatePaint()
    }

    constructor(color: Int, thickness: Float, cap: Paint.Cap, clipRect: RectF?) : this(
        color,
        thickness,
        cap,
        AutomaticControlPointBezierLine(),
        if (clipRect != null) RectF(clipRect) else null
    )

    override fun getColor(): Int = colorValue

    override fun setColor(color: Int) {
        if (colorValue != color) {
            colorValue = color
            updatePaint()
            invalidate()
        }
    }

    fun setThickness(thickness: Float) {
        if (this.thickness != thickness) {
            this.thickness = thickness
            updatePaint()
            invalidate()
        }
    }

    private fun updatePaint() {
        paint.color = colorValue
        paint.strokeWidth = thickness
        paint.style = Paint.Style.STROKE
        paint.isAntiAlias = true
        paint.strokeCap = cap
    }

    fun setFirstPoint(point: PointF) {
        bezierLine.reset()
        bezierLine.addPoint(point.x, point.y)
        invalidate()
    }

    fun addNewPoint(point: PointF) {
        if (cap != Paint.Cap.ROUND) {
            bezierLine.addPointFiltered(point.x, point.y, thickness * 0.5f)
        } else {
            bezierLine.addPoint(point.x, point.y)
        }
        invalidate()
    }

    override fun render(rendererContext: RendererContext) {
        super.render(rendererContext)
        val canvas = rendererContext.canvas
        canvas.save()
        if (clipRect != null) {
            canvas.clipRect(clipRect)
        }

        val alpha = paint.alpha
        paint.alpha = rendererContext.getAlpha(alpha)

        paint.xfermode = rendererContext.maskPaint?.xfermode

        bezierLine.draw(canvas, paint)

        paint.alpha = alpha
        rendererContext.canvas.restore()
    }

    override fun hitTest(x: Float, y: Float): Boolean = false
}
