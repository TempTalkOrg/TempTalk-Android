package com.difft.android.chat.ui

import com.difft.android.chat.group.GroupChatPopupActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner













@RunWith(RobolectricTestRunner::class)
class KeyboardPanelHostWiringTest {

    private val hostMethods = listOf(
        "addKeyboardStateListener",
        "removeKeyboardStateListener",
        "freezeKeyboardPadding",
        "releaseKeyboardPaddingFreeze",
        "onChatPanelVisibilityChanged",
    )

    @Test
    fun `both popup activities implement KeyboardPanelHost`() {
        assertTrue(
            "ChatPopupActivity must implement KeyboardPanelHost",
            KeyboardPanelHost::class.java.isAssignableFrom(ChatPopupActivity::class.java)
        )
        assertTrue(
            "GroupChatPopupActivity must implement KeyboardPanelHost",
            KeyboardPanelHost::class.java.isAssignableFrom(GroupChatPopupActivity::class.java)
        )
    }

    @Test
    fun `the full-screen chat activity does not implement KeyboardPanelHost`() {
        assertFalse(
            "the full-screen path must keep resolving through its parent fragment's " +
                "InsetAwareConstraintLayout, not through the Activity",
            KeyboardPanelHost::class.java.isAssignableFrom(ChatActivity::class.java)
        )
    }

    




    @Test
    fun `each popup activity declares all five host methods itself`() {
        listOf(ChatPopupActivity::class.java, GroupChatPopupActivity::class.java).forEach { type ->
            val declared = type.declaredMethods.map { it.name }.toSet()
            hostMethods.forEach { method ->
                assertTrue("${type.simpleName} must declare $method", method in declared)
            }
        }
    }
}
