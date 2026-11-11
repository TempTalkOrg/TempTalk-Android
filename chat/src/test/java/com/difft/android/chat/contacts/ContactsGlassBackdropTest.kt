package com.difft.android.chat.contacts

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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContactsGlassBackdropTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
        override val lifecycle: Lifecycle get() = registry
    }
    private class Source : GlassBackdrop {
        val listeners = linkedSetOf<() -> Unit>()
        val regions = linkedMapOf<() -> Unit, Pair<RectF?, Boolean>>()
        var adds = 0
        var removes = 0
        var closes = 0
        var updates = 0
        var draws = 0
        var sharpDraws = 0
        var position: Pair<Float, Float>? = null
        override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean {
            draws++
            position = consumerWindowX to consumerWindowY
            return true
        }
        override fun drawSharp(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float, bounds: RectF): Boolean {
            sharpDraws++
            position = consumerWindowX to consumerWindowY
            return true
        }
        override fun addListener(listener: () -> Unit) { adds++; listeners += listener }
        override fun removeListener(listener: () -> Unit) { removes++; listeners -= listener; regions.remove(listener) }
        override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
            updates++
            regions[listener] = boundsInWindow?.let(::RectF) to sharp
        }
        override fun invalidate() = listeners.toList().forEach { it() }
        override fun close() { closes++ }
    }
    private val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
    private val activity = controller.get()
    private val owner = Owner()
    private val viewport = FrameLayout(activity)
    private val sources = List(2) { Source() }
    private var proxy: ContactsGlassBackdrop? = null

    private fun attach(): ContactsGlassBackdrop {
        activity.setContentView(viewport)
        settle()
        return ContactsGlassBackdrop(viewport, owner).also {
            proxy = it
            it.setSource(sources[0])
        }
    }

    private fun settle() {
        viewport.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        viewport.layout(0, 0, 400, 800)
        ShadowLooper.idleMainLooper()
        viewport.viewTreeObserver.dispatchOnPreDraw()
    }

    @After
    fun cleanUp() {
        proxy?.close()
        owner.registry.currentState = Lifecycle.State.DESTROYED
        controller.pause().stop().destroy()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `never resumed Contacts page cannot borrow its registered Compose source`() {
        val source = attach()
        val listener = {}
        source.addListener(listener)
        source.updateRegion(listener, RectF(0f, 0f, 400f, 80f))
        repeat(30) { viewport.viewTreeObserver.dispatchOnPreDraw() }
        assertTrue(sources.all { it.adds == 0 && it.draws == 0 })
        assertFalse(source.draw(Canvas(), 0f, 0f))
    }

    @Test
    fun `resumed page borrows once replays copied area and ignores other sources`() {
        val source = attach()
        var redraws = 0
        val listener: () -> Unit = { redraws++ }
        source.addListener(listener)
        val bounds = RectF(12f, 700f, 200f, 760f)
        source.updateRegion(listener, bounds, sharp = true)
        bounds.setEmpty()
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(1, sources[0].adds)
        assertEquals(0, sources[1].adds)
        assertEquals(RectF(12f, 700f, 200f, 760f), sources[0].regions[listener]!!.first)
        assertTrue(sources[0].regions[listener]!!.second)
        redraws = 0
        repeat(30) { viewport.viewTreeObserver.dispatchOnPreDraw() }
        sources[1].invalidate()
        assertEquals(0, redraws)
        assertEquals(1, sources[0].adds)
        assertEquals(1, sources[0].updates)
        assertTrue(source.drawSharp(Canvas(), 25f, 40f, RectF(0f, 0f, 50f, 40f)))
        assertEquals(25f to 40f, sources[0].position)
    }

    @Test
    fun `source replacement pause and resume transfer demand without owning source lifetime`() {
        val source = attach()
        owner.registry.currentState = Lifecycle.State.RESUMED
        val listener = {}
        source.addListener(listener)
        source.updateRegion(listener, RectF(0f, 0f, 400f, 80f))
        source.setSource(sources[1])
        assertTrue(sources[0].listeners.isEmpty())
        assertEquals(setOf(listener), sources[1].listeners)
        owner.registry.currentState = Lifecycle.State.STARTED
        assertTrue(sources.all { it.listeners.isEmpty() })
        assertFalse(source.draw(Canvas(), 0f, 0f))
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(setOf(listener), sources[1].listeners)
        source.close()
        assertTrue(sources.all { it.listeners.isEmpty() && it.closes == 0 })
        assertNull(viewport.getTag(R.id.glass_backdrop))
    }

    @Test
    fun `late source registration and detach recover without ancestor self resolution`() {
        val source = attach()
        source.setSource(null)
        owner.registry.currentState = Lifecycle.State.RESUMED
        val listener = {}
        source.addListener(listener)
        source.updateRegion(listener, RectF(0f, 0f, 100f, 80f))
        viewport.viewTreeObserver.dispatchOnPreDraw()
        assertFalse(source.draw(Canvas(), 0f, 0f))
        source.setSource(sources[0])
        assertEquals(1, sources[0].adds)
        val replacement = Source()
        source.setSource(replacement)
        assertTrue(sources[0].listeners.isEmpty())
        assertEquals(setOf(listener), replacement.listeners)
        source.onViewDetachedFromWindow(viewport)
        assertTrue(replacement.listeners.isEmpty())
        source.onViewAttachedToWindow(viewport)
        assertEquals(setOf(listener), replacement.listeners)
        source.close()
        assertEquals(0, replacement.closes)
    }

    @Test
    fun `destroying page clears home tag and demand without closing Compose host source`() {
        val source = attach()
        owner.registry.currentState = Lifecycle.State.RESUMED
        val listener = {}
        source.addListener(listener)
        source.updateRegion(listener, RectF(0f, 700f, 100f, 780f), sharp = true)
        owner.registry.currentState = Lifecycle.State.DESTROYED
        assertNull(viewport.getTag(R.id.glass_backdrop))
        assertTrue(sources[0].listeners.isEmpty())
        assertEquals(0, sources[0].closes)
        source.setSource(sources[1])
        source.addListener(listener)
        assertTrue(sources[1].listeners.isEmpty())
        assertFalse(source.draw(Canvas(), 0f, 0f))
    }
}
