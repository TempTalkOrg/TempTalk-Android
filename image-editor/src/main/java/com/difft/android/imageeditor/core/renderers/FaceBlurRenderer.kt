package com.difft.android.imageeditor.core.renderers

import kotlinx.parcelize.Parcelize
import com.difft.android.imageeditor.core.Bounds
import com.difft.android.imageeditor.core.Renderer
import com.difft.android.imageeditor.core.RendererContext




@Parcelize
class FaceBlurRenderer : Renderer {

    override fun render(rendererContext: RendererContext) {
        rendererContext.canvas.drawRect(Bounds.FULL_BOUNDS, rendererContext.maskPaint!!)
    }

    override fun hitTest(x: Float, y: Float): Boolean = Bounds.FULL_BOUNDS.contains(x, y)
}
