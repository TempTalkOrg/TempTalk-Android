package com.difft.android.chat.ui

import android.content.pm.ApplicationInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration






@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [34])
class ChatAttachmentMenuLifecycleTest {

    private val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
    private val content: ViewGroup = activity.findViewById(android.R.id.content)

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun menu(owner: Owner, onPick: () -> Unit): ChatAttachmentMenu {
        val area = FrameLayout(activity)
        val plus = View(activity)
        area.addView(plus, FrameLayout.LayoutParams(100, 100))
        activity.setContentView(area)
        idle()
        return ChatAttachmentMenu(plus, area, owner, listOf(ChatAttachmentMenu.Item(android.R.drawable.ic_menu_camera, "Camera", onPick))) {}
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun overlays() = content.childCount

    @Test
    fun `in RTL the card stays where its placement puts it`() {
        
        activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        content.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val menu = menu(Owner()) {}
        menu.show()
        idle()

        val overlay = content.getChildAt(content.childCount - 1) as ViewGroup
        val card = overlay.getChildAt(0)
        
        
        assertEquals(0, card.left)
    }

    @Test
    fun `a pause removes the open menu`() {
        val owner = Owner()
        val menu = menu(owner) {}
        val before = overlays()
        menu.show()
        assertEquals(before + 1, overlays())

        owner.registry.currentState = Lifecycle.State.STARTED

        assertFalse(menu.isShowing)
        assertEquals(before, overlays())
    }

    @Test
    fun `a pick finishing after a pause does not run`() {
        val owner = Owner()
        var picked = false
        val menu = menu(owner) { picked = true }
        menu.show()

        menu.dismiss { picked = true }
        owner.registry.currentState = Lifecycle.State.STARTED
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertFalse(picked)
    }

    @Test
    fun `a pick on the chat in front runs after the menu shrinks`() {
        val owner = Owner()
        var picked = false
        val menu = menu(owner) {}
        menu.show()

        menu.dismiss { picked = true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertTrue(picked)
        assertFalse(menu.isShowing)
    }
}
