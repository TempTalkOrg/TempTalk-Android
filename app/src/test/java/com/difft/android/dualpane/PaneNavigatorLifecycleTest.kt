package com.difft.android.dualpane

import android.os.Looper
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.R
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CONTACTS
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_ME
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * [PaneNavigator] across timing and lifecycle edges on [PaneNavigatorTestHost] (which registers the
 * real [IndexBackCallback]): a page arriving during the one-pane close slide, a destroyed
 * instance's late fragment callbacks, recreation with a collapsed list, a queued external open
 * across recreation, opens from the Me tab, and Back order with page callbacks.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PaneNavigatorLifecycleTest {

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val nav: PaneNavigator get() = host.navigator

    @Before
    fun setUp() {
        PaneNavigatorTestHost.listCollapsed = false
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        PaneNavigatorTestHost.listCollapsed = false
    }

    private fun launch() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun target(id: String) = ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, id)

    private fun open(id: String) {
        nav.openConversation(target(id), PaneNavigator.Origin.Tab(nav.currentTab))
        idle()
    }

    private fun labels(tab: Int) = host.pages(tab).map { it.label }

    private fun back() {
        host.onBackPressedDispatcher.onBackPressed()
        idle()
    }

    // ---------------------------------------------------------------- one-pane close race

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `an open arriving during the close slide keeps its conversation`() {
        launch()
        open("a")
        settle()
        val layer = host.findViewById<View>(R.id.detail_pane)

        assertTrue(nav.pop(TAB_CHATS)) // starts the slide out
        nav.openConversation(target("b"), PaneNavigator.Origin.External)
        idle()
        settle()

        assertEquals(listOf("conv:b:0"), labels(TAB_CHATS))
        assertEquals(View.VISIBLE, layer.visibility)
        assertEquals(0f, layer.translationX)
        assertTrue(nav.detailCoversList.value)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `a push arriving during the close slide keeps the stack`() {
        launch()
        open("a")
        settle()

        nav.pop(TAB_CHATS)
        nav.push(TAB_CHATS, StackPageFragment.newInstance("info"))
        idle()
        settle()

        assertEquals(listOf("conv:a:0", "info"), labels(TAB_CHATS))
        assertEquals(0f, host.findViewById<View>(R.id.detail_pane).translationX)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `an uninterrupted close still returns to the list`() {
        launch()
        open("a")
        settle()

        nav.pop(TAB_CHATS)
        settle()

        assertEquals(0, nav.depth())
        assertEquals(View.GONE, host.findViewById<View>(R.id.detail_pane).visibility)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `a routine re-derive while a close is pending leaves the layer slid out`() {
        launch()
        open("a")
        settle()
        val layer = host.findViewById<View>(R.id.detail_pane)

        nav.pop(TAB_CHATS)
        // Execute the queued pop, without draining the close animation before stopping.
        shadowOf(Looper.getMainLooper()).runOneTask()
        controller!!.pause().stop() // the clear queued by the slide's end waits for start
        settle()
        val slidOut = layer.translationX
        assertTrue("precondition: the slide finished off screen", slidOut != 0f)

        nav.onHostStackChanged()
        idle()
        assertEquals("only a new page may bring the layer back", slidOut, layer.translationX)

        controller!!.start().resume()
        settle()
        assertEquals(0, nav.depth())
        assertEquals(View.GONE, layer.visibility)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `a request for another tab lets the pending close finish`() {
        launch()
        open("a")
        settle()

        nav.pop(TAB_CHATS)
        nav.replaceRoot(TAB_CONTACTS, StackPageFragment.newInstance("elsewhere"), selectionId = null)
        idle()
        settle()

        assertEquals("the Chats close was not cancelled", 0, nav.depth(TAB_CHATS))
        assertEquals(listOf("elsewhere"), labels(TAB_CONTACTS))
    }

    // ---------------------------------------------------------------- Me tab root

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a Me settings page replaces the Me tab's root`() {
        launch()
        nav.selectTab(TAB_ME)
        idle()

        nav.replaceRoot(TAB_ME, StackPageFragment.newInstance("account"), selectionId = null)
        idle()
        nav.replaceRoot(TAB_ME, StackPageFragment.newInstance("privacy"), selectionId = null)
        idle()

        assertEquals(listOf("privacy"), labels(TAB_ME))
        assertEquals(0, nav.depth(TAB_CHATS))
    }

    // ---------------------------------------------------------------- teardown and restore

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a destroyed instance's late fragment callbacks never reach its listener`() {
        launch()
        open("a")
        val old = host
        val emptied = old.emptiedCalls
        val changed = old.stateChangedCalls

        controller!!.pause().stop().destroy()
        idle()

        assertEquals(emptied, old.emptiedCalls)
        assertEquals(changed, old.stateChangedCalls)
        controller = null
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `recreation keeps a collapsed list collapsed`() {
        launch()
        open("a")
        PaneNavigatorTestHost.listCollapsed = true
        val old = host

        controller!!.recreate()
        idle()

        assertTrue("the old instance's teardown must not expand the list", PaneNavigatorTestHost.listCollapsed)
        assertEquals(0, old.emptiedCalls)
        assertEquals(0, host.emptiedCalls)
        assertEquals(listOf("conv:a:0"), labels(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `the user emptying the shown stack still re-opens a collapsed list`() {
        launch()
        open("a")
        PaneNavigatorTestHost.listCollapsed = true

        nav.clear(TAB_CHATS)
        idle()

        assertFalse(PaneNavigatorTestHost.listCollapsed)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `an external open queued while state is saved survives a recreation`() {
        launch()
        controller!!.pause().stop()
        nav.openConversation(target("x"), PaneNavigator.Origin.External)
        idle()
        assertEquals(0, nav.depth())

        controller!!.recreate()
        if (!host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller!!.start().resume()
        idle()

        assertEquals(listOf("conv:x:0"), labels(TAB_CHATS))
    }

    // ---------------------------------------------------------------- Me tab origin

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a conversation opened from the Me tab opens in Chats`() {
        launch()
        nav.selectTab(TAB_ME)
        idle()

        nav.openConversation(target("bot"), PaneNavigator.Origin.Tab(TAB_ME))
        idle()

        assertEquals(listOf(TAB_CHATS), host.requestedTabs)
        assertEquals(TAB_CHATS, nav.currentTab)
        assertEquals(listOf("conv:bot:0"), labels(TAB_CHATS))
        assertEquals(0, nav.depth(TAB_ME))
    }

    // ---------------------------------------------------------------- Back order

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `the top page's own Back callback runs before the host's, which pops when it declines`() {
        launch()
        open("a")
        nav.push(TAB_CHATS, StackPageFragment.newInstance("editing", handlesBack = true))
        idle()

        back()
        assertEquals("the page handled it", 1, host.pages(TAB_CHATS).last().backPresses)
        assertEquals(2, nav.depth())

        nav.push(TAB_CHATS, StackPageFragment.newInstance("plain", handlesBack = false))
        idle()
        back()
        assertEquals("the host popped the declining page", listOf("conv:a:0", "editing"), labels(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane root Back expands a collapsed list, then sends the task back`() {
        launch()
        nav.openConversation(target("a"), PaneNavigator.Origin.Tab(TAB_CHATS))
        idle()
        // The root conversation's own callback is the page's; make it decline like a hosted list.
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("root", handlesBack = false), selectionId = "a")
        idle()
        PaneNavigatorTestHost.listCollapsed = true

        back()
        assertFalse(PaneNavigatorTestHost.listCollapsed)
        assertEquals(0, host.moveTaskToBackCalls)

        back()
        assertEquals(1, host.moveTaskToBackCalls)
        assertEquals(1, nav.depth())
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane root Back returns to the list`() {
        launch()
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("root", handlesBack = false), selectionId = "a")
        idle()
        settle()

        back()
        settle()

        assertEquals(0, nav.depth())
        assertEquals(0, host.moveTaskToBackCalls)
    }
}
