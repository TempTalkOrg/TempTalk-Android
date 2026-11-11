package com.difft.android.chat.invite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.difft.android.chat.qr.QrDecoder
import com.difft.android.chat.qr.QrEncoder
import com.difft.android.chat.qr.QrErrorCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode





@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class InviteQrGenerationTest {

    
    private val bg2NightInt = Color.rgb(0x1B, 0x1B, 0x1B)

    
    private fun logoBitmap(): Bitmap =
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.RED)
        }

    @Test
    fun `T3-1 invite param round-trip with logo decodes back`() {
        val url = "https://invite.test/u/i.html?pi=" + "a".repeat(120)
        val bitmap = QrEncoder.encode(
            content = url,
            sizePx = 200,
            foreground = bg2NightInt,
            errorCorrection = QrErrorCorrection.HIGH,
            logo = logoBitmap(),
        )
        assertNotNull("invite QR with logo must encode", bitmap)
        assertEquals(url, QrDecoder().decodeBitmap(bitmap!!))
    }

    @Test
    fun `T3-2 invite param no-logo fallback decodes back`() {
        
        val url = "https://invite.test/u/i.html?pi=abc123"
        val bitmap = QrEncoder.encode(
            content = url,
            sizePx = 200,
            foreground = bg2NightInt,
            errorCorrection = QrErrorCorrection.HIGH,
            logo = null,
        )
        assertNotNull("invite QR without logo must encode", bitmap)
        assertEquals(url, QrDecoder().decodeBitmap(bitmap!!))
    }

    @Test
    fun `T3-5 custom foreground color fidelity`() {
        val url = "https://invite.test/u/i.html?pi=color"
        val bitmap = QrEncoder.encode(
            content = url,
            sizePx = 200,
            foreground = bg2NightInt,
            errorCorrection = QrErrorCorrection.HIGH,
            logo = null,
        )
        assertNotNull(bitmap)
        val w = bitmap!!.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        
        val allValid = pixels.all { it == bg2NightInt || it == Color.WHITE }
        assertTrue("every pixel must be bg2_night or white", allValid)
    }
}
