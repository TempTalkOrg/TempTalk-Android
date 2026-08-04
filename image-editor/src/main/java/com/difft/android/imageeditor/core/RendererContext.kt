package com.difft.android.imageeditor.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import com.difft.android.imageeditor.core.model.EditorElement
import kotlin.math.max
import kotlin.math.min

/**
 * Contains all of the information required for a [Renderer] to do its job.
 *
 * Includes a [canvas], preconfigured with the correct matrix.
 *
 * The [canvasMatrix] should further matrix manipulation be required.
 */
class RendererContext(
    @JvmField val context: Context,
    @JvmField val canvas: Canvas,
    @JvmField val rendererReady: Ready,
    @JvmField val invalidate: Invalidate,
    @JvmField val typefaceProvider: TypefaceProvider
) {

    @JvmField
    val canvasMatrix: CanvasMatrix = CanvasMatrix(canvas)

    private var blockingLoad = false

    private var fade = 1f

    private var isEditing = true

    // Public var: mutated by EditorElement.draw() during the render pass. #1093
    var children: List<EditorElement> = emptyList()
    var maskPaint: Paint? = null

    fun setBlockingLoad(blockingLoad: Boolean) {
        this.blockingLoad = blockingLoad
    }

    /**
     * [Renderer]s generally run in the foreground but can load any data they require in the background.
     *
     * If they do so, they can use the [invalidate] callback when ready to inform the view it needs to be redrawn.
     *
     * However, when isBlockingLoad is true, the renderer is running in the background for the final render
     * and must load the data immediately and block the render until done so.
     */
    fun isBlockingLoad(): Boolean = blockingLoad

    fun mapRect(dst: RectF, src: RectF): Boolean = canvasMatrix.mapRect(dst, src)

    fun setIsEditing(isEditing: Boolean) {
        this.isEditing = isEditing
    }

    fun isEditing(): Boolean = isEditing

    fun setFade(fade: Float) {
        this.fade = fade
    }

    fun getAlpha(alpha: Int): Int = max(0, min(255, (fade * alpha).toInt()))

    /**
     * Persist the current state on to a stack, must be complimented by a call to [restore].
     */
    fun save() {
        canvasMatrix.save()
    }

    /**
     * Restore the current state from the stack, must match a call to [save].
     */
    fun restore() {
        canvasMatrix.restore()
    }

    fun getCurrent(into: Matrix) {
        canvasMatrix.getCurrent(into)
    }

    /**
     * Allows a RenderContext creator to specify which font to use for text on the fly.
     */
    interface TypefaceProvider {
        fun getSelectedTypeface(context: Context, renderer: Renderer, invalidate: Invalidate): Typeface
    }

    fun interface Ready {

        fun onReady(renderer: Renderer, cropMatrix: Matrix?, size: Point?)

        companion object {
            // Plain val: all consumers are Kotlin and resolve NULL directly. #1093
            val NULL = Ready { _, _, _ -> }
        }
    }

    fun interface Invalidate {

        fun onInvalidate(renderer: Renderer)

        companion object {
            // Plain val: all consumers are Kotlin and resolve NULL directly. #1093
            val NULL = Invalidate { _ -> }
        }
    }
}
