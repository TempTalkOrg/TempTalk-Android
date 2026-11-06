package com.difft.android.dualpane

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.pane.detailNavigation
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
class PaneMultiPopTest {
    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host get() = controller!!.get()
    private val nav get() = host.navigator
    @After fun tearDown() { controller?.pause()?.stop()?.destroy() }
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
    private fun launch() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup(); settle()
        nav.selectTab(TAB_ME); settle()
        nav.showMePage(StackPageFragment.newInstance("root", false)); settle()
        nav.push(TAB_ME, StackPageFragment.newInstance("management", false)); settle()
        nav.push(TAB_ME, StackPageFragment.newInstance("transfer", false)); settle()
    }
    @Test fun `pop two reveals the root in a single transaction`() {
        launch()
        val top = host.pages(TAB_ME).last()
        assertTrue(top.detailNavigation().pop(top, 2)); settle()
        assertEquals(listOf("root"), host.pages(TAB_ME).map { it.label })
    }
    @Test fun `queued pop cannot remove a subsequently queued newer top`() {
        launch()
        val top = host.pages(TAB_ME).last()
        nav.push(TAB_ME, StackPageFragment.newInstance("card", false))
        assertTrue(top.detailNavigation().pop(top, 2)); settle()
        assertEquals(listOf("root", "management", "transfer", "card"), host.pages(TAB_ME).map { it.label })
    }
    @Test fun `covered source and nonpositive count are refused`() {
        launch()
        val pages = host.pages(TAB_ME)
        assertFalse(pages.first().detailNavigation().pop(pages.first(), 2))
        assertFalse(pages.last().detailNavigation().pop(pages.last(), 0)); settle()
        assertEquals(3, nav.depth())
    }
    @Test fun `two-pane oversized pop retains its root`() {
        launch()
        val top = host.pages(TAB_ME).last()
        assertTrue(top.detailNavigation().pop(top, 20)); settle()
        assertEquals(1, nav.depth())
    }
    @Test @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane oversized pop clears the layer without finishing the home screen`() {
        launch()
        val top = host.pages(TAB_ME).last()
        assertTrue(top.detailNavigation().pop(top, 20)); settle()
        assertEquals(0, nav.depth())
        assertFalse(host.isFinishing)
    }
}
