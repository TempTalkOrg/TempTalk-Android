package com.difft.android.chat.recent

import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.ui.glass.GlassBackdrop
import com.difft.android.base.ui.topchrome.TopChromeGlassDrawable
import com.difft.android.base.ui.glass.installAppBarOverlayChrome
import com.difft.android.chat.R
import com.difft.android.chat.common.HomeHeaderCollapser
import com.google.android.material.appbar.AppBarLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.shadows.ShadowLooper






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "mdpi")
class RecentChatGlassGeometryTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `collapse and reveal retain expanded padding without layout requests or skipped frames`() {
        Fixture(collapsible = true).use { f ->
            f.manager.scrollToPositionWithOffset(10, 0)
            f.settle()
            val topPadding = f.rows.paddingTop
            val bottomPadding = f.rows.paddingBottom
            val bodyHeight = f.body.height
            val margin = (f.body.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
            assertTrue(f.behavior.onStartNestedScroll(f.root, f.appBar, f.body, f.rows,
                ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH))
            repeat(HEADER) { step ->
                val consumed = IntArray(2)
                f.behavior.onNestedPreScroll(f.root, f.appBar, f.rows, 0, 1, consumed, ViewCompat.TYPE_TOUCH)
                assertEquals(1, consumed[1])
                f.assertScrollFrame(topPadding, bottomPadding, bodyHeight, margin)
                assertEquals((-step - 1).toFloat(), f.body.translationY, 0f)
                f.assertProtectedHeader()
            }
            repeat(HEADER) {
                f.behavior.onNestedScroll(f.root, f.appBar, f.rows, 0, 0, 0, -1,
                    ViewCompat.TYPE_TOUCH, IntArray(2))
                f.assertScrollFrame(topPadding, bottomPadding, bodyHeight, margin)
                f.assertProtectedHeader()
            }
            assertEquals(0f, f.body.translationY, 0f)
            assertEquals("the inset padding stays clipped", STATUS, checkNotNull(f.appBar.clipBounds).top)
        }
    }

