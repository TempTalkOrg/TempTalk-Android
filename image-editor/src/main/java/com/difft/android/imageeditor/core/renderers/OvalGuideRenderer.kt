package com.difft.android.imageeditor.core.renderers

import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import com.difft.android.imageeditor.R
import com.difft.android.imageeditor.core.Bounds
import com.difft.android.imageeditor.core.Renderer
import com.difft.android.imageeditor.core.RendererContext

/**
 * Renders an oval inside of the [Bounds].
 *
 * Hit tests outside of the bounds.
 */
@Parcelize
class OvalGuideRenderer(@ColorRes private val ovalGuideColor: Int) : Renderer {

    @IgnoredOnParcel
    private val paint = Paint()

    @IgnoredOnParcel
    private val dst = RectF()

    init {
        paint.style = Paint.Style.STROKE
        paint.isAntiAlias = true
    }

    override fun render(rendererContext: RendererContext) {
        rendererContext.save()

        val canvas = rendererContext.canvas
        val context = rendererContext.context
        val stroke = context.resources.getDimensionPixelSize(R.dimen.oval_guide_stroke_width)
        val halfStroke = stroke / 2f

        paint.strokeWidth = stroke.toFloat()
        paint.color = ContextCompat.getColor(context, ovalGuideColor)

        rendererContext.mapRect(dst, Bounds.FULL_BOUNDS)
        dst.set(dst.left + halfStroke, dst.top + halfStroke, dst.right - halfStroke, dst.bottom - halfStroke)

        rendererContext.canvasMatrix.setToIdentity()
        canvas.drawOval(dst, paint)

        rendererContext.restore()
    }

    override fun hitTest(x: Float, y: Float): Boolean = !Bounds.contains(x, y)
}
