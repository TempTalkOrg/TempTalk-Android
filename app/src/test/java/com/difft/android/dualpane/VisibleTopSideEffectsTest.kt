package com.difft.android.dualpane

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.pane.isVisibleTopNow
import com.difft.android.base.utils.pane.onVisibleTopAgain
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CONTACTS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit






@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w840dp-h900dp")
class VisibleTopSideEffectsTest {

    
    class ListPart : Fragment() {
        var catchUps = 0

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            onVisibleTopAgain { catchUps++ }
        }
    }

    class ConversationPage : Fragment() {
        val list: ListPart get() = childFragmentManager.findFragmentByTag("list") as ListPart

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context).apply { id = android.R.id.custom } 

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            if (savedInstanceState == null) childFragmentManager.beginTransaction().add(view.id, ListPart(), "list").commitNow()
        }
    }

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val nav: PaneNavigator get() = host.navigator

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun launch(): ConversationPage {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        val page = ConversationPage()
        nav.replaceRoot(TAB_CHATS, page, selectionId = "u1")
        settle()
        return page
    }

    @Test
    fun `a read queued before a push finds the list covered`() {
        val list = launch().list
        assertTrue(list.isVisibleTopNow())
        val shown = list.catchUps
        var sawVisible: Boolean? = null

        
        list.requireView().postDelayed({ sawVisible = list.isVisibleTopNow() }, 50)
        nav.push(TAB_CHATS, StackPageFragment.newInstance("info"))
        settle()

        assertEquals(false, sawVisible)
        assertEquals("no catch-up while covered", shown, list.catchUps)
    }

    @Test
    fun `revealing the list catches up once`() {
        val list = launch().list
        val shown = list.catchUps

        nav.push(TAB_CHATS, StackPageFragment.newInstance("info"))
        settle()
        nav.pop(TAB_CHATS)
        settle()

        assertTrue(list.isVisibleTopNow())
        assertEquals(shown + 1, list.catchUps)
    }

    @Test
    fun `a list restored under a pushed page catches up when revealed`() {
        launch()
        nav.push(TAB_CHATS, StackPageFragment.newInstance("group info"))
        settle()

        controller!!.recreate() 
        settle()
        val restored = (host.host(TAB_CHATS)!!.rootPage() as ConversationPage).list
        assertFalse(restored.isVisibleTopNow())
        assertEquals("nothing while covered", 0, restored.catchUps)

        nav.pop(TAB_CHATS)
        settle()
        assertEquals("caught up on reveal", 1, restored.catchUps)
    }

    @Test
    fun `a hidden tab or the background holds reads until shown again`() {
        val list = launch().list
        val shown = list.catchUps

        nav.selectTab(TAB_CONTACTS)
        settle()
        assertFalse(list.isVisibleTopNow())
        nav.selectTab(TAB_CHATS)
        settle()
        assertEquals(shown + 1, list.catchUps)

        controller!!.pause().stop()
        assertFalse(list.isVisibleTopNow())
        controller!!.start().resume()
        settle()
        assertEquals(shown + 2, list.catchUps)
    }

    @Test
    fun `outside a stack nothing changes`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val list = ListPart()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, list).commitNow()

        assertTrue(list.isVisibleTopNow())
    }
}