    @Test
    fun `collapsed title clips below the status inset but preserves the pinned focus strip`() {
        Fixture(collapsible = true).use { f ->
            f.assertProtectedHeader()
            val expandedTop = f.windowTop(f.header)
            assertEquals(STATUS, expandedTop)
            f.assertBand(STATUS + HEADER + FOCUS)
            val padding = f.rows.paddingTop
            assertEquals("the measured header bottom holds the inset once", HEADER + FOCUS + STATUS, padding)
            assertEquals(0, f.manager.findFirstVisibleItemPosition())
            assertEquals(padding, f.manager.findViewByPosition(0)!!.top)
            f.assertLastRowClear()
            f.behavior.onStartNestedScroll(f.root, f.appBar, f.body, f.rows,
                ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
            f.behavior.onNestedPreScroll(f.root, f.appBar, f.rows, 0, HEADER, IntArray(2), ViewCompat.TYPE_TOUCH)
            assertEquals(padding, f.rows.paddingTop)
            f.assertProtectedHeader()
            assertEquals(STATUS + HEADER, checkNotNull(f.appBar.clipBounds).top)
            assertEquals("pinned focus starts at the protected edge", STATUS, f.windowTop(f.focus))
            assertTrue("no part of the title remains below the clip", f.header.bottom <= f.appBar.clipBounds!!.top)
            f.assertBand(STATUS + FOCUS)
            f.assertLastRowClear()
        }
    }

    @Test
    fun `tall window keeps a fixed visible header and preserves first and last row clearance`() {
        Fixture(collapsible = false).use { f ->
            assertEquals(0, f.appBar.totalScrollRange)
            assertEquals(STATUS, f.windowTop(f.header))
            assertEquals(STATUS, checkNotNull(f.appBar.clipBounds).top)
            assertEquals(HEADER + FOCUS + STATUS, f.rows.paddingTop)
            assertEquals(f.rows.paddingTop, f.manager.findViewByPosition(0)!!.top)
            f.assertProtectedHeader()
            f.assertBand(STATUS + HEADER + FOCUS)
            f.assertLastRowClear()
        }
    }

    @Test
    fun `an idle band schedules no redraw`() {
        Fixture(collapsible = true).use { f ->
            val band = f.bandView()
            
            band.draw(Canvas(android.graphics.Bitmap.createBitmap(400, 200, android.graphics.Bitmap.Config.ARGB_8888)))
            assertFalse(f.root.viewTreeObserver.dispatchOnPreDraw())
            assertFalse("no geometry change, no invalidation", band.isDirty)
        }
    }

    @Test
    fun `fully collapsed title clips away without a focus strip and structural focus changes settle`() {
        Fixture(collapsible = true, focusHeight = 0).use { f ->
            val padding = f.rows.paddingTop
            f.behavior.onStartNestedScroll(f.root, f.appBar, f.body, f.rows,
                ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
            f.behavior.onNestedPreScroll(f.root, f.appBar, f.rows, 0, HEADER, IntArray(2), ViewCompat.TYPE_TOUCH)
            assertTrue(checkNotNull(f.appBar.clipBounds).isEmpty)
            assertEquals(padding, f.rows.paddingTop)
            f.assertProtectedHeader()
            f.assertBand(STATUS)
            f.focus.layoutParams = f.focus.layoutParams.apply { height = FOCUS }
            f.settle()
            assertEquals(padding + FOCUS, f.rows.paddingTop)
            assertEquals(STATUS + HEADER, checkNotNull(f.appBar.clipBounds).top)
            f.assertProtectedHeader()
            f.assertLastRowClear()
        }
    }

    @Test
    fun `covering layers suppress page-local controls but keep the band until Chats is resumed and uncovered`() {
        val covered = MutableStateFlow(false)
        Fixture(collapsible = true, covered = covered).use { f ->
            val strip = f.bandView()
            val add = f.root.findViewById<View>(R.id.add_options)
            val proxy = f.root.findViewById<View>(R.id.iv_proxy_status).apply { visibility = View.VISIBLE }
            f.settle()
            val cover = f.coverPage().apply { visibility = View.INVISIBLE }
            
            
            ShadowLooper.idleMainLooper()
            f.settle()
            val source = RecordingSource()
            f.root.setTag(com.difft.android.base.R.id.glass_backdrop, source)
            
            assertTrue(strip.isShown)
            assertTrue(add.isShown)
            assertTrue(proxy.isShown)
            assertTrue(f.focus.isShown)
            assertTrue("the band is owned by Chats, never the activity overlay", strip.parent === f.root)
            assertEquals(0, f.windowTop(strip))
            val padding = f.rows.paddingTop
            val translation = f.body.translationY
            val height = f.body.height

            
            repeat(3) {
                cover.visibility = View.VISIBLE
                covered.value = true
                ShadowLooper.idleMainLooper()
                f.root.viewTreeObserver.dispatchOnPreDraw()
                assertTrue("the band shows around an opening or closing layer", strip.isShown)
                assertEquals(View.INVISIBLE, f.appBar.visibility)
                assertEquals("the layer starts at the window top and paints its own top area", 0, f.windowTop(cover))
                assertFalse(add.isShown)
                assertFalse(proxy.isShown)
                assertFalse(f.focus.isShown)
                assertEquals(padding, f.rows.paddingTop)
                assertEquals(translation, f.body.translationY, 0f)
                assertEquals(height, f.body.height)
                assertFalse(f.rows.isLayoutRequested)

                
                f.moveOwner(Lifecycle.State.STARTED)
                cover.visibility = View.INVISIBLE
                covered.value = false
                ShadowLooper.idleMainLooper()
                assertFalse(strip.isShown)
                assertFalse(add.isShown)
                f.moveOwner(Lifecycle.State.RESUMED)
                assertTrue(strip.isShown)
                assertTrue(add.isShown)
                assertTrue(proxy.isShown)
            }
            assertFalse("temporary suppression must not close the page source", source.closed)
        }
    }

    @Test
    fun `a restored covering layer never exposes chrome on initial installation`() {
        val covered = MutableStateFlow(true)
        Fixture(collapsible = false, covered = covered).use { f ->
            assertEquals(View.INVISIBLE, f.appBar.visibility)
            assertTrue("the band is under the layer, not hidden by it", f.bandView().isShown)
            covered.value = false
            ShadowLooper.idleMainLooper()
            assertTrue(f.appBar.isShown)
            assertTrue(f.bandView().isShown)
        }
    }

    private class RecordingSource : GlassBackdrop {
        val regions = mutableMapOf<() -> Unit, RectF>()
        var draws = 0
        var closed = false
        override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean {
            draws++
            return true
        }
        override fun addListener(listener: () -> Unit) = Unit
        override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
            if (boundsInWindow == null) regions.remove(listener) else regions[listener] = RectF(boundsInWindow)
        }
        override fun removeListener(listener: () -> Unit) { regions.remove(listener) }
        override fun invalidate() = Unit
        override fun close() { closed = true }
    }

    private class Fixture(
        collapsible: Boolean, focusHeight: Int = FOCUS,
        covered: MutableStateFlow<Boolean>? = null,
    ) : AutoCloseable {
        private val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        private val activity = controller.get()
        private val context = ContextThemeWrapper(activity, com.difft.android.base.R.style.Theme_Chative)
        private val platformInsets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), android.graphics.Insets.of(0, STATUS, 0, 0)).build()
        
        val root = object : CoordinatorLayout(context) {
            override fun getRootWindowInsets(): WindowInsets = platformInsets
        }
        private val inflated = LayoutInflater.from(context).inflate(R.layout.chat_fragment_recent_chat, null) as CoordinatorLayout
        val appBar: AppBarLayout
        val header: View
        val focus: View
        val rows: RecyclerView
        val body: View
        val manager = LinearLayoutManager(context)
        private val owner = Owner()
        private val originalIndex: Int
        private val originalBehavior: CoordinatorLayout.Behavior<*>?
        val behavior: AppBarLayout.Behavior
            get() = (appBar.layoutParams as CoordinatorLayout.LayoutParams).behavior as AppBarLayout.Behavior

