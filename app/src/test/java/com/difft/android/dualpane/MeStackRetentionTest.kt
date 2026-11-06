package com.difft.android.dualpane

import android.os.Looper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.R
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_ME
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Every home tab keeps the current top on fold and only explicit opens present retained stacks. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MeStackRetentionTest {
    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host get() = controller!!.get()
    private val nav get() = host.navigator
    @After fun tearDown() { controller?.pause()?.stop()?.destroy() }
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
    private fun launch() { controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup(); settle() }
    private fun select(tab: Int) { nav.selectTab(tab); settle() }
    private fun recreate(qualifiers: String) { RuntimeEnvironment.setQualifiers(qualifiers); controller!!.recreate(); settle() }

    @Test @Config(qualifiers = "w840dp-h900dp")
    fun `current Me stack keeps its top on fold and back walks to its list`() {
        launch(); select(TAB_ME)
        nav.showMePage(StackPageFragment.newInstance("account", handlesBack = false)); settle()
        nav.push(TAB_ME, StackPageFragment.newInstance("custom id", handlesBack = false)); settle()
        recreate("w400dp-h800dp")
        assertFalse(nav.isDualPane)
        assertEquals(2, nav.depth())
        assertFalse(host.host(TAB_ME)!!.isHidden)
        assertEquals(listOf("account", "custom id"), host.pages(TAB_ME).map { it.label })
        assertTrue(nav.detailCoversList.value)
        host.onBackPressedDispatcher.onBackPressed(); settle()
        assertEquals(1, nav.depth())
        host.onBackPressedDispatcher.onBackPressed(); settle()
        assertEquals(0, nav.depth())
        assertEquals(0, host.moveTaskToBackCalls)
        assertFalse(host.isFinishing)
        recreate("w840dp-h900dp")
        assertEquals(0, nav.depth())
    }

    @Test @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane Me opens its hosted layer and survives unfold`() {
        launch(); select(TAB_ME)
        assertTrue(nav.showMePage(StackPageFragment.newInstance("account", handlesBack = false))); settle()
        assertEquals(1, nav.depth())
        assertEquals(View.VISIBLE, host.findViewById<View>(R.id.detail_pane).visibility)
        recreate("w840dp-h900dp")
        assertEquals("account", host.pages(TAB_ME).single().label)
        assertEquals(1, nav.depth())
    }

    @Test @Config(qualifiers = "w840dp-h900dp")
    fun `other tab Me stack stays unpresented after fold until explicitly opened`() {
        launch(); select(TAB_ME)
        nav.showMePage(StackPageFragment.newInstance("account", handlesBack = false)); settle()
        select(TAB_CHATS); recreate("w400dp-h800dp"); select(TAB_ME)
        assertEquals(0, nav.depth())
        assertTrue(host.host(TAB_ME)!!.isHidden)
        assertTrue(nav.showMePage(StackPageFragment.newInstance("account", handlesBack = false))); settle()
        assertEquals(1, nav.depth())
        assertFalse(host.host(TAB_ME)!!.isHidden)
    }

    @Test @Config(qualifiers = "w840dp-h900dp")
    fun `reselecting same Me root keeps its instance and removes pushed pages`() {
        launch(); select(TAB_ME)
        nav.showMePage(StackPageFragment.newInstance("account", handlesBack = false)); settle()
        val root = host.pages(TAB_ME).single()
        nav.push(TAB_ME, StackPageFragment.newInstance("custom id", handlesBack = false)); settle()
        nav.showMePage(StackPageFragment.newInstance("account", handlesBack = false)); settle()
        assertEquals(1, nav.depth())
        assertSame(root, host.pages(TAB_ME).single())
    }
}
