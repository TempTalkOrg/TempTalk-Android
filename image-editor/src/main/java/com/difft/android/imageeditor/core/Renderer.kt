package com.difft.android.imageeditor.core

import android.os.Parcelable
import com.difft.android.imageeditor.core.model.EditorElement

/**
 * Responsible for rendering a single [EditorElement] to the canvas.
 *
 * Because it knows the most about the whereabouts of the image it is also responsible for hit detection.
 */
interface Renderer : Parcelable {

    /**
     * Draw self to the context.
     *
     * @param rendererContext The context to draw to.
     */
    fun render(rendererContext: RendererContext)

    /**
     * @param x Local coordinate X
     * @param y Local coordinate Y
     * @return true iff hit.
     */
    fun hitTest(x: Float, y: Float): Boolean
}
