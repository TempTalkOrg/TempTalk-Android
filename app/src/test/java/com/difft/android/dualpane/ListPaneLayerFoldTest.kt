package com.difft.android.dualpane

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.R
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

private class FoldSearchDestination(private val label: String) : DetailDestination {
    override fun newFragment(): Fragment = StackPageFragment.newInstance(label, handlesBack = false)
    override fun shellIntent(context: Context): Intent = Intent()
    override val pageKey: String get() = "fold:$label"
}

/**
 * Search across fold and unfold (design "global search in the list pane" §4.5, G4/G5) and the
 * uncover re-focus (D1, R1-F4): the layer host and its stack restore into either tree, the
 * conversation layer sits above the search layer in one-pane, Back returns to search and then to
 * the list, and only the layer's visible top page is told when the conversation layer pops. The
 * folded-groups root restores its kind from the host argument and closes itself safely after a
 * restore (folded groups as a layer §3.1, §3.2).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ListPaneLayerFoldTest {

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val layer: ListPaneLayer get() = host.listLayer
    private val nav: PaneNavigator get() = host.navigator
    private val container: View get() = host.findViewById(R.id.list_layer)
    private val detailPane: View get() = host.findViewById(R.id.detail_pane)

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

    private fun recreate(qualifiers: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        controller!!.recreate()
        settle()
    }

    private fun openSearch() {
        layer.open(ListPaneLayerRoot.SEARCH)
        settle()
    }

    private fun openConversation(id: String) {
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("conv:$id", handlesBack = false), selectionId = id)
        settle()
    }

    private fun back() {
        host.onBackPressedDispatcher.onBackPressed()
        settle()
    }

    private fun pages(): List<String> = layer.host!!.pages().map { (it as StackPageFragment).label }

    private fun root(): StackPageFragment = layer.host!!.rootPage() as StackPageFragment

    private fun push(label: String) {
        val top = layer.host!!.topPage()!!
        assertTrue(layer.host!!.detailNavigation.push(top, FoldSearchDestination(label)))
        settle()
    }

    private fun inListPane(): Boolean = container.parent === host.findViewById<View>(R.id.list_pane)

    @Test
    fun `two-pane search with a conversation - fold shows the conversation over search, Back returns to search then the list, unfold restores both panes`() {
        launch("w840dp-h900dp")
        openSearch()
        openConversation("a")
        assertTrue(inListPane())

        recreate("w400dp-h800dp")
        assertFalse(nav.isDualPane)
        assertTrue("search survives the fold", layer.active)
        assertEquals(listOf("search"), pages())
        assertEquals(View.VISIBLE, container.visibility)
        assertEquals("the conversation layer is on top", View.VISIBLE, detailPane.visibility)
        assertEquals(1, nav.depth())
        assertEquals("a recreation is not an uncover", 0, root().uncovers)

        back()
        assertEquals(0, nav.depth())
        assertTrue(layer.active)
        assertEquals("the uncovered page takes the keyboard back (D1)", 1, root().uncovers)

        recreate("w840dp-h900dp")
        assertTrue(nav.isDualPane)
        assertTrue(layer.active)
        assertTrue("back in the list pane", inListPane())
        assertEquals(View.VISIBLE, container.visibility)

        back()
        assertFalse(layer.active)
        assertEquals(View.GONE, container.visibility)
    }

    @Test
    fun `two-pane search alone - folded it is the window, Back closes it, unfolded it returns to the list pane`() {
        launch("w840dp-h900dp")
        openSearch()
        push("message-search")

        recreate("w400dp-h800dp")
        assertTrue(layer.active)
        assertEquals(listOf("search", "message-search"), pages())
        assertEquals(View.GONE, detailPane.visibility)
        assertEquals(View.VISIBLE, container.visibility)
        assertFalse(inListPane())

        recreate("w840dp-h900dp")
        assertTrue(inListPane())
        assertEquals(listOf("search", "message-search"), pages())

        back()
        assertEquals(listOf("search"), pages())
        back()
        assertFalse(layer.active)
        back()
        assertEquals(1, host.moveTaskToBackCalls)
    }

    @Test
    fun `search opened folded moves into the list pane on unfold, with the conversation on the right`() {
        launch("w400dp-h800dp")
        openSearch()
        openConversation("b")
        assertEquals(View.VISIBLE, detailPane.visibility)

        recreate("w840dp-h900dp")
        assertTrue(nav.isDualPane)
        assertTrue(layer.active)
        assertTrue(inListPane())
        assertEquals(1, nav.depth())
        assertEquals("unfold is not an uncover", 0, root().uncovers)

        recreate("w400dp-h800dp")
        assertEquals("the conversation covers search again", View.VISIBLE, detailPane.visibility)
        assertTrue(layer.active)
    }

    @Test
    fun `the uncover reaches the layer's visible top page only (R1-F4)`() {
        launch("w400dp-h800dp")
        openSearch()
        push("message-search")
        val pushed = layer.host!!.topPage() as StackPageFragment
        openConversation("c")
        back()
        assertEquals(1, pushed.uncovers)
        assertEquals("the hidden root is not told", 0, root().uncovers)

        back() // pops the pushed page
        assertEquals(listOf("search"), pages())
        openConversation("d")
        back()
        assertEquals("now the root is the visible top", 1, root().uncovers)
    }

    @Test
    fun `a conversation layer covering search tells the visible top page to let the keyboard go (S-3)`() {
        launch("w400dp-h800dp")
        openSearch()
        push("message-search")
        val pushed = layer.host!!.topPage() as StackPageFragment
        assertEquals(0, pushed.covers)

        openConversation("c") // a notification or link opens it: no result tap hid the keyboard
        assertEquals(1, pushed.covers)
        assertEquals("the hidden root is not told", 0, root().covers)
        assertEquals(0, pushed.uncovers)

        back()
        assertEquals(1, pushed.uncovers)
        assertEquals("no second cover without a new conversation", 1, pushed.covers)
    }

    @Test
    fun `two-pane never uncovers - the detail pane does not cover the search layer`() {
        launch("w840dp-h900dp")
        openSearch()
        openConversation("a")
        nav.clear(TAB_CHATS)
        settle()
        assertEquals(0, root().uncovers)
    }

    // ------------------------------------------------------------------ folded root

    private fun openFolded() {
        layer.open(ListPaneLayerRoot.FOLDED_GROUPS)
        settle()
    }

    @Test
    fun `fold and unfold restore the folded root from the host argument, with the conversation over it in one-pane`() {
        FoldedStandInFragment.folding.value = true
        launch("w840dp-h900dp")
        openFolded()
        openConversation("g")

        recreate("w400dp-h800dp")
        assertTrue(layer.active)
        assertEquals(ListPaneLayerRoot.FOLDED_GROUPS, layer.root)
        assertTrue(layer.host!!.rootPage() is FoldedStandInFragment)
        assertEquals("the conversation layer is on top", View.VISIBLE, detailPane.visibility)

        back()
        assertEquals(0, nav.depth())
        assertTrue("Back returns to the folded list", layer.active)

        recreate("w840dp-h900dp")
        assertTrue(inListPane())
        assertEquals(ListPaneLayerRoot.FOLDED_GROUPS, layer.root)
        assertEquals(View.VISIBLE, container.visibility)

        back()
        assertFalse(layer.active)
    }

    /**
     * R1-F1: a restored folded root is created inside the Activity's `super.onCreate`, before the
     * layer exists; its self-close collector waits for STARTED, so a focus that ended while the app
     * was away closes the layer after the start instead of failing during the restore.
     */
    @Test
    fun `a restored folded root whose focus ended closes the layer after the start`() {
        FoldedStandInFragment.folding.value = true
        launch("w400dp-h800dp")
        openFolded()
        val saved = android.os.Bundle()
        controller!!.pause().saveInstanceState(saved).stop().destroy()
        settle()

        FoldedStandInFragment.folding.value = false
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup(saved)
        settle()

        assertFalse(layer.active)
        assertEquals(null, layer.host)
        assertEquals(View.GONE, container.visibility)
        FoldedStandInFragment.folding.value = true
    }
}
