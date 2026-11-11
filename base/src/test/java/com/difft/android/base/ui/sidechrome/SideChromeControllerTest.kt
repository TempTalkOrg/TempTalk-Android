package com.difft.android.base.ui.sidechrome

import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.glass.GlassBackdrop
import com.difft.android.base.ui.glass.GlassMode
import com.difft.android.base.ui.glass.ViewGlassBackdrop
import com.difft.android.base.ui.glass.findGlassBackdrop
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import kotlin.math.roundToInt


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w424dp-h622dp-440dpi")
class SideChromeControllerTest {

    class HostActivity : FragmentActivity(), SideChromeHost {
        override val sideChrome: SideChromeController by lazy { SideChromeController(this) }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = PAGE_ID })
        }

        override fun onContentChanged() {
            super.onContentChanged()
            sideChrome.install()
        }
    }

    class XmlPage : Fragment() {
        lateinit var backButton: ImageView
        override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(requireContext()).also { root -> backButton = ImageView(requireContext()).also(root::addView) }
    }

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE, true)
        SideChromeSettings.resetForTest()
    }

    @After
    fun tearDown() {
        SideChromeSettings.setPlacement(context, NavigationPlacement.DEFAULT)
        SideChromeSettings.resetForTest()
    }

    private fun launch(): HostActivity {
        val activity = Robolectric.buildActivity(HostActivity::class.java).setup().visible().get()
        ShadowLooper.idleMainLooper()
        return activity
    }

    private fun HostActivity.rail(): View? {
        val frame = findViewById<FrameLayout>(android.R.id.content)
        return (0 until frame.childCount).map(frame::getChildAt).firstOrNull { it.id != PAGE_ID }
    }

    private fun HostActivity.pageMargin(): Int =
        (findViewById<View>(PAGE_ID).layoutParams as FrameLayout.LayoutParams).rightMargin

    private fun HostActivity.addPage(): XmlPage {
        val page = XmlPage()
        supportFragmentManager.beginTransaction().add(PAGE_ID, page).commitNow()
        ShadowLooper.idleMainLooper()
        return page
    }

    @Test
    fun `the bottom placement keeps the page whole`() {
        SideChromeSettings.setPlacement(context, NavigationPlacement.DEFAULT)
        val activity = launch()
        val page = activity.addPage()
        page.sideChromeBar().setBack(page.backButton, action = {})
        ShadowLooper.idleMainLooper()

        assertFalse(activity.sideChrome.active)
        assertEquals(View.VISIBLE, page.backButton.visibility)
        assertEquals(0, activity.pageMargin())
        assertEquals(0, activity.sideChrome.reservedPx)
    }

    @Test
    fun `a short window reports itself to headers with either placement`() {
        listOf(NavigationPlacement.DEFAULT to false, NavigationPlacement.RIGHT to true).forEach { (placement, onBar) ->
            SideChromeSettings.setPlacement(context, placement)
            val activity = launch()
            val page = activity.addPage()
            var reported: Pair<Boolean, Boolean>? = null
            page.sideChromeBar().onPlacement { bar, short -> reported = bar to short }
            ShadowLooper.idleMainLooper()

            assertTrue(activity.sideChrome.shortWindow)
            assertEquals(onBar to true, reported)
        }
    }

    @Test
    fun `right placement moves the back button to the bar`() {
        SideChromeSettings.setPlacement(context, NavigationPlacement.RIGHT)
        val activity = launch()
        assertTrue(activity.sideChrome.active)
        
        assertEquals(View.GONE, activity.rail()?.visibility)

        val page = activity.addPage()
        page.sideChromeBar().setBack(page.backButton, action = {})
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()

        
        assertEquals(View.INVISIBLE, page.backButton.visibility)
        assertEquals((16 * activity.resources.displayMetrics.density).roundToInt(), page.backButton.layoutParams.width)
        assertNotNull(activity.sideChrome.registry.current?.back)
        assertEquals(View.VISIBLE, activity.rail()?.visibility)
        
        assertEquals(0, activity.pageMargin())
        val reserve = (SideChromeEligibility.BAR_RESERVE_DP * activity.resources.displayMetrics.density).roundToInt()
        assertEquals(reserve, activity.sideChrome.reservedPx)
    }

    @Test
    fun `actions follow the page's state`() {
        SideChromeSettings.setPlacement(context, NavigationPlacement.RIGHT)
        val activity = launch()
        val page = activity.addPage()
        val call = ImageView(activity)
        val bar = page.sideChromeBar()
        bar.setAction("call", call, icon = 1, description = "Call", visible = false)
        assertEquals(emptyList<RailAction>(), activity.sideChrome.registry.current?.actions)

        bar.setAction("call", call, icon = 1, description = "Call", visible = true, onClick = {})
        assertEquals(listOf("call"), activity.sideChrome.registry.current?.actions?.map { it.id })
        assertEquals(View.GONE, call.visibility)

        bar.setAction("call", call, icon = 1, description = "Call", visible = true, enabled = false)
        assertFalse(activity.sideChrome.registry.current!!.actions.single().enabled)
    }

    @Test
    fun `a covered page removes both control placements and restores only its requested actions`() {
        val activity = launch()
        val page = activity.addPage()
        val root = page.requireView() as FrameLayout
        val add = ImageView(activity).also(root::addView)
        val proxy = ImageView(activity).also(root::addView)
        val bar = page.sideChromeBar(base = true)
        bar.setBack(page.backButton, action = {})
        bar.setAction("add", add, icon = com.difft.android.base.R.drawable.chative_ic_back, description = "Add", visible = true, onClick = {})
        bar.setAction("proxy", proxy, icon = com.difft.android.base.R.drawable.chative_ic_back, description = "Proxy", visible = true)
        
        assertEquals(View.GONE, add.visibility)
        assertEquals(listOf("add", "proxy"), activity.sideChrome.registry.current?.actions?.map { it.id })

        bar.setEligible(false)
        assertEquals(View.GONE, page.backButton.visibility)
        assertEquals(View.GONE, add.visibility)
        assertEquals(View.GONE, proxy.visibility)
        assertFalse(add.isEnabled)
        assertFalse(add.isShown)
        assertNull(activity.sideChrome.registry.current)
        assertTrue("suppressing a control never promotes or reparents it", add.parent === root)

        
        bar.setAction("proxy", proxy, icon = com.difft.android.base.R.drawable.chative_ic_back, description = "Proxy", visible = false)
        bar.setEligible(true)
        assertEquals(View.GONE, add.visibility)
        assertEquals(View.GONE, proxy.visibility)
        assertTrue(add.isEnabled)
        assertEquals(listOf("add"), activity.sideChrome.registry.current?.actions?.map { it.id })

        SideChromeSettings.setPlacement(context, NavigationPlacement.RIGHT)
        activity.sideChrome.refresh()
        activity.deliverInsets(0, 0, 0, 0)
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
        assertTrue(activity.sideChrome.active)
        assertEquals(View.GONE, add.visibility)
        assertEquals(listOf("add"), activity.sideChrome.registry.current?.actions?.map { it.id })
        bar.setEligible(false)
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
        assertNull(activity.sideChrome.registry.current)
        assertEquals(View.GONE, activity.rail()?.visibility)
        bar.setEligible(true)
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, activity.rail()?.visibility)
        assertEquals(listOf("add"), activity.sideChrome.registry.current?.actions?.map { it.id })
    }

    private fun HostActivity.deliverInsets(left: Int, top: Int, right: Int, bottom: Int): Insets {
        var received = Insets.NONE
        val page = findViewById<View>(PAGE_ID)
        ViewCompat.setOnApplyWindowInsetsListener(page) { _, insets ->
            received = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            insets
        }
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(left, top, right, bottom))
            .build()
        ViewCompat.dispatchApplyWindowInsets(findViewById(android.R.id.content), insets)
        return received
    }

    @Test
    fun `the page receives the window insets unchanged by default`() {
        val activity = launch()
        assertEquals(Insets.of(0, 50, 30, 40), activity.deliverInsets(0, 50, 30, 40))
    }

    @Test
    fun `with the bar showing the page still receives the window insets unchanged`() {
        SideChromeSettings.setPlacement(context, NavigationPlacement.RIGHT)
        val activity = launch()
        val page = activity.addPage()
        page.sideChromeBar().setBack(page.backButton, action = {})
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()

        assertEquals(Insets.of(0, 50, 30, 40), activity.deliverInsets(0, 50, 30, 40))
        assertEquals(0, activity.pageMargin())
    }

    @Test
    fun `a page pads its content clear of the bar while it shows`() {
        SideChromeSettings.setPlacement(context, NavigationPlacement.RIGHT)
        val activity = launch()
        val page = activity.addPage()
        val content = page.requireView()
        content.setPadding(0, 0, 10, 0)
        page.padForSideChrome(content)
        page.sideChromeBar().setBack(page.backButton, action = {})
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()

        val reserve = (SideChromeEligibility.BAR_RESERVE_DP * activity.resources.displayMetrics.density).roundToInt()
        assertEquals(10 + reserve, content.paddingRight)
    }

    @Test
    fun `a window without a hinge never uses the bar`() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE, false)
        SideChromeSettings.setPlacement(context, NavigationPlacement.RIGHT)
        val activity = launch()
        val page = activity.addPage()
        page.sideChromeBar().setBack(page.backButton, action = {})
        ShadowLooper.idleMainLooper()

        assertFalse(activity.sideChrome.active)
        assertFalse(activity.sideChrome.shortWindow)
        assertEquals(View.VISIBLE, page.backButton.visibility)
        assertNull(activity.rail()?.takeIf { it.visibility == View.VISIBLE })
    }

    @Test
    fun `rail records its narrow viewport from the sibling page and preserves page sources`() = withGlassMode(true) {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            val page = activity.findViewById<View>(PAGE_ID)
            val pageSource = object : GlassBackdrop {
                override fun draw(canvas: android.graphics.Canvas, consumerWindowX: Float, consumerWindowY: Float) = false
                override fun addListener(listener: () -> Unit) = Unit
                override fun removeListener(listener: () -> Unit) = Unit
                override fun invalidate() = Unit
                override fun close() = Unit
            }
            page.setTag(com.difft.android.base.R.id.glass_backdrop, pageSource)
            val rail = activity.rail()!!
            val source = rail.findGlassBackdrop()

            assertTrue(source is ViewGlassBackdrop)
            assertSame(rail, ReflectionHelpers.getField<View>(source, "viewport"))
            assertSame(page, ReflectionHelpers.getField<View>(source, "content"))
            assertSame(page.parent, rail.parent)
            assertNotSame(page, rail)
            assertSame("rail capture must not replace a screen's own source", pageSource, page.findGlassBackdrop())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `replacing the page closes the previous rail source and destruction closes the replacement`() = withGlassMode(true) {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        val activity = controller.get()
        val previous = activity.rail()!!.findGlassBackdrop()!!
        val replacementPage = FrameLayout(activity).apply { id = PAGE_ID }
        activity.setContentView(replacementPage)
        val replacement = activity.rail()!!.findGlassBackdrop()!!

        assertNotSame(previous, replacement)
        assertTrue(ReflectionHelpers.getField<Boolean>(previous, "closed"))
        assertSame(replacementPage, ReflectionHelpers.getField<View>(replacement, "content"))
        controller.pause().stop().destroy()
        assertTrue(ReflectionHelpers.getField<Boolean>(replacement, "closed"))
    }

    @Test
    fun `unsupported glass keeps controller geometry and its fallback source contract`() = withGlassMode(false) {
        SideChromeSettings.setPlacement(context, NavigationPlacement.RIGHT)
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            val page = activity.addPage()
            page.sideChromeBar().setBack(page.backButton, action = {})
            Snapshot.sendApplyNotifications()
            ShadowLooper.idleMainLooper()

            assertTrue(activity.sideChrome.active)
            assertNotNull(activity.rail()!!.findGlassBackdrop())
            assertEquals(0, activity.pageMargin())
            assertEquals(Insets.of(0, 50, 30, 40), activity.deliverInsets(0, 50, 30, 40))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun withGlassMode(live: Boolean, block: () -> Unit) {
        val previous = ReflectionHelpers.getStaticField<Boolean?>(GlassMode::class.java, "live")
        ReflectionHelpers.setStaticField(GlassMode::class.java, "live", live)
        try {
            block()
        } finally {
            ReflectionHelpers.setStaticField(GlassMode::class.java, "live", previous)
        }
    }

    private companion object {
        const val PAGE_ID = 0x7f0a1001
    }
}
