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

    
    var children: List<EditorElement> = emptyList()
    var maskPaint: Paint? = null

    fun setBlockingLoad(blockingLoad: Boolean) {
        this.blockingLoad = blockingLoad
    }

    







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

    


    fun save() {
        canvasMatrix.save()
    }

    


    fun restore() {
        canvasMatrix.restore()
    }

    fun getCurrent(into: Matrix) {
        canvasMatrix.getCurrent(into)
    }

    


    interface TypefaceProvider {
        fun getSelectedTypeface(context: Context, renderer: Renderer, invalidate: Invalidate): Typeface
    }

    fun interface Ready {

        fun onReady(renderer: Renderer, cropMatrix: Matrix?, size: Point?)

        companion object {
            
            val NULL = Ready { _, _, _ -> }
        }
    }

    fun interface Invalidate {

        fun onInvalidate(renderer: Renderer)

        companion object {
            
            val NULL = Invalidate { _ -> }
        }
    }
}
