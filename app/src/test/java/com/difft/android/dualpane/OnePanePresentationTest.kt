package com.difft.android.dualpane

import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * One-pane presents only the stack of the tab in front at the fold and of tabs the user opens
 * something in while in one-pane; other stacks restored from two-pane are kept hidden and shown
 * again on unfold. Also: the one-pane layer paints the navigation-bar strip like ChatActivity.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class OnePanePresentationTest {

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val nav: PaneNavigator get() = host.navigator
    private val layer: View get() = host.findViewById(R.id.detail_pane)

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun open(id: String) {
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, id), PaneNavigator.Origin.Tab(nav.currentTab))
        settle()
    }

    private fun select(tab: Int) {
        nav.selectTab(tab)
        settle()
    }

    private fun recreate(qualifiers: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        controller!!.recreate()
        settle()
    }

    /** Two-pane with a conversation in Chats and in Contacts, Chats in front, then folded. */
    private fun foldWithTwoStacks() {
        RuntimeEnvironment.setQualifiers("w840dp-h900dp")
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        settle()
        open("a")
        select(TAB_CONTACTS)
        open("b")
        select(TAB_CHATS)
        recreate("w400dp-h800dp")
        assertFalse(nav.isDualPane)
    }

    @Test
    fun `the tab in front at the fold keeps its layer, a retained tab shows its list, and back`() {
        foldWithTwoStacks()
        assertEquals(1, nav.depth())
        assertEquals(View.VISIBLE, layer.visibility)

        select(TAB_CONTACTS)
        assertEquals("retained, not presented", 0, nav.depth())
        assertEquals(View.GONE, layer.visibility)
        assertFalse(nav.detailCoversList.value)
        assertFalse(nav.canPop(TAB_CONTACTS))
        assertTrue(host.host(TAB_CONTACTS)!!.isHidden)
        assertEquals(listOf("conv:b:0"), host.pages(TAB_CONTACTS).map { it.label })

        select(TAB_CHATS)
        assertEquals("the Chats layer comes back", 1, nav.depth())
        assertEquals(View.VISIBLE, layer.visibility)
        assertTrue(nav.detailCoversList.value)
    }

    @Test
    fun `opening something in a retained tab presents it`() {
        foldWithTwoStacks()
        select(TAB_CONTACTS)

        open("c")

        assertEquals(1, nav.depth())
        assertEquals(View.VISIBLE, layer.visibility)
        assertFalse(host.host(TAB_CONTACTS)!!.isHidden)
        assertEquals(listOf("conv:c:0"), host.pages(TAB_CONTACTS).map { it.label })
    }

    @Test
    fun `a retained conversation is never resumed when another one is opened into its tab`() {
        foldWithTwoStacks()
        select(TAB_CONTACTS)
        val retained = host.pages(TAB_CONTACTS).single()
        val resumes = retained.resumes

        open("c")

        assertEquals("b would cancel its notifications on resume", resumes, retained.resumes)
        assertEquals(listOf("conv:c:0"), host.pages(TAB_CONTACTS).map { it.label })
    }

    @Test
    fun `a push into a retained or Me stack is refused and presents nothing`() {
        foldWithTwoStacks()
        select(TAB_CONTACTS)
        val retained = host.pages(TAB_CONTACTS).single()
        val resumes = retained.resumes

        assertFalse(nav.push(TAB_CONTACTS, StackPageFragment.newInstance("settings")))
        assertFalse(nav.push(TAB_ME, StackPageFragment.newInstance("account")))
        settle()

        assertEquals(listOf("conv:b:0"), host.pages(TAB_CONTACTS).map { it.label })
        assertEquals("still not presented", 0, nav.depth())
        assertFalse(nav.detailCoversList.value)
        assertTrue(host.host(TAB_CONTACTS)!!.isHidden)
        assertEquals("the retained page is not resumed", resumes, retained.resumes)
        assertEquals(0, host.pages(TAB_ME).size)
    }

    @Test
    fun `unfolding presents every stack again`() {
        foldWithTwoStacks()

        recreate("w840dp-h900dp")

        assertTrue(nav.isDualPane)
        assertEquals(1, nav.depth(TAB_CHATS))
        assertEquals(1, nav.depth(TAB_CONTACTS))
        select(TAB_CONTACTS)
        assertFalse(host.host(TAB_CONTACTS)!!.isHidden)
        assertEquals(listOf("conv:b:0"), host.pages(TAB_CONTACTS).map { it.label })
    }

    @Test
    fun `a one-pane recreation keeps which stacks are presented`() {
        foldWithTwoStacks()

        recreate("w400dp-h800dp") // e.g. a theme change while folded

        assertEquals(1, nav.depth(TAB_CHATS))
        assertEquals(0, nav.depth(TAB_CONTACTS))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `the one-pane layer paints the navigation-bar strip behind a conversation`() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        settle()
        open("a")

        // TestPages: 2 = layer variant (strip painted), 0 = plain pane variant.
        assertEquals(2, (layer.background as ColorDrawable).color)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `the root under the list stays solid while a conversation slides in`() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        settle()
        val root = host.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bg1 = androidx.core.content.ContextCompat.getColor(host, com.difft.android.base.R.color.bg1)

        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, "a"), PaneNavigator.Origin.Tab(TAB_CHATS))
        shadowOf(Looper.getMainLooper()).idle() // the layer is still sliding in over the list

        assertEquals("the status-bar strip ChatActivity paints, never the pattern", bg1, (root.background as ColorDrawable).color)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `the two-pane pane also paints the navigation-bar strip behind a conversation`() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        settle()
        open("a")

        // The two-pane detail column reaches the window bottom too, so it uses the layer variant.
        assertEquals(2, (layer.background as ColorDrawable).color)
    }
}
