package com.difft.android.imageeditor.core

import android.graphics.Matrix
import android.graphics.RectF
import com.difft.android.imageeditor.core.model.EditorElement

/**
 * The local extent of a [EditorElement].
 * i.e. all [EditorElement]s have a bounding rectangle from:
 *
 * [LEFT] to [RIGHT] and from [TOP] to [BOTTOM].
 */
class Bounds {

    companion object {

        const val LEFT = -1000f
        const val RIGHT = 1000f

        const val TOP = -1000f
        const val BOTTOM = 1000f

        const val CENTRE_X = (LEFT + RIGHT) / 2f
        const val CENTRE_Y = (TOP + BOTTOM) / 2f

        @JvmField
        val CENTRE = floatArrayOf(CENTRE_X, CENTRE_Y)

        private val POINTS = floatArrayOf(
            LEFT, TOP,
            RIGHT, TOP,
            RIGHT, BOTTOM,
            LEFT, BOTTOM
        )

        @JvmStatic
        fun newFullBounds(): RectF = RectF(LEFT, TOP, RIGHT, BOTTOM)

        @JvmField
        val FULL_BOUNDS: RectF = newFullBounds()

        @JvmStatic
        fun contains(x: Float, y: Float): Boolean =
            x >= FULL_BOUNDS.left && x <= FULL_BOUNDS.right &&
                y >= FULL_BOUNDS.top && y <= FULL_BOUNDS.bottom

        /**
         * Maps all the points of bounds with the supplied matrix and determines whether they are still in bounds.
         *
         * @param matrix matrix to transform points by, null is treated as identity.
         * @return true iff all points remain in bounds after transformation.
         */
        @JvmStatic
        fun boundsRemainInBounds(matrix: Matrix?): Boolean {
            if (matrix == null) return true

            val dst = FloatArray(POINTS.size)

            matrix.mapPoints(dst, POINTS)

            return allWithinBounds(dst)
        }

        private fun allWithinBounds(points: FloatArray): Boolean {
            var allHit = true

            for (i in 0 until points.size / 2) {
                val x = points[2 * i]
                val y = points[2 * i + 1]

                if (!contains(x, y)) {
                    allHit = false
                    break
                }
            }

            return allHit
        }
    }
}
