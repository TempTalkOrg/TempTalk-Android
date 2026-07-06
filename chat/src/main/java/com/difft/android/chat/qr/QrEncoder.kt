package com.difft.android.chat.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.annotation.ColorInt
import androidx.core.graphics.createBitmap
import com.difft.android.base.log.lumberjack.L
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** EC level passed through to ZXing. HIGH survives a center logo (~30% damage tolerance). */
enum class QrErrorCorrection(internal val zxing: ErrorCorrectionLevel) {
    LOW(ErrorCorrectionLevel.L),
    MEDIUM(ErrorCorrectionLevel.M),
    QUARTILE(ErrorCorrectionLevel.Q),
    HIGH(ErrorCorrectionLevel.H),
}

/**
 * Stateless QR encoder: string -> square QR [Bitmap]. Fresh [MultiFormatWriter] per call, so it is
 * callable from any thread. Logs encode length, never the content (invite codes / device-link keys
 * are sensitive).
 */
object QrEncoder {

    private const val LOGO_RATIO = 0.20f   // logo edge ~= 20% of QR edge (matches BGA ~size/5)

    /**
     * Render [content] to a square [sizePx]x[sizePx] QR bitmap.
     * Returns null on failure (empty content, sizePx <= 0, writer error, OOM) — never throws.
     */
    fun encode(
        content: String,
        sizePx: Int,
        @ColorInt foreground: Int,
        @ColorInt background: Int = Color.WHITE,
        errorCorrection: QrErrorCorrection = QrErrorCorrection.MEDIUM,
        marginModules: Int = 1,
        logo: Bitmap? = null,
    ): Bitmap? {
        if (content.isEmpty() || sizePx <= 0) return null
        return try {
            val matrix = MultiFormatWriter().encode(
                content, BarcodeFormat.QR_CODE, sizePx, sizePx,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to errorCorrection.zxing,
                    EncodeHintType.MARGIN to marginModules,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
            val qr = matrix.toBitmap(foreground, background)
            if (logo != null) qr.drawCenteredLogo(logo) else qr
        } catch (e: Exception) {
            L.e { "[QrEncoder] encode failed size=$sizePx len=${content.length}: ${e.stackTraceToString()}" }
            null
        }
    }

    private fun BitMatrix.toBitmap(@ColorInt fg: Int, @ColorInt bg: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) pixels[row + x] = if (this[x, y]) fg else bg
        }
        return createBitmap(width, height).apply { setPixels(pixels, 0, width, 0, 0, width, height) }
    }

    private fun Bitmap.drawCenteredLogo(logo: Bitmap): Bitmap {
        val logoEdge = (width * LOGO_RATIO).toInt().coerceAtLeast(1)
        val left = (width - logoEdge) / 2
        val top = (height - logoEdge) / 2
        Canvas(this).drawBitmap(logo, null, Rect(left, top, left + logoEdge, top + logoEdge), null)
        return this
    }
}
