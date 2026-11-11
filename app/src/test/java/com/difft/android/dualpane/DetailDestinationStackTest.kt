package com.difft.android.dualpane

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.RemovedConversations
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit





@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DetailDestinationStackTest {

    private class SettingsDestination : DetailDestination {
        override fun newFragment(): Fragment = StackPageFragment.newInstance("settings", handlesBack = false)
        override fun shellIntent(context: Context): Intent = Intent("settings-shell")
    }

    
    private class AboutDestination(private val label: String, override val subjectId: String) : DetailDestination {
        override fun newFragment(): Fragment = StackPageFragment.newInstance(label, handlesBack = false)
        override fun shellIntent(context: Context): Intent = Intent(label)
    }

    
    private class VariantDestination(
        private val label: String,
        override val subjectId: String,
        private val variant: String,
    ) : DetailDestination {
        override fun newFragment(): Fragment = StackPageFragment.newInstance(label, handlesBack = false)
        override fun shellIntent(context: Context): Intent = Intent(label)
        override val pageKey: String get() = "variant:$subjectId:$variant"
    }

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val nav: PaneNavigator get() = host.navigator

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        RemovedConversations.remove("m")
    }

    private fun pushFromTop(label: String, subjectId: String) {
        val top = host.pages(TAB_CHATS).last()
        top.detailNavigation().push(top, AboutDestination(label, subjectId))
        idle()
    }

    
    private fun removeFriend(id: String) {
        val settings = host.pages(TAB_CHATS).last()
        RemovedConversations.add(id)
        PaneNavigator.closeConversationEverywhere(id)
        settings.detailNavigation().closeConversation(settings)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun launchWithConversation(): StackPageFragment {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("conversation", handlesBack = false), selectionId = "u1")
        idle()
        return host.pages(TAB_CHATS).single()
    }

    private fun labels() = host.pages(TAB_CHATS).map { it.label }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `the destination stacks above the conversation and Back returns to it`() {
        val conversation = launchWithConversation()
        val resumesBefore = conversation.resumes

        conversation.detailNavigation().push(conversation, SettingsDestination())
        idle()
        assertEquals(listOf("conversation", "settings"), labels())
        assertNull("no Activity in the home stack", shadowOf(host).nextStartedActivity)

        host.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(listOf("conversation"), labels())
        assertEquals("the conversation resumes again on return", resumesBefore + 1, conversation.resumes)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane - the destination stacks in the layer and Back returns to the conversation`() {
        val conversation = launchWithConversation()

        conversation.detailNavigation().push(conversation, SettingsDestination())
        idle()
        host.onBackPressedDispatcher.onBackPressed()
        idle()

        assertEquals(listOf("conversation"), labels())
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `closing the conversation from the pushed page closes the whole stack`() {
        val conversation = launchWithConversation()
        conversation.detailNavigation().push(conversation, SettingsDestination())
        idle()
        val settings = host.pages(TAB_CHATS).last()

        settings.detailNavigation().closeConversation(settings)
        idle()

        assertEquals(0, nav.depth(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `removing a friend from a card pushed above a group closes only that friend's pages`() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.GROUP, "G"), PaneNavigator.Origin.Tab(TAB_CHATS))
        idle()
        pushFromTop("info G", "G")
        pushFromTop("card m", "m")
        pushFromTop("settings m", "m")
        val info = host.pages(TAB_CHATS)[1]
        val infoResumes = info.resumes

        removeFriend("m")

        assertEquals(listOf("conv:G:0", "info G"), labels())
        assertEquals("the revealed page resumes", infoResumes + 1, info.resumes)
        assertFalse(RemovedConversations.contains("m"))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane - removing a friend from a card above a group keeps the group pages`() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.GROUP, "G"), PaneNavigator.Origin.Tab(TAB_CHATS))
        idle()
        pushFromTop("info G", "G")
        pushFromTop("card m", "m")
        pushFromTop("settings m", "m")

        removeFriend("m")

        assertEquals(listOf("conv:G:0", "info G"), labels())
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `removing a friend from settings above that friend's card closes the whole stack`() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("card m", handlesBack = false), selectionId = "m")
        idle()
        pushFromTop("settings m", "m")

        removeFriend("m")

        assertEquals(0, nav.depth(TAB_CHATS))
        assertFalse(RemovedConversations.contains("m"))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `only the visible top pushes - a covered page or a hidden tab is refused`() {
        val conversation = launchWithConversation()
        assertTrue(conversation.detailNavigation().push(conversation, AboutDestination("settings", "u1")))
        idle()

        assertFalse("covered", conversation.detailNavigation().push(conversation, AboutDestination("again", "u1")))
        idle()
        assertEquals(listOf("conversation", "settings"), labels())

        nav.replaceRoot(PaneNavigator.TAB_CONTACTS, StackPageFragment.newInstance("card", handlesBack = false), selectionId = "c")
        idle()
        val card = host.pages(PaneNavigator.TAB_CONTACTS).single()
        assertFalse("hidden tab", card.detailNavigation().push(card, AboutDestination("card settings", "c")))
        idle()
        assertEquals(listOf("card"), host.pages(PaneNavigator.TAB_CONTACTS).map { it.label })
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a second identical push before the first lands is dropped, variants both land`() {
        val conversation = launchWithConversation()

        assertTrue(conversation.detailNavigation().push(conversation, AboutDestination("settings", "u1")))
        assertTrue(conversation.detailNavigation().push(conversation, AboutDestination("settings", "u1")))
        idle()
        assertEquals(listOf("conversation", "settings"), labels())

        host.onBackPressedDispatcher.onBackPressed()
        idle()
        conversation.detailNavigation().push(conversation, AboutDestination("settings", "u1"))
        conversation.detailNavigation().push(conversation, VariantDestination("moderators", "u1", variant = "add"))
        conversation.detailNavigation().push(conversation, VariantDestination("moderators", "u1", variant = "remove"))
        idle()
        assertEquals(listOf("conversation", "settings", "moderators", "moderators"), labels())
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a group opened from common groups replaces the card root, and leaves the other tab`() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.GROUP, "g"), PaneNavigator.Origin.Tab(TAB_CHATS))
        idle()
        nav.selectTab(PaneNavigator.TAB_CONTACTS)
        nav.replaceRoot(PaneNavigator.TAB_CONTACTS, StackPageFragment.newInstance("card m", handlesBack = false), selectionId = "m")
        idle()
        val card = host.pages(PaneNavigator.TAB_CONTACTS).single()
        card.detailNavigation().push(card, AboutDestination("common groups m", "m"))
        idle()
        val commonGroups = host.pages(PaneNavigator.TAB_CONTACTS).last()

        commonGroups.detailNavigation().openConversation(ConversationTarget(ConversationTarget.Kind.GROUP, "g"))
        idle()

        assertEquals(listOf("conv:g:0"), host.pages(PaneNavigator.TAB_CONTACTS).map { it.label })
        assertEquals("one instance per conversation", 0, host.pages(TAB_CHATS).size)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a conversation open from the pushed page replaces the stack`() {
        val conversation = launchWithConversation()
        conversation.detailNavigation().push(conversation, SettingsDestination())
        idle()
        val settings = host.pages(TAB_CHATS).last()

        settings.detailNavigation().openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, "u2"))
        idle()

        assertEquals(listOf("conv:u2:0"), labels())
    }
}