        init {
            while (inflated.childCount > 0) {
                val child = inflated.getChildAt(0)
                inflated.removeView(child)
                root.addView(child)
            }
            appBar = root.findViewById(R.id.home_app_bar)
            header = root.findViewById(R.id.home_header)
            focus = root.findViewById(R.id.focus_status_strip)
            rows = root.findViewById(R.id.recycler_view_recent_message)
            body = rows.parent as View
            header.layoutParams.height = HEADER
            focus.layoutParams.height = focusHeight
            rows.layoutManager = manager
            rows.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 30
                override fun onCreateViewHolder(parent: ViewGroup, type: Int) = object : RecyclerView.ViewHolder(
                    View(parent.context).apply { layoutParams = RecyclerView.LayoutParams(-1, 40) },
                ) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            }
            rows.setPadding(0, 0, 0, BOTTOM + if (collapsible) HEADER else 0)
            activity.setContentView(root)
            HomeHeaderCollapser.bind(appBar, header, collapsible)
            layout()
            
            root.translationY -= windowTop(root)
            originalIndex = root.indexOfChild(appBar)
            originalBehavior = (body.layoutParams as CoordinatorLayout.LayoutParams).behavior
            installAppBarOverlayChrome(owner, root, appBar, body, rows, covered)
            settle()
            assertEquals(0, body.top)
            assertTrue(root.indexOfChild(appBar) > root.indexOfChild(body))
            assertNull((body.layoutParams as CoordinatorLayout.LayoutParams).behavior)
            assertNotNull((appBar.layoutParams as CoordinatorLayout.LayoutParams).behavior)
            assertFalse(rows.clipToPadding)
            assertEquals(STATUS, appBar.paddingTop)
        }

        fun settle() {
            repeat(5) { layout(); root.viewTreeObserver.dispatchOnPreDraw() }
            layout()
            assertFalse("stable geometry must not cancel drawing", root.viewTreeObserver.dispatchOnPreDraw())
        }

        fun assertScrollFrame(top: Int, bottom: Int, height: Int, margin: Int) {
            assertEquals("scroll offset cannot repad the list", top, rows.paddingTop)
            assertEquals(bottom, rows.paddingBottom)
            assertEquals(height, body.height)
            assertEquals(margin, (body.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
            assertFalse("header movement must not request a list layout", rows.isLayoutRequested)
            assertFalse("header movement must not cancel the pre-draw", root.viewTreeObserver.dispatchOnPreDraw())
            assertFalse(rows.isLayoutRequested)
        }

        fun assertProtectedHeader() {
            val clip = checkNotNull(appBar.clipBounds)
            assertTrue("header pixels cannot paint above status icons", clip.isEmpty || windowTop(appBar) + clip.top >= STATUS)
            if (focus.height > 0) {
                assertTrue("pinned focus remains inside the app bar clip", clip.contains(Rect(0, focus.top, focus.width, focus.bottom)))
            }
        }

        
        fun assertBand(visibleBottom: Int) {
            val band = bandView()
            assertEquals(0, windowTop(band))
            assertEquals("sized to the expanded header", STATUS + HEADER + focus.height, band.height)
            assertEquals(visibleBottom.toFloat(), (band.background as TopChromeGlassDrawable).extent(), 0f)
            assertEquals("under the app bar's controls", root.indexOfChild(appBar) - 1, root.indexOfChild(band))
            assertFalse(band.isClickable)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, band.importantForAccessibility)
        }

        fun bandView(): View = (0 until root.childCount).map(root::getChildAt)
            .single { it.background is TopChromeGlassDrawable }

        fun moveOwner(state: Lifecycle.State) { owner.registry.currentState = state }

        fun coverPage(): View = View(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            val host = root.parent as ViewGroup
            host.addView(this, FrameLayout.LayoutParams(400, 800))
            layout(0, 0, 400, 800)
            translationY = (-windowTop(this)).toFloat()
            assertTrue(host.indexOfChild(this) > host.indexOfChild(root))
        }

        fun assertLastRowClear() {
            manager.scrollToPositionWithOffset(29, 0)
            settle()
            val last = manager.findViewByPosition(29)!!
            assertEquals(29, manager.findLastVisibleItemPosition())
            assertTrue("final row clears the capsule in window coordinates",
                windowTop(rows) + last.bottom <= windowTop(root) + root.height - BOTTOM)
        }

        fun windowTop(view: View): Int = IntArray(2).also(view::getLocationInWindow)[1]

        private fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 800)
        }

        override fun close() {
            owner.registry.currentState = Lifecycle.State.DESTROYED
            assertEquals(originalIndex, root.indexOfChild(appBar))
            assertEquals(originalBehavior, (body.layoutParams as CoordinatorLayout.LayoutParams).behavior)
            assertEquals(0, rows.paddingTop)
            assertEquals(0, appBar.paddingTop)
            assertEquals(0f, body.translationY, 0f)
            assertNull(appBar.clipBounds)
            assertEquals(2, root.childCount)
            controller.pause().stop().destroy()
        }
    }

    private companion object {
        const val STATUS = 24
        const val HEADER = 52
        const val FOCUS = 40
        const val BOTTOM = 84
    }
}
