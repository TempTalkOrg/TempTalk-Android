package com.difft.android.dualpane

import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.R
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.RemovedConversations
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CONTACTS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit






@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PaneNavigatorRemovedConversationRestoreTest {

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val nav: PaneNavigator get() = host.navigator

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        RemovedConversations.remove("u1")
        RemovedConversations.remove("m")
    }

    private fun labels(tab: Int) = host.pages(tab).map { it.label }

    private fun launch() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        settle()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun open(id: String) {
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, id), PaneNavigator.Origin.Tab(nav.currentTab))
        settle()
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `a removed conversation stays closed when a recreation interrupts the close slide`() {
        launch()
        open("u1")

        RemovedConversations.add("u1")
        PaneNavigator.closeConversationEverywhere("u1") 
        controller!!.recreate() 
        settle()

        assertEquals(0, nav.depth(TAB_CHATS))
        assertFalse(RemovedConversations.contains("u1"))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a removed conversation stays closed when its queued close is lost with the instance`() {
        launch()
        open("u1")
        controller!!.pause().stop()

        RemovedConversations.add("u1")
        PaneNavigator.closeConversationEverywhere("u1") 
        controller!!.recreate()
        settle()

        assertEquals(0, nav.depth(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `opening a removed conversation again is not undone`() {
        launch()
        RemovedConversations.add("u1")

        open("u1")
        nav.push(TAB_CHATS, StackPageFragment.newInstance("info"))
        settle()

        assertEquals(2, nav.depth(TAB_CHATS))
        assertFalse(RemovedConversations.contains("u1"))
    }

    
    private fun closeThenOpenQueued() {
        PaneNavigator.closeConversationEverywhere("u1")
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, "u2"), PaneNavigator.Origin.Tab(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a close queued before an open does not remove the conversation opened after it`() {
        launch()
        open("u1")

        closeThenOpenQueued()
        settle()

        assertEquals(listOf("conv:u2:0"), labels(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a close and an open queued behind saved state run in order`() {
        launch()
        open("u1")
        controller!!.pause().stop()

        closeThenOpenQueued()
        controller!!.start().resume()
        settle()

        assertEquals(listOf("conv:u2:0"), labels(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane - a close queued before an open keeps the conversation opened after it`() {
        launch()
        open("u1")

        closeThenOpenQueued()
        settle()

        assertEquals(listOf("conv:u2:0"), labels(TAB_CHATS))
        assertEquals(1, nav.depth(TAB_CHATS))
    }

    



    private fun closeThenKeepQueued(keep: () -> Unit) {
        val root = host.pages(TAB_CHATS).first()
        root.detailNavigation().closeConversation(root)
        keep()
        settle()
    }

    private fun reopenU1() =
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, "u1"), PaneNavigator.Origin.Tab(TAB_CHATS))

    private fun pushInfo() {
        nav.push(TAB_CHATS, StackPageFragment.newInstance("info"))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane - reopening the conversation after its queued close keeps it`() {
        launch()
        open("u1")

        closeThenKeepQueued(::reopenU1)

        assertEquals(listOf("conv:u1:0"), labels(TAB_CHATS))
        assertEquals(1, nav.depth(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane - a push after the queued close keeps the stack`() {
        launch()
        open("u1")

        closeThenKeepQueued(::pushInfo)

        assertEquals(listOf("conv:u1:0", "info"), labels(TAB_CHATS))
        assertEquals(2, nav.depth(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane - reopening the conversation after its queued close keeps it`() {
        launch()
        open("u1")

        closeThenKeepQueued(::reopenU1)

        assertEquals(listOf("conv:u1:0"), labels(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane - a push after the queued close keeps the stack`() {
        launch()
        open("u1")

        closeThenKeepQueued(::pushInfo)

        assertEquals(listOf("conv:u1:0", "info"), labels(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a removed friend's conversation closes even when a push is queued after the close`() {
        launch()
        open("u1")

        
        
        RemovedConversations.add("u1")
        PaneNavigator.closeConversationEverywhere("u1")
        pushInfo()
        settle()

        assertEquals(0, nav.depth(TAB_CHATS))
        assertFalse(RemovedConversations.contains("u1"))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a push dropped as a duplicate does not cancel a close queued before it`() {
        launch()
        open("G")
        nav.push(TAB_CHATS, StackPageFragment.newInstance("card m"), subjectId = "m", pageKey = "card:m")
        nav.closeSubject(TAB_CHATS, "G")
        nav.push(TAB_CHATS, StackPageFragment.newInstance("card m"), subjectId = "m", pageKey = "card:m")
        settle()

        assertEquals(0, nav.depth(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane - a close of another tab's root while a slide runs is not lost`() {
        launch()
        nav.selectTab(TAB_CONTACTS)
        settle()
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.GROUP, "B"), PaneNavigator.Origin.Tab(TAB_CONTACTS))
        settle()
        nav.selectTab(TAB_CHATS)
        settle()
        open("A")

        nav.pop(TAB_CHATS) 
        nav.selectTab(TAB_CONTACTS)
        nav.closeSubject(TAB_CONTACTS, "B") 
        settle()

        assertEquals(0, nav.depth(TAB_CONTACTS))
        assertEquals(emptyList<String>(), labels(TAB_CONTACTS))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a removed contact's pages close after a recreation, at any depth`() {
        launch()
        open("G")
        nav.push(TAB_CHATS, StackPageFragment.newInstance("info G"), subjectId = "G")
        nav.push(TAB_CHATS, StackPageFragment.newInstance("card m"), subjectId = "m")
        nav.push(TAB_CHATS, StackPageFragment.newInstance("settings m"), subjectId = "m")
        nav.replaceRoot(TAB_CONTACTS, StackPageFragment.newInstance("card m root"), selectionId = "m")
        nav.push(TAB_CONTACTS, StackPageFragment.newInstance("settings m"), subjectId = "m")
        settle()
        controller!!.pause().stop()

        
        RemovedConversations.add("m")
        controller!!.recreate()
        settle()

        assertEquals(listOf("conv:G:0", "info G"), labels(TAB_CHATS))
        assertEquals(emptyList<String>(), labels(TAB_CONTACTS))
        assertFalse(RemovedConversations.contains("m"))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a page about a removed contact opened again survives the sweep`() {
        launch()
        open("G")
        RemovedConversations.add("m")

        nav.push(TAB_CHATS, StackPageFragment.newInstance("card m"), subjectId = "m")
        settle()
        assertEquals(listOf("conv:G:0", "card m"), labels(TAB_CHATS))
        assertFalse(RemovedConversations.contains("m"))

        RemovedConversations.add("m")
        nav.replaceRoot(TAB_CONTACTS, StackPageFragment.newInstance("card m root"), selectionId = "m")
        settle()
        assertEquals(listOf("card m root"), labels(TAB_CONTACTS))
        assertFalse(RemovedConversations.contains("m"))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `the one-pane layer keeps the conversation background behind a push`() {
        launch()
        open("a")
        val layer = host.findViewById<View>(R.id.detail_pane)
        val conversationBackground = (layer.background as ColorDrawable).color

        nav.push(TAB_CHATS, StackPageFragment.newInstance("settings"))
        settle()

        assertEquals(conversationBackground, (layer.background as ColorDrawable).color)
    }
}
