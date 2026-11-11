package com.difft.android.base.utils

import android.app.Activity
import android.graphics.Insets
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.Gravity
import android.view.WindowInsets
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
class CornerCutoutTest {

    
    private class WrappingRow(activity: Activity) : FrameLayout(activity) {
        var insets: WindowInsets? = null
        override fun getRootWindowInsets(): WindowInsets? = insets
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), if (paddingLeft > 0) TALL else SHORT)
        }
    }

    @Test
    fun `padding that wraps the row taller than a bar does not undo itself`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = WrappingRow(activity)
        val host = FrameLayout(activity).apply {
            addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        activity.setContentView(host)
        ShadowLooper.idleMainLooper()
        val location = IntArray(2).also(row::getLocationInWindow)
        
        val camera = Rect(0, location[1] + 10, 40, location[1] + 50)
        row.insets = WindowInsets.Builder()
            .setVisible(WindowInsets.Type.statusBars(), false)
            .setDisplayCutout(DisplayCutout(Insets.of(0, 0, 0, 40), null, null, null, camera))
            .build()

        row.avoidCornerCutout()
        val paddings = (1..6).map {
            row.viewTreeObserver.dispatchOnPreDraw()
            ShadowLooper.idleMainLooper()
            row.paddingLeft
        }

        assertTrue("the row steps aside for the camera", paddings.first() > 0)
        assertEquals("and keeps it once taller than a bar", setOf(paddings.first()), paddings.toSet())
        assertTrue(row.height > 96)
        assertFalse("a settled row lets the frame draw", row.viewTreeObserver.dispatchOnPreDraw())
    }

    @Test
    fun `padding set by an insets listener after install survives`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = WrappingRow(activity)
        activity.setContentView(FrameLayout(activity).apply { addView(row) })
        ShadowLooper.idleMainLooper()
        row.insets = WindowInsets.Builder().setVisible(WindowInsets.Type.statusBars(), false).build()
        row.avoidCornerCutout()
        row.viewTreeObserver.dispatchOnPreDraw()

        
        row.setPadding(SIDE_BAR, 0, SIDE_BAR, 0)
        repeat(3) {
            row.viewTreeObserver.dispatchOnPreDraw()
            ShadowLooper.idleMainLooper()
        }

        assertEquals(SIDE_BAR, row.paddingLeft)
        assertEquals(SIDE_BAR, row.paddingRight)
    }

    private companion object {
        const val SIDE_BAR = 48
        const val SHORT = 80
        const val TALL = 100
    }
}
