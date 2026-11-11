package com.difft.android.base.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderNode
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
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassBindingIdleTest {
    @After
    fun drainSnapshotNotifications() {
        
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
    }

    private class ConsumerView(context: Context) : View(context) {
        var attached = true
        var frameRequests = 0
        override fun isAttachedToWindow() = attached
        override fun isShown() = true
        override fun isHardwareAccelerated() = true
        override fun postInvalidateOnAnimation() { frameRequests++ }
    }

    private class Source : GlassBackdrop {
        val listeners = mutableSetOf<() -> Unit>()
        var adds = 0
        var removes = 0
        var draws = 0
        override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean {
            draws++
            return false
        }
        override fun addListener(listener: () -> Unit) { adds++; listeners.add(listener) }
        override fun removeListener(listener: () -> Unit) { removes++; listeners.remove(listener) }
        override fun invalidate() { listeners.toList().forEach { it() } }
        override fun close() = Unit
    }

    @Test
    fun `Compose refresh and pre-draw stay idle until one notification then detach and rebind once`() {
        val source = Source()
        val view = consumer(source)
        val binding = ComposeGlassBinding(view, null)
        binding.start()
        try {
            val initialFrame = binding.frame.intValue
            repeat(30) {
                binding.refresh()
                view.viewTreeObserver.dispatchOnPreDraw()
            }
            assertEquals(1, source.adds)
            assertEquals(0, source.removes)
            assertEquals(initialFrame, binding.frame.intValue)
            assertEquals(0, view.frameRequests)

            source.invalidate()
            repeat(30) {
                binding.refresh()
                view.viewTreeObserver.dispatchOnPreDraw()
            }
            assertEquals(initialFrame + 1, binding.frame.intValue)
            assertEquals(1, source.adds)
            assertEquals(0, source.removes)

            view.attached = false
            binding.onViewDetachedFromWindow(view)
            val detachedFrame = binding.frame.intValue
            source.invalidate()
            repeat(30) {
                binding.refresh()
                view.viewTreeObserver.dispatchOnPreDraw()
            }
            assertEquals(1, source.removes)
            assertTrue(source.listeners.isEmpty())
            assertEquals(detachedFrame, binding.frame.intValue)

            view.attached = true
            binding.onViewAttachedToWindow(view)
            val reboundFrame = binding.frame.intValue
            repeat(30) {
                binding.refresh()
                view.viewTreeObserver.dispatchOnPreDraw()
            }
            assertEquals(2, source.adds)
            assertEquals(1, source.removes)
            assertEquals(reboundFrame, binding.frame.intValue)
            assertEquals(0, view.frameRequests)
        } finally {
            binding.stop()
        }
        val stoppedFrame = binding.frame.intValue
        source.invalidate()
        view.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(2, source.removes)
        assertTrue(source.listeners.isEmpty())
        assertEquals(stoppedFrame, binding.frame.intValue)
    }

    @Test
    fun `View hardware draws keep one listener and request no frames beyond source notification`() {
        val source = Source()
        val view = consumer(source)
        val binding = GlassSurfaceBinding(view)
        val target = RenderNode("GlassBindingIdleTest").apply { setPosition(0, 0, 100, 100) }
        val canvas = target.beginRecording(100, 100)
        try {
            repeat(30) { assertFalse(binding.draw(canvas)) }
            assertEquals("all draws must pass the production hardware guard", 30, source.draws)
            assertEquals(1, source.adds)
            assertEquals(0, source.removes)
            assertEquals(0, view.frameRequests)

            source.invalidate()
            repeat(30) { assertFalse(binding.draw(canvas)) }
            assertEquals(60, source.draws)
            assertEquals(1, source.adds)
            assertEquals(0, source.removes)
            assertEquals(1, view.frameRequests)

            view.attached = false
            binding.onViewDetachedFromWindow(view)
            source.invalidate()
            repeat(30) { assertFalse(binding.draw(canvas)) }
            assertEquals(60, source.draws)
            assertEquals(1, source.removes)
            assertEquals(1, view.frameRequests)

            view.attached = true
            binding.onViewAttachedToWindow(view)
            repeat(30) { assertFalse(binding.draw(canvas)) }
            assertEquals(90, source.draws)
            assertEquals(2, source.adds)
            assertEquals(1, source.removes)
            assertEquals(1, view.frameRequests)
        } finally {
            binding.close()
            target.endRecording()
            target.discardDisplayList()
        }
        source.invalidate()
        assertEquals(2, source.removes)
        assertTrue(source.listeners.isEmpty())
        assertEquals(1, view.frameRequests)
    }

    private fun consumer(source: GlassBackdrop) =
        ConsumerView(ApplicationProvider.getApplicationContext()).apply {
            layout(0, 0, 100, 100)
            setTag(R.id.glass_backdrop, source)
            frameRequests = 0
        }
}
