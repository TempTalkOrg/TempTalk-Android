package com.difft.android.me

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.RenderNode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.appcompat.view.ContextThemeWrapper
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.R
import com.difft.android.base.ui.glass.GlassMode
import com.difft.android.base.ui.glass.installAppBarOverlayChrome
import com.difft.android.base.ui.topchrome.TopChromeGlassDrawable
import com.difft.android.databinding.MeFragmentBinding
import com.difft.android.home.HomePageGlassBackdrop
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeGlassGeometryTest {
    private var previousMode: Boolean? = null

    @Before fun enableGlass() {
        previousMode = ReflectionHelpers.getStaticField(GlassMode::class.java, "live")
        ReflectionHelpers.setStaticField(GlassMode::class.java, "live", true)
    }

    @After fun restoreMode() = ReflectionHelpers.setStaticField(GlassMode::class.java, "live", previousMode)

    @Test
    fun `the band covers the status bar and the first card keeps its gap below it`() {
        Fixture().use { f ->
            assertEquals("the empty app bar is the band's status part", STATUS, f.appBar.height)
            assertEquals(STATUS, f.band().height)
            assertEquals(0, f.windowTop(f.scroll))
            assertEquals(STATUS + GAP, f.scroll.paddingTop)
            assertEquals(STATUS + GAP, f.windowTop(f.cards))
            assertEquals(16, f.cards.left)
            assertEquals(368, f.cards.width)
            f.scroll.scrollTo(0, 100)
            f.settle()
            assertEquals(0, f.windowTop(f.scroll))
            assertEquals(STATUS + GAP - f.scroll.scrollY, f.windowTop(f.cards))
            f.assertLastCardClear()
        }
    }

    @Test
    @Config(sdk = [30, 34])
    fun `disabled live mode keeps the same geometry without a source`() {
        ReflectionHelpers.setStaticField(GlassMode::class.java, "live", false)
        Fixture(live = false).use { f ->
            assertNull(f.root.getTag(com.difft.android.base.R.id.glass_backdrop))
            assertEquals(STATUS + GAP, f.windowTop(f.cards))
            f.assertLastCardClear()
        }
    }

    @Test
    fun `bottom chrome borrows only resumed scroll content and owner destruction closes the native source`() {
        Fixture().use { f ->
            val proxy = f.root.getTag(com.difft.android.base.R.id.glass_backdrop) as HomePageGlassBackdrop
            val source = ReflectionHelpers.getField<Any>(proxy, "source")
            assertSame(f.scroll, ReflectionHelpers.getField<View>(source, "content"))
            assertSame(f.root, ReflectionHelpers.getField<View>(source, "viewport"))
            assertTrue("no bottom consumer means no offscreen capture demand", f.demands(source).isEmpty())
            assertNull(ReflectionHelpers.getField<RenderNode?>(source, "sharedCapture"))
            val background = ReflectionHelpers.getField<(Canvas) -> Unit>(source, "drawBackground")
            val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            try {
                background(Canvas(image))
                
                assertEquals(ContextCompat.getColor(f.root.context, com.difft.android.base.R.color.bg), image.getPixel(0, 0))
            } finally { image.recycle() }
            val listener: () -> Unit = {}
            val area = RectF(16f, f.windowTop(f.root) + 300f, 116f, f.windowTop(f.root) + 350f)
            proxy.addListener(listener)
            proxy.updateRegion(listener, area)
            f.settle()
            assertEquals(1, f.demands(source).size)
            val output = RenderNode("MeBottomGlass").apply { setPosition(0, 0, 100, 50) }
            val canvas = output.beginRecording()
            try {
                assertTrue(proxy.draw(canvas, area.left, area.top))
                f.owner.registry.currentState = Lifecycle.State.STARTED
                assertTrue(f.demands(source).isEmpty())
                assertFalse(proxy.draw(canvas, area.left, area.top))
                f.owner.registry.currentState = Lifecycle.State.RESUMED
                assertEquals(1, f.demands(source).size)
                f.settle()
                assertTrue(proxy.draw(canvas, area.left, area.top))
                proxy.onViewDetachedFromWindow(f.root)
                assertTrue(f.demands(source).isEmpty())
                proxy.onViewAttachedToWindow(f.root)
                f.settle()
                assertEquals(1, f.demands(source).size)
                assertFalse(ReflectionHelpers.getField<Boolean>(source, "closed"))
                f.owner.registry.currentState = Lifecycle.State.DESTROYED
                assertTrue(ReflectionHelpers.getField<Boolean>(source, "closed"))
                assertTrue(f.demands(source).isEmpty())
                assertNull(f.root.getTag(com.difft.android.base.R.id.glass_backdrop))
            } finally { output.endRecording(); output.discardDisplayList() }
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    
    private class Fixture(private val live: Boolean = true) : AutoCloseable {
        private val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        private val activity = controller.get()
        private val context = ContextThemeWrapper(activity, com.difft.android.base.R.style.Theme_Chative)
        private val insets = WindowInsets.Builder().setInsets(WindowInsets.Type.statusBars(),
            android.graphics.Insets.of(0, STATUS, 0, 0)).build()
        val root = object : CoordinatorLayout(context) {
            override fun getRootWindowInsets(): WindowInsets = insets
            override fun isHardwareAccelerated() = true
        }
        private val inflated = LayoutInflater.from(context).inflate(R.layout.me_fragment, null) as CoordinatorLayout
        val owner = Owner()
        val binding: MeFragmentBinding
        val appBar get() = binding.homeAppBar
        val scroll get() = binding.scrollView2
        val cards get() = scroll.getChildAt(0) as ViewGroup
        private val previousBehavior: CoordinatorLayout.Behavior<*>?
        private val nativeSource: Any?

        init {
            while (inflated.childCount > 0) {
                val child = inflated.getChildAt(0)
                inflated.removeView(child)
                root.addView(child)
            }
            binding = MeFragmentBinding.bind(root)
            scroll.setPadding(scroll.paddingLeft, GAP, scroll.paddingRight, BOTTOM)
            previousBehavior = (scroll.layoutParams as CoordinatorLayout.LayoutParams).behavior
            activity.setContentView(root)
            layout()
            
            root.translationY -= windowTop(root)
            installMeGlassChrome(owner, binding)
            installAppBarOverlayChrome(owner, root, appBar, scroll, scroll, installSource = false)
            nativeSource = (root.getTag(com.difft.android.base.R.id.glass_backdrop) as? HomePageGlassBackdrop)
                ?.let { ReflectionHelpers.getField<Any>(it, "source") }
            settle()
            if (live) assertNotNull(nativeSource) else assertNull(nativeSource)
            assertNull("the body lies under the band", (scroll.layoutParams as CoordinatorLayout.LayoutParams).behavior)
        }

        fun settle() {
            repeat(4) { layout(); root.viewTreeObserver.dispatchOnPreDraw() }
            assertFalse(root.isLayoutRequested)
            assertFalse(scroll.isLayoutRequested)
        }

        fun band(): View = (0 until root.childCount).map(root::getChildAt).single { it.background is TopChromeGlassDrawable }

        fun assertLastCardClear() {
            assertTrue("short fixture must overflow", scroll.canScrollVertically(1) || scroll.scrollY > 0)
            val params = cards.layoutParams as ViewGroup.MarginLayoutParams
            val range = (cards.height + params.topMargin + params.bottomMargin -
                (scroll.height - scroll.paddingTop - scroll.paddingBottom)).coerceAtLeast(0)
            scroll.scrollTo(0, range)
            settle()
            assertEquals(range, scroll.scrollY)
            assertFalse(scroll.canScrollVertically(1))
            assertTrue("the final card clears the bottom capsule",
                windowTop(cards) + cards.height <= windowTop(root) + root.height - BOTTOM)
        }

        fun demands(source: Any): Map<*, *> = ReflectionHelpers.getField(source, "demands")
        fun windowTop(view: View) = IntArray(2).also(view::getLocationInWindow)[1]

        private fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 400)
        }

        override fun close() {
            owner.registry.currentState = Lifecycle.State.DESTROYED
            assertNull(root.getTag(com.difft.android.base.R.id.glass_backdrop))
            nativeSource?.let { assertTrue(ReflectionHelpers.getField<Boolean>(it, "closed")) }
            assertEquals("the band is removed", 2, root.childCount)
            assertSame(previousBehavior, (scroll.layoutParams as CoordinatorLayout.LayoutParams).behavior)
            assertEquals(GAP, scroll.paddingTop)
            controller.pause().stop().destroy()
        }
    }

    private companion object {
        const val STATUS = 24
        const val GAP = 25
        const val BOTTOM = 84
    }
}
