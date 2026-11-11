package com.difft.android.base.utils

import android.graphics.Insets
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.View
import android.view.WindowInsets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeTopInsetTest {
    
    private val view = View(ApplicationProvider.getApplicationContext()).apply { layout(0, 0, 1000, 2000) }

    private fun insets(status: Int, statusVisible: Boolean, cutout: Int): WindowInsetsCompat =
        WindowInsetsCompat.toWindowInsetsCompat(
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, status, 0, 0))
                .setVisible(WindowInsets.Type.statusBars(), statusVisible)
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(0, cutout, 0, 0))
                .build(),
        )

    @Test
    fun `the taller of the status bar and the cutout while the status bar shows`() {
        assertEquals(24, view.safeTopInset(insets(status = 24, statusVisible = true, cutout = 18)))
        assertEquals(30, view.safeTopInset(insets(status = 24, statusVisible = true, cutout = 30)))
        assertEquals("nothing extra while the status bar shows", 0, view.hiddenStatusBarTop(insets(24, true, 0)))
    }

    @Test
    fun `nothing at the top once the status bar is hidden without a centred camera`() {
        
        assertEquals(0, view.safeTopInset(insets(status = 0, statusVisible = false, cutout = 18)))
        assertEquals(0, view.safeTopInset(insets(status = 0, statusVisible = false, cutout = 0)))
        assertEquals(0, view.safeTopInset(null))
        assertTrue(view.cutoutRectsInWindow(null).isEmpty())
    }

    @Test
    fun `a corner camera only moves the top-bar row it reaches`() {
        
        val camera = Rect(940, 10, 1000, 60)
        val cornerCutout = DisplayCutout(Insets.of(0, 0, 60, 0), null, null, camera, null)
        val hidden = WindowInsetsCompat.toWindowInsetsCompat(
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.NONE)
                .setVisible(WindowInsets.Type.statusBars(), false)
                .setDisplayCutout(cornerCutout)
                .build(),
        )
        assertEquals("a corner camera does not push the page down", 0, view.safeTopInset(hidden))
        
        assertEquals(0 to 64, view.cornerCutoutExtra(0, 8, 1000, 60, hidden))
        assertEquals("a row below the camera stays as is", 0 to 0, view.cornerCutoutExtra(0, 100, 1000, 152, hidden))
    }

    @Test
    fun `a camera rotated into a bottom corner moves only the bottom bar row`() {
        val camera = Rect(0, 1940, 60, 1990)
        val bottomCorner = DisplayCutout(Insets.of(0, 0, 0, 60), null, null, null, camera)
        val hidden = WindowInsetsCompat.toWindowInsetsCompat(
            WindowInsets.Builder()
                .setVisible(WindowInsets.Type.statusBars(), false)
                .setDisplayCutout(bottomCorner)
                .build(),
        )
        assertEquals("the page top is untouched", 0, view.safeTopInset(hidden))
        assertEquals(64 to 0, view.cornerCutoutExtra(0, 1920, 1000, 1990, hidden))
        assertEquals(0 to 0, view.cornerCutoutExtra(0, 8, 1000, 60, hidden))
    }

    @Test
    fun `a camera in the middle of the top edge moves the page below it`() {
        val camera = Rect(470, 10, 530, 60)
        val centred = DisplayCutout(Insets.of(0, 60, 0, 0), null, camera, null, null)
        val hidden = WindowInsetsCompat.toWindowInsetsCompat(
            WindowInsets.Builder()
                .setVisible(WindowInsets.Type.statusBars(), false)
                .setDisplayCutout(centred)
                .build(),
        )
        assertEquals(60, view.safeTopInset(hidden))
        assertEquals(0 to 0, view.cornerCutoutExtra(0, 8, 1000, 60, hidden))
    }
}
