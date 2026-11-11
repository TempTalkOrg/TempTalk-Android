package com.difft.android.base.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemBarGlassPainterTest {
    @Test
    fun `combined blur image and uniform tint share one progressive opacity mask`() {
        val bitmap = Bitmap.createBitmap(10, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        SystemBarGlassPainter().draw(canvas, RectF(0f, 0f, 10f, 100f), SystemBarEdge.TOP, 20f, Color.BLUE) {
            it.drawColor(Color.RED)
            true
        }

        val middle = bitmap.getPixel(5, 50)
        assertNear(126, Color.alpha(middle))
        assertNear(184, Color.blue(middle))
        assertNear(71, Color.red(middle))
        assertTrue(Color.alpha(bitmap.getPixel(5, 0)) > 250)
        assertTrue(Color.alpha(bitmap.getPixel(5, 99)) < 4)
        bitmap.recycle()
    }

    @Test
    fun `fallback keeps 32 percent plateau and reverses with the selected edge`() {
        val painter = SystemBarGlassPainter()
        val bitmap = Bitmap.createBitmap(10, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = RectF(0f, 0f, 10f, 100f)
        painter.draw(canvas, bounds, SystemBarEdge.TOP, 20f, Color.BLUE) { false }
        assertNear(82, Color.alpha(bitmap.getPixel(5, 0)))
        assertNear(82, Color.alpha(bitmap.getPixel(5, 19)))
        assertNear(41, Color.alpha(bitmap.getPixel(5, 60)))

        bitmap.eraseColor(Color.TRANSPARENT)
        painter.draw(canvas, bounds, SystemBarEdge.BOTTOM, 20f, Color.BLUE) { false }
        assertNear(82, Color.alpha(bitmap.getPixel(5, 99)))
        assertNear(82, Color.alpha(bitmap.getPixel(5, 80)))
        assertTrue(Color.alpha(bitmap.getPixel(5, 0)) < 4)
        bitmap.recycle()
    }

    @Test
    fun `mask is isolated from the page beneath the strip and ignores empty insets`() {
        val bitmap = Bitmap.createBitmap(10, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        val canvas = Canvas(bitmap)
        val painter = SystemBarGlassPainter()
        painter.draw(canvas, RectF(0f, 0f, 10f, 100f), SystemBarEdge.TOP, 20f, Color.BLUE) {
            it.drawColor(Color.RED)
            true
        }
        val tail = bitmap.getPixel(5, 99)
        assertEquals(255, Color.alpha(tail))
        assertTrue(Color.green(tail) > 250)
        var called = false
        painter.draw(canvas, RectF(0f, 0f, 10f, 100f), SystemBarEdge.TOP, 0f, Color.BLUE) { called = true; true }
        assertTrue(!called)
        bitmap.recycle()
    }

    private fun assertNear(expected: Int, actual: Int) = assertEquals(expected.toFloat(), actual.toFloat(), 3f)
}
