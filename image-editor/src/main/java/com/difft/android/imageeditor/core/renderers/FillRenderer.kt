package com.difft.android.imageeditor.core.renderers

import android.graphics.Path
import android.graphics.RectF
import androidx.annotation.ColorInt
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import util.DimensionUnit
import com.difft.android.imageeditor.core.Bounds
import com.difft.android.imageeditor.core.Renderer
import com.difft.android.imageeditor.core.RendererContext

/**
 * Renders the [color] outside of the [Bounds].
 *
 * Hit tests outside of the bounds.
 */
@Parcelize
class FillRenderer(@ColorInt private val color: Int) : Renderer {

    @IgnoredOnParcel
    private val dst = RectF()

    @IgnoredOnParcel
    private val path = Path()

    override fun render(rendererContext: RendererContext) {
        rendererContext.canvas.save()

        rendererContext.mapRect(dst, Bounds.FULL_BOUNDS)
        rendererContext.canvasMatrix.setToIdentity()

        path.reset()
        path.addRoundRect(dst, DimensionUnit.DP.toPixels(18f), DimensionUnit.DP.toPixels(18f), Path.Direction.CW)

        rendererContext.canvas.clipPath(path)
        rendererContext.canvas.drawColor(color)
        rendererContext.canvas.restore()
    }

    override fun hitTest(x: Float, y: Float): Boolean = !Bounds.contains(x, y)
}
