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

/**
 * Generation call-site round-trips for the invite-QR params (design §7 T3-1, T3-2, T3-5).
 *
 * These pin the exact parameter combination `InviteUtils.createQRBitmap` passes to [QrEncoder.encode]
 * — sizePx=200, the `bg2_night` foreground, HIGH error correction, optional center logo — and prove
 * the produced QR survives that combination by decoding it back with a REAL [QrDecoder].
 *
 * [GraphicsMode.Mode.NATIVE] so `getPixels` reads real composited pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class InviteQrGenerationTest {

    // A concrete int stands in for the resolved bg2_night color; the encoder only needs a ColorInt.
    private val bg2NightInt = Color.rgb(0x1B, 0x1B, 0x1B)

    /** Solid red square used as a stand-in for ic_invite_qr_logo. */
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
        // ic_invite_qr_logo can be absent (drawable lookup returns null) -> logo=null branch.
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
        // Every module pixel must be exactly the custom foreground or the default white background.
        val allValid = pixels.all { it == bg2NightInt || it == Color.WHITE }
        assertTrue("every pixel must be bg2_night or white", allValid)
    }
}
