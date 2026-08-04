package com.difft.android.chat.scribbles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import com.difft.android.base.log.lumberjack.L
import java.util.Locale

/**
 * Detects faces with the built in Android face detection.
 */
internal class AndroidFaceDetector : FaceDetector {

    override fun detect(bitmap: Bitmap): List<FaceDetector.Face> {
        val source = bitmap
        val startTime = System.currentTimeMillis()

        L.d { TAG + String.format(Locale.US, "Bitmap format is %dx%d %s", source.width, source.height, source.config) }

        val createBitmap = source.config != Bitmap.Config.RGB_565 || source.width % 2 != 0
        val work: Bitmap

        if (createBitmap) {
            L.d { TAG + "Changing colour format to 565, with even width" }
            work = Bitmap.createBitmap(source.width and 0x1.inv(), source.height, Bitmap.Config.RGB_565)
            Canvas(work).drawBitmap(source, 0f, 0f, null)
        } else {
            work = source
        }

        try {
            val faceDetector = android.media.FaceDetector(work.width, work.height, MAX_FACES)
            val faces = arrayOfNulls<android.media.FaceDetector.Face>(MAX_FACES)
            val foundFaces = faceDetector.findFaces(work, faces)

            L.d { TAG + String.format(Locale.US, "Found %d faces", foundFaces) }

            return faces.take(foundFaces).map { faceToFace(it!!) }
        } finally {
            if (createBitmap) {
                work.recycle()
            }

            L.d { TAG + "Finished in " + (System.currentTimeMillis() - startTime) + " ms" }
        }
    }

    private class DefaultFace(
        override val bounds: RectF,
        override val confidence: Float
    ) : FaceDetector.Face {
        override val detectorClass: Class<out FaceDetector>
            get() = AndroidFaceDetector::class.java
    }

    companion object {
        private const val TAG = "AndroidFaceDetector"
        private const val MAX_FACES = 20

        private fun faceToFace(face: android.media.FaceDetector.Face): FaceDetector.Face {
            val point = PointF()
            face.getMidPoint(point)

            val halfWidth = face.eyesDistance() * 1.4f
            val yOffset = face.eyesDistance() * 0.4f
            val bounds = RectF(
                point.x - halfWidth,
                point.y - halfWidth + yOffset,
                point.x + halfWidth,
                point.y + halfWidth + yOffset
            )

            return DefaultFace(bounds, face.confidence())
        }
    }
}
