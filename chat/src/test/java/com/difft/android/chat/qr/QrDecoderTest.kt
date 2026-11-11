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







@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class QrDecoderTest {

    private val decoder = QrDecoder()

    private fun encode(content: String, size: Int = 512, ec: QrErrorCorrection = QrErrorCorrection.MEDIUM): Bitmap =
        QrEncoder.encode(content, size, Color.BLACK, errorCorrection = ec)
            ?: error("encode returned null for content of length ${content.length}")

    
    @Test
    fun `T6 - decodeBitmap returns the encoded simple string`() {
        val content = "https://x.test/abc"
        assertEquals(content, decoder.decodeBitmap(encode(content)))
    }

    
    @Test
    fun `T7 - decodeBitmap returns a dense long payload`() {
        
        val dense = "https://temptalk.app/invite?pi=" + "A1b2C3d4E5f6G7h8".repeat(20)
        assertEquals(dense, decoder.decodeBitmap(encode(dense, size = 768)))
    }

    
    @Test
    fun `T8 - decodeBitmap on a non-QR image returns null without throwing`() {
        val notQr = createBitmap(256, 256).apply { eraseColor(Color.GRAY) }
        assertNull(decoder.decodeBitmap(notQr))
    }

    
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

    
    @Test
    fun `T10 - high-EC qr with center logo still decodes`() {
        val url = "https://temptalk.app/invite?pi=" + "Zz9Yy8Xx7".repeat(8)
        val logo = createBitmap(48, 48).apply { eraseColor(Color.RED) }
        val withLogo = QrEncoder.encode(url, 512, Color.BLACK, errorCorrection = QrErrorCorrection.HIGH, logo = logo)
        assertNotNull(withLogo)
        assertEquals(url, decoder.decodeBitmap(withLogo!!))
    }

    
    @Test
    fun `T11 - GlobalHistogramBinarizer fallback path decodes a clean qr`() {
        
        
        
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
