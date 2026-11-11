package com.difft.android.dualpane

import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.DualPaneUtils.setupDynamicBackButton
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import com.difft.android.base.utils.pane.ConversationTarget
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
@Config(sdk = [33])
class DetailPageChromeTest {

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

    private fun open(id: String) {
        nav.openConversation(ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, id), PaneNavigator.Origin.Tab(nav.currentTab))
        idle()
    }

    private fun push(label: String) {
        nav.push(nav.currentTab, StackPageFragment.newInstance(label))
        idle()
    }

    private fun backButtonOf(page: StackPageFragment): ImageView =
        ImageView(host).also {
            page.setupDynamicBackButton(
                backButton = it,
                isEditMode = false,
                backIconRes = android.R.drawable.ic_media_previous,
                closeIconRes = android.R.drawable.ic_menu_close_clear_cancel,
                onExitEditMode = {},
            )
        }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `two-pane root hides the back button, a pushed page shows one that pops`() {
        launch()
        open("a")
        val root = host.pages(TAB_CHATS).single()
        assertFalse(root.showsDetailBackButton())
        assertEquals(View.GONE, backButtonOf(root).visibility)

        push("info")
        val top = host.pages(TAB_CHATS).last()
        val button = backButtonOf(top)
        assertEquals(View.VISIBLE, button.visibility)

        button.performClick()
        idle()
        assertEquals(1, nav.depth())
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a root recreated under pushed pages shows no back button, before or after the pops`() {
        launch()
        open("a")
        push("info")
        push("card")

        controller!!.recreate() 
        idle()
        val root = host.pages(TAB_CHATS).first()
        
        val rootButton = backButtonOf(root)
        assertFalse(root.showsDetailBackButton())
        assertEquals(View.GONE, rootButton.visibility)

        val top = host.pages(TAB_CHATS).last()
        val topButton = backButtonOf(top)
        assertEquals(View.VISIBLE, topButton.visibility)
        topButton.performClick()
        idle()
        nav.pop(TAB_CHATS)
        idle()

        assertEquals("the conversation stays", listOf("conv:a:0"), host.pages(TAB_CHATS).map { it.label })
        assertEquals("no dead back button on the revealed root", View.GONE, rootButton.visibility)
        assertFalse(root.showsDetailBackButton())
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane root shows the back button and it returns to the list`() {
        launch()
        open("a")
        val root = host.pages(TAB_CHATS).single()
        val button = backButtonOf(root)
        assertTrue(root.showsDetailBackButton())
        assertEquals(View.VISIBLE, button.visibility)

        button.performClick()
        idle()
        assertEquals(0, nav.depth())
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `overlays are dismissed when the page is covered, hidden or popped`() {
        launch()
        open("a")
        val root = host.pages(TAB_CHATS).single()
        assertEquals(0, root.overlayDismissals)

        push("info")
        assertEquals("covered", 1, root.overlayDismissals)
        val top = host.pages(TAB_CHATS).last()

        nav.selectTab(TAB_CONTACTS)
        idle()
        assertEquals("hidden with its tab", 1, top.overlayDismissals)

        nav.selectTab(TAB_CHATS)
        idle()
        nav.pop(TAB_CHATS)
        idle()
        assertEquals("popped", 2, top.overlayDismissals)
        assertEquals("the revealed page keeps no stale dismissal", 1, root.overlayDismissals)
    }
}
