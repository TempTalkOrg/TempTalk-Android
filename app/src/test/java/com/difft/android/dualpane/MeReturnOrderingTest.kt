package com.difft.android.dualpane

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_ME
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit


@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w840dp-h900dp")
class MeReturnOrderingTest {
    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host get() = controller!!.get()
    private val nav get() = host.navigator
    @After fun tearDown() { controller?.pause()?.stop()?.destroy() }
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
    private fun launch() { controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup(); settle() }

    @Test fun `cancelled return does not select Me or construct its page`() {
        launch()
        var created = false
        nav.openMePage(keepExisting = { false }, createPage = {
            created = true
            StackPageFragment.newInstance("stale", false)
        }, shouldOpen = { false })
        settle()
        assertEquals(TAB_CHATS, nav.currentTab)
        assertFalse(created)
    }

    @Test fun `newer Me root supersedes a queued notification return`() {
        launch()
        nav.selectTab(TAB_ME); settle()
        var returnCommitted = false
        nav.openMePage(keepExisting = { false }, createPage = {
            StackPageFragment.newInstance("stale", false)
        }, onPresented = { returnCommitted = true })
        nav.showMePage(StackPageFragment.newInstance("account", false))
        settle()
        assertFalse(returnCommitted)
        assertEquals("account", host.pages(TAB_ME).single().label)
    }
}
