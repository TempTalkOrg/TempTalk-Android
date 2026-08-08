package com.difft.android.chat.scribbles

import android.graphics.Bitmap
import android.graphics.RectF

internal interface FaceDetector {
    fun detect(bitmap: Bitmap): List<Face>

    interface Face {
        val bounds: RectF
        val detectorClass: Class<out FaceDetector>
        val confidence: Float
    }
}
