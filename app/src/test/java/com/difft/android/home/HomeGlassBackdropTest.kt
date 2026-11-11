package com.difft.android.home

import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.difft.android.base.R
import com.difft.android.base.ui.glass.GlassBackdrop
import com.difft.android.base.ui.glass.GlassMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeGlassBackdropTest {
    private var previousMode: Boolean? = null

    @Before
    fun enableLiveMode() {
        previousMode = ReflectionHelpers.getStaticField(GlassMode::class.java, "live")
        ReflectionHelpers.setStaticField(GlassMode::class.java, "live", true)
    }

    private class Source : GlassBackdrop {
        val listeners = linkedSetOf<() -> Unit>()
        var adds = 0
        var removes = 0
        var closes = 0
        var invalidations = 0
        var draws = 0
        var sharpDraws = 0
        var lastPosition: Pair<Float, Float>? = null
        var lastSharpPosition: Pair<Float, Float>? = null
        var lastSharpBounds: RectF? = null
        val regions = linkedMapOf<() -> Unit, Pair<RectF?, Boolean>>()
        override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean {
            draws++
            lastPosition = consumerWindowX to consumerWindowY
            return true
        }
        override fun drawSharp(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float, bounds: RectF): Boolean {
            sharpDraws++
            lastSharpPosition = consumerWindowX to consumerWindowY
            lastSharpBounds = RectF(bounds)
            return true
        }
        override fun addListener(listener: () -> Unit) { adds++; listeners += listener }
        override fun removeListener(listener: () -> Unit) { removes++; listeners -= listener; regions.remove(listener) }
        override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
            regions[listener] = boundsInWindow?.let(::RectF) to sharp
        }
        override fun invalidate() { invalidations++; listeners.toList().forEach { it() } }
        override fun close() { closes++ }
    }

    private val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
    private val activity = controller.get()
    private val sources = List(3) { Source() }
    private val pageLeaves = arrayOfNulls<View>(3)
    private val viewport = FrameLayout(activity)
    private val outer = FrameLayout(activity)
    private val pager = ViewPager2(activity).apply {
        offscreenPageLimit = 1
        isUserInputEnabled = false
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = sources.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(FrameLayout(activity).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val page = holder.itemView as FrameLayout
                page.removeAllViews()
                pageLeaves[position] = View(activity).also {
                    it.setTag(R.id.glass_backdrop, sources[position])
                    page.addView(it)
                }
            }
        }
    }
    private var proxy: HomeGlassBackdrop? = null

    private fun attach(): HomeGlassBackdrop {
        viewport.addView(pager, FrameLayout.LayoutParams(-1, -1))
        outer.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(outer)
        settleLayout()
        assertNotNull("the real pager must have attached its current adapter holder", pageLeaves[0])
        return HomeGlassBackdrop(viewport, pager).also { proxy = it }
    }

    private fun settleLayout() {
        outer.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        outer.layout(0, 0, 400, 800)
        ShadowLooper.idleMainLooper()
        outer.viewTreeObserver.dispatchOnPreDraw()
    }

    @After
    fun cleanUp() {
        proxy?.close()
        controller.pause().stop().destroy()
        ShadowLooper.idleMainLooper()
        ReflectionHelpers.setStaticField(GlassMode::class.java, "live", previousMode)
    }

    @Test
    fun `unsupported mode never subscribes or draws a page source`() {
        ReflectionHelpers.setStaticField(GlassMode::class.java, "live", false)
        val source = attach()
        source.addListener {}
        repeat(20) { viewport.viewTreeObserver.dispatchOnPreDraw() }
        assertTrue(sources.all { it.adds == 0 && it.draws == 0 })
        assertFalse(source.draw(Canvas(), 0f, 0f))
    }

    @Test
    fun `demand created before the pager attaches resolves its page source on pre-draw`() {
        val source = HomeGlassBackdrop(viewport, pager).also { proxy = it }
        var redraws = 0
        source.addListener { redraws++ }
        assertEquals(0, sources[0].adds)
        assertFalse(source.draw(Canvas(), 0f, 0f))
        viewport.addView(pager, FrameLayout.LayoutParams(-1, -1))
        outer.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(outer)
        settleLayout()
        assertEquals(1, sources[0].adds)
        assertEquals(1, redraws)
        assertTrue(source.draw(Canvas(), 0f, 0f))
        assertEquals(0, sources[1].adds)
    }

    @Test
    fun `stable idle frames borrow once and offscreen invalidation creates no home demand`() {
        val source = attach()
        var redraws = 0
        val consumer = { redraws++; Unit }
        source.addListener(consumer)
        source.addListener(consumer)
        assertNotNull("the adjacent page is attached, exercising offscreen exclusion", pageLeaves[1])
        assertEquals(1, sources[0].adds)
        assertEquals(0, sources[1].adds)
        redraws = 0
        repeat(20) { viewport.viewTreeObserver.dispatchOnPreDraw() }
        sources[1].invalidate()
        assertEquals("idle pre-draws and offscreen updates must not redraw the capsule", 0, redraws)
        assertEquals(1, sources[0].adds)
        assertEquals(0, sources[0].removes)
        assertEquals(0, sources[0].invalidations)
        assertTrue(source.draw(Canvas(), 12f, 34f))
        assertEquals(12f to 34f, sources[0].lastPosition)
        assertEquals(1, sources[0].draws)
        assertEquals(0, sources[1].draws)
        source.removeListener(consumer)
        assertEquals(1, sources[0].removes)
        assertEquals(0, sources[0].closes)
    }

    @Test
    fun `switching pages releases prior home demand and returning borrows its current source once`() {
        val source = attach()
        source.addListener {}
        pager.setCurrentItem(HomeTab.CONTACTS, false)
        settleLayout()
        assertEquals(1, sources[0].removes)
        assertEquals(0, sources[0].closes)
        assertEquals(1, sources[1].adds)
        assertTrue(source.draw(Canvas(), 0f, 0f))
        assertEquals(1, sources[1].draws)
        pager.setCurrentItem(HomeTab.CHATS, false)
        settleLayout()
        assertEquals(2, sources[0].adds)
        assertTrue(sources[1].listeners.isEmpty())
        assertEquals(0, sources[1].closes)
        assertTrue(source.draw(Canvas(), 0f, 0f))
    }

    @Test
    fun `all three selected pages borrow independently forward both draw paths and ignore offscreen work`() {
        val bridge = attach()
        var redraws = 0
        val consumer: () -> Unit = { redraws++ }
        val region = RectF(40f, 700f, 300f, 760f)
        val crop = RectF(-4f, -8f, 120f, 70f)
        bridge.addListener(consumer)
        bridge.updateRegion(consumer, region, sharp = true)
        repeat(HomeTab.COUNT) { selected ->
            pager.setCurrentItem(selected, false)
            settleLayout()
            val active = sources[selected]
            assertEquals(setOf(consumer), active.listeners)
            assertEquals(region to true, active.regions[consumer])
            assertTrue(sources.filterIndexed { index, _ -> index != selected }.all { it.listeners.isEmpty() })
            redraws = 0
            val adds = active.adds
            repeat(30) { viewport.viewTreeObserver.dispatchOnPreDraw() }
            sources.filterIndexed { index, _ -> index != selected }.forEach { it.invalidate() }
            assertEquals("offscreen updates must not invalidate current home chrome", 0, redraws)
            assertEquals(adds, active.adds)
            active.invalidate()
            assertEquals(1, redraws)
            assertTrue(bridge.draw(Canvas(), 12f, 34f))
            assertTrue(bridge.drawSharp(Canvas(), 56f, 78f, crop))
            assertEquals(12f to 34f, active.lastPosition)
            assertEquals(56f to 78f, active.lastSharpPosition)
            assertEquals(crop, active.lastSharpBounds)
            assertEquals(1, active.draws)
            assertEquals(1, active.sharpDraws)
        }
        bridge.close()
        assertTrue(sources.all { it.listeners.isEmpty() && it.regions.isEmpty() && it.closes == 0 })
    }

    @Test
    fun `late Contacts and Me page sources never fall through to attached neighbours or ancestors`() {
        val bridge = attach()
        val ancestor = Source()
        outer.setTag(R.id.glass_backdrop, ancestor)
        val consumer: () -> Unit = {}
        bridge.addListener(consumer)
        bridge.updateRegion(consumer, RectF(0f, 700f, 300f, 760f))
        for (selected in HomeTab.CONTACTS..HomeTab.ME) {
            
            pager.setCurrentItem(selected, false)
            settleLayout()
            val leaf = checkNotNull(pageLeaves[selected])
            leaf.setTag(R.id.glass_backdrop, null)
            viewport.viewTreeObserver.dispatchOnPreDraw()
            assertFalse(bridge.draw(Canvas(), 0f, 0f))
            assertTrue(sources.all { it.listeners.isEmpty() })
            assertTrue(ancestor.listeners.isEmpty())
            leaf.setTag(R.id.glass_backdrop, sources[selected])
            viewport.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(setOf(consumer), sources[selected].listeners)
            assertEquals(RectF(0f, 700f, 300f, 760f) to false, sources[selected].regions[consumer])
            assertTrue(bridge.draw(Canvas(), 0f, 0f))
        }
        assertEquals(0, ancestor.adds)
        assertTrue(sources.all { it.closes == 0 })
    }

    @Test
    fun `home regions preserve independent notifications and replay on page rebind`() {
        val source = attach()
        var topFrames = 0
        var bottomFrames = 0
        val top: () -> Unit = { topFrames++ }
        val bottom: () -> Unit = { bottomFrames++ }
        source.addListener(top)
        source.addListener(bottom)
        val topBounds = RectF(0f, 0f, 400f, 100f)
        val bottomBounds = RectF(40f, 700f, 300f, 760f)
        source.updateRegion(top, topBounds)
        source.updateRegion(bottom, bottomBounds, sharp = true)
        assertEquals(2, sources[0].listeners.size)
        assertEquals(topBounds to false, sources[0].regions[top])
        assertEquals(bottomBounds to true, sources[0].regions[bottom])
        topFrames = 0
        bottomFrames = 0
        sources[0].listeners.single { it === bottom }.invoke()
        assertEquals(0, topFrames)
        assertEquals(1, bottomFrames)
        
        bottomBounds.offset(5f, 0f)
        pager.setCurrentItem(HomeTab.CONTACTS, false)
        settleLayout()
        assertTrue(sources[0].listeners.isEmpty())
        assertTrue(sources[0].regions.isEmpty())
        pager.setCurrentItem(HomeTab.CHATS, false)
        settleLayout()
        assertEquals(RectF(40f, 700f, 300f, 760f) to true, sources[0].regions[bottom])
        assertEquals(topBounds to false, sources[0].regions[top])
        source.removeListener(top)
        assertFalse(sources[0].listeners.contains(top))
        assertEquals(1, sources[0].listeners.size)
        source.removeListener(bottom)
        assertTrue(sources[0].listeners.isEmpty())
        assertEquals(0, sources[0].closes)
    }

    @Test
    fun `late page tags and replacement sources are discovered without borrowing ancestors`() {
        val source = attach()
        val leaf = pageLeaves[0]!!
        leaf.setTag(R.id.glass_backdrop, null)
        val ancestor = Source()
        outer.setTag(R.id.glass_backdrop, ancestor)
        var redraws = 0
        source.addListener { redraws++ }
        viewport.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(0, sources[0].adds)
        assertEquals(0, ancestor.adds)
        assertFalse(source.draw(Canvas(), 0f, 0f))
        leaf.setTag(R.id.glass_backdrop, sources[0])
        viewport.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(1, sources[0].adds)
        assertEquals(1, redraws)
        val replacement = Source()
        leaf.setTag(R.id.glass_backdrop, replacement)
        viewport.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(1, sources[0].removes)
        assertEquals(0, sources[0].closes)
        assertEquals(1, replacement.adds)
        assertEquals(2, redraws)
        source.close()
        assertEquals(1, replacement.removes)
        assertEquals(0, replacement.closes)
        assertNull(viewport.getTag(R.id.glass_backdrop))
    }

    @Test
    fun `detach releases borrowed listeners and reattach restores demand without closing the page`() {
        val source = attach()
        var redraws = 0
        source.addListener { redraws++ }
        assertSame(source, viewport.getTag(R.id.glass_backdrop))
        outer.removeView(viewport)
        assertEquals(1, sources[0].removes)
        redraws = 0
        sources[0].invalidate()
        assertEquals(0, redraws)
        assertEquals(0, sources[0].closes)
        outer.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        settleLayout()
        assertEquals(2, sources[0].adds)
        source.close()
        assertEquals(2, sources[0].removes)
        assertEquals(0, sources[0].closes)
        assertNull(viewport.getTag(R.id.glass_backdrop))
        viewport.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(2, sources[0].adds)
    }
}
