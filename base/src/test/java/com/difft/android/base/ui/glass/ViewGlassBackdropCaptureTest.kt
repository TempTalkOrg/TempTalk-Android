package com.difft.android.base.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderNode
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewGlassBackdropCaptureTest {
    private class RegionalView(context: Context) : ViewGroup(context), GlassDamageSource {
        override val glassDamage = GlassRegionDirtyTracker(this)
        val targets = mutableListOf<RenderNode>()
        var requestedFrames = 0
        override fun isHardwareAccelerated() = true
        override fun isAttachedToWindow() = true
        override fun isShown() = true
        override fun postInvalidateOnAnimation() { requestedFrames++ }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            getChildAt(0)?.layout(0, 0, width, 60)
            getChildAt(1)?.layout(0, 900, width, 960)
        }
        override fun draw(canvas: Canvas) {
            targets += ReflectionHelpers.getField<RenderNode>(canvas, "mNode")
            canvas.drawColor(Color.RED)
        }
    }
    private class CaptureView(context: Context) : View(context) {
        val targets = mutableListOf<RenderNode>()

        
        override fun isHardwareAccelerated() = true
        override fun isAttachedToWindow() = true
        override fun isShown() = true
        override fun postInvalidateOnAnimation() = Unit

        override fun draw(canvas: Canvas) {
            
            targets += ReflectionHelpers.getField<RenderNode>(canvas, "mNode")
            canvas.drawColor(Color.RED)
        }
    }

    @Test
    fun `each capture targets a never reused inner node while the blur node stays stable`() {
        val view = captureView()
        val backdrop = ViewGlassBackdrop(view, view, null)
        subscribe(backdrop, view) {}
        try {
            capture(backdrop, view)
            val blurNode = blurNode(backdrop)
            repeat(2) {
                val previous = view.targets.last()
                assertTrue(previous.hasDisplayList())
                capture(backdrop, view)
                assertSame(blurNode, blurNode(backdrop))
                assertFalse("replaced captures must release their display lists", previous.hasDisplayList())
            }

            assertEquals(3, view.targets.size)
            assertEquals("a two-node pool would retain stretch on the third capture", 3, view.targets.toSet().size)
            view.targets.forEach { assertNotSame("Views must never draw into the blur node", blurNode, it) }
            assertTrue(view.targets.last().hasDisplayList())
            assertTrue(blurNode.hasDisplayList())
        } finally {
            backdrop.close()
        }
    }

    @Test
    fun `demand removal detach and close release both nodes and restart with fresh targets`() {
        val view = captureView()
        val backdrop = ViewGlassBackdrop(view, view, null)
        val consumer: () -> Unit = {}
        subscribe(backdrop, view, consumer)
        try {
            capture(backdrop, view)
            val firstBlur = blurNode(backdrop)
            backdrop.removeListener(consumer)
            assertFalse(firstBlur.hasDisplayList())
            assertFalse(view.targets.last().hasDisplayList())

            backdrop.addListener(consumer)
            backdrop.updateRegion(consumer, RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()))
            capture(backdrop, view)
            val secondBlur = blurNode(backdrop)
            assertNotSame(firstBlur, secondBlur)
            backdrop.onViewDetachedFromWindow(view)
            assertFalse(secondBlur.hasDisplayList())
            assertFalse(view.targets.last().hasDisplayList())

            backdrop.onViewAttachedToWindow(view)
            capture(backdrop, view)
            val thirdBlur = blurNode(backdrop)
            assertNotSame(secondBlur, thirdBlur)
            backdrop.close()
            assertFalse(thirdBlur.hasDisplayList())
            assertFalse(view.targets.last().hasDisplayList())
            capture(backdrop, view)
            assertEquals("closed sources cannot record again", 3, view.targets.size)
            assertEquals(3, view.targets.toSet().size)
        } finally {
            backdrop.close()
        }
    }

    @Test
    fun `sharp input is full-resolution bounded cached and isolated from every earlier recording`() {
        val view = captureView()
        var backgroundDraws = 0
        var notifications = 0
        val backdrop = ViewGlassBackdrop(view, view, { backgroundDraws++ })
        val consumer: () -> Unit = { notifications++ }
        val output = RenderNode("SharpConsumer").apply { setPosition(0, 0, 80, 80) }
        subscribe(backdrop, view, consumer)
        try {
            capture(backdrop, view)
            val blurCapture = view.targets.single()
            val canvas = output.beginRecording()
            try {
                val bounds = RectF(-3.4f, -2.2f, 43.1f, 30.3f)
                assertTrue(backdrop.drawSharp(canvas, 100f, 200f, bounds))
                val sharp = view.targets.last()
                assertNotSame(blurCapture, sharp)
                assertEquals(48, sharp.width)
                assertEquals(34, sharp.height)
                assertEquals(2, backgroundDraws)
                assertEquals(1, notifications)
                repeat(30) { assertTrue(backdrop.drawSharp(canvas, 100f, 200f, bounds)) }
                assertEquals("unchanged crop must reuse its immutable recording", 2, view.targets.size)
                assertEquals("sharp draws cannot notify their own consumers", 1, notifications)

                bounds.offset(10f, 0f)
                assertTrue(backdrop.drawSharp(canvas, 100f, 200f, bounds))
                val moved = view.targets.last()
                assertNotSame(sharp, moved)
                assertTrue("another consumer can still reference the earlier crop", sharp.hasDisplayList())
                repeat(30) {
                    assertTrue(backdrop.drawSharp(canvas, 90f, 200f, bounds))
                    assertTrue(backdrop.drawSharp(canvas, 100f, 200f, bounds))
                }
                assertEquals("two stable consumers must not evict each other's crops", 3, view.targets.size)
                assertEquals(1, notifications)
                assertTrue(backdrop.drawSharp(canvas, 110f, 200f, bounds))
                assertEquals(4, view.targets.size)
                repeat(30) {
                    assertTrue(backdrop.drawSharp(canvas, 90f, 200f, bounds))
                    assertTrue(backdrop.drawSharp(canvas, 100f, 200f, bounds))
                    assertTrue(backdrop.drawSharp(canvas, 110f, 200f, bounds))
                }
                assertEquals("panel, passive row and pill must retain all three stable crops", 4, view.targets.size)
                
                
                assertTrue(backdrop.drawSharp(canvas, 120f, 200f, bounds))
                assertEquals(5, view.targets.size)
                assertEquals(3, ReflectionHelpers.getField<Map<*, *>>(backdrop, "sharpNodes").size)
                assertTrue(sharp.hasDisplayList())
                capture(backdrop, view)
                assertTrue(backdrop.drawSharp(canvas, 100f, 200f, bounds))
                assertNotSame("source mutations require a fresh sharp target", moved, view.targets.last())
                assertEquals(7, view.targets.toSet().size)

                backdrop.removeListener(consumer)
                assertFalse(view.targets.last().hasDisplayList())
                val count = view.targets.size
                assertFalse(backdrop.drawSharp(canvas, 100f, 200f, bounds))
                assertEquals(count, view.targets.size)
            } finally {
                output.endRecording()
            }
        } finally {
            backdrop.close()
            output.discardDisplayList()
        }
    }

    @Test
    fun `only registered top and bottom regions capture and bottom damage leaves top and sharp top cached`() {
        val view = regionalView()
        val backdrop = ViewGlassBackdrop(view, view, null)
        var topChanges = 0
        var bottomChanges = 0
        var sharpChanges = 0
        val top: () -> Unit = { topChanges++ }
        val bottom: () -> Unit = { bottomChanges++ }
        val sharp: () -> Unit = { sharpChanges++ }
        val target = RenderNode("RegionalConsumer").apply { setPosition(0, 0, 200, 100) }
        try {
            
            backdrop.addListener(top)
            view.viewTreeObserver.dispatchOnPreDraw()
            assertTrue(view.targets.isEmpty())
            backdrop.updateRegion(top, RectF(0f, 0f, 400f, 80f))
            backdrop.addListener(bottom)
            backdrop.updateRegion(bottom, RectF(0f, 920f, 400f, 1000f))
            backdrop.addListener(sharp)
            backdrop.updateRegion(sharp, RectF(40f, 20f, 160f, 60f), sharp = true)
            view.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(2, view.targets.size)
            assertTrue(view.targets.all { it.width == 100 && it.height == 26 })
            val topCapture = view.targets[0]
            val bottomCapture = view.targets[1]
            val canvas = target.beginRecording()
            try {
                val sharpBounds = RectF(40f, 20f, 160f, 60f)
                assertTrue(backdrop.drawSharp(canvas, 0f, 0f, sharpBounds))
                val sharpCapture = view.targets.last()
                assertEquals(3, view.targets.size)
                val requestedFrames = view.requestedFrames
                repeat(30) {
                    backdrop.updateRegion(top, RectF(0f, 0f, 400f, 80f))
                    view.viewTreeObserver.dispatchOnPreDraw()
                    assertTrue(backdrop.drawSharp(canvas, 0f, 0f, sharpBounds))
                }
                assertEquals(3, view.targets.size)
                assertEquals(requestedFrames, view.requestedFrames)
                assertEquals(1, topChanges)
                assertEquals(1, bottomChanges)
                assertEquals(1, sharpChanges)

                view.glassDamage.descendantInvalidated(view.getChildAt(1))
                view.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("only the bottom texture is re-recorded", 4, view.targets.size)
                assertTrue(topCapture.hasDisplayList())
                assertFalse(bottomCapture.hasDisplayList())
                assertEquals(1, topChanges)
                assertEquals(2, bottomChanges)
                assertEquals(1, sharpChanges)
                assertTrue(backdrop.drawSharp(canvas, 0f, 0f, sharpBounds))
                assertEquals(4, view.targets.size)
                assertTrue(sharpCapture.hasDisplayList())

                view.glassDamage.descendantInvalidated(view.getChildAt(0))
                view.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(5, view.targets.size)
                assertEquals(2, topChanges)
                assertEquals(2, sharpChanges)
                assertTrue(backdrop.drawSharp(canvas, 0f, 0f, sharpBounds))
                assertEquals(6, view.targets.size)
                assertNotSame(sharpCapture, view.targets.last())
            } finally { target.endRecording() }
        } finally {
            backdrop.close()
            target.discardDisplayList()
        }
    }

    @Test
    fun `transient source retains full commands once while effects remain cropped`() {
        val view = regionalView()
        val backdrop = ViewGlassBackdrop(view, view, null)
        val top: () -> Unit = {}
        val bottom: () -> Unit = {}
        val target = RenderNode("TransientConsumer").apply { setPosition(0, 0, 200, 100) }
        try {
            backdrop.addListener(top)
            backdrop.updateRegion(top, RectF(0f, 0f, 400f, 80f))
            backdrop.addListener(bottom)
            backdrop.updateRegion(bottom, RectF(0f, 920f, 400f, 1000f))
            
            
            view.getChildAt(1).layout(0, 1100, 400, 1160)
            view.setHasTransientState(true)
            view.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(1, view.targets.size)
            assertEquals(400, view.targets.single().width)
            assertEquals(1160, view.targets.single().height)
            val outputs = ReflectionHelpers.getField<List<Any>>(backdrop, "regions")
            outputs.forEach {
                val node = ReflectionHelpers.getField<RenderNode>(it, "node")
                assertEquals(100, node.width)
                assertEquals(26, node.height)
            }
            val canvas = target.beginRecording()
            try {
                assertTrue(backdrop.drawSharp(canvas, 0f, 0f, RectF(40f, 20f, 160f, 60f)))
                assertEquals("sharp crop references shared commands without another View traversal", 1, view.targets.size)
            } finally { target.endRecording() }
            view.setHasTransientState(false)
            view.viewTreeObserver.dispatchOnPreDraw()
            assertEquals("ordinary selective capture resumes in both cropped regions", 3, view.targets.size)
        } finally {
            backdrop.close()
            target.discardDisplayList()
        }
    }

    @Test
    fun `overscroll keeps the original stretch target even with off-viewport transient children`() {
        val view = regionalView()
        val backdrop = ViewGlassBackdrop(view, view, null)
        val consumer: () -> Unit = {}
        try {
            backdrop.addListener(consumer)
            backdrop.updateRegion(consumer, RectF(0f, 0f, 400f, 80f))
            view.getChildAt(0).layout(0, -100, 400, -40)
            view.getChildAt(1).layout(0, 1100, 400, 1160)
            view.setHasTransientState(true)
            view.glassDamage.overscrollActive = true
            view.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(1, view.targets.size)
            assertEquals(400, view.targets.last().width)
            assertEquals(1000, view.targets.last().height)
            assertEquals(GlassSharpRegion(0, 0, 400, 1000),
                ReflectionHelpers.getField<GlassSharpRegion>(backdrop, "sharedBounds"))

            view.glassDamage.overscrollActive = false
            view.viewTreeObserver.dispatchOnPreDraw()
            assertEquals("transient-only capture can retain the wider attached footprint", 2, view.targets.size)
            assertEquals(1260, view.targets.last().height)
            assertEquals(GlassSharpRegion(0, -100, 400, 1160),
                ReflectionHelpers.getField<GlassSharpRegion>(backdrop, "sharedBounds"))
        } finally { backdrop.close() }
    }

    private fun regionalView() = RegionalView(ApplicationProvider.getApplicationContext()).apply {
        clipToPadding = false
        addView(View(context))
        addView(View(context))
        measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        layout(0, 0, 400, 1000)
    }

    private fun captureView() = CaptureView(ApplicationProvider.getApplicationContext()).apply {
        layout(0, 0, 320, 640)
    }

    private fun capture(backdrop: ViewGlassBackdrop, view: View) {
        backdrop.invalidate()
        view.viewTreeObserver.dispatchOnPreDraw()
    }

    private fun subscribe(backdrop: ViewGlassBackdrop, view: View, listener: () -> Unit) {
        backdrop.addListener(listener)
        backdrop.updateRegion(listener, RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()))
    }

    private fun blurNode(backdrop: ViewGlassBackdrop): RenderNode {
        val regions = ReflectionHelpers.getField<List<Any>>(backdrop, "regions")
        return ReflectionHelpers.getField(regions.single(), "node")
    }
}
