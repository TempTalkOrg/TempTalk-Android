package com.difft.android.base.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.RenderNode
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassViewRegionBindingTest {
    private class Consumer(context: Context) : View(context) {
        var shown = true
        override fun isAttachedToWindow() = true
        override fun isShown() = shown
        override fun isHardwareAccelerated() = true
        override fun postInvalidateOnAnimation() = Unit
        override fun getLocationInWindow(outLocation: IntArray) {
            outLocation[0] = 10
            outLocation[1] = 20
        }
    }

    private class Source : GlassBackdrop {
        val listeners = linkedSetOf<() -> Unit>()
        var bounds: RectF? = null
        var sharp = false
        var updates = 0
        val samplingMatrix = FloatArray(9)
        private val matrix = Matrix()
        override fun addListener(listener: () -> Unit) { listeners += listener }
        override fun removeListener(listener: () -> Unit) { listeners -= listener; bounds = null }
        override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
            assertTrue(listeners.contains(listener))
            updates++
            bounds = boundsInWindow?.let(::RectF)
            this.sharp = sharp
        }
        @Suppress("DEPRECATION")
        override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean {
            canvas.getMatrix(matrix)
            matrix.getValues(samplingMatrix)
            return true
        }
        override fun invalidate() = Unit
        override fun close() = Unit
    }

    @Test
    fun `unchanged View requests are inert and hiding clears only its sampling area`() {
        val source = Source()
        val view = Consumer(ApplicationProvider.getApplicationContext()).apply {
            layout(0, 0, 100, 80)
            setTag(R.id.glass_backdrop, source)
        }
        val binding = GlassSurfaceBinding(view)
        val output = RenderNode("GlassViewRegionTest").apply { setPosition(0, 0, 100, 80) }
        val canvas = output.beginRecording()
        try {
            val shape = RectF(4f, 6f, 20f, 16f)
            assertTrue(binding.draw(canvas, shape))
            assertEquals(RectF(14f, 26f, 30f, 36f), source.bounds)
            assertFalse(source.sharp)
            repeat(30) { binding.draw(canvas, shape); view.viewTreeObserver.dispatchOnPreDraw() }
            assertEquals(1, source.updates)
            view.shown = false
            repeat(30) { view.viewTreeObserver.dispatchOnPreDraw() }
            assertNull(source.bounds)
            assertEquals(2, source.updates)
            view.shown = true
            assertTrue(binding.draw(canvas, shape))
            assertEquals(3, source.updates)
            assertEquals(RectF(14f, 26f, 30f, 36f), source.bounds)
        } finally {
            binding.close()
            output.endRecording()
            output.discardDisplayList()
        }
        assertTrue(source.listeners.isEmpty())
    }

    @Test
    fun `transformed shapes map their complete area and replay on source replacement`() {
        val first = Source()
        val second = Source()
        val view = Consumer(ApplicationProvider.getApplicationContext()).apply {
            layout(0, 0, 100, 80)
            pivotX = 0f
            pivotY = 0f
            scaleX = 2f
            scaleY = 0.5f
            setTag(R.id.glass_backdrop, first)
        }
        val binding = GlassSurfaceBinding(view)
        val output = RenderNode("GlassViewTransformRegionTest").apply { setPosition(0, 0, 200, 80) }
        val canvas = output.beginRecording()
        try {
            val shape = RectF(4f, 6f, 20f, 16f)
            binding.draw(canvas, shape)
            assertEquals(RectF(18f, 23f, 50f, 28f), first.bounds)
            assertEquals("page sampling cancels the consumer's horizontal growth", 0.5f, first.samplingMatrix[Matrix.MSCALE_X], 0f)
            assertEquals("page sampling cancels the consumer's vertical shrink", 2f, first.samplingMatrix[Matrix.MSCALE_Y], 0f)
            view.setTag(R.id.glass_backdrop, second)
            binding.draw(canvas, shape)
            assertTrue(first.listeners.isEmpty())
            assertEquals(RectF(18f, 23f, 50f, 28f), second.bounds)
            assertEquals(1, second.updates)
        } finally {
            binding.close()
            output.endRecording()
            output.discardDisplayList()
        }
        assertTrue(second.listeners.isEmpty())
    }
}
