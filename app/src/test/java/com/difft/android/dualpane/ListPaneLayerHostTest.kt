package com.difft.android.dualpane

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.R

import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_ME
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** A plain page as a layer destination (the message search / More pages stand in). */
private class TestSearchDestination(private val label: String) : DetailDestination {
    override fun newFragment(): Fragment = StackPageFragment.newInstance(label, handlesBack = false)
    override fun shellIntent(context: Context): Intent = Intent()
    override val pageKey: String get() = "test:$label"
}

/**
 * The list pane's layer on the real `activity_index` trees (design "global search in the list
 * pane" §4.2, §4.4, §4.6; R1-F1, R1-F5, R2-F1; folded groups as a layer §3.1): one host per
 * `open()`, the root kind a request asks for, a FIFO queue that waits for a saved state, the Back
 * order with the layer, the accessibility coverage of the covered views and the selection
 * forwarded to every page of the layer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ListPaneLayerHostTest {

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val layer: ListPaneLayer get() = host.listLayer
    private val nav: PaneNavigator get() = host.navigator
    private val container: View get() = host.findViewById(R.id.list_layer)
    private val list: View get() = host.findViewById(R.id.index_viewpager)

    @Before
    fun setUp() {
        FoldedStandInFragment.folding.value = true
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun launch(qualifiers: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        settle()
    }

    private fun open(root: ListPaneLayerRoot = ListPaneLayerRoot.SEARCH) {
        layer.open(root)
        settle()
    }

    private fun back() {
        host.onBackPressedDispatcher.onBackPressed()
        settle()
    }

    private fun hostFragment(): ListPaneLayerHostFragment = layer.host ?: error("no list-pane layer host")

    private fun pages(): List<String> = hostFragment().pages().map { (it as? StackPageFragment)?.label ?: FOLDED }

    private fun hosts(): Int = host.supportFragmentManager.fragments.count { it is ListPaneLayerHostFragment }

    private fun push(label: String): Boolean {
        val top = hostFragment().topPage()!!
        return hostFragment().detailNavigation.push(top, TestSearchDestination(label)).also { settle() }
    }

    private fun covered(view: View): Boolean =
        view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS &&
            (view as ViewGroup).descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS

    // ------------------------------------------------------------------ host and queue

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane - open adds the root into the list pane, push and pop work the child stack, pop at the root closes`() {
        launch("w840dp-h900dp")
        assertTrue(nav.isDualPane)
        assertEquals(View.GONE, container.visibility)

        open()
        assertTrue(layer.active)
        assertEquals(View.VISIBLE, container.visibility)
        assertEquals(listOf("search"), pages())
        assertEquals(1, layer.depth)
        assertEquals(1, host.layerActiveChanges)
        assertTrue("the host sits inside the list pane", container.parent === host.findViewById(R.id.list_pane))

        assertTrue(push("message-search"))
        assertEquals(listOf("search", "message-search"), pages())
        assertTrue("the root is hidden under the push", hostFragment().rootPage()!!.isHidden)
        assertEquals(2, layer.depth)

        val top = hostFragment().topPage()!!
        assertTrue(hostFragment().detailNavigation.pop(top))
        settle()
        assertEquals(listOf("search"), pages())
        assertFalse(hostFragment().rootPage()!!.isHidden)

        assertTrue(hostFragment().detailNavigation.pop(hostFragment().rootPage()!!))
        settle()
        assertFalse(layer.active)
        assertNull(layer.host)
        assertEquals(View.GONE, container.visibility)
        assertEquals(0, layer.depth)
        assertEquals(2, host.layerActiveChanges)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two open requests before the first drain add one host, close then open leave one host active`() {
        launch("w840dp-h900dp")
        layer.open(ListPaneLayerRoot.SEARCH)
        layer.open(ListPaneLayerRoot.SEARCH)
        settle()
        assertEquals(1, host.supportFragmentManager.fragments.count { it is ListPaneLayerHostFragment })
        assertEquals(listOf("search"), pages())

        layer.close()
        layer.open(ListPaneLayerRoot.SEARCH)
        settle()
        assertTrue(layer.active)
        assertEquals(1, host.supportFragmentManager.fragments.count { it is ListPaneLayerHostFragment })
        assertEquals(View.VISIBLE, container.visibility)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a request made while state is saved is drained on the next start`() {
        launch("w840dp-h900dp")
        controller!!.pause().saveInstanceState(Bundle()).stop()

        layer.open(ListPaneLayerRoot.SEARCH)
        settle()
        assertTrue("truth flips at once", layer.active)
        assertNull("no commit while saved", layer.host)

        controller!!.start().resume()
        settle()
        assertEquals(listOf("search"), pages())
        assertEquals(View.VISIBLE, container.visibility)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `recreation keeps the layer and its pushed page, a restored host without the flag is removed`() {
        launch("w840dp-h900dp")
        open()
        push("message-search")

        controller!!.recreate()
        settle()
        assertTrue(layer.active)
        assertEquals(listOf("search", "message-search"), pages())
        assertEquals(View.VISIBLE, container.visibility)
        assertTrue(hostFragment().rootPage()!!.isHidden)

        // Saved state and FragmentManager disagree: the flag wins and the orphan host goes.
        val saved = Bundle()
        controller!!.saveInstanceState(saved)
        saved.remove("list_layer_active")
        controller!!.pause().stop().destroy()
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup(saved)
        settle()
        assertFalse(layer.active)
        assertNull(layer.host)
        assertEquals(View.GONE, container.visibility)
    }

    // ------------------------------------------------------------------ Back

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane Back pops the layer's pushed page, then closes the layer, then sends the task back`() {
        launch("w840dp-h900dp")
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("conv:a", handlesBack = false), selectionId = "a")
        settle()
        open()
        push("message-search")

        back()
        assertEquals(listOf("search"), pages())
        assertEquals("the conversation stays", 1, nav.depth())

        back()
        assertFalse(layer.active)
        assertEquals(1, nav.depth())

        back()
        assertEquals(1, host.moveTaskToBackCalls)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a collapsed list is revealed before the invisible layer is closed`() {
        launch("w840dp-h900dp")
        open()
        PaneNavigatorTestHost.listCollapsed = true
        try {
            back()
            assertFalse("expanded first", PaneNavigatorTestHost.listCollapsed)
            assertTrue(layer.active)
            back()
            assertFalse(layer.active)
        } finally {
            PaneNavigatorTestHost.listCollapsed = false
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane - search covers the tab bar, a result opens the conversation layer over it and Back returns to search then to the list`() {
        launch("w400dp-h800dp")
        assertFalse(nav.isDualPane)
        open()
        assertTrue(covered(list))
        assertTrue("the tab bar is covered too (D5)", covered(host.findViewById(R.id.home_tab_bar)))
        assertFalse("the layer itself is reachable", covered(container))
        assertTrue("the layer is a direct child of the root, under the detail layer", container.parent === host.findViewById<View>(R.id.detail_pane).parent)

        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("conv:a", handlesBack = false), selectionId = "a")
        settle()
        assertEquals(View.VISIBLE, host.findViewById<View>(R.id.detail_pane).visibility)
        assertTrue("search stays active under the conversation layer", layer.active)
        assertTrue("the covered search layer hides from TalkBack and focus (R1-F5)", covered(container))
        assertTrue(hostFragment().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))

        back()
        assertEquals(0, nav.depth())
        assertTrue(layer.active)
        assertFalse("uncovered again", covered(container))
        assertTrue(covered(list))

        back()
        assertFalse(layer.active)
        assertFalse(covered(list))
        assertFalse(covered(host.findViewById(R.id.home_tab_bar)))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane - the list hides from TalkBack while search is up and comes back when it closes`() {
        launch("w840dp-h900dp")
        assertFalse(covered(list))
        open()
        assertTrue(covered(list))
        layer.close()
        settle()
        assertFalse(covered(list))
    }

    // ------------------------------------------------------------------ status strip colour

    private fun stripColors(): Pair<Int, Int?> {
        val root = (host.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        return when (val bg = root.background) {
            is ColorDrawable -> bg.color to null
            is PaneSplitBackgroundDrawable -> bg.listColor to bg.detailColor
            else -> error("unexpected root background $bg")
        }
    }

    private fun color(res: Int) = ContextCompat.getColor(host, res)

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane - on the Me tab the list column strip turns bg1 while the layer is up and back to bg after`() {
        launch("w840dp-h900dp")
        nav.selectTab(PaneNavigator.TAB_ME)
        settle()
        assertEquals(color(com.difft.android.base.R.color.bg), stripColors().first)
        open()
        assertEquals("the search page sits on bg1", color(com.difft.android.base.R.color.bg1), stripColors().first)
        assertEquals("the detail column keeps the Me colour", color(com.difft.android.base.R.color.bg), stripColors().second)
        layer.close()
        settle()
        assertEquals(color(com.difft.android.base.R.color.bg), stripColors().first)
    }

    @Test
    fun `one-pane - on the Me tab the root strip turns bg1 while the layer is up, also after a recreation`() {
        launch("w400dp-h640dp")
        nav.selectTab(PaneNavigator.TAB_ME)
        settle()
        assertEquals(color(com.difft.android.base.R.color.bg), stripColors().first)
        open()
        assertEquals(color(com.difft.android.base.R.color.bg1), stripColors().first)
        controller!!.recreate()
        settle()
        assertEquals("a fold/unfold recreation keeps the layer's strip colour", color(com.difft.android.base.R.color.bg1), stripColors().first)
    }

    // ------------------------------------------------------------------ selection

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `the detail selection reaches the root and a pushed page, and a page pushed later gets the current one (R2-F1)`() {
        launch("w840dp-h900dp")
        open()
        val root = hostFragment().rootPage() as StackPageFragment
        hostFragment().updateDualPaneSelection("g1")
        assertEquals("g1", root.selections.last())

        push("more-groups")
        val more = hostFragment().topPage() as StackPageFragment
        assertEquals("initialised with the current selection", listOf("g1"), more.selections)

        hostFragment().updateDualPaneSelection("g2")
        assertEquals("g2", root.selections.last())
        assertEquals("g2", more.selections.last())
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a conversation already open when search opens is highlighted at once, also after a recreation (S-2)`() {
        launch("w840dp-h900dp")
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("conv:a", handlesBack = false), selectionId = "a")
        settle()
        assertEquals("a", nav.currentSelectedId)

        open()
        assertEquals(listOf("a"), (hostFragment().rootPage() as StackPageFragment).selections)

        controller!!.recreate()
        settle()
        assertEquals("the restored root is seeded from the restored selection", listOf("a"), (hostFragment().rootPage() as StackPageFragment).selections)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane never seeds a selection`() {
        launch("w400dp-h800dp")
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("conv:a", handlesBack = false), selectionId = "a")
        settle()
        back()
        open()
        assertEquals(listOf<String?>(null), (hostFragment().rootPage() as StackPageFragment).selections)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a duplicate push of the top page's key is dropped and a push from a covered page is refused`() {
        launch("w840dp-h900dp")
        open()
        val root = hostFragment().rootPage()!!
        assertTrue(push("more"))
        assertTrue("a second push of the same key is accepted but not added", hostFragment().detailNavigation.push(hostFragment().topPage()!!, TestSearchDestination("more")))
        settle()
        assertEquals(listOf("search", "more"), pages())

        assertFalse(hostFragment().detailNavigation.push(root, TestSearchDestination("other")))
        settle()
        assertEquals(listOf("search", "more"), pages())
    }

    // ------------------------------------------------------------------ root kinds

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `open with the folded root builds the folded page in the list pane, and its pop at the root closes the layer`() {
        launch("w840dp-h900dp")
        open(ListPaneLayerRoot.FOLDED_GROUPS)
        assertTrue(layer.active)
        assertEquals(ListPaneLayerRoot.FOLDED_GROUPS, layer.root)
        assertEquals(listOf(FOLDED), pages())
        assertTrue(container.parent === host.findViewById(R.id.list_pane))
        assertTrue(covered(list))

        assertTrue(hostFragment().detailNavigation.pop(hostFragment().rootPage()!!))
        settle()
        assertFalse(layer.active)
        assertNull(layer.host)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `open with the other root while active replaces the host, active flips once`() {
        launch("w840dp-h900dp")
        open(ListPaneLayerRoot.SEARCH)
        push("message-search")
        open(ListPaneLayerRoot.FOLDED_GROUPS)
        assertTrue(layer.active)
        assertEquals(1, hosts())
        assertEquals(ListPaneLayerRoot.FOLDED_GROUPS, layer.root)
        assertEquals(listOf(FOLDED), pages())
        assertEquals(1, host.layerActiveChanges)

        open(ListPaneLayerRoot.FOLDED_GROUPS)
        val folded = hostFragment()
        open(ListPaneLayerRoot.FOLDED_GROUPS)
        assertTrue("the same root keeps its host", folded === hostFragment())
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two opens with different roots queued in one frame end with one host of the last root`() {
        launch("w840dp-h900dp")
        layer.open(ListPaneLayerRoot.FOLDED_GROUPS)
        layer.open(ListPaneLayerRoot.SEARCH)
        settle()
        assertEquals(1, hosts())
        assertEquals(ListPaneLayerRoot.SEARCH, layer.root)
        assertEquals(listOf("search"), pages())
        assertEquals(1, host.layerActiveChanges)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a programmatic tab request closes the layer`() {
        launch("w840dp-h900dp")
        open(ListPaneLayerRoot.FOLDED_GROUPS)

        nav.showMePage(StackPageFragment.newInstance("me:settings"))
        settle()
        assertEquals(listOf(TAB_ME), host.requestedTabs)
        assertFalse(layer.active)
        assertNull(layer.host)
        assertEquals(View.GONE, container.visibility)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `the folded page gets the selection as a chat-list page, and closes the layer when folding ends`() {
        launch("w840dp-h900dp")
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("conv:g0", handlesBack = false), selectionId = "g0")
        settle()
        open(ListPaneLayerRoot.FOLDED_GROUPS)
        val folded = hostFragment().rootPage() as FoldedStandInFragment
        assertEquals("seeded with the open conversation", listOf<String?>("g0"), folded.selections)

        hostFragment().updateDualPaneSelection("g1")
        assertEquals("g1", folded.selections.last())

        FoldedStandInFragment.folding.value = false
        settle()
        assertFalse(layer.active)
        assertNull(layer.host)
        assertEquals("the conversation stays", 1, nav.depth())
    }

    private companion object {
        const val FOLDED = "folded"
    }
}
