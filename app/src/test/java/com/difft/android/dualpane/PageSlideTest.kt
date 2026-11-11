package com.difft.android.dualpane

import android.os.Looper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class PageSlideTest {

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val nav: PaneNavigator get() = host.navigator
    private val runs = mutableListOf<PageSlide.Role>()

    @After
    fun tearDown() {
        PageSlide.onRun = null
        runCatching { controller?.pause()?.stop()?.destroy() }
    }

    private fun launch() {
        PageSlide.onRun = { runs += it }
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun open(id: String) {
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, id), PaneNavigator.Origin.Tab(nav.currentTab))
        idle()
    }

    private fun push(label: String) {
        nav.push(nav.currentTab, StackPageFragment.newInstance(label))
        idle()
    }

    private fun assertAtRest(page: StackPageFragment) {
        val view = page.requireView()
        assertEquals(0f, view.translationX, 0f)
        assertNull(view.foreground)
    }

    private fun pushSlidesOverTheCoveredPage() {
        open("a")
        val root = host.pages(TAB_CHATS).single()
        push("info")
        val top = host.pages(TAB_CHATS).last()

        assertEquals(listOf(PageSlide.Role.ENTER_OVER, PageSlide.Role.EXIT_UNDER).sorted(), runs.sorted())
        assertEquals(View.VISIBLE, top.requireView().visibility)
        assertAtRest(top)
        assertTrue(root.isHidden)
        assertEquals(View.GONE, root.requireView().visibility)
        assertAtRest(root)
    }

    private fun userPopSlidesOutOverTheRevealedPage() {
        open("a")
        push("info")
        val (root, pushed) = host.pages(TAB_CHATS)
        runs.clear()

        assertTrue(nav.pop(TAB_CHATS))
        idle()

        assertEquals(listOf(PageSlide.Role.LEAVE_OVER, PageSlide.Role.RETURN_UNDER).sorted(), runs.sorted())
        assertNull(pushed.view)
        assertEquals(listOf(root), host.pages(TAB_CHATS))
        assertEquals(View.VISIBLE, root.requireView().visibility)
        assertAtRest(root)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane push slides the entering page over the covered one`() {
        launch()
        pushSlidesOverTheCoveredPage()
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane user pop slides the leaving page out over the revealed one`() {
        launch()
        userPopSlidesOutOverTheRevealedPage()
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane push slides the entering page over the covered one`() {
        launch()
        pushSlidesOverTheCoveredPage()
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane user pop slides the leaving page out over the revealed one`() {
        launch()
        userPopSlidesOutOverTheRevealedPage()
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a reopened root, a replaced root and a closed subject carry no slide`() {
        launch()
        open("a")
        push("info")
        runs.clear()

        
        open("a")
        assertEquals(emptyList<PageSlide.Role>(), runs)
        assertEquals(1, nav.depth())

        push("info")
        runs.clear()
        
        open("b")
        assertEquals(emptyList<PageSlide.Role>(), runs)
        assertEquals("conv:b:0", host.pages(TAB_CHATS).single().label)

        nav.push(TAB_CHATS, StackPageFragment.newInstance("card"), subjectId = "x")
        idle()
        runs.clear()
        
        nav.closeSubject(TAB_CHATS, "x")
        idle()
        assertEquals(emptyList<PageSlide.Role>(), runs)
        assertEquals(1, nav.depth())
        assertNotNull(host.pages(TAB_CHATS).single().view)
    }
}
