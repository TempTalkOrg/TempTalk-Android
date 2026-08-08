package com.difft.android.imageeditor.core

import android.graphics.Matrix
import kotlin.math.atan2
import kotlin.math.sqrt

class MatrixUtils {

    companion object {

        private val tempMatrixValues = ThreadLocal<FloatArray>()

        @JvmStatic
        fun getTempMatrixValues(): FloatArray {
            var floats = tempMatrixValues.get()
            if (floats == null) {
                floats = FloatArray(9)
                tempMatrixValues.set(floats)
            }
            return floats
        }

        /**
         * Extracts the angle from a matrix in radians.
         */
        @JvmStatic
        fun getRotationAngle(matrix: Matrix): Float {
            val matrixValues = getTempMatrixValues()
            matrix.getValues(matrixValues)
            return (-atan2(matrixValues[Matrix.MSKEW_X].toDouble(), matrixValues[Matrix.MSCALE_X].toDouble())).toFloat()
        }

        /** Gets the scale on the X axis */
        @JvmStatic
        fun getScaleX(matrix: Matrix): Float {
            val matrixValues = getTempMatrixValues()
            matrix.getValues(matrixValues)
            val scaleX = matrixValues[Matrix.MSCALE_X]
            val skewX = matrixValues[Matrix.MSKEW_X]
            return sqrt(scaleX * scaleX + skewX * skewX)
        }
    }
}
