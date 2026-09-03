package com.difft.android.chat.ui

import com.difft.android.chat.group.GroupChatPopupActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which classes implement [KeyboardPanelHost], asserted structurally.
 *
 * `ChatMessageInputFragment` resolves its host by trying the parent fragment's
 * `InsetAwareConstraintLayout` first and the Activity second. The popup Activities have no parent
 * fragment, so the Activity branch is the only one that can reach them — if either popup ever stops
 * implementing the interface, both reported bugs silently return with no compile error anywhere.
 *
 * The negative half matters just as much: the full-screen chat must keep resolving through the
 * parent-fragment branch. Making `ChatActivity` implement the interface "for symmetry" would add a
 * second, lower-priority host to the path this change must not touch.
 */
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

    /**
     * The interface declares no default bodies on purpose, so a missing implementation is a compile
     * error. This pins the other half: each popup declares all five itself rather than inheriting
     * them from somewhere that could later be changed out from under it.
     */
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
