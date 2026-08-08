package com.difft.android.imageeditor.core.model

import android.graphics.Matrix
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import com.difft.android.imageeditor.R
import com.difft.android.imageeditor.core.Bounds
import com.difft.android.imageeditor.core.Renderer
import com.difft.android.imageeditor.core.RendererContext
import java.util.UUID

/**
 * Hit tests a circle that is [R.dimen.crop_area_renderer_edge_size] in radius on the screen.
 *
 * Does not draw anything.
 */
@Parcelize
class CropThumbRenderer(
    override val controlPoint: ThumbRenderer.ControlPoint,
    @TypeParceler<UUID, UuidParceler>() val toControl: UUID
) : Renderer, ThumbRenderer {

    @IgnoredOnParcel
    private val centreOnScreen = FloatArray(2)

    @IgnoredOnParcel
    private val matrix = Matrix()

    @IgnoredOnParcel
    private var size = 0

    override val elementToControl: UUID
        get() = toControl

    override fun render(rendererContext: RendererContext) {
        rendererContext.canvasMatrix.mapPoints(centreOnScreen, Bounds.CENTRE)
        rendererContext.canvasMatrix.copyTo(matrix)
        size = rendererContext.context.resources.getDimensionPixelSize(R.dimen.crop_area_renderer_edge_size)
    }

    override fun hitTest(x: Float, y: Float): Boolean {
        val hitPointOnScreen = FloatArray(2)
        matrix.mapPoints(hitPointOnScreen, floatArrayOf(x, y))

        val dx = centreOnScreen[0] - hitPointOnScreen[0]
        val dy = centreOnScreen[1] - hitPointOnScreen[1]

        return dx * dx + dy * dy < size * size
    }
}
