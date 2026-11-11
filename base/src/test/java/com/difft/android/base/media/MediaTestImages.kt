package com.difft.android.base.media

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertTrue
import java.io.File

internal fun writeImage(
    file: File,
    width: Int = 64,
    height: Int = 48,
    format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    configure: (Bitmap) -> Unit = { it.eraseColor(Color.rgb(30, 90, 160)) }
): File {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        configure(bitmap)
        file.outputStream().use { assertTrue(bitmap.compress(format, 95, it)) }
    } finally {
        bitmap.recycle()
    }
    return file
}
