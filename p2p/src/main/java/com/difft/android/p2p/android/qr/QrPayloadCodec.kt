package com.difft.android.p2p.android.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.Base45
import com.difft.android.base.migration.MigrationQrWire
import com.difft.android.base.migration.PeerApps
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.qr.QrPayloadV2Codec
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The pairing code's text and image. The text is the v2 binary structure as Base45, which a QR
 * encodes in alphanumeric mode, and the same text travels in an intent between the two packages on
 * one device.
 *
 * A scanned code reaches [decode] either as the bare Base45 text or as the compatible envelope iOS
 * publishes (see [unwrapCompatibleEnvelope]); a Desktop login link is unwrapped by
 * `LinkMigrationInfo` into the bare text first. Both resolve through the one strict codec, so every
 * caller gets the same answer.
 */
object QrPayloadCodec {

    /**
     * Renders [payload] as a bare QR of about [sizePx] pixels (EC M, whole pixels per module, no
     * margin — the screen's white tile is the quiet zone). Null when the payload cannot be encoded.
     */
    fun encode(payload: QrPayload, sizePx: Int = 600): Bitmap? = runCatching {
        val text = encodeText(payload)
        // Size 0 = one pixel per module; scaled up below in whole pixels.
        val matrix = MultiFormatWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
        )
        val modules = matrix.width
        val scale = (sizePx / modules).coerceAtLeast(1)
        val side = modules * scale
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            val row = y * side
            val my = y / scale
            for (x in 0 until side) {
                pixels[row + x] = if (matrix[x / scale, my]) Color.BLACK else Color.WHITE
            }
        }
        createBitmap(side, side).also { it.setPixels(pixels, 0, side, 0, 0, side, side) }
    }.onFailure { L.e { "[P2P][QR] encode failed: ${it.message}" } }.getOrNull()

    /** The code's text content: Base45 of the v2 structure. Throws on a payload this build should never have built. */
    fun encodeText(payload: QrPayload): String = Base45.encode(QrPayloadV2Codec.encode(payload))

    /** Parses a scanned string into a [QrPayload]; null if it isn't a v2 pairing code in either shape. */
    fun decode(raw: String): QrPayload? {
        val text = MigrationQrWire.stripLineBreaks(raw)
        unwrapCompatibleEnvelope(text)?.let { return QrPayloadV2Codec.decode(it) }
        if (!Base45.isAlphabet(text)) return null
        val bytes = Base45.decode(text) ?: run {
            L.w { "[P2P][QR] bare code is not legal Base45 len=${text.length}" }
            return null
        }
        return QrPayloadV2Codec.decode(bytes)
    }

    /**
     * The payload bytes carried inside the legacy device-transfer link (see
     * [PeerApps.envelopePayloadBytes]), or null when [raw] is not such a link — the ordinary case, where
     * [raw] is the payload itself.
     */
    private fun unwrapCompatibleEnvelope(raw: String): ByteArray? {
        if (!PeerApps.looksLikeMigrationQr(raw) || MigrationQrWire.looksLikeBarePayload(raw)) return null
        return PeerApps.envelopePayloadBytes(raw).also {
            if (it == null) L.w { "[P2P][QR] envelope payload rejected" }
        }
    }
}
