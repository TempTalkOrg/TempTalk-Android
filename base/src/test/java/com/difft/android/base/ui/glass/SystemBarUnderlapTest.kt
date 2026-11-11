package com.difft.android.base.ui.glass

import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SystemBarUnderlapTest {
    @Test
    fun `underlap cannot extend beyond a consumed top or the reserved navigation area`() {
        assertEquals(Insets.of(0, 24, 0, 30), systemBarUnderlapInsets(24, 24, 30, 300))
        assertEquals(Insets.of(0, 12, 0, 10), systemBarUnderlapInsets(24, 12, 30, 10))
        assertEquals(Insets.NONE, systemBarUnderlapInsets(24, -10, 30, 0))
        assertEquals(Insets.NONE, systemBarUnderlapInsets(-1, 100, -1, 100))
    }

    @Test
    fun `body extends above its viewport without moving its bottom and restores exactly`() {
        val root = FrameLayout(ApplicationProvider.getApplicationContext())
        val body = View(root.context)
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(776, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 776)
        }
        layout()
        assertFalse(applyOverlayBodyUnderlap(body, top = 24))
        layout()
        assertEquals(0, body.top)
        assertEquals(-24f, body.y, 0f)
        assertEquals(776f, body.bottom + body.translationY, 0f)
        assertTrue(applyOverlayBodyUnderlap(body, top = 24))
        assertFalse(applyOverlayBodyUnderlap(body, top = 0))
        layout()
        assertEquals(0f, body.y, 0f)
        assertEquals(776, body.bottom)
    }

    @Test
    fun `collapse offsets translate the taller body without padding or layout mutations`() {
        val root = FrameLayout(ApplicationProvider.getApplicationContext())
        val body = View(root.context)
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        body.setPadding(0, 116, 0, 136)
        assertFalse(applyOverlayBodyUnderlap(body, top = 24, collapseRange = 52))
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(776, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 776)
        val height = body.height
        assertEquals(852, height)
        for (offset in 0 downTo -52) {
            assertTrue(applyOverlayBodyUnderlap(body, top = 24, collapseRange = 52, offset = offset))
            assertFalse(body.isLayoutRequested)
            assertEquals(height, body.height)
            assertEquals(116, body.paddingTop)
            assertEquals(136, body.paddingBottom)
            assertEquals((-24 + offset).toFloat(), body.translationY, 0f)
            assertTrue("body still reaches the viewport bottom", body.bottom + body.translationY >= 776)
        }
    }
}
