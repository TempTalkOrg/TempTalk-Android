package com.difft.android.base.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeGlassBindingRegionTest {
    @After
    fun drainSnapshots() {
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
    }

    @Test
    fun `unchanged bounds stay idle and source replacement replays copied sharp demand`() {
        val first = Source()
        val second = Source()
        val view = Consumer(ApplicationProvider.getApplicationContext())
        view.setTag(R.id.glass_backdrop, first)
        val binding = ComposeGlassBinding(view, null)
        binding.start()
        try {
            val bounds = RectF(10f, 20f, 90f, 70f)
            binding.updateRegion(bounds, sharp = true)
            val frame = binding.frame.intValue
            repeat(180) {
                binding.refresh()
                binding.updateRegion(bounds, sharp = true)
            }
            assertEquals(1, first.updates)
            assertEquals(frame, binding.frame.intValue)
            bounds.offset(100f, 100f)
            view.setTag(R.id.glass_backdrop, second)
            binding.refresh()
            assertTrue(first.regions.isEmpty())
            assertEquals(RectF(10f, 20f, 90f, 70f), second.regions.values.single())
            assertTrue(second.sharp)
            binding.updateRegion(bounds, sharp = false)
            assertEquals(RectF(110f, 120f, 190f, 170f), second.regions.values.single())
            assertFalse(second.sharp)
            binding.updateRegion(null)
            assertTrue(second.regions.isEmpty())
        } finally {
            binding.stop()
        }
        assertTrue(first.regions.isEmpty())
        assertTrue(second.regions.isEmpty())
    }

    @Test
    fun `hidden consumer clears demand then restores prior area without recomposition`() {
        val source = Source()
        val view = Consumer(ApplicationProvider.getApplicationContext())
        val binding = ComposeGlassBinding(view, source)
        binding.start()
        try {
            binding.updateRegion(RectF(0f, 10f, 120f, 50f))
            assertEquals(1, source.regions.size)
            view.shown = false
            binding.refresh()
            assertTrue(source.regions.isEmpty())
            view.shown = true
            binding.refresh()
            assertEquals(RectF(0f, 10f, 120f, 50f), source.regions.values.single())
        } finally {
            binding.stop()
        }
        assertTrue(source.regions.isEmpty())
    }

    private class Consumer(context: Context) : View(context) {
        var shown = true
        override fun isAttachedToWindow() = true
        override fun isShown() = shown
    }

    private class Source : GlassBackdrop {
        val regions = mutableMapOf<() -> Unit, RectF>()
        var updates = 0
        var sharp = false
        override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
            updates++
            this.sharp = sharp
            if (boundsInWindow == null) regions.remove(listener) else regions[listener] = RectF(boundsInWindow)
        }
        override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float) = false
        override fun addListener(listener: () -> Unit) = Unit
        override fun removeListener(listener: () -> Unit) { regions.remove(listener) }
        override fun invalidate() = Unit
        override fun close() = Unit
    }
}
