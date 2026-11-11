package com.difft.android.dualpane

import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.R
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CONTACTS
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_ME
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit






@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PaneNavigatorTest {

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val nav: PaneNavigator get() = host.navigator

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
    }

    private fun launch() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun open(id: String, tab: Int = nav.currentTab, jump: Long? = null) {
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, id, jump), PaneNavigator.Origin.Tab(tab))
        idle()
    }

    private fun push(label: String, tab: Int = nav.currentTab) {
        nav.push(tab, StackPageFragment.newInstance(label))
        idle()
    }

    private fun selectTab(tab: Int) {
        nav.selectTab(tab)
        idle()
    }

    private fun Fragment.state(): Lifecycle.State = lifecycle.currentState

    private fun labels(tab: Int) = host.pages(tab).map { it.label }

    

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `push keeps the covered page view and caps it at STARTED, pop restores it`() {
        launch()
        open("a")
        push("info")

        val (root, top) = host.pages(TAB_CHATS)
        assertEquals(listOf("conv:a:0", "info"), labels(TAB_CHATS))
        assertNotNull("a covered page keeps its view (scroll, selection, playback)", root.view)
        assertTrue(root.isHidden)
        assertEquals(Lifecycle.State.STARTED, root.state())
        assertEquals(Lifecycle.State.RESUMED, top.state())

        assertTrue(nav.pop(TAB_CHATS))
        idle()
        assertEquals(listOf("conv:a:0"), labels(TAB_CHATS))
        assertSame("pop reveals the same instance, not a rebuilt one", root, host.pages(TAB_CHATS).single())
        assertFalse(root.isHidden)
        assertEquals(Lifecycle.State.RESUMED, root.state())
        assertFalse("the popped page is removed", top.isAdded)
        assertNull(top.view)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane root cannot pop`() {
        launch()
        open("a")
        assertFalse(nav.canPop(TAB_CHATS))
        assertFalse(nav.pop(TAB_CHATS))
        idle()
        assertEquals(1, nav.depth())
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a different conversation replaces the root and drops pushed pages`() {
        launch()
        open("a")
        push("info")
        open("b")
        assertEquals(listOf("conv:b:0"), labels(TAB_CHATS))
        assertEquals("b", nav.currentSelectedId)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `same conversation pops to its root without a jump and is rebuilt with one`() {
        launch()
        open("a")
        val root = host.pages(TAB_CHATS).single()
        push("info")

        open("a")
        assertSame("no jump: pop back to the existing conversation", root, host.pages(TAB_CHATS).single())

        open("a", jump = 42L)
        val rebuilt = host.pages(TAB_CHATS).single()
        assertNotSame("a jump is read at ViewModel creation, so it needs a new page", root, rebuilt)
        assertEquals("conv:a:42", rebuilt.label)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `replace root and clear empty the stack, and the empty pane re-opens the list`() {
        launch()
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("contact"), selectionId = "u1")
        idle()
        assertEquals("u1", nav.currentSelectedId)
        val emptiedBefore = host.emptiedCalls

        nav.clear(TAB_CHATS)
        idle()
        assertEquals(0, nav.depth())
        assertNull(nav.currentSelectedId)
        assertEquals(emptiedBefore + 1, host.emptiedCalls)
        assertEquals(View.VISIBLE, host.findViewById<View>(R.id.empty_detail_view).visibility)
    }

    

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `each tab keeps its own stack and hidden tabs are capped at STARTED`() {
        launch()
        open("a")
        push("info")
        selectTab(TAB_CONTACTS)
        open("b")

        val chats = host.host(TAB_CHATS)!!
        val contacts = host.host(TAB_CONTACTS)!!
        assertTrue(chats.isHidden)
        assertEquals(Lifecycle.State.STARTED, chats.state())
        assertEquals("hidden tab's top page is paused", Lifecycle.State.STARTED, host.pages(TAB_CHATS).last().state())
        assertFalse(contacts.isHidden)
        assertEquals(Lifecycle.State.RESUMED, host.pages(TAB_CONTACTS).single().state())
        assertEquals("b", nav.currentConversationOnScreen())

        selectTab(TAB_CHATS)
        assertEquals(listOf("conv:a:0", "info"), labels(TAB_CHATS))
        assertEquals(Lifecycle.State.RESUMED, host.pages(TAB_CHATS).last().state())
        assertTrue(contacts.isHidden)
        assertNull("a pushed page covers the conversation", nav.currentConversationOnScreen())
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a conversation lives in at most one tab stack`() {
        launch()
        open("x")
        selectTab(TAB_CONTACTS)
        open("x")

        assertEquals(0, nav.depth(TAB_CHATS))
        assertEquals(listOf("conv:x:0"), labels(TAB_CONTACTS))

        selectTab(TAB_CHATS)
        selectTab(TAB_CONTACTS)
        assertEquals("x", nav.currentConversationOnScreen())
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `an external open selects Chats first`() {
        launch()
        selectTab(TAB_ME)
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.GROUP, "g"), PaneNavigator.Origin.External)
        idle()
        assertEquals(listOf(TAB_CHATS), host.requestedTabs)
        assertEquals(TAB_CHATS, nav.currentTab)
        assertEquals(listOf("conv:g:0"), labels(TAB_CHATS))
    }

    

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `only the visible top of the current tab receives Back`() {
        launch()
        open("a")
        push("info")
        val (root, top) = host.pages(TAB_CHATS)
        assertEquals(Lifecycle.State.CREATED, root.backOwner.lifecycle.currentState)
        assertEquals(Lifecycle.State.STARTED, top.backOwner.lifecycle.currentState)

        host.onBackPressedDispatcher.onBackPressed()
        assertEquals(0, root.backPresses)
        assertEquals(1, top.backPresses)

        selectTab(TAB_CONTACTS)
        assertEquals("a hidden tab's page never handles Back", Lifecycle.State.CREATED, top.backOwner.lifecycle.currentState)
        host.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, top.backPresses)

        selectTab(TAB_CHATS)
        assertEquals(Lifecycle.State.STARTED, top.backOwner.lifecycle.currentState)

        top.detailNavigation().pop(top)
        idle()
        assertEquals(Lifecycle.State.DESTROYED, top.backOwner.lifecycle.currentState)
        assertEquals(Lifecycle.State.STARTED, root.backOwner.lifecycle.currentState)
        host.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, root.backPresses)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a covered page cannot pop the page above it`() {
        launch()
        open("a")
        push("info")
        val (root, _) = host.pages(TAB_CHATS)
        assertFalse(root.detailNavigation().pop(root))
        idle()
        assertEquals(2, nav.depth())
    }

    

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane layer is shown iff the stack is non-empty and hides the list meanwhile`() {
        launch()
        val layer = host.findViewById<View>(R.id.detail_pane)
        val list = host.findViewById<View>(R.id.index_viewpager)
        assertFalse(nav.isDualPane)
        assertEquals(View.GONE, layer.visibility)

        open("a")
        settle()
        assertEquals(View.VISIBLE, layer.visibility)
        assertTrue(nav.detailCoversList.value)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, list.importantForAccessibility)
        assertTrue("one-pane root can pop to the list", nav.canPop(TAB_CHATS))

        assertTrue(nav.pop(TAB_CHATS))
        settle()
        assertEquals(0, nav.depth())
        assertEquals(View.GONE, layer.visibility)
        assertFalse(nav.detailCoversList.value)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, list.importantForAccessibility)
    }

    

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `fold and unfold restore every tab stack into the other layout`() {
        launch()
        open("a")
        push("info")
        selectTab(TAB_ME)
        nav.replaceRoot(TAB_ME, StackPageFragment.newInstance("settings"), selectionId = null)
        idle()
        selectTab(TAB_CONTACTS)
        open("b")

        RuntimeEnvironment.setQualifiers("w400dp-h800dp")
        controller!!.recreate()
        idle()
        assertFalse(nav.isDualPane)
        assertEquals("the navigator restores its own tab before the pager does", TAB_CONTACTS, nav.currentTab)
        assertEquals(listOf("conv:a:0", "info"), labels(TAB_CHATS))
        assertTrue(host.pages(TAB_CHATS).first().isHidden)
        assertEquals(listOf("conv:b:0"), labels(TAB_CONTACTS))
        assertTrue("the Me stack is kept but not presented in one-pane", host.host(TAB_ME)!!.isHidden)
        assertEquals(0, nav.depth(TAB_ME))
        assertEquals(View.VISIBLE, host.findViewById<View>(R.id.detail_pane).visibility)
        assertEquals("b", nav.currentConversationOnScreen())

        RuntimeEnvironment.setQualifiers("w840dp-h900dp")
        controller!!.recreate()
        idle()
        assertTrue(nav.isDualPane)
        assertEquals(TAB_CONTACTS, nav.currentTab)
        assertEquals(listOf("conv:a:0", "info"), labels(TAB_CHATS))
        assertTrue(host.host(TAB_CHATS)!!.isHidden)
        assertEquals(listOf("conv:b:0"), labels(TAB_CONTACTS))
        assertEquals(Lifecycle.State.RESUMED, host.pages(TAB_CONTACTS).single().state())
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `the conversation lookup works in one-pane and during restore on a non-first tab`() {
        launch()
        selectTab(TAB_CONTACTS)
        open("b")
        assertEquals("conv:b:0", (nav.currentConversationRoot() as StackPageFragment).label)
        push("info")
        assertEquals("a covered conversation is still the tab's conversation", "conv:b:0", (nav.currentConversationRoot() as StackPageFragment).label)

        controller!!.recreate()
        idle()
        assertEquals(
            "resolved from the restored tab's host before any pager restore",
            "conv:b:0",
            (host.lookupDuringCreate as StackPageFragment).label,
        )
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `covering the list in one-pane notifies the host`() {
        launch()
        val before = host.stateChangedCalls
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("page"), selectionId = null)
        idle()
        assertTrue(nav.detailCoversList.value)
        assertTrue("a cover with no selection change still notifies", host.stateChangedCalls > before)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `requests after state save are queued and replayed on start`() {
        launch()
        controller!!.pause().stop()
        assertTrue(host.supportFragmentManager.isStateSaved)

        open("a")
        assertEquals("nothing commits while state is saved", 0, nav.depth())

        controller!!.start()
        idle()
        assertEquals(listOf("conv:a:0"), labels(TAB_CHATS))
    }
}
