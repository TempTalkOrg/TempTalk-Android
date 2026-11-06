package com.difft.android.dualpane

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.detailNavigationUnderDialog
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

/**
 * Pushing from a sheet (design §3.6): a sheet shown on a conversation page's FragmentManager pushes
 * onto that page's stack through [detailNavigationUnderDialog]; a sheet shown on the Activity has
 * no page and goes through the navigator's [PaneNavigator.pushOnVisibleStack], which pushes only
 * while the conversation it was shown over is still the root.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w840dp-h900dp")
class SheetPushTest {

    class Sheet : DialogFragment()

    private class Settings : DetailDestination {
        override fun newFragment(): Fragment = StackPageFragment.newInstance("settings", handlesBack = false)
        override fun shellIntent(context: Context): Intent = Intent("settings")
        override val subjectId: String get() = "y"
    }

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val nav: PaneNavigator get() = host.navigator

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun labels() = host.pages(TAB_CHATS).map { it.label }

    private fun launchWithConversation(): StackPageFragment {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, "y"), PaneNavigator.Origin.Tab(TAB_CHATS))
        settle()
        return host.pages(TAB_CHATS).single()
    }

    @Test
    fun `a sheet over a conversation page pushes onto that page's stack`() {
        val conversation = launchWithConversation()
        val sheet = Sheet()
        sheet.show(conversation.childFragmentManager, "card")
        settle()

        val navigation = sheet.detailNavigationUnderDialog()
        assertTrue(navigation!!.push(sheet, Settings()))
        sheet.dismiss()
        settle()

        assertEquals(listOf("conv:y:0", "settings"), labels())
    }

    @Test
    fun `a sheet on the Activity has no page under it`() {
        launchWithConversation()
        val sheet = Sheet()
        sheet.show(host.supportFragmentManager, "card")
        settle()

        assertNull(sheet.detailNavigationUnderDialog())
    }

    @Test
    fun `an Activity sheet pushes only over the conversation it was shown over`() {
        launchWithConversation()

        assertFalse("shown from the list", nav.pushOnVisibleStack(Settings(), overConversation = null))
        assertFalse("another conversation is the root now", nav.pushOnVisibleStack(Settings(), overConversation = "z"))
        settle()
        assertEquals(listOf("conv:y:0"), labels())

        assertTrue(nav.pushOnVisibleStack(Settings(), overConversation = "y"))
        settle()
        assertEquals(listOf("conv:y:0", "settings"), labels())
    }
}
