package com.difft.android.imageeditor.core

import android.graphics.Matrix
import android.graphics.PointF
import com.difft.android.imageeditor.core.model.EditorElement

internal abstract class ElementEditSession(
    override val selected: EditorElement,
    private val inverseMatrix: Matrix
) : EditSession {

    val startPointElement: Array<PointF> = Array(2) { PointF() }
    val endPointElement: Array<PointF> = Array(2) { PointF() }
    val startPointScreen: Array<PointF> = Array(2) { PointF() }
    val endPointScreen: Array<PointF> = Array(2) { PointF() }

    fun setScreenStartPoint(p: Int, point: PointF) {
        startPointScreen[p] = point
        mapPoint(startPointElement[p], inverseMatrix, point)
    }

    fun setScreenEndPoint(p: Int, point: PointF) {
        endPointScreen[p] = point
        mapPoint(endPointElement[p], inverseMatrix, point)
    }

    override fun commit() {
        selected.commitEditorMatrix()
    }

    companion object {
        /**
         * Map src to dst using the matrix.
         *
         * @param dst    Output point.
         * @param matrix Matrix to transform point with.
         * @param src    Input point.
         */
        private fun mapPoint(dst: PointF, matrix: Matrix, src: PointF) {
            val input = floatArrayOf(src.x, src.y)
            val out = FloatArray(2)
            matrix.mapPoints(out, input)
            dst.set(out[0], out[1])
        }
    }
}
