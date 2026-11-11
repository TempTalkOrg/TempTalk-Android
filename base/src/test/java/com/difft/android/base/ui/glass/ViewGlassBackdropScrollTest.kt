package com.difft.android.base.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.RenderNode
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.widget.NestedScrollView
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
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewGlassBackdropScrollTest {
    private class RecordingScrollView(context: Context) : NestedScrollView(context) {
        val targets = mutableListOf<RenderNode>()
        val transforms = mutableListOf<FloatArray>()
        var frames = 0
        var computations = 0
        override fun isHardwareAccelerated() = true
        override fun isAttachedToWindow() = true
        override fun isShown() = true
        
        override fun isDirty() = false
        override fun postInvalidateOnAnimation() { frames++ }
        override fun computeScroll() { computations++; super.computeScroll() }
        override fun draw(canvas: Canvas) {
            targets += ReflectionHelpers.getField<RenderNode>(canvas, "mNode")
            transforms += matrix(canvas)
            super.draw(canvas)
        }
    }

    @Test
    fun `unknown consumer area does not advance the nested source scroller`() {
        val view = scrollView()
        val backdrop = ViewGlassBackdrop(view, view, null)
        val listener: () -> Unit = {}
        try {
            backdrop.addListener(listener)
            val before = view.computations
            val frames = view.frames
            repeat(30) { view.viewTreeObserver.dispatchOnPreDraw() }
            assertEquals(before, view.computations)
            assertEquals(frames, view.frames)
            assertTrue(view.targets.isEmpty())
            backdrop.updateRegion(listener, RectF(0f, 0f, 320f, 80f))
            view.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(before + 1, view.computations)
            assertEquals(1, view.targets.size)
        } finally { backdrop.close() }
    }

    @Test
    fun `scroll offsets apply only to content while nested source commands retain viewport extent`() {
        val view = scrollView()
        val backgrounds = mutableListOf<FloatArray>()
        var changes = 0
        val listener: () -> Unit = { changes++ }
        val backdrop = ViewGlassBackdrop(view, view, { backgrounds += matrix(it) },
            captureInsets = { Insets.of(8, 24, 16, 0) })
        val target = RenderNode("ScrolledGlassConsumer").apply { setPosition(0, 0, 320, 100) }
        try {
            view.scrollTo(20, 200)
            assertEquals(20, view.scrollX)
            assertEquals(200, view.scrollY)
            backdrop.addListener(listener)
            backdrop.updateRegion(listener, RectF(0f, 0f, 320f, 80f))
            view.viewTreeObserver.dispatchOnPreDraw()
            val commands = view.targets.single()
            assertEquals(344, commands.width)
            assertEquals(664, commands.height)
            assertEquals(GlassSharpRegion(-8, -24, 336, 640),
                ReflectionHelpers.getField<GlassSharpRegion>(backdrop, "sharedBounds"))
            assertEquals(8f, backgrounds.single()[Matrix.MTRANS_X], 0f)
            assertEquals(24f, backgrounds.single()[Matrix.MTRANS_Y], 0f)
            assertEquals(-12f, view.transforms.single()[Matrix.MTRANS_X], 0f)
            assertEquals(-176f, view.transforms.single()[Matrix.MTRANS_Y], 0f)
            val regions = ReflectionHelpers.getField<List<Any>>(backdrop, "regions")
            val blur = ReflectionHelpers.getField<RenderNode>(regions.single(), "node")
            assertEquals(86, blur.width)
            assertTrue("blur texture stays regional", blur.height * GlassTuning.DOWNSAMPLE < commands.height)

            val canvas = target.beginRecording()
            try {
                assertTrue(backdrop.drawSharp(canvas, 0f, 0f, RectF(20f, 20f, 100f, 60f)))
                assertEquals("sharp ROI must reuse the parent commands, including its EdgeEffects", 1, view.targets.size)
                val frames = view.frames
                repeat(30) {
                    view.viewTreeObserver.dispatchOnPreDraw()
                    assertTrue(backdrop.drawSharp(canvas, 0f, 0f, RectF(20f, 20f, 100f, 60f)))
                }
                assertEquals(1, view.targets.size)
                assertEquals(1, changes)
                assertEquals(frames, view.frames)

                view.scrollTo(30, 260)
                view.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("own scroll alone dirties the capture", 2, view.targets.size)
                assertNotSame(commands, view.targets.last())
                assertSame(blur, ReflectionHelpers.getField<RenderNode>(
                    ReflectionHelpers.getField<List<Any>>(backdrop, "regions").single(), "node"))
                assertEquals(-22f, view.transforms.last()[Matrix.MTRANS_X], 0f)
                assertEquals(-236f, view.transforms.last()[Matrix.MTRANS_Y], 0f)
                assertEquals(2, changes)
                assertTrue(backdrop.drawSharp(canvas, 0f, 0f, RectF(20f, 20f, 100f, 60f)))
                assertEquals(2, view.targets.size)
                assertEquals(View.OVER_SCROLL_ALWAYS, view.overScrollMode)
            } finally { target.endRecording() }
            backdrop.close()
            assertFalse(view.targets.last().hasDisplayList())
            assertFalse(blur.hasDisplayList())
        } finally { backdrop.close(); target.discardDisplayList() }
    }

    @Test
    fun `capture advances the real nested fling before snapshot and same-time normal compute is inert`() {
        val view = scrollView()
        val backdrop = ViewGlassBackdrop(view, view, null)
        val listener: () -> Unit = {}
        try {
            view.scrollTo(0, 200)
            backdrop.addListener(listener)
            backdrop.updateRegion(listener, RectF(0f, 0f, 320f, 80f))
            view.viewTreeObserver.dispatchOnPreDraw()
            view.fling(1500)
            ShadowSystemClock.advanceBy(Duration.ofMillis(64))
            view.viewTreeObserver.dispatchOnPreDraw()
            val capturedScroll = view.scrollY
            assertTrue("fixture must advance the real platform scroller", capturedScroll > 200)
            assertEquals(2, view.targets.size)
            assertEquals(-capturedScroll.toFloat(), view.transforms.last()[Matrix.MTRANS_Y], 0f)
            view.computeScroll()
            assertEquals("normal drawing at the same animation time must not apply the delta twice", capturedScroll, view.scrollY)
        } finally { backdrop.close() }
    }

    private fun scrollView() = RecordingScrollView(ApplicationProvider.getApplicationContext()).apply {
        overScrollMode = View.OVER_SCROLL_ALWAYS
        clipToPadding = false
        addView(View(context).apply {
            minimumHeight = 2000
            setBackgroundColor(Color.RED)
        }, FrameLayout.LayoutParams(600, FrameLayout.LayoutParams.WRAP_CONTENT))
        measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        layout(0, 0, 320, 640)
    }

    companion object {
        @Suppress("DEPRECATION")
        private fun matrix(canvas: Canvas): FloatArray = FloatArray(9).also {
            val transform = Matrix()
            canvas.getMatrix(transform)
            transform.getValues(it)
        }
    }
}
