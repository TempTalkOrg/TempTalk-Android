package com.difft.android.base.ui.glass

import android.app.Activity
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassRegionDirtyTrackerTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var source: CountingFrame
    private lateinit var topRow: FrameLayout
    private lateinit var bottomRow: FrameLayout
    private lateinit var bottomText: View
    private lateinit var tracker: GlassRegionDirtyTracker
    private val top = RectF(0f, 0f, 300f, 100f)
    private val bottom = RectF(0f, 500f, 300f, 600f)

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        source = CountingFrame(activity)
        activity.setContentView(source)
        topRow = FrameLayout(activity)
        bottomRow = FrameLayout(activity)
        bottomText = View(activity)
        source.addView(topRow, FrameLayout.LayoutParams(300, 100))
        source.addView(bottomRow, FrameLayout.LayoutParams(300, 100))
        bottomRow.addView(bottomText, FrameLayout.LayoutParams(100, 50))
        source.layout(0, 0, 300, 600)
        topRow.layout(0, 0, 300, 100)
        bottomRow.layout(0, 500, 300, 600)
        bottomText.layout(0, 0, 100, 50)
        tracker = GlassRegionDirtyTracker(source)
        tracker.acquire()
        
        val location = IntArray(2)
        source.getLocationInWindow(location)
        top.offset(location[0].toFloat(), location[1].toFloat())
        bottom.offset(location[0].toFloat(), location[1].toFloat())
        tracker.beginFrame()
        assertTrue(tracker.isDirty(top))
        assertTrue(tracker.isDirty(bottom))
        tracker.endFrame()
    }

    @After
    fun tearDown() { controller.pause().stop().destroy() }

    @Test
    fun `unchanged top bottom and sharp subregion stay clean across frames`() {
        val sharp = RectF(bottom).apply { inset(50f, 20f) }
        repeat(30) {
            tracker.beginFrame()
            assertFalse(tracker.isDirty(top))
            assertFalse(tracker.isDirty(bottom))
            assertFalse(tracker.isDirty(sharp))
            tracker.endFrame()
        }
    }

    @Test
    fun `bottom descendant damages bottom and sharp without invalidating top`() {
        tracker.descendantInvalidated(bottomText)
        tracker.beginFrame()
        assertFalse(tracker.isDirty(top))
        assertTrue(tracker.isDirty(bottom))
        assertTrue(tracker.isDirty(RectF(bottom).apply { inset(50f, 20f) }))
        tracker.endFrame()
        assertBothClean()
    }

    @Test
    fun `damage generated while recording survives ending the frozen frame`() {
        tracker.beginFrame()
        tracker.descendantInvalidated(bottomText)
        assertFalse(tracker.isDirty(bottom))
        tracker.endFrame()
        tracker.beginFrame()
        assertFalse(tracker.isDirty(top))
        assertTrue(tracker.isDirty(bottom))
        tracker.endFrame()
        assertBothClean()
    }

    @Test
    fun `row translation damages both its previous and new footprint`() {
        bottomRow.translationY = -500f
        tracker.beginFrame()
        assertTrue(tracker.isDirty(top))
        assertTrue(tracker.isDirty(bottom))
        tracker.endFrame()
        assertBothClean()
    }

    @Test
    fun `same bounds reflection alpha and clipping still invalidate that row`() {
        bottomRow.pivotX = 150f
        bottomRow.scaleX = -1f
        assertBottomOnly()
        bottomRow.alpha = 0.5f
        assertBottomOnly()
        bottomRow.clipBounds = Rect(0, 0, 100, 100)
        assertBottomOnly()
    }

    @Test
    fun `source transform scroll and explicit invalidation affect both crops`() {
        source.scaleX = 0.9f
        assertBothDirty()
        source.scrollTo(0, 10)
        assertBothDirty()
        tracker.invalidateAll()
        assertBothDirty()
        assertBothClean()
    }

    @Test
    fun `removed row footprint is cleared and replacement identity is recorded`() {
        source.removeView(bottomRow)
        assertBottomOnly()
        val replacement = View(source.context)
        source.addView(replacement, FrameLayout.LayoutParams(300, 100))
        replacement.layout(0, 500, 300, 600)
        assertBottomOnly()
        assertBothClean()
    }

    @Test
    fun `filter margin participates in intersection beyond visible crop`() {
        val aboveBottom = RectF(bottom.left, bottom.top - 40f, bottom.right, bottom.top - 1f)
        tracker.descendantInvalidated(bottomText)
        tracker.beginFrame()
        assertFalse(tracker.isDirty(aboveBottom))
        aboveBottom.inset(0f, -10f)
        assertTrue(tracker.isDirty(aboveBottom))
        tracker.endFrame()
    }

    @Test
    fun `inactive hooks perform no coordinate mapping and reacquire invalidates old caches`() {
        tracker.release()
        val mappings = source.windowMappings
        repeat(30) {
            tracker.descendantInvalidated(bottomText)
            tracker.beginFrame()
            tracker.endFrame()
        }
        assertTrue(source.windowMappings == mappings)
        tracker.acquire()
        assertBothDirty()
        assertBothClean()
    }

    @Test
    fun `transient animation requests full-source fallback only while active`() {
        bottomText.setHasTransientState(true)
        assertTrue(tracker.requiresFullCapture)
        assertBothDirty()
        bottomText.setHasTransientState(false)
        assertFalse(tracker.requiresFullCapture)
        assertBothClean()
    }

    @Test
    fun `unbounded row shadow or overflow uses conservative damage without polluting flat rows`() {
        bottomRow.elevation = 8f
        assertBothDirty()
        assertTrue(tracker.requiresFullCapture)
        tracker.descendantInvalidated(bottomText)
        assertBothDirty()
        bottomRow.elevation = 0f
        assertBothDirty()
        assertFalse(tracker.requiresFullCapture)
        tracker.descendantInvalidated(bottomText)
        assertBottomOnly()
        bottomRow.clipChildren = false
        assertBothDirty()
        assertTrue(tracker.requiresFullCapture)
    }

    @Test
    fun `animated command bounds retain off-viewport attached row footprints`() {
        val offscreen = View(source.context)
        source.addView(offscreen, FrameLayout.LayoutParams(300, 100))
        offscreen.layout(0, 700, 300, 800)
        offscreen.setHasTransientState(true)
        tracker.beginFrame()
        val capture = RectF()
        tracker.captureBounds(capture)
        assertEquals(RectF(top.left, top.top, top.right, bottom.bottom + 200f), capture)
        assertTrue(tracker.requiresFullCapture)
        tracker.endFrame()
    }

    private fun assertBottomOnly() {
        tracker.beginFrame()
        assertFalse(tracker.isDirty(top))
        assertTrue(tracker.isDirty(bottom))
        tracker.endFrame()
    }

    private fun assertBothDirty() {
        tracker.beginFrame()
        assertTrue(tracker.isDirty(top))
        assertTrue(tracker.isDirty(bottom))
        tracker.endFrame()
    }

    private fun assertBothClean() {
        tracker.beginFrame()
        assertFalse(tracker.isDirty(top))
        assertFalse(tracker.isDirty(bottom))
        tracker.endFrame()
    }

    private class CountingFrame(context: Context) : FrameLayout(context) {
        var windowMappings = 0
        override fun transformMatrixToGlobal(matrix: Matrix) {
            windowMappings++
            super.transformMatrixToGlobal(matrix)
        }
    }
}
