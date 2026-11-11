package com.difft.android.base.ui.glass

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.RenderNode
import android.widget.EdgeEffect
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassEdgeEffectFactoryTest {
    @Test
    fun `pull release finish and multiple edges report the platform lifetime`() {
        val source = RecyclerView(ApplicationProvider.getApplicationContext())
        val damage = GlassRegionDirtyTracker(source)
        damage.acquire()
        val factory = GlassEdgeEffectFactory(damage)
        val top = factory.createEdgeEffect(source, RecyclerView.EdgeEffectFactory.DIRECTION_TOP)
        val bottom = factory.createEdgeEffect(source, RecyclerView.EdgeEffectFactory.DIRECTION_BOTTOM)
        val standard = EdgeEffect(source.context)
        top.setSize(300, 600)
        bottom.setSize(300, 600)
        standard.setSize(300, 600)
        assertFalse(damage.requiresFullCapture)
        assertEquals(standard.onPullDistance(0.2f, 0.5f), top.onPullDistance(0.2f, 0.5f), 0f)
        assertEquals(standard.distance, top.distance, 0f)
        assertEquals(standard.isFinished, top.isFinished)
        
        assumeFalse(standard.isFinished)
        assertTrue(damage.overscrollActive)
        assertTrue(damage.requiresFullCapture)

        top.onRelease()
        standard.onRelease()
        assertEquals(standard.isFinished, top.isFinished)
        bottom.onAbsorb(2000)
        top.finish()
        assertTrue("another active edge keeps the shared capture", damage.overscrollActive)
        bottom.finish()
        assertFalse(damage.overscrollActive)
        assertFalse(damage.requiresFullCapture)
        factory.reset()
        damage.release()
    }

    @Test
    fun `a detach settles the edges but keeps tracking the objects RecyclerView reuses`() {
        val source = RecyclerView(ApplicationProvider.getApplicationContext())
        val damage = GlassRegionDirtyTracker(source)
        damage.acquire()
        val factory = GlassEdgeEffectFactory(damage)
        val top = factory.createEdgeEffect(source, RecyclerView.EdgeEffectFactory.DIRECTION_TOP)
        factory.finishAll()
        assertTrue(top.isFinished)
        assertFalse(damage.overscrollActive)
        val tracked = org.robolectric.util.ReflectionHelpers.getField<Array<EdgeEffect?>>(factory, "edges")
        assertTrue("the reused edge stays tracked after a detach", tracked[RecyclerView.EdgeEffectFactory.DIRECTION_TOP] === top)
        factory.reset()
        assertTrue(tracked.all { it == null })
        damage.release()
    }

    @Test
    fun `native draw and reset keep state in sync without scheduling tracker frames`() {
        val source = RecyclerView(ApplicationProvider.getApplicationContext())
        source.layout(0, 0, 300, 600)
        val damage = GlassRegionDirtyTracker(source)
        damage.acquire()
        val factory = GlassEdgeEffectFactory(damage)
        val edge = factory.createEdgeEffect(source, RecyclerView.EdgeEffectFactory.DIRECTION_TOP)
        edge.setSize(300, 600)
        val target = RenderNode("GlassEdgeEffectTest").apply { setPosition(0, 0, 300, 600) }
        try {
            edge.onPull(0.2f, 0.5f)
            val canvas: Canvas = target.beginRecording()
            try { edge.draw(canvas) } finally { target.endRecording() }
            assertEquals(!edge.isFinished, damage.overscrollActive)
            factory.reset()
            assertTrue(edge.isFinished)
            assertFalse(damage.overscrollActive)
            damage.beginFrame()
            damage.endFrame()
            repeat(30) {
                damage.beginFrame()
                assertFalse(damage.isDirty(RectF(0f, 0f, 300f, 600f)))
                damage.endFrame()
            }
        } finally {
            factory.reset()
            damage.release()
            target.discardDisplayList()
        }
    }
}
