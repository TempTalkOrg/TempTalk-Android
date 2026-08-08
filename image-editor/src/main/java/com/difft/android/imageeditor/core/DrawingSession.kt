package com.difft.android.imageeditor.core

import android.graphics.Matrix
import android.graphics.PointF
import com.difft.android.imageeditor.core.model.EditorElement
import com.difft.android.imageeditor.core.renderers.BezierDrawingRenderer

/**
 * Passes touch events into a [BezierDrawingRenderer].
 */
internal class DrawingSession private constructor(
    selected: EditorElement,
    inverseMatrix: Matrix,
    private val renderer: BezierDrawingRenderer
) : ElementEditSession(selected, inverseMatrix) {

    override fun movePoint(p: Int, point: PointF) {
        if (p != 0) return
        setScreenEndPoint(p, point)
        renderer.addNewPoint(endPointElement[0])
    }

    override fun newPoint(newInverse: Matrix, point: PointF, p: Int): EditSession = this

    override fun removePoint(newInverse: Matrix, p: Int): EditSession = this

    companion object {
        fun start(element: EditorElement, renderer: BezierDrawingRenderer, inverseMatrix: Matrix, point: PointF): EditSession {
            val drawingSession = DrawingSession(element, inverseMatrix, renderer)
            drawingSession.setScreenStartPoint(0, point)
            renderer.setFirstPoint(drawingSession.startPointElement[0])
            return drawingSession
        }
    }
}
