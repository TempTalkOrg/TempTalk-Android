package com.difft.android.imageeditor.core.renderers

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.annotation.ColorInt
import androidx.core.content.res.ResourcesCompat
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import com.difft.android.imageeditor.R
import com.difft.android.imageeditor.core.Bounds
import com.difft.android.imageeditor.core.Renderer
import com.difft.android.imageeditor.core.RendererContext

/**
 * Renders a box outside of the current crop area using [R.color.crop_area_renderer_outer_color]
 * and around the edge it renders the markers for the thumbs using [R.color.crop_area_renderer_edge_color],
 * [R.dimen.crop_area_renderer_edge_thickness] and [R.dimen.crop_area_renderer_edge_size].
 *
 * Hit tests outside of the bounds.
 */
@Parcelize
class CropAreaRenderer(
    @ColorInt private val color: Int,
    private val renderCenterThumbs: Boolean
) : Renderer {

    @IgnoredOnParcel
    private val cropClipPath = Path()

    @IgnoredOnParcel
    private val screenClipPath = Path()

    @IgnoredOnParcel
    private val dst = RectF()

    @IgnoredOnParcel
    private val paint = Paint()

    init {
        cropClipPath.toggleInverseFillType()
        cropClipPath.moveTo(Bounds.LEFT, Bounds.TOP)
        cropClipPath.lineTo(Bounds.RIGHT, Bounds.TOP)
        cropClipPath.lineTo(Bounds.RIGHT, Bounds.BOTTOM)
        cropClipPath.lineTo(Bounds.LEFT, Bounds.BOTTOM)
        cropClipPath.close()
        screenClipPath.toggleInverseFillType()
    }

    override fun render(rendererContext: RendererContext) {
        rendererContext.save()

        val canvas = rendererContext.canvas
        val resources = rendererContext.context.resources

        canvas.clipPath(cropClipPath)
        canvas.drawColor(color)

        rendererContext.mapRect(dst, Bounds.FULL_BOUNDS)

        val thickness = resources.getDimensionPixelSize(R.dimen.crop_area_renderer_edge_thickness)
        val size = minOf(
            resources.getDimensionPixelSize(R.dimen.crop_area_renderer_edge_size).toFloat(),
            minOf(dst.width(), dst.height()) / 3f - 10
        ).toInt()

        paint.color = ResourcesCompat.getColor(resources, R.color.crop_area_renderer_edge_color, null)

        rendererContext.canvasMatrix.setToIdentity()
        screenClipPath.reset()
        screenClipPath.moveTo(dst.left, dst.top)
        screenClipPath.lineTo(dst.right, dst.top)
        screenClipPath.lineTo(dst.right, dst.bottom)
        screenClipPath.lineTo(dst.left, dst.bottom)
        screenClipPath.close()
        canvas.clipPath(screenClipPath)
        canvas.translate(dst.left, dst.top)

        val halfDx = (dst.right - dst.left - size + thickness) / 2
        val halfDy = (dst.bottom - dst.top - size + thickness) / 2

        canvas.drawRect(-thickness.toFloat(), -thickness.toFloat(), size.toFloat(), size.toFloat(), paint)

        canvas.translate(0f, halfDy)
        if (renderCenterThumbs) canvas.drawRect(-thickness.toFloat(), -thickness.toFloat(), size.toFloat(), size.toFloat(), paint)

        canvas.translate(0f, halfDy)
        canvas.drawRect(-thickness.toFloat(), -thickness.toFloat(), size.toFloat(), size.toFloat(), paint)

        canvas.translate(halfDx, 0f)
        if (renderCenterThumbs) canvas.drawRect(-thickness.toFloat(), -thickness.toFloat(), size.toFloat(), size.toFloat(), paint)

        canvas.translate(halfDx, 0f)
        canvas.drawRect(-thickness.toFloat(), -thickness.toFloat(), size.toFloat(), size.toFloat(), paint)

        canvas.translate(0f, -halfDy)
        if (renderCenterThumbs) canvas.drawRect(-thickness.toFloat(), -thickness.toFloat(), size.toFloat(), size.toFloat(), paint)

        canvas.translate(0f, -halfDy)
        canvas.drawRect(-thickness.toFloat(), -thickness.toFloat(), size.toFloat(), size.toFloat(), paint)

        canvas.translate(-halfDx, 0f)
        if (renderCenterThumbs) canvas.drawRect(-thickness.toFloat(), -thickness.toFloat(), size.toFloat(), size.toFloat(), paint)

        rendererContext.restore()
    }

    override fun hitTest(x: Float, y: Float): Boolean = !Bounds.contains(x, y)
}
