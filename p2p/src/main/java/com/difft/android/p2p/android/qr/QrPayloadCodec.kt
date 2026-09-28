package com.difft.android.p2p.android.qr

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Base64
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
     * The payload bytes carried inside the legacy device-transfer link, or null when [raw] is not such
     * a link (the ordinary case — then it is the payload itself).
     *
     * iOS publishes its pairing code as `chative://transfer?version=1&…&migrationV2=<base64url(bytes)>`:
     * the legacy link an old iOS still understands, with the pairing payload appended. The encoding is
     * base64url without padding, so the padding is restored before decoding, and the alphabet is
     * checked first — `Uri` has already percent-decoded the value, and anything outside the alphabet
     * means this is not that parameter.
     *
     * Never logs the decoded bytes: the payload carries the transfer key.
     */
    private fun unwrapCompatibleEnvelope(raw: String): ByteArray? {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in PeerApps.ENVELOPE_SCHEMES) return null
        if (!PeerApps.ENVELOPE_HOST.equals(uri.host, ignoreCase = true)) return null
        val encoded = runCatching { uri.getQueryParameter(PeerApps.ENVELOPE_PAYLOAD_QUERY) }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        if (!encoded.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) {
            L.w { "[P2P][QR] envelope payload is not base64url" }
            return null
        }
        val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
        val bytes = runCatching { Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
        if (bytes == null || bytes.isEmpty() || bytes.size > MigrationQrWire.MAX_PAYLOAD_BYTES) {
            L.w { "[P2P][QR] envelope payload rejected (size=${bytes?.size})" }
            return null
        }
        return bytes
    }
}
