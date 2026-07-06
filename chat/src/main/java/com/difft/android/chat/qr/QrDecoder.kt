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

/**
 * Decodes a single QR from any ZXing [LuminanceSource] (camera frame OR bitmap).
 *
 * NOT thread-safe — [MultiFormatReader] keeps per-decode state, so confine ONE instance to ONE
 * thread (the live analyzer pins one to its single analysis executor; the gallery path uses a
 * fresh throwaway instance on Dispatchers.IO). Never logs decoded contents at any level.
 */
class QrDecoder {

    // Reused across frames (hot loop). Confined to the owning thread.
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            )
        )
    }

    /** Core entry point. Returns decoded text, or null if no QR found (no throw on NotFound). */
    fun decode(source: LuminanceSource): String? {
        // runCatching also absorbs the rarer Format/ChecksumException a borderline frame can throw
        // from the hybrid pass, so the GlobalHistogramBinarizer fallback always gets a chance to run.
        runCatching { decodeWith(source, hybrid = true) }.getOrNull()?.let { return it } // best for uneven light
        return runCatching { decodeWith(source, hybrid = false) }.getOrNull()            // fallback: clean, evenly-lit
    }

    /** Convenience for the gallery / picked-image path. Off-main-thread safe. */
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
            null                       // normal per-frame "no QR" — never logged (would spam)
        } finally {
            reader.reset()             // clear per-decode state before reuse
        }
    }
}
