package com.difft.android.chat.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Unit tests for [QrEncoder] (design §7 T1–T5, plus logo round-trip in [QrDecoderTest]).
 *
 * Robolectric only because the encoder produces an `android.graphics.Bitmap`. The encode logic
 * itself is pure ZXing. No MockK — deterministic transforms.
 *
 * [GraphicsMode.Mode.NATIVE] is required so `Canvas.drawBitmap` actually composites pixels (T5):
 * the default legacy shadow Canvas is a no-op for pixel-level reads.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class QrEncoderTest {

    /** Count distinct ARGB colors present in a bitmap (used to prove fg/bg presence). */
    private fun Bitmap.colorSet(): Set<Int> {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.toHashSet()
    }

    // ── T1: happy path ───────────────────────────────────────────────────────────────────
    @Test
    fun `T1 - encode happy path produces square bitmap with black and white`() {
        val bmp = QrEncoder.encode("https://x.test/abc", 400, Color.BLACK)
        assertNotNull(bmp)
        assertEquals(400, bmp!!.width)
        assertEquals(400, bmp.height)
        val colors = bmp.colorSet()
        assertTrue("expected black modules", colors.contains(Color.BLACK))
        assertTrue("expected white background", colors.contains(Color.WHITE))
    }

    // ── T2: guard clauses ────────────────────────────────────────────────────────────────
    @Test
    fun `T2 - empty content returns null`() {
        assertNull(QrEncoder.encode("", 400, Color.BLACK))
    }

    @Test
    fun `T2 - non-positive size returns null`() {
        assertNull(QrEncoder.encode("x", 0, Color.BLACK))
        assertNull(QrEncoder.encode("x", -10, Color.BLACK))
    }

    // ── T3: fg/bg color param ──────────────────────────────────────────────────────────────
    @Test
    fun `T3 - every pixel is exactly the configured foreground or background`() {
        val bmp = QrEncoder.encode("color-test", 200, foreground = Color.RED, background = Color.BLUE)
        assertNotNull(bmp)
        val colors = bmp!!.colorSet()
        assertEquals(
            "only the two configured colors may appear",
            setOf(Color.RED, Color.BLUE),
            colors,
        )
    }

    // ── T4: quiet-zone margin param ────────────────────────────────────────────────────────
    @Test
    fun `T4 - larger margin yields a wider uniform background border ring`() {
        val content = "margin-comparison-payload"
        val small = QrEncoder.encode(content, 300, Color.BLACK, marginModules = 1)!!
        val large = QrEncoder.encode(content, 300, Color.BLACK, marginModules = 8)!!

        // Count fully-white rows from the top edge inward — a bigger quiet zone => more white rows.
        fun topWhiteRows(bmp: Bitmap): Int {
            var rows = 0
            for (y in 0 until bmp.height) {
                var allWhite = true
                for (x in 0 until bmp.width) {
                    if (bmp.getPixel(x, y) != Color.WHITE) { allWhite = false; break }
                }
                if (allWhite) rows++ else break
            }
            return rows
        }
        assertTrue(
            "larger margin must produce a wider white border",
            topWhiteRows(large) > topWhiteRows(small),
        )
    }

    // ── T5: logo overlay compositing ───────────────────────────────────────────────────────
    @Test
    fun `T5 - logo composited into center region`() {
        val logo = createBitmap(32, 32).apply { eraseColor(Color.RED) }
        val bmp = QrEncoder.encode(
            "logo-overlay-payload", 400, Color.BLACK,
            errorCorrection = QrErrorCorrection.HIGH, logo = logo,
        )
        assertNotNull(bmp)
        // Center pixel must be the logo color (logo edge ~20% of 400 => ~80px centered block).
        assertEquals(Color.RED, bmp!!.getPixel(200, 200))
    }
}
