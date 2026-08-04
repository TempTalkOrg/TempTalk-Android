package com.difft.android.imageeditor.core.model

import android.graphics.Matrix
import kotlin.math.abs

class Bisect private constructor() {

    fun interface Predicate {
        fun test(): Boolean
    }

    fun interface ModifyElement {
        fun applyFactor(matrix: Matrix, factor: Float)
    }

    companion object {
        const val ACCURACY = 0.001f

        private const val MAX_ITERATIONS = 16

        /**
         * Given a predicate function, attempts to finds the boundary between predicate true and predicate false.
         * If it returns true, it will animate the element to the closest true value found to that boundary.
         *
         * @param element          The element to modify.
         * @param outOfBoundsValue The current value, known to be out of bounds. 1 for a scale and 0 for a translate.
         * @param atMost           A value believed to be in bounds.
         * @param predicate        The out of bounds predicate.
         * @param modifyElement    Apply the latest value to the element local matrix.
         * @param invalidate       For animation if finds a result.
         * @return true iff finds a result.
         */
        @JvmStatic
        fun bisectToTest(
            element: EditorElement,
            outOfBoundsValue: Float,
            atMost: Float,
            predicate: Predicate,
            modifyElement: ModifyElement,
            invalidate: Runnable
        ): Boolean {
            val closestSuccesful = bisectToTest(element, outOfBoundsValue, atMost, predicate, modifyElement)

            return if (closestSuccesful != null) {
                element.animateLocalTo(closestSuccesful, invalidate)
                true
            } else {
                false
            }
        }

        /**
         * Given a predicate function, attempts to finds the boundary between predicate true and predicate false.
         * Returns new local matrix for the element if a solution is found.
         *
         * @param element          The element to modify.
         * @param outOfBoundsValue The current value, known to be out of bounds. 1 for a scale and 0 for a translate.
         * @param atMost           A value believed to be in bounds.
         * @param predicate        The out of bounds predicate.
         * @param modifyElement    Apply the latest value to the element local matrix.
         * @return matrix to replace local matrix iff finds a result, null otherwise.
         */
        @JvmStatic
        fun bisectToTest(
            element: EditorElement,
            outOfBoundsValue: Float,
            atMost: Float,
            predicate: Predicate,
            modifyElement: ModifyElement
        ): Matrix? {
            var outOfBounds = outOfBoundsValue
            val elementMatrix = element.localMatrix
            val original = Matrix(elementMatrix)
            val closestSuccessful = Matrix()
            var haveResult = false
            var attempt = 0
            var successValue = 0f
            var inBoundsValue = atMost
            var nextValueToTry = inBoundsValue

            do {
                attempt++

                modifyElement.applyFactor(elementMatrix, nextValueToTry)
                try {
                    if (predicate.test()) {
                        inBoundsValue = nextValueToTry

                        // if first success or closer to out of bounds than the current closest
                        if (!haveResult || abs(nextValueToTry - outOfBounds) < abs(successValue - outOfBounds)) {
                            haveResult = true
                            successValue = nextValueToTry
                            closestSuccessful.set(elementMatrix)
                        }
                    } else {
                        if (attempt == 1) {
                            // failure on first attempt means inBoundsValue is actually out of bounds and so no solution
                            return null
                        }
                        outOfBounds = nextValueToTry
                    }
                } finally {
                    // reset
                    elementMatrix.set(original)
                }

                nextValueToTry = (inBoundsValue + outOfBounds) / 2f
            } while (attempt < MAX_ITERATIONS && abs(inBoundsValue - outOfBounds) > ACCURACY)

            return if (haveResult) closestSuccessful else null
        }
    }
}
