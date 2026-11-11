package com.difft.android.dualpane

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.chat.contacts.contactsdetail.ContactCardNavigation
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class UnderDialogNavigationTest {

    
    class Sheet : DialogFragment() {
        val card: Fragment get() = childFragmentManager.findFragmentByTag("card")!!

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context).apply { id = View.generateViewId() }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            if (savedInstanceState == null) childFragmentManager.beginTransaction().add(view.id, Fragment(), "card").commitNow()
        }
    }

    private class Settings : DetailDestination {
        override fun newFragment(): Fragment = StackPageFragment.newInstance("settings", handlesBack = false)
        override fun shellIntent(context: Context): Intent = Intent(SHELL)
        override val subjectId: String get() = "m"
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

    private fun launch(qualifiers: String, conversation: String? = "y") {
        RuntimeEnvironment.setQualifiers(qualifiers)
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        conversation?.let {
            nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, it), PaneNavigator.Origin.Tab(TAB_CHATS))
        }
        settle()
    }

    private fun showSheet(fm: FragmentManager): Sheet {
        val sheet = Sheet()
        sheet.show(fm, "sheet")
        settle()
        return sheet
    }

    private fun startedShell(): String? = shadowOf(host).nextStartedActivity?.action

    @Test
    fun `T11 - a sheet over a hosted conversation pushes onto its stack and goes away`() {
        launch("w840dp-h900dp")
        val sheet = showSheet(host.pages(TAB_CHATS).single().childFragmentManager)

        ContactCardNavigation.push(sheet.card, Settings(), sheet, overConversation = null)
        settle()

        assertEquals(listOf("conv:y:0", "settings"), labels())
        assertFalse(sheet.isAdded)
        assertNull(startedShell())
    }

    @Test
    fun `T11 - a sheet shown by a page's nested fragment (a pager tab) pushes onto that page's stack`() {
        launch("w840dp-h900dp")
        val page = host.pages(TAB_CHATS).single()
        val tab = Fragment()
        page.childFragmentManager.beginTransaction().add(tab, "tab").commitNow()
        val sheet = showSheet(tab.childFragmentManager)

        ContactCardNavigation.push(sheet.card, Settings(), sheet, overConversation = null)
        settle()

        assertEquals(listOf("conv:y:0", "settings"), labels())
        assertFalse(sheet.isAdded)
        assertNull(startedShell())
    }

    @Test
    fun `T11 - one-pane, a list sheet on the Activity opens the shell`() {
        launch("w400dp-h800dp", conversation = null)
        val sheet = showSheet(host.supportFragmentManager)

        ContactCardNavigation.push(sheet.card, Settings(), sheet, overConversation = null)
        settle()

        assertEquals(SHELL, startedShell())
        assertFalse(sheet.isAdded)
    }

    @Test
    fun `T40 - a message-link sheet on the Activity over conversation y pushes onto y's stack`() {
        launch("w840dp-h900dp")
        val sheet = showSheet(host.supportFragmentManager)

        ContactCardNavigation.push(sheet.card, Settings(), sheet, overConversation = "y")
        settle()

        assertEquals(listOf("conv:y:0", "settings"), labels())
        assertNull(startedShell())
    }

    @Test
    fun `T60 - a list sheet restored into a two-pane tab showing y still opens the shell`() {
        launch("w840dp-h900dp")
        val sheet = showSheet(host.supportFragmentManager)

        ContactCardNavigation.push(sheet.card, Settings(), sheet, overConversation = null)
        settle()

        assertEquals(SHELL, startedShell())
        assertEquals(listOf("conv:y:0"), labels())
    }

    @Test
    fun `T60 - a link sheet whose conversation is no longer the root opens the shell`() {
        launch("w840dp-h900dp")
        val sheet = showSheet(host.supportFragmentManager)
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, "z"), PaneNavigator.Origin.Tab(TAB_CHATS))
        settle()

        ContactCardNavigation.push(sheet.card, Settings(), sheet, overConversation = "y")
        settle()

        assertEquals(SHELL, startedShell())
        assertEquals(listOf("conv:z:0"), labels())
    }

    @Test
    fun `T60 - a push accepted but lost before its drain opens nothing`() {
        launch("w840dp-h900dp")
        val sheet = showSheet(host.pages(TAB_CHATS).single().childFragmentManager)

        ContactCardNavigation.push(sheet.card, Settings(), sheet, overConversation = null)
        controller!!.recreate() 
        settle()

        assertEquals(listOf("conv:y:0"), labels())
        assertNull(startedShell())
    }

    private companion object {
        const val SHELL = "settings-shell"
    }
}
