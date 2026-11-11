package com.difft.android.base.ui.glass

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.EqMatcher
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewGlassBackdropPublicationTest {
    
    
    
    private val assignedEffects = mutableListOf<RenderEffect?>()
    private val outputs = mutableListOf<Output>()

    @Before
    fun observeNativeEffectAssignments() {
        mockkConstructor(RenderNode::class)
        every { constructedWith<RenderNode>(EqMatcher("GlassBackdropRegion")).setRenderEffect(any()) } answers {
            val result = callOriginal()
            assignedEffects += firstArg<RenderEffect?>()
            result
        }
    }

    @After
    fun releaseConstructorSpy() {
        outputs.forEach { it.close() }
        unmockkConstructor(RenderNode::class)
    }

    private class Source(context: Context) : ViewGroup(context), GlassDamageSource {
        override val glassDamage = GlassRegionDirtyTracker(this)
        val captures = mutableListOf<RenderNode>()
        var onCapture: (() -> Unit)? = null
        var failCapture = false
        var windowX = 0
        var windowY = 0
        override fun isHardwareAccelerated() = true
        override fun isAttachedToWindow() = true
        override fun isShown() = true
        override fun postInvalidateOnAnimation() = Unit
        override fun getLocationInWindow(location: IntArray) { location[0] = windowX; location[1] = windowY }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
        override fun draw(canvas: Canvas) {
            captures += ReflectionHelpers.getField<RenderNode>(canvas, "mNode")
            onCapture?.invoke()
            canvas.drawColor(Color.RED)
            if (failCapture) error("capture failure")
        }
    }

    
    private inner class Output(
        val area: Rect,
        private val requireBlur: Boolean = true,
        private val backing: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
    ) : Canvas(backing), AutoCloseable {
        init {
            
            
            assertTrue(ReflectionHelpers.getField<Long>(this, "mNativeCanvasWrapper") != 0L)
            outputs += this
        }
        val nodes = mutableListOf<RenderNode>()
        val transforms = mutableListOf<FloatArray>()
        var saves = 0
        override fun isHardwareAccelerated() = true
        override fun getClipBounds(bounds: Rect): Boolean { bounds.set(area); return !area.isEmpty }
        override fun save(): Int { saves++; return super.save() }
        @Suppress("DEPRECATION")
        override fun drawRenderNode(renderNode: RenderNode) {
            if (requireBlur) assertTrue("blur output setter must run before publication", assignedEffects.lastOrNull() != null)
            nodes += renderNode
            transforms += FloatArray(9).also { values -> Matrix().also(::getMatrix).getValues(values) }
        }
        override fun close() = backing.recycle()
    }

    @Test
    fun `moving and merged regions cannot publish partial blur before capture`() {
        val source = source()
        val backdrop = ViewGlassBackdrop(source, source, null)
        val consumer: () -> Unit = {}
        try {
            backdrop.addListener(consumer)
            backdrop.updateRegion(consumer, RectF(0f, 0f, 400f, 80f))
            source.viewTreeObserver.dispatchOnPreDraw()
            val initial = Output(Rect(0, 0, 400, 80))
            assertTrue(backdrop.draw(initial, 0f, 0f))
            assertFiltered(backdrop, initial.nodes.single(), source)

            backdrop.updateRegion(consumer, RectF(0f, 160f, 400f, 240f))
            val moved = Output(Rect(0, 160, 400, 240))
            assertFalse("the old tile does not cover the moved consumer; it must stay opaque", backdrop.draw(moved, 0f, 0f))
            assertTrue(moved.nodes.isEmpty())
            assertEquals("failure must be decided before touching the output canvas", 0, moved.saves)
            source.viewTreeObserver.dispatchOnPreDraw()
            assertTrue(backdrop.draw(moved, 0f, 0f))
            assertFiltered(backdrop, moved.nodes.single(), source)

            val bottom: () -> Unit = {}
            backdrop.addListener(bottom)
            backdrop.updateRegion(bottom, RectF(0f, 920f, 400f, 1000f))
            source.viewTreeObserver.dispatchOnPreDraw()
            val spanning = Output(Rect(0, 160, 400, 1000))
            assertFalse("separate blur tiles leave an uncovered middle", backdrop.draw(spanning, 0f, 0f))
            assertTrue(spanning.nodes.isEmpty())
            val bridge: () -> Unit = {}
            backdrop.addListener(bridge)
            backdrop.updateRegion(bridge, RectF(0f, 230f, 400f, 880f))
            assertFalse(backdrop.draw(spanning, 0f, 0f))
            source.viewTreeObserver.dispatchOnPreDraw()
            assertTrue(backdrop.draw(spanning, 0f, 0f))
            assertFiltered(backdrop, spanning.nodes.single(), source)
            backdrop.removeListener(bridge)
            source.viewTreeObserver.dispatchOnPreDraw()
            assertFalse(backdrop.draw(Output(Rect(0, 160, 400, 1000)), 0f, 0f))
        } finally { backdrop.close() }
    }

    @Test
    fun `configuration resize overscroll and settle publish only filtered region outputs`() {
        val source = source()
        val backdrop = ViewGlassBackdrop(source, source, null)
        val consumer: () -> Unit = {}
        val originalMode = source.resources.configuration.uiMode
        try {
            backdrop.addListener(consumer)
            backdrop.updateRegion(consumer, RectF(0f, 0f, 400f, 80f))
            source.viewTreeObserver.dispatchOnPreDraw()
            val initialRegion = regions(backdrop).single()
            val initialEffect = ReflectionHelpers.getField<RenderEffect>(initialRegion, "effect")
            val initialNode = ReflectionHelpers.getField<RenderNode>(initialRegion, "node")
            source.resources.configuration.uiMode = originalMode xor Configuration.UI_MODE_NIGHT_MASK
            source.viewTreeObserver.dispatchOnPreDraw()
            val configured = regions(backdrop).single()
            assertSame(initialNode, ReflectionHelpers.getField<RenderNode>(configured, "node"))
            assertNotSame(initialEffect, ReflectionHelpers.getField<RenderEffect>(configured, "effect"))

            source.layout(0, 0, 420, 1000)
            backdrop.updateRegion(consumer, RectF(0f, 0f, 420f, 80f))
            source.viewTreeObserver.dispatchOnPreDraw()
            source.glassDamage.overscrollActive = true
            source.viewTreeObserver.dispatchOnPreDraw()
            val shared = source.captures.last()
            assertEquals(420, shared.width)
            assertEquals(1000, shared.height)
            val edgeOutput = Output(Rect(0, 0, 420, 80))
            assertTrue(backdrop.draw(edgeOutput, 0f, 0f))
            assertFiltered(backdrop, edgeOutput.nodes.single(), source)
            assertNotSame(shared, edgeOutput.nodes.single())

            source.glassDamage.overscrollActive = false
            source.viewTreeObserver.dispatchOnPreDraw()
            val settled = Output(Rect(0, 0, 420, 80))
            assertTrue(backdrop.draw(settled, 0f, 0f))
            assertFiltered(backdrop, settled.nodes.single(), source)
            val recordings = source.captures.size
            repeat(30) {
                source.viewTreeObserver.dispatchOnPreDraw()
                val output = Output(Rect(0, 0, 420, 80))
                assertTrue(backdrop.draw(output, 0f, 0f))
                assertFiltered(backdrop, output.nodes.single(), source)
            }
            assertEquals(recordings, source.captures.size)
        } finally {
            source.resources.configuration.uiMode = originalMode
            backdrop.close()
        }
    }

    @Test
    fun `capture is unavailable reentrantly and failure retries without publishing a partial node`() {
        val source = source()
        val backdrop = ViewGlassBackdrop(source, source, null)
        val consumer: () -> Unit = {}
        try {
            backdrop.addListener(consumer)
            backdrop.updateRegion(consumer, RectF(0f, 0f, 400f, 80f))
            source.viewTreeObserver.dispatchOnPreDraw()
            source.onCapture = {
                assertFalse(backdrop.draw(Output(Rect(0, 0, 400, 80)), 0f, 0f))
                assertFalse(backdrop.drawSharp(Output(Rect(0, 0, 40, 40)), 0f, 0f, RectF(0f, 0f, 40f, 40f)))
            }
            source.failCapture = true
            backdrop.invalidate()
            try {
                source.viewTreeObserver.dispatchOnPreDraw()
                fail("production must reach the failing source draw")
            } catch (expected: IllegalStateException) {
                assertEquals("capture failure", expected.message)
            }
            val output = Output(Rect(0, 0, 400, 80))
            assertFalse(backdrop.draw(output, 0f, 0f))
            assertTrue(output.nodes.isEmpty())
            assertFalse(ReflectionHelpers.getField<Boolean>(regions(backdrop).single(), "recorded"))
            source.failCapture = false
            source.onCapture = null
            source.viewTreeObserver.dispatchOnPreDraw()
            assertTrue(backdrop.draw(output, 0f, 0f))
            assertFiltered(backdrop, output.nodes.single(), source)
        } finally { backdrop.close() }
    }

    @Test
    fun `failed whole-source transition cannot publish a new origin for the old recording`() {
        val source = source()
        val backdrop = ViewGlassBackdrop(source, source, null)
        val consumer: () -> Unit = {}
        try {
            source.glassDamage.overscrollActive = true
            backdrop.addListener(consumer)
            backdrop.updateRegion(consumer, RectF(0f, 0f, 400f, 80f))
            source.viewTreeObserver.dispatchOnPreDraw()
            val previous = ReflectionHelpers.getField<GlassSharpRegion>(backdrop, "sharedBounds")
            source.windowX = 30
            source.windowY = 50
            backdrop.updateRegion(consumer, RectF(30f, 50f, 430f, 130f))
            source.failCapture = true
            try {
                source.viewTreeObserver.dispatchOnPreDraw()
                fail("production must reach the failing whole-source draw")
            } catch (expected: IllegalStateException) {
                assertEquals("capture failure", expected.message)
            }
            assertEquals(previous, ReflectionHelpers.getField<GlassSharpRegion>(backdrop, "sharedBounds"))
            assertFalse(backdrop.draw(Output(Rect(0, 0, 400, 80)), 30f, 50f))
            source.failCapture = false
            source.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(GlassSharpRegion(30, 50, 430, 1050),
                ReflectionHelpers.getField<GlassSharpRegion>(backdrop, "sharedBounds"))
            val output = Output(Rect(0, 0, 400, 80))
            assertTrue(backdrop.draw(output, 30f, 50f))
            assertFiltered(backdrop, output.nodes.single(), source)
        } finally { backdrop.close() }
    }

    @Test
    fun `window offset changes move filtered output and old crops are never ordinary output`() {
        val source = source()
        val backdrop = ViewGlassBackdrop(source, source, null)
        val consumer: () -> Unit = {}
        try {
            backdrop.addListener(consumer)
            backdrop.updateRegion(consumer, RectF(0f, 0f, 400f, 80f))
            source.viewTreeObserver.dispatchOnPreDraw()
            val raw = Output(Rect(0, 0, 40, 40), requireBlur = false)
            assertTrue(backdrop.drawSharp(raw, 0f, 0f, RectF(0f, 0f, 40f, 40f)))
            val sharp = raw.nodes.single()
            source.windowX = 30
            source.windowY = 50
            backdrop.updateRegion(consumer, RectF(30f, 50f, 430f, 130f))
            source.viewTreeObserver.dispatchOnPreDraw()
            val output = Output(Rect(0, 0, 400, 80))
            assertTrue(backdrop.draw(output, 30f, 50f))
            assertNotSame(sharp, output.nodes.single())
            assertFiltered(backdrop, output.nodes.single(), source)
            assertEquals(GlassTuning.DOWNSAMPLE, output.transforms.single()[Matrix.MSCALE_X], 0f)
            assertEquals(0f, output.transforms.single()[Matrix.MTRANS_X], 0f)
            assertEquals(0f, output.transforms.single()[Matrix.MTRANS_Y], 0f)
        } finally { backdrop.close() }
    }

    private fun source() = Source(ApplicationProvider.getApplicationContext()).apply { layout(0, 0, 400, 1000) }
    private fun regions(backdrop: ViewGlassBackdrop): List<Any> = ReflectionHelpers.getField(backdrop, "regions")
    private fun assertFiltered(backdrop: ViewGlassBackdrop, node: RenderNode, source: Source) {
        val region = regions(backdrop).single { ReflectionHelpers.getField<RenderNode>(it, "node") === node }
        assertTrue(ReflectionHelpers.getField<Boolean>(region, "recorded"))
        assertSame(ReflectionHelpers.getField<RenderEffect>(backdrop, "blurEffect"),
            ReflectionHelpers.getField<RenderEffect>(region, "effect"))
        val effect = ReflectionHelpers.getField<RenderEffect>(region, "effect")
        assertSame("the output constructor's last native effect must match the published filter", effect, assignedEffects.last())
        verify(atLeast = 1) { constructedWith<RenderNode>(EqMatcher("GlassBackdropRegion")).setRenderEffect(effect) }
        assertTrue(node.hasDisplayList())
        assertTrue("ordinary output must never be a public View.draw capture", source.captures.none { it === node })
    }
}
