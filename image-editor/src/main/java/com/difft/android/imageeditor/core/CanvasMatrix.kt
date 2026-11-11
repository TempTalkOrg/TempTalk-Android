package com.difft.android.imageeditor.core

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF











class CanvasMatrix(private val canvas: Canvas) {

    private val canvasMatrix = Matrix()
    private val temp = Matrix()
    private val stack: Array<Matrix> = Array(STACK_HEIGHT_LIMIT) { Matrix() }
    private var stackHeight = 0

    fun concat(matrix: Matrix) {
        canvas.concat(matrix)
        canvasMatrix.preConcat(matrix)
    }

    internal fun save() {
        canvas.save()
        if (stackHeight == STACK_HEIGHT_LIMIT) {
            throw AssertionError("Not enough space on stack")
        }
        stack[stackHeight++].set(canvasMatrix)
    }

    internal fun restore() {
        canvas.restore()
        canvasMatrix.set(stack[--stackHeight])
    }

    internal fun getCurrent(into: Matrix) {
        into.set(canvasMatrix)
    }

    fun setToIdentity() {
        if (canvasMatrix.invert(temp)) {
            concat(temp)
        }
    }

    fun initial(viewMatrix: Matrix) {
        concat(viewMatrix)
    }

    internal fun mapRect(dst: RectF, src: RectF): Boolean = canvasMatrix.mapRect(dst, src)

    fun mapPoints(dst: FloatArray, src: FloatArray) {
        canvasMatrix.mapPoints(dst, src)
    }

    fun copyTo(matrix: Matrix) {
        matrix.set(canvasMatrix)
    }

    companion object {
        private const val STACK_HEIGHT_LIMIT = 16
    }
}
