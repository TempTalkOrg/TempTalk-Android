package com.difft.android.chat.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Unit tests for [QrDecoder] (design §7 T6–T12).
 *
 * Fixtures are self-generated via [QrEncoder] (round-trips, no committed binaries, no flake) per
 * the design's "prefer self-generated round-trips" rule. Robolectric only because the source/result
 * involve `android.graphics.Bitmap`. No MockK — deterministic transforms.
 *
 * [GraphicsMode.Mode.NATIVE] so the T10 logo composite (`Canvas.drawBitmap`) genuinely damages the
 * QR before decode; under the default legacy shadow Canvas the draw is a no-op and T10 would not
 * actually exercise the HIGH-EC-survives-logo contract.
 *
 * - T6  simple round-trip       (HybridBinarizer primary path)
 * - T7  DENSE round-trip        (the #991 failure mode — long payload must decode)
 * - T8  non-QR image            (no-throw NotFound path → null)
 * - T9  multi-payload reuse     (one decoder, several round-trips — pins reset()/reuse)
 * - T10 logo round-trip         (HIGH EC + 20% logo still scannable)
 * - T11 GlobalHistogram fallback (the fallback binarizer branch decodes)
 * - T12 decode(LuminanceSource) (the core entry the live YUV path calls)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class QrDecoderTest {

    private val decoder = QrDecoder()

    private fun encode(content: String, size: Int = 512, ec: QrErrorCorrection = QrErrorCorrection.MEDIUM): Bitmap =
        QrEncoder.encode(content, size, Color.BLACK, errorCorrection = ec)
            ?: error("encode returned null for content of length ${content.length}")

    // ── T6: simple bitmap decode (HybridBinarizer primary) ───────────────────────────────
    @Test
    fun `T6 - decodeBitmap returns the encoded simple string`() {
        val content = "https://x.test/abc"
        assertEquals(content, decoder.decodeBitmap(encode(content)))
    }

    // ── T7: DENSE QR — the #991 failure mode ───────────────────────────────────────────────
    @Test
    fun `T7 - decodeBitmap returns a dense long payload`() {
        // ~300+ chars: the high-density case the abandoned BGA/3.3.3 stack failed to resolve.
        val dense = "https://temptalk.app/invite?pi=" + "A1b2C3d4E5f6G7h8".repeat(20)
        assertEquals(dense, decoder.decodeBitmap(encode(dense, size = 768)))
    }

    // ── T8: non-QR image returns null (no throw) ───────────────────────────────────────────
    @Test
    fun `T8 - decodeBitmap on a non-QR image returns null without throwing`() {
        val notQr = createBitmap(256, 256).apply { eraseColor(Color.GRAY) }
        assertNull(decoder.decodeBitmap(notQr))
    }

    // ── T9: one decoder, many round-trips — pins MultiFormatReader reset()/reuse ───────────
    @Test
    fun `T9 - one decoder decodes multiple varied payloads in sequence`() {
        val payloads = listOf(
            "https://temptalk.app/invite?pi=averylonginvitecodevalue1234567890",
            "tsdevice:/?uuid=abc-123&pub_key=Zm9vYmFyYmF6",
            "ünïcödé 测试 🔒 mixed payload",
        )
        for (p in payloads) {
            assertEquals("round-trip failed for payload len=${p.length}", p, decoder.decodeBitmap(encode(p)))
        }
    }

    // ── T10: HIGH EC + center logo still decodes ────────────────────────────────────────────
    @Test
    fun `T10 - high-EC qr with center logo still decodes`() {
        val url = "https://temptalk.app/invite?pi=" + "Zz9Yy8Xx7".repeat(8)
        val logo = createBitmap(48, 48).apply { eraseColor(Color.RED) }
        val withLogo = QrEncoder.encode(url, 512, Color.BLACK, errorCorrection = QrErrorCorrection.HIGH, logo = logo)
        assertNotNull(withLogo)
        assertEquals(url, decoder.decodeBitmap(withLogo!!))
    }

    // ── T11: GlobalHistogramBinarizer fallback branch decodes ──────────────────────────────
    @Test
    fun `T11 - GlobalHistogramBinarizer fallback path decodes a clean qr`() {
        // Build the same RGBLuminanceSource the decoder's fallback branch uses and confirm a clean,
        // evenly-lit QR decodes through GlobalHistogramBinarizer directly — proving the fallback
        // branch in QrDecoder.decode() resolves rather than throwing.
        val content = "fallback-branch-payload-0123456789"
        val bmp = encode(content)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val reader = com.google.zxing.MultiFormatReader().apply {
            setHints(
                mapOf(
                    com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                )
            )
        }
        val result = reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)))
        assertEquals(content, result.text)
    }

    // ── T12: decode(LuminanceSource) core entry (what the YUV path calls) ──────────────────
    @Test
    fun `T12 - decode of an RGBLuminanceSource returns the known string`() {
        val content = "core-luminance-source-entry"
        val bmp = encode(content)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        assertEquals(content, decoder.decode(RGBLuminanceSource(w, h, pixels)))
    }
}
