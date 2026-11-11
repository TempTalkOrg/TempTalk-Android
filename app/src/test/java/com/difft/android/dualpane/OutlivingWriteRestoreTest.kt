package com.difft.android.dualpane

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.pane.PageOperations
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.launchOutlivingPage
import com.difft.android.base.utils.pane.onOperationEnded
import com.difft.android.base.utils.pane.waitWhileVisibleTop
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import kotlinx.coroutines.CompletableDeferred
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
class OutlivingWriteRestoreTest {

    
    object Store {
        var value = 0
        var server = CompletableDeferred<Unit>()
    }

    class SettingPage : Fragment() {
        val wait = PageWait(this)
        var shown = -1
            private set

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            waitWhileVisibleTop(PageOperations.running(KEY), wait)
            onOperationEnded(KEY) { shown = Store.value }
        }

        override fun onResume() {
            super.onResume()
            shown = Store.value
        }

        fun write(newValue: Int) {
            launchOutlivingPage {
                PageOperations.track(KEY) {
                    Store.server.await()
                    Store.value = newValue
                }
            }
        }
    }

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val nav: PaneNavigator get() = host.navigator

    @After
    fun tearDown() {
        Store.server.complete(Unit)
        runTasksUntil { !PageOperations.running(KEY).value }
        runCatching { controller?.pause()?.stop()?.destroy() }
        ComposeDialogManager.dismissWait()
        Store.value = 0
        Store.server = CompletableDeferred()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    
    private fun runTasksUntil(done: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(MAX_TASKS) {
            if (done()) return
            looper.runOneTask()
        }
    }

    private fun page(): SettingPage = host.host(TAB_CHATS)!!.rootPage() as SettingPage

    private fun launchWithPage(): SettingPage {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        nav.replaceRoot(TAB_CHATS, SettingPage(), selectionId = "g")
        settle()
        return page()
    }

    





    private fun foldThenWriteFromGonePage(newValue: Int): SettingPage {
        val old = launchWithPage()
        controller!!.recreate()
        old.write(newValue)
        runTasksUntil { PageOperations.running(KEY).value }
        return page().also { assertTrue("a new instance", it !== old) }
    }

    @Test
    fun `a fold mid-write - the restored page waits, then shows the stored outcome`() {
        val restored = foldThenWriteFromGonePage(newValue = 2)
        assertTrue("the restored page shows the write's wait", restored.wait.held)
        assertEquals(0, restored.shown)

        Store.server.complete(Unit)
        runTasksUntil { !restored.wait.held }

        assertFalse(restored.wait.held)
        assertEquals("re-read when the key ended", 2, restored.shown)
    }

    @Test
    fun `a write that ends in the background is re-read and shown on return`() {
        val restored = foldThenWriteFromGonePage(newValue = 3)

        controller!!.pause().stop()
        Store.server.complete(Unit)
        runTasksUntil { !PageOperations.running(KEY).value }
        assertEquals("re-read while in the background", 3, restored.shown)

        controller!!.start().resume()
        assertFalse(restored.wait.held)
        assertEquals(3, restored.shown)
    }

    @Test
    fun `a page re-reads on return from a page that changed the value`() {
        val page = launchWithPage()
        nav.push(TAB_CHATS, StackPageFragment.newInstance("global"))
        settle()

        Store.value = 5 
        nav.pop(TAB_CHATS)
        settle()

        assertEquals(5, page.shown)
    }

    private companion object {
        const val KEY = "test-setting"
        const val MAX_TASKS = 300
    }
}
