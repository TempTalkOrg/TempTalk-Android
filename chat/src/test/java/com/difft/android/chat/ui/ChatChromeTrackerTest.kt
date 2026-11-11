package com.difft.android.chat.ui

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.ui.messageaction.ChatChromeInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper







@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [34])
class ChatChromeTrackerTest {

    private val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    
    private fun views() = Triple(View(activity), View(activity), View(activity)).also { (list, header, input) ->
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(list)
        root.addView(header)
        root.addView(input)
        ShadowLooper.idleMainLooper()
        list.layout(0, 0, 400, 800)
        header.layout(0, 0, 400, 0)
        input.layout(0, 800, 400, 800)
    }

    @Test
    fun `a change is delivered before the frame, which is skipped once`() {
        val owner = Owner()
        val (list, header, input) = views()
        val delivered = mutableListOf<ChatChromeInsets>()
        trackChatChrome(owner, list, header, input) { delivered += it }
        list.viewTreeObserver.dispatchOnPreDraw()
        delivered.clear()

        header.layout(0, 0, 400, 100)
        input.layout(0, 740, 400, 800)

        
        assertTrue("the frame with stale padding is skipped", list.viewTreeObserver.dispatchOnPreDraw())
        assertEquals(listOf(ChatChromeInsets(top = 100, bottom = 60)), delivered)
        assertFalse("the next frame draws", list.viewTreeObserver.dispatchOnPreDraw())
        assertEquals(1, delivered.size)
    }

    @Test
    fun `nothing is delivered after the view is destroyed`() {
        val owner = Owner()
        val (list, header, input) = views()
        val delivered = mutableListOf<ChatChromeInsets>()
        trackChatChrome(owner, list, header, input) { delivered += it }
        owner.registry.currentState = Lifecycle.State.DESTROYED

        header.layout(0, 0, 400, 100)
        assertFalse("no frame is cancelled", list.viewTreeObserver.dispatchOnPreDraw())

        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `the window drops the listener when the page view is removed before it is destroyed`() {
        val owner = Owner()
        val (list, header, input) = views()
        val window = list.viewTreeObserver
        val before = preDrawListenerCount(window)
        trackChatChrome(owner, list, header, input) {}
        assertEquals(before + 1, preDrawListenerCount(window))

        
        
        (list.parent as ViewGroup).removeView(list)
        owner.registry.currentState = Lifecycle.State.DESTROYED

        assertEquals("the page is not kept by the window", before, preDrawListenerCount(window))
    }

    private fun preDrawListenerCount(observer: ViewTreeObserver): Int {
        val listeners = ViewTreeObserver::class.java.getDeclaredField("mOnPreDrawListeners")
            .apply { isAccessible = true }
            .get(observer) ?: return 0
        return listeners.javaClass.getDeclaredMethod("size").apply { isAccessible = true }.invoke(listeners) as Int
    }

    @Test
    fun `a page whose view is already destroyed is not tracked`() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.DESTROYED }
        val (list, header, input) = views()
        val delivered = mutableListOf<ChatChromeInsets>()
        trackChatChrome(owner, list, header, input) { delivered += it }

        header.layout(0, 0, 400, 100)
        list.viewTreeObserver.dispatchOnPreDraw()

        assertTrue(delivered.isEmpty())
    }
}
