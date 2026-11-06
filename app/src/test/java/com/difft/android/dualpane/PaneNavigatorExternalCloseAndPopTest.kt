package com.difft.android.dualpane

import android.graphics.drawable.ColorDrawable
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CONTACTS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/**
 * [PaneNavigator]: a removed contact's conversation closes on
 * the home screen from outside its page, pushed one-pane pages are opaque during the slide, and
 * only a one-pane user pop of a pushed page animates (no stored pop animations survive an unfold).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PaneNavigatorExternalCloseAndPopTest {

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val nav: PaneNavigator get() = host.navigator

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
    }

    private fun launch() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        settle()
    }

    /** Runs posted work without letting animations finish. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun open(id: String, tab: Int = nav.currentTab) {
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, id), PaneNavigator.Origin.Tab(tab))
        settle()
    }

    private fun push(label: String) {
        nav.push(nav.currentTab, StackPageFragment.newInstance(label, handlesBack = false))
        settle()
    }

    // ---------------------------------------------------------------- removed conversation

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a removed contact's conversation closes on the home screen without its page`() {
        launch()
        open("u1")
        push("settings")
        nav.selectTab(TAB_CONTACTS)
        settle()
        open("u2")

        PaneNavigator.closeConversationEverywhere("u1")
        settle()

        assertEquals(0, nav.depth(TAB_CHATS))
        assertEquals("other conversations stay", 1, nav.depth(TAB_CONTACTS))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `with the home screen gone the close is a no-op`() {
        launch()
        open("u1")
        controller!!.pause().stop().destroy()
        controller = null

        PaneNavigator.closeConversationEverywhere("u1")
        settle()
    }

    // ---------------------------------------------------------------- opaque pushed pages

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane pushed pages are opaque, the root keeps the pane background`() {
        launch()
        open("a")
        push("settings")

        val (root, pushed) = host.pages(TAB_CHATS)
        assertNull(root.view!!.background)
        assertTrue(pushed.view!!.background is ColorDrawable)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane pushed pages are left as they are`() {
        launch()
        open("a")
        push("settings")

        assertNull(host.pages(TAB_CHATS).last().view!!.background)
    }

    // ---------------------------------------------------------------- pop animations

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `only a one-pane user pop of a pushed page animates`() {
        launch()
        // The pop decides its animations when it happens; nothing is stored with the push.
        assertNotNull(nav.popAnimations(userPop = true))
        assertNull("clear, replace and pop-to-root are instant", nav.popAnimations(userPop = false))

        open("a")
        push("settings")
        assertTrue(nav.pop(TAB_CHATS))
        settle()
        assertEquals(1, nav.depth())
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `a page pushed in one-pane pops instantly after an unfold`() {
        launch()
        open("a")
        push("settings")

        RuntimeEnvironment.setQualifiers("w840dp-h900dp")
        controller!!.recreate()
        settle()
        val pushed = host.pages(TAB_CHATS).last()

        assertTrue(nav.pop(TAB_CHATS))
        idle()

        assertNull("no stored pop animation", pushed.view)
        assertEquals(1, nav.depth())
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `a reopened root drops its pushed pages instantly`() {
        launch()
        open("a")
        push("settings")
        val pushed = host.pages(TAB_CHATS).last()

        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, "a"), PaneNavigator.Origin.Tab(TAB_CHATS))
        idle()

        assertNull(pushed.view)
        assertEquals(1, nav.depth())
    }
}
