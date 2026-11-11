package com.difft.android.base.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.widget.applyFloatingSurface
import com.difft.android.base.widget.applyGlassSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassFallbackTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `nearest viewport source replaces and disposes its own tag`() {
        val page = FrameLayout(context)
        val pane = FrameLayout(context)
        val chrome = View(context)
        page.addView(pane)
        pane.addView(chrome)
        val pageBackdrop = page.installGlassBackdrop()
        val paneBackdrop = pane.installGlassBackdrop()

        assertSame(paneBackdrop, chrome.findGlassBackdrop())
        pageBackdrop.close()
        assertSame(paneBackdrop, chrome.findGlassBackdrop())
        paneBackdrop.close()
        assertNull(chrome.findGlassBackdrop())

        val replacement = page.installGlassBackdrop()
        assertSame(replacement, chrome.findGlassBackdrop())
        pageBackdrop.close()
        assertSame(replacement, chrome.findGlassBackdrop())
        replacement.close()
    }

    @Test
    fun `unsupported API keeps the existing opaque fill and elevation`() {
        val control = View(context)
        control.layout(0, 0, 100, 60)
        control.applyFloatingSurface(cornerRadiusPx = 20f, fillColor = Color.RED)
        val oldElevation = control.elevation
        val oldPixel = centerPixel(control)

        control.applyGlassSurface(cornerRadiusPx = 20f, fillColor = Color.RED)

        assertFalse(GlassMode.isLive(context))
        assertEquals(oldElevation, control.elevation, 0f)
        assertEquals(Color.RED, oldPixel)
        assertEquals(oldPixel, centerPixel(control))
    }

    private fun centerPixel(view: View): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap.getPixel(view.width / 2, view.height / 2).also { bitmap.recycle() }
    }
}
