package com.difft.android.chat.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer








class QrDecoder {

    
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            )
        )
    }

    
    fun decode(source: LuminanceSource): String? {
        
        
        runCatching { decodeWith(source, hybrid = true) }.getOrNull()?.let { return it } 
        return runCatching { decodeWith(source, hybrid = false) }.getOrNull()            
    }

    
    fun decodeBitmap(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return decode(RGBLuminanceSource(w, h, pixels))
    }

    private fun decodeWith(source: LuminanceSource, hybrid: Boolean): String? {
        val binarizer = if (hybrid) HybridBinarizer(source) else GlobalHistogramBinarizer(source)
        return try {
            reader.decodeWithState(BinaryBitmap(binarizer)).text
        } catch (e: NotFoundException) {
            null                       
        } finally {
            reader.reset()             
        }
    }
}
