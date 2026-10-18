package com.difft.android.chat.invite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.difft.android.base.R as BaseR

/**
 * The migration scan page's viewfinder draws only four primary corners: no mask outside the frame,
 * nothing inside it. The general scanner keeps its dimmed mask.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class ScanFrameOverlayMigrationStyleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val density = context.resources.displayMetrics.density

    private fun render(migrationStyle: Boolean): Bitmap {
        val w = (400 * density).toInt()
        val h = (800 * density).toInt()
        val view = ScanFrameOverlayView(context).also { it.migrationStyle = migrationStyle }
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    @Test
    fun `migration style draws only the four corners`() {
        val bmp = render(migrationStyle = true)
        val cx = bmp.width / 2
        val cy = bmp.height / 2
        val half = (124 * density).toInt()
        val primary = ContextCompat.getColor(context, BaseR.color.primary)

        assertEquals("no mask outside the frame", Color.TRANSPARENT, bmp.getPixel(4, 4))
        assertEquals("nothing inside the frame", Color.TRANSPARENT, bmp.getPixel(cx, cy))
        // On the top edge, 20dp in from the left: inside the 42dp corner arm, past the 10dp rounding.
        assertEquals("corner arm in primary", primary, bmp.getPixel(cx - half + (20 * density).toInt(), cy - half + 1))
        // Mid top edge: between the two arms, so no stroke there.
        assertEquals("no border between corners", Color.TRANSPARENT, bmp.getPixel(cx, cy - half + 1))
    }

    @Test
    fun `the general scanner keeps its mask`() {
        assertNotEquals(Color.TRANSPARENT, render(migrationStyle = false).getPixel(4, 4))
    }
}
