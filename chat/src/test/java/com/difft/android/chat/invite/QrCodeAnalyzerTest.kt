package com.difft.android.chat.invite

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import com.difft.android.chat.qr.QrEncoder
import com.google.zxing.PlanarYUVLuminanceSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer





@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class QrCodeAnalyzerTest {

    



    private fun lumaBuffer(bmp: Bitmap, rowStride: Int): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val data = ByteArray(rowStride * h)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                
                val argb = pixels[y * w + x]
                val luma = (0.299 * Color.red(argb) + 0.587 * Color.green(argb) + 0.114 * Color.blue(argb)).toInt()
                data[y * rowStride + x] = luma.toByte()
            }
            
        }
        return data
    }

    
    private fun fakeImageProxy(luma: ByteArray, rowStride: Int, w: Int, h: Int): ImageProxy {
        val plane = mockk<ImageProxy.PlaneProxy>(relaxed = true)
        every { plane.buffer } returns ByteBuffer.wrap(luma)
        every { plane.rowStride } returns rowStride
        val image = mockk<ImageProxy>(relaxed = true)
        every { image.planes } returns arrayOf(plane)
        every { image.width } returns w
        every { image.height } returns h
        return image
    }

    private fun encode(content: String, size: Int = 512): Bitmap =
        QrEncoder.encode(content, size, Color.BLACK)
            ?: error("encode returned null for content of length ${content.length}")

    
    @Test
    fun `T13 - analyze decodes a QR frame, fires onDecoded once and closes the image`() {
        val content = "https://x.test/abc"
        val bmp = encode(content)
        val image = fakeImageProxy(lumaBuffer(bmp, bmp.width), bmp.width, bmp.width, bmp.height)
        val results = mutableListOf<String>()
        val analyzer = QrCodeAnalyzer { results.add(it) }

        analyzer.analyze(image)

        assertEquals(listOf(content), results)
        verify(exactly = 1) { image.close() }
    }

    
    @Test
    fun `T14 - feeding the same decodable frame twice fires onDecoded only once`() {
        val content = "https://x.test/dup"
        val bmp = encode(content)
        val results = mutableListOf<String>()
        val analyzer = QrCodeAnalyzer { results.add(it) }

        val first = fakeImageProxy(lumaBuffer(bmp, bmp.width), bmp.width, bmp.width, bmp.height)
        val second = fakeImageProxy(lumaBuffer(bmp, bmp.width), bmp.width, bmp.width, bmp.height)
        analyzer.analyze(first)
        analyzer.analyze(second)

        assertEquals(listOf(content), results) 
        verify(exactly = 1) { first.close() }
        verify(exactly = 1) { second.close() } 
    }

    
    @Test
    fun `T15 - a non-QR frame does not fire onDecoded but still closes the image`() {
        val notQr = androidx.core.graphics.createBitmap(256, 256).apply { eraseColor(Color.GRAY) }
        val image = fakeImageProxy(lumaBuffer(notQr, notQr.width), notQr.width, notQr.width, notQr.height)
        var fired = false
        val analyzer = QrCodeAnalyzer { fired = true }

        analyzer.analyze(image)

        assertEquals(false, fired)
        verify(exactly = 1) { image.close() }
    }

    
    @Test
    fun `T16 - a malformed frame buffer does not crash and still closes the image`() {
        
        
        val w = 256
        val h = 256
        val rowStride = 128 
        val image = fakeImageProxy(ByteArray(rowStride * h), rowStride, w, h)
        var fired = false
        val analyzer = QrCodeAnalyzer { fired = true }

        analyzer.analyze(image) 

        assertEquals(false, fired)
        verify(exactly = 1) { image.close() }
    }

    
    @Test
    fun `T16b - a buffer smaller than rowStride times height does not crash and closes the image`() {
        
        
        
        val w = 256
        val h = 256
        val rowStride = 256
        val image = fakeImageProxy(ByteArray(rowStride * (h / 2)), rowStride, w, h) 
        var fired = false
        val analyzer = QrCodeAnalyzer { fired = true }

        analyzer.analyze(image) 

        assertEquals(false, fired)
        verify(exactly = 1) { image.close() }
    }

    
    @Test
    fun `T16c - rearm re-enables decoding after the one-shot latch fired`() {
        val content = "https://x.test/rearm"
        val bmp = encode(content)
        val results = mutableListOf<String>()
        val analyzer = QrCodeAnalyzer { results.add(it) }

        val first = fakeImageProxy(lumaBuffer(bmp, bmp.width), bmp.width, bmp.width, bmp.height)
        analyzer.analyze(first)
        assertEquals(listOf(content), results) 

        
        val dropped = fakeImageProxy(lumaBuffer(bmp, bmp.width), bmp.width, bmp.width, bmp.height)
        analyzer.analyze(dropped)
        assertEquals(listOf(content), results) 

        
        analyzer.rearm()
        val second = fakeImageProxy(lumaBuffer(bmp, bmp.width), bmp.width, bmp.width, bmp.height)
        analyzer.analyze(second)
        assertEquals(listOf(content, content), results)
        verify(exactly = 1) { first.close() }
        verify(exactly = 1) { dropped.close() }
        verify(exactly = 1) { second.close() }
    }

    @Test
    fun `skip resumes scanning but passes over the declined code`() {
        val declined = "https://x.test/declined"
        val other = "https://x.test/other"
        val results = mutableListOf<String>()
        val analyzer = QrCodeAnalyzer { results.add(it) }
        fun frame(content: String) = encode(content).let { fakeImageProxy(lumaBuffer(it, it.width), it.width, it.width, it.height) }

        analyzer.analyze(frame(declined))
        assertEquals(listOf(declined), results)

        
        analyzer.skip(declined)
        analyzer.analyze(frame(declined))
        assertEquals(listOf(declined), results)

        
        analyzer.analyze(frame(other))
        assertEquals(listOf(declined, other), results)
    }

    
    @Test
    fun `T-A1 - a padded Y-plane with rowStride greater than width decodes correctly`() {
        
        val content = "https://x.test/padded-stride"
        val bmp = encode(content)
        val padding = 48
        val rowStride = bmp.width + padding
        val image = fakeImageProxy(lumaBuffer(bmp, rowStride), rowStride, bmp.width, bmp.height)
        val results = mutableListOf<String>()
        val analyzer = QrCodeAnalyzer { results.add(it) }

        analyzer.analyze(image)

        assertEquals(listOf(content), results)
        verify(exactly = 1) { image.close() }
    }

    
    @Test
    fun `T-A2 - PlanarYUVLuminanceSource crops the QR out of a larger padded buffer`() {
        
        val content = "crop-contract-payload-1234567890"
        val bmp = encode(content)
        val padding = 32
        val rowStride = bmp.width + padding
        val luma = lumaBuffer(bmp, rowStride)

        val source = PlanarYUVLuminanceSource(
            luma, rowStride, bmp.height, 0, 0, bmp.width, bmp.height, false,
        )
        assertEquals(content, com.difft.android.chat.qr.QrDecoder().decode(source))

        
        val wrong = PlanarYUVLuminanceSource(
            luma, bmp.width, bmp.height, 0, 0, bmp.width, bmp.height, false,
        )
        assertNull(com.difft.android.chat.qr.QrDecoder().decode(wrong))
    }
}
