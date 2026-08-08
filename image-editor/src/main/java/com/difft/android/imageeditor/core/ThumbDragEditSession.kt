package com.difft.android.imageeditor.core

import android.graphics.Matrix
import android.graphics.PointF
import com.difft.android.imageeditor.core.model.EditorElement
import com.difft.android.imageeditor.core.model.ThumbRenderer
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

internal class ThumbDragEditSession private constructor(
    selected: EditorElement,
    private val controlPoint: ThumbRenderer.ControlPoint,
    inverseMatrix: Matrix,
    private val thumbContainerRelativeMatrix: Matrix
) : ElementEditSession(selected, inverseMatrix) {

    private val oppositeControlPoint = PointF()
    private val oppositeControlPointOnControlParent = FloatArray(2)
    private val oppositeControlPointOnElement = FloatArray(2)

    override fun movePoint(p: Int, point: PointF) {
        setScreenEndPoint(p, point)

        val editorMatrix = selected.editorMatrix

        editorMatrix.reset()

        // Think of this process as a pinch to zoom/rotate, one finger being on the control point being manipulated, and the other on its opposite.
        // Even if the opposite thumb doesn't exist on the tree, the position it would be at gives the virtual second finger position for the pinch.

        // The opposite control point needs an additional mapping to put it in to the same coordinate system as the dragged thumb
        oppositeControlPointOnControlParent[0] = controlPoint.opposite().x
        oppositeControlPointOnControlParent[1] = controlPoint.opposite().y
        thumbContainerRelativeMatrix.mapPoints(oppositeControlPointOnElement, oppositeControlPointOnControlParent)
        val x = oppositeControlPointOnElement[0]
        val y = oppositeControlPointOnElement[1]
        oppositeControlPoint.set(x, y)

        val dx = endPointElement[0].x - startPointElement[0].x
        val dy = endPointElement[0].y - startPointElement[0].y

        val xEnd = controlPoint.x + dx
        val yEnd = controlPoint.y + dy

        if (controlPoint.isScaleAndRotateThumb()) {
            val scale = findScale(oppositeControlPoint, startPointElement[0], endPointElement[0])
            editorMatrix.postTranslate(-oppositeControlPoint.x, -oppositeControlPoint.y)
            editorMatrix.postScale(scale, scale)
            val angle = angle(endPointElement[0], oppositeControlPoint) - angle(startPointElement[0], oppositeControlPoint)
            rotate(editorMatrix, angle)
            editorMatrix.postTranslate(oppositeControlPoint.x, oppositeControlPoint.y)
        } else {
            // 8 point controls, where edges scale in just one dimension and corners scale in both, optionally fixed aspect ratio
            val aspectLocked = selected.flags.isAspectLocked() && !controlPoint.isCenter()
            val defaultScale = if (aspectLocked) 2f else 1f
            val scaleX = if (controlPoint.isVerticalCenter()) defaultScale else (xEnd - x) / (controlPoint.x - x)
            val scaleY = if (controlPoint.isHorizontalCenter()) defaultScale else (yEnd - y) / (controlPoint.y - y)

            scale(editorMatrix, aspectLocked, scaleX, scaleY, controlPoint.opposite())
        }
    }

    override fun newPoint(newInverse: Matrix, point: PointF, p: Int): EditSession? = null

    override fun removePoint(newInverse: Matrix, p: Int): EditSession? = null

    companion object {
        fun startDrag(
            selected: EditorElement,
            inverseViewModelMatrix: Matrix,
            thumbContainerRelativeMatrix: Matrix,
            controlPoint: ThumbRenderer.ControlPoint,
            point: PointF
        ): EditSession? {
            if (!selected.flags.isEditable()) return null

            val elementDragEditSession = ThumbDragEditSession(selected, controlPoint, inverseViewModelMatrix, thumbContainerRelativeMatrix)
            elementDragEditSession.setScreenStartPoint(0, point)
            elementDragEditSession.setScreenEndPoint(0, point)
            return elementDragEditSession
        }

        private fun scale(editorMatrix: Matrix, aspectLocked: Boolean, scaleX: Float, scaleY: Float, around: ThumbRenderer.ControlPoint) {
            val x = around.x
            val y = around.y
            editorMatrix.postTranslate(-x, -y)
            if (aspectLocked) {
                val minScale = min(scaleX, scaleY)
                editorMatrix.postScale(minScale, minScale)
            } else {
                editorMatrix.postScale(scaleX, scaleY)
            }
            editorMatrix.postTranslate(x, y)
        }

        private fun rotate(editorMatrix: Matrix, angle: Double) {
            editorMatrix.postRotate(Math.toDegrees(angle).toFloat())
        }

        private fun angle(a: PointF, b: PointF): Double = atan2((a.y - b.y).toDouble(), (a.x - b.x).toDouble())

        /**
         * Find relative distance between an old and new Point relative to an anchor.
         *
         * ```
         * |to - anchor| / |from - anchor|
         * ```
         *
         * @param anchor Fixed point.
         * @param from   Starting point.
         * @param to     Ending point.
         * @return Scale required to scale a line anchor->from to reach the to point from anchor.
         */
        private fun findScale(anchor: PointF, from: PointF, to: PointF): Float {
            val originalD2 = getDistanceSquared(from, anchor)
            val newD2 = getDistanceSquared(to, anchor)
            return sqrt((newD2 / originalD2).toDouble()).toFloat()
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
