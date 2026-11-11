package com.difft.android.dualpane

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.waitWhileVisibleTop
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
class VisibleTopWaitTest {

    class WaitingPage : Fragment() {
        val wait = PageWait(this)

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            waitWhileVisibleTop(running, wait)
        }

        companion object {
            val running = MutableStateFlow(false)
        }
    }

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val nav: PaneNavigator get() = host.navigator

    @After
    fun tearDown() {
        WaitingPage.running.value = false
        runCatching { controller?.pause()?.stop()?.destroy() }
        ComposeDialogManager.dismissWait()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    
    private fun runUntil(done: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(MAX_TASKS) {
            if (done()) return
            looper.runOneTask()
        }
    }

    private fun launchWaiting(): WaitingPage {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        val page = WaitingPage()
        nav.replaceRoot(TAB_CHATS, page, selectionId = "g")
        settle()
        WaitingPage.running.value = true
        assertTrue(page.wait.held)
        return page
    }

    private fun topOfContent(): View {
        val content = host.findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(content.childCount - 1)
    }

    @Test
    fun `background and return keep the same dialog`() = backgroundAndReturn()

    
    @Test
    @Config(sdk = [28])
    fun `API 28 - background and return keep the same dialog`() = backgroundAndReturn()

    private fun backgroundAndReturn() {
        val page = launchWaiting()
        val dialog = topOfContent()

        controller!!.pause().stop()
        assertTrue("kept while only the Activity is stopped", page.wait.held)

        controller!!.start().resume()
        assertTrue(page.wait.held)
        assertSame("not mounted again", dialog, topOfContent())
    }

    @Test
    fun `work that ended in the background releases the wait on return`() {
        val page = launchWaiting()

        controller!!.pause().stop()
        WaitingPage.running.value = false
        assertTrue("nothing collects while stopped", page.wait.held)
        controller!!.start().resume()

        assertFalse(page.wait.held)
    }

    @Test
    fun `a page covered while the Activity runs lets go`() {
        val page = launchWaiting()

        nav.push(TAB_CHATS, StackPageFragment.newInstance("info"))
        runUntil { host.host(TAB_CHATS)?.depth == 2 }

        assertTrue(page.isHidden)
        assertFalse(page.wait.held)
    }

    private companion object {
        const val MAX_TASKS = 200
    }
}
