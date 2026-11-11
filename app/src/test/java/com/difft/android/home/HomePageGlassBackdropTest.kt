package com.difft.android.home

import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.base.R
import com.difft.android.base.ui.glass.GlassBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomePageGlassBackdropTest {
    @Test
    fun `only resumed pages forward independent regions and pause releases demand without ownership`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val viewport = FrameLayout(activity)
        activity.setContentView(viewport)
        val owner = Owner(Lifecycle.State.STARTED)
        val source = Source()
        val gate = HomePageGlassBackdrop(viewport, owner, source)
        var topFrames = 0
        var bottomFrames = 0
        val top: () -> Unit = { topFrames++ }
        val bottom: () -> Unit = { bottomFrames++ }
        val topBounds = RectF(0f, 0f, 400f, 100f)
        val bottomBounds = RectF(40f, 700f, 300f, 760f)
        try {
            gate.addListener(top)
            gate.addListener(bottom)
            gate.updateRegion(top, topBounds)
            gate.updateRegion(bottom, bottomBounds, sharp = true)
            bottomBounds.offset(5f, 0f)
            gate.invalidate()
            assertTrue(source.listeners.isEmpty())
            assertEquals(0, source.invalidations)
            assertFalse(gate.draw(Canvas(), 1f, 2f))
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertEquals(2, source.listeners.size)
            assertEquals(topBounds to false, source.regions[top])
            assertEquals(RectF(40f, 700f, 300f, 760f) to true, source.regions[bottom])
            assertEquals(1, source.invalidations)
            val updates = source.updates
            gate.updateRegion(top, RectF(topBounds))
            assertEquals(updates, source.updates)
            topFrames = 0
            bottomFrames = 0
            source.listeners.single { it === bottom }.invoke()
            assertEquals(0, topFrames)
            assertEquals(1, bottomFrames)
            repeat(30) { viewport.viewTreeObserver.dispatchOnPreDraw() }
            assertEquals(2, source.adds)
            assertEquals(updates, source.updates)
            assertTrue(gate.draw(Canvas(), 1f, 2f))
            assertEquals(1f to 2f, source.lastPosition)
            assertTrue(gate.drawSharp(Canvas(), 3f, 4f, topBounds))
            assertEquals(3f to 4f, source.lastPosition)
            owner.registry.currentState = Lifecycle.State.STARTED
            assertTrue(source.listeners.isEmpty())
            assertTrue(source.regions.isEmpty())
            assertEquals(2, source.removes)
            assertFalse(gate.drawSharp(Canvas(), 0f, 0f, topBounds))
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertEquals(4, source.adds)
            assertEquals(RectF(40f, 700f, 300f, 760f) to true, source.regions[bottom])
            gate.removeListener(top)
            owner.registry.currentState = Lifecycle.State.DESTROYED
            assertTrue(source.listeners.isEmpty())
            assertEquals(4, source.removes)
            assertEquals(0, source.closes)
            assertNull(viewport.getTag(R.id.glass_backdrop))
        } finally {
            gate.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `late attachment and detachment replay retained demand only while attached`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val outer = FrameLayout(activity)
        val viewport = FrameLayout(activity)
        activity.setContentView(outer)
        val owner = Owner(Lifecycle.State.RESUMED)
        val source = Source()
        val gate = HomePageGlassBackdrop(viewport, owner, source)
        val consumer: () -> Unit = {}
        val bounds = RectF(0f, 0f, 100f, 50f)
        try {
            gate.addListener(consumer)
            gate.updateRegion(consumer, bounds, sharp = true)
            assertEquals(0, source.adds)
            outer.addView(viewport)
            assertEquals(1, source.adds)
            assertEquals(bounds to true, source.regions[consumer])
            assertSame(gate, viewport.getTag(R.id.glass_backdrop))
            outer.removeView(viewport)
            assertEquals(1, source.removes)
            assertTrue(source.listeners.isEmpty())
            outer.addView(viewport)
            assertEquals(2, source.adds)
            assertEquals(bounds to true, source.regions[consumer])
            gate.close()
            gate.close()
            assertEquals(2, source.removes)
            assertEquals(0, source.closes)
            assertNull(viewport.getTag(R.id.glass_backdrop))
        } finally {
            gate.close()
            controller.pause().stop().destroy()
        }
    }

    private class Owner(state: Lifecycle.State) : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = state }
        override val lifecycle: Lifecycle get() = registry
    }

    private class Source : GlassBackdrop {
        val listeners = linkedSetOf<() -> Unit>()
        val regions = linkedMapOf<() -> Unit, Pair<RectF?, Boolean>>()
        var adds = 0
        var removes = 0
        var updates = 0
        var invalidations = 0
        var closes = 0
        var lastPosition: Pair<Float, Float>? = null
        override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean {
            lastPosition = consumerWindowX to consumerWindowY
            return true
        }
        override fun drawSharp(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float, bounds: RectF): Boolean =
            draw(canvas, consumerWindowX, consumerWindowY)
        override fun addListener(listener: () -> Unit) { listeners += listener; adds++ }
        override fun removeListener(listener: () -> Unit) { listeners -= listener; regions.remove(listener); removes++ }
        override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
            updates++
            regions[listener] = boundsInWindow?.let(::RectF) to sharp
        }
        override fun invalidate() { invalidations++; listeners.toList().forEach { it() } }
        override fun close() { closes++ }
    }
}
