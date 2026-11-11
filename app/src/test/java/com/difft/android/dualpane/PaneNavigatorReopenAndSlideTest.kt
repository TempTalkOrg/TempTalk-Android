package com.difft.android.dualpane

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_ME
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit







@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PaneNavigatorReopenAndSlideTest {

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

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun open(id: String, jump: Long? = null) {
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, id, jump), PaneNavigator.Origin.Tab(nav.currentTab))
        idle()
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `reopening the root conversation tells the kept page`() {
        launch()
        open("a")
        val root = host.pages(TAB_CHATS).single()
        assertEquals(0, root.reopens)

        nav.push(TAB_CHATS, StackPageFragment.newInstance("info"))
        idle()
        open("a")
        assertEquals("popped back to and told", 1, root.reopens)

        open("a")
        assertEquals("tapped again while already shown", 2, root.reopens)

        open("b")
        assertEquals("a different conversation is a fresh page, not a reopen", 2, root.reopens)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a jump rebuilds the page instead of reopening it`() {
        launch()
        open("a")
        val root = host.pages(TAB_CHATS).single()

        open("a", jump = 42L)

        assertEquals(0, root.reopens)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane pushes slide in and user pops slide out`() {
        launch()
        assertTrue(nav.slides())
        assertTrue(nav.popSlides(userPop = true))
    }

    
    private fun startsFromNegativeX(animRes: Int): Boolean {
        val parser = RuntimeEnvironment.getApplication().resources.getAnimation(animRes)
        while (parser.next() != XmlPullParser.START_TAG) Unit
        val from = parser.getAttributeValue(ANDROID_NS, "fromXDelta")
        val to = parser.getAttributeValue(ANDROID_NS, "toXDelta")
        val moving = from.trim('0', '.', '%').ifEmpty { to }
        check(moving.isNotEmpty()) { "no offset in from=$from to=$to" }
        return moving.startsWith("-")
    }

    @Test
    fun `left-to-right - an Activity enters from the right and leaves to the right, like a page`() {
        assertFalse(startsFromNegativeX(com.difft.android.base.R.anim.page_push_enter))
        assertFalse(startsFromNegativeX(com.difft.android.base.R.anim.page_pop_exit))
    }

    @Test
    @Config(qualifiers = "ar-ldrtl")
    fun `right-to-left - the window slides mirror like the pane layout`() {
        assertTrue(startsFromNegativeX(com.difft.android.base.R.anim.page_push_enter))
        assertTrue(startsFromNegativeX(com.difft.android.base.R.anim.page_pop_exit))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane pushes and user pops slide too`() {
        launch()
        assertTrue(nav.slides())
        assertTrue(nav.popSlides(userPop = true))
        assertFalse(nav.popSlides(userPop = false))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a Me settings page becomes the Me root whatever tab is current`() {
        launch()
        assertEquals(TAB_CHATS, nav.currentTab)

        assertTrue(nav.showMePage(StackPageFragment.newInstance("account")))
        idle()
        assertTrue(nav.showMePage(StackPageFragment.newInstance("privacy")))
        idle()

        assertEquals(listOf("privacy"), host.pages(TAB_ME).map { it.label })
        assertEquals(0, nav.depth(TAB_CHATS))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane presents Me and Back returns to its list without finishing home`() {
        launch()
        assertTrue(nav.showMePage(StackPageFragment.newInstance("account", handlesBack = false)))
        idle()
        assertEquals(TAB_ME, nav.currentTab)
        assertEquals(listOf("account"), host.pages(TAB_ME).map { it.label })
        assertTrue(nav.detailCoversList.value)
        host.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(0, nav.depth(TAB_ME))
        assertFalse(host.isFinishing)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
