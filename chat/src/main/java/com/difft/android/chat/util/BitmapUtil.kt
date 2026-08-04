package com.difft.android.chat.util

import android.graphics.Bitmap
import androidx.annotation.WorkerThread
import java.io.ByteArrayOutputStream

object BitmapUtil {

    @JvmStatic
    @WorkerThread
    fun createScaledBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) {
            return bitmap
        }

        if (maxWidth <= 0 || maxHeight <= 0) {
            return bitmap
        }

        var newWidth = maxWidth
        var newHeight = maxHeight

        val widthRatio = bitmap.width / maxWidth.toFloat()
        val heightRatio = bitmap.height / maxHeight.toFloat()

        if (widthRatio > heightRatio) {
            newHeight = (bitmap.height / widthRatio).toInt()
        } else {
            newWidth = (bitmap.width / heightRatio).toInt()
        }

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    @JvmStatic
    fun toByteArray(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null) return null
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
