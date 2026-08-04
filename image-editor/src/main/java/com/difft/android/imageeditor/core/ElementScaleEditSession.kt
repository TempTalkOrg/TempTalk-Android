package com.difft.android.imageeditor.core

import android.graphics.Matrix
import android.graphics.PointF
import com.difft.android.imageeditor.core.model.EditorElement
import kotlin.math.atan2
import kotlin.math.sqrt

internal class ElementScaleEditSession private constructor(
    selected: EditorElement,
    inverseMatrix: Matrix
) : ElementEditSession(selected, inverseMatrix) {

    override fun movePoint(p: Int, point: PointF) {
        setScreenEndPoint(p, point)
        val editorMatrix = selected.editorMatrix

        editorMatrix.reset()

        if (selected.flags.isAspectLocked()) {
            val scale = findScale(startPointElement, endPointElement).toFloat()

            editorMatrix.postTranslate(-startPointElement[0].x, -startPointElement[0].y)
            editorMatrix.postScale(scale, scale)

            val angle = angle(endPointElement[0], endPointElement[1]) - angle(startPointElement[0], startPointElement[1])

            if (!selected.flags.isRotateLocked()) {
                editorMatrix.postRotate(Math.toDegrees(angle).toFloat())
            }

            editorMatrix.postTranslate(endPointElement[0].x, endPointElement[0].y)
        } else {
            editorMatrix.postTranslate(-startPointElement[0].x, -startPointElement[0].y)

            val scaleX = (endPointElement[1].x - endPointElement[0].x) / (startPointElement[1].x - startPointElement[0].x)
            val scaleY = (endPointElement[1].y - endPointElement[0].y) / (startPointElement[1].y - startPointElement[0].y)

            editorMatrix.postScale(scaleX, scaleY)

            editorMatrix.postTranslate(endPointElement[0].x, endPointElement[0].y)
        }
    }

    override fun newPoint(newInverse: Matrix, point: PointF, p: Int): EditSession = this

    override fun removePoint(newInverse: Matrix, p: Int): EditSession? = convertToDrag(p, newInverse)

    private fun convertToDrag(p: Int, inverse: Matrix): ElementDragEditSession? =
        ElementDragEditSession.startDrag(selected, inverse, endPointScreen[1 - p])

    companion object {
        fun startScale(session: ElementDragEditSession, inverseMatrix: Matrix, point: PointF, p: Int): ElementScaleEditSession {
            session.commit()
            val newSession = ElementScaleEditSession(session.selected, inverseMatrix)
            newSession.setScreenStartPoint(1 - p, session.endPointScreen[0])
            newSession.setScreenEndPoint(1 - p, session.endPointScreen[0])
            newSession.setScreenStartPoint(p, point)
            newSession.setScreenEndPoint(p, point)
            return newSession
        }

        private fun angle(a: PointF, b: PointF): Double = atan2((a.y - b.y).toDouble(), (a.x - b.x).toDouble())

        /**
         * Find relative distance between an old and new set of Points.
         *
         * @param from Pair of points.
         * @param to   New pair of points.
         * @return Scale
         */
        private fun findScale(from: Array<PointF>, to: Array<PointF>): Double {
            val originalD2 = getDistanceSquared(from[0], from[1])
            val newD2 = getDistanceSquared(to[0], to[1])
            return sqrt((newD2 / originalD2).toDouble())
        }

        /**
         * Distance between two points squared.
         */
        private fun getDistanceSquared(a: PointF, b: PointF): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return dx * dx + dy * dy
        }
    }
}
