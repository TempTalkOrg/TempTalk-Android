package com.difft.android.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.base.widget.InsetAwareConstraintLayout
import com.difft.android.chat.R
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.ui.messageaction.ChatChromeInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper






@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [34])
class ConversationGlassGeometryTest {
    private val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private data class Page(
        val root: InsetAwareConstraintLayout,
        val content: View,
        val fade: View,
        val spacer: View,
        val call: View,
        val header: View,
        val input: View,
    )

    private fun page(layout: Int = R.layout.chat_fragment_chat): Page {
        
        
        val inflater = LayoutInflater.from(activity.applicationContext).cloneInContext(activity)
        inflater.factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, context: android.content.Context, attrs: android.util.AttributeSet): View? =
                if (name == "androidx.fragment.app.FragmentContainerView") FrameLayout(context, attrs) else null
            override fun onCreateView(name: String, context: android.content.Context, attrs: android.util.AttributeSet): View? =
                onCreateView(null, name, context, attrs)
        }
        val root = inflater.inflate(layout, null) as InsetAwareConstraintLayout
        return Page(root, root.findViewById(R.id.fragment_container_view_contents), root.findViewById(R.id.chat_top_fade),
            root.findViewById(R.id.top_inset_spacer), root.findViewById(R.id.fragment_container_view_call),
            root.findViewById(R.id.fragment_container_view_header), root.findViewById(R.id.fragment_container_view_input)).also {
            it.header.layoutParams.height = 100
            it.input.layoutParams.height = 60
        }
    }

    private fun layout(root: View, width: Int = 400, height: Int = 800) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    @Test
    fun `real conversation constraints hang the top stack below the spacer and extend rows only through the navigation strip`() {
        val variants = listOf(R.layout.chat_fragment_chat, R.layout.chat_fragment_group_chat).flatMap { layoutRes ->
            listOf(OPTIMIZATION_NONE, OPTIMIZATION_STANDARD).map { layoutRes to it }
        }
        variants.forEach { (layoutRes, optimization) ->
            val page = page(layoutRes)
            page.root.optimizationLevel = optimization
            page.root.setPadding(0, 0, 0, 30)
            page.spacer.layoutParams.height = 24
            layout(page.root)
            val originalInputBottom = page.input.bottom

            assertEquals("no call banner: the header sits right under the spacer", 24, page.header.top)
            assertEquals(0, page.fade.top)
            assertEquals(page.header.bottom + tail(), page.fade.bottom)

            assertFalse(applyConversationUnderlap(page.content, bottom = 30))
            layout(page.root)

            assertEquals(0, page.content.top)
            assertEquals(0f, page.content.translationY, 0f)
            assertEquals(800, page.content.bottom)
            assertEquals(24, page.header.top)
            assertEquals(originalInputBottom, page.input.bottom)
            assertTrue(applyConversationUnderlap(page.content, bottom = 30))

            page.call.visibility = View.VISIBLE
            layout(page.root)
            assertEquals("the banner hangs from the spacer", 24, page.call.top)
            assertEquals("the header hangs from the banner", page.call.bottom, page.header.top)
        }
    }

    @Test
    fun `keyboard resize keeps the same input obstruction and last-row clearance`() {
        val page = page()
        val host = FrameLayout(activity)
        host.addView(page.root, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(host)
        ShadowLooper.idleMainLooper()
        page.root.setPadding(0, 0, 0, 30)
        page.spacer.layoutParams.height = 24
        applyConversationUnderlap(page.content, bottom = 30)
        layout(host)
        val owner = Owner()
        val reports = mutableListOf<ChatChromeInsets>()
        trackChatChrome(owner, page.content, page.header, page.input) { reports += it }
        page.content.viewTreeObserver.dispatchOnPreDraw()
        assertEquals("the measured top already holds the inset once", ChatChromeInsets(top = 124, bottom = 90), reports.last())
        val oldBottom = page.content.bottom

        page.root.setPadding(0, 0, 0, 300)
        layout(host)
        page.content.viewTreeObserver.dispatchOnPreDraw()

        assertEquals(270, oldBottom - page.content.bottom)
        assertEquals(90, page.content.bottom - page.input.top)
        assertEquals(page.root.height - 300, page.input.bottom)
        assertEquals("constant bottom padding preserves existing reanchor behavior", 1, reports.size)
        owner.registry.currentState = Lifecycle.State.DESTROYED
    }

    @Test
    fun `underlap never unclips or clips the page's ancestors`() {
        val host = FrameLayout(activity)
        val pane = FrameLayout(activity)
        host.addView(pane)
        val page = page()
        pane.addView(page.root)
        activity.setContentView(host)
        ShadowLooper.idleMainLooper()
        val owner = Owner()
        trackConversationUnderlap(owner, page.root, page.content)
        layout(host)
        page.root.viewTreeObserver.dispatchOnPreDraw()
        listOf(host, pane).forEach {
            assertTrue(it.clipChildren)
            assertTrue(it.clipToPadding)
            assertNull(it.clipBounds)
        }
        owner.registry.currentState = Lifecycle.State.DESTROYED
    }

    @Test
    fun `blur source is only rows and excludes sibling date and scroll controls`() {
        val list = LayoutInflater.from(activity).inflate(R.layout.chat_fragment_message_list, null)
        val rows = list.requireConversationMessageRows()
        assertSame(list.findViewById<View>(R.id.recyclerViewMessage), rows)
        listOf(R.id.cv_day_time, R.id.cl_to_bottom, R.id.cl_at, R.id.lottie_view_loading).forEach { overlayId ->
            assertNotNull(list.findViewById<View>(overlayId))
            assertNull("overlay siblings must not enter the recorded source", rows.findViewById<View>(overlayId))
        }
        assertThrows(IllegalStateException::class.java) {
            FrameLayout(activity).requireConversationMessageRows()
        }
    }

    @Test
    fun `non-glass conversation geometry continues to stop above navigation strip`() {
        val page = page()
        page.root.setPadding(0, 0, 0, 30)
        layout(page.root)
        assertEquals(0, page.content.top)
        assertEquals(770, page.content.bottom)
        assertEquals(770, page.input.bottom)
    }

    private fun tail(): Int = activity.resources.getDimensionPixelSize(R.dimen.chat_top_fade_tail)
}


private const val OPTIMIZATION_NONE = 0
private const val OPTIMIZATION_STANDARD = 257
