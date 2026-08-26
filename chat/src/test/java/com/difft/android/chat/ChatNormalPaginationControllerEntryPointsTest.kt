package com.difft.android.chat

import com.difft.android.chat.ChatNormalPaginationController.Companion.JUMP_PAGE_SIZE
import com.difft.android.chat.pagination.RoomAnchors
import com.difft.android.chat.pagination.testing.PaginationTestBase
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.difft.app.database.models.MessageModel
import org.difft.app.database.test.builders.buildMessageModel
import org.difft.app.database.test.builders.buildMessageSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases #5..#8 — the four window-building entry points, exercised end to end through the
 * `ChatMessageWindowSource` seam.
 *
 * Each case pins the boundary operator its path depends on (`gt` / `le` / `ge` / `lt` / plain
 * descending), because a single wrong boundary is invisible in review and produces a window that
 * is off by exactly one row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatNormalPaginationControllerEntryPointsTest : PaginationTestBase() {

    // #5 — first screen from readPosition, mixed branch: fewer unread than a page, so the load
    // back-fills already-read rows through the `le` boundary and lands the scroll on the first
    // unread row's index INSIDE the page.
    @Test
    fun `first screen from read position back-fills read rows and scrolls to the first unread`() = runTest {
        val messages = buildMessageSequence(40)
        source.seed(messages)
        source.roomRow = RoomAnchors(readPosition = 35_000L, sendStatus = ROOM_SEND_STATUS_NONE)
        val controller = controller()

        controller.initLoadMessage(null)

        val behavior = controller.behavior()
        // m35..m39 are unread (5 rows); the loader back-fills m20..m34 plus one before-anchor.
        assertEquals((20..39).map { "m$it" }, controller.windowIds())
        assertEquals(ScrollAction.ToPosition(15), behavior.scrollAction)
        assertEquals("m19", behavior.anchorMessageBefore?.id)
        assertNull(behavior.anchorMessageAfter)
        assertEquals(35_000L, behavior.readPosition)
        // The `le` back-fill really ran — the branch is not reachable by the `gt` query alone.
        assertEquals(1, source.callCount("atOrOlderThan"))
    }

    // #6 — #1103 first-screen anchoring at the earliest failed outgoing message. The window is
    // keyed on systemShowTimestamp but the scroll command carries `timeStamp`; the two disagree on
    // a failed row and swapping them desyncs the scroll from the window.
    @Test
    fun `first screen anchors at the earliest failed outgoing message and scrolls by timeStamp`() = runTest {
        val failed = buildMessageModel(
            id = "failed",
            systemShowTimestamp = 3_000L,
            timeStamp = 9_999L,
            fromWho = MY_ID,
        ).apply { sendType = MessageModel.SEND_TYPE_FAILED }
        val others = buildMessageSequence(30).filterNot { it.systemShowTimestamp == 3_000L }
        source.seed(others + failed)
        source.roomRow = RoomAnchors(readPosition = 4_000L, sendStatus = ROOM_SEND_STATUS_FAILED)
        val controller = controller()

        controller.initLoadMessage(null)

        val behavior = controller.behavior()
        assertEquals(ScrollAction.ToMessage(9_999L), behavior.scrollAction)
        assertTrue("failed" in controller.windowIds())
        assertEquals("failed", controller.windowIds().first())
    }

    // #7 — jumpToMessage on a mid-conversation target.
    //
    // Only the after-anchor can be non-null here: the forward query alone overflows the page, so
    // splitMessageWindow takes the "no before-anchor" branch by construction. A window with BOTH
    // anchors is unreachable from this entry point — the over-fetch is one row, not two.
    @Test
    fun `jumpToMessage centres the window on the target and keeps an after-anchor`() = runTest {
        val messages = buildMessageSequence(100)
        source.seed(messages)
        val target = messages[49]
        val controller = controller()

        val jumped = controller.jumpToMessage(target.timeStamp)

        assertTrue(jumped)
        val behavior = controller.behavior()
        assertEquals(ScrollAction.ToMessage(target.timeStamp), behavior.scrollAction)
        assertTrue(target.id in controller.windowIds())
        assertEquals(JUMP_PAGE_SIZE.toInt(), controller.window().size)
        assertNotNull(behavior.anchorMessageAfter)
        assertWindowIsContiguousSliceOf(messages, controller.window())
    }

    // #7 (miss variant) — an unknown timestamp must leave the window untouched.
    @Test
    fun `jumpToMessage on an unknown timestamp returns false and leaves the window untouched`() = runTest {
        source.seed(buildMessageSequence(100))
        val controller = controller()
        controller.jumpToBottom()
        val before = controller.windowIds()

        val jumped = controller.jumpToMessage(TIMESTAMP_NOT_IN_CONVERSATION)

        assertFalse(jumped)
        assertEquals(before, controller.windowIds())
    }

    // #8 — jumpToBottom ends the window on the newest row, so there is nothing to anchor after it.
    @Test
    fun `jumpToBottom loads the newest page with a before-anchor and no after-anchor`() = runTest {
        val messages = buildMessageSequence(100)
        source.seed(messages)
        val controller = controller()

        controller.jumpToBottom()

        val behavior = controller.behavior()
        assertEquals(ScrollAction.ToBottom, behavior.scrollAction)
        assertEquals(messages.takeLast(JUMP_PAGE_SIZE.toInt()).map { it.id }, controller.windowIds())
        assertNotNull(behavior.anchorMessageBefore)
        assertNull(behavior.anchorMessageAfter)
    }

    private companion object {
        const val TIMESTAMP_NOT_IN_CONVERSATION = 7L
    }
}
