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

                        
                        if (!haveResult || abs(nextValueToTry - outOfBounds) < abs(successValue - outOfBounds)) {
                            haveResult = true
                            successValue = nextValueToTry
                            closestSuccessful.set(elementMatrix)
                        }
                    } else {
                        if (attempt == 1) {
                            
                            return null
                        }
                        outOfBounds = nextValueToTry
                    }
                } finally {
                    
                    elementMatrix.set(original)
                }

                nextValueToTry = (inBoundsValue + outOfBounds) / 2f
            } while (attempt < MAX_ITERATIONS && abs(inBoundsValue - outOfBounds) > ACCURACY)

            return if (haveResult) closestSuccessful else null
        }
    }
}
