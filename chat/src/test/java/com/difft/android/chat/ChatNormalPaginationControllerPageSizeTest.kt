package com.difft.android.chat

import com.difft.android.chat.ChatNormalPaginationController.Companion.INITIAL_PAGE_SIZE
import com.difft.android.chat.ChatNormalPaginationController.Companion.JUMP_PAGE_SIZE
import com.difft.android.chat.ChatNormalPaginationController.Companion.MAX_MESSAGE_COUNT
import com.difft.android.chat.ChatNormalPaginationController.Companion.SCROLL_PAGE_SIZE
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases #67..#71 — which page-size constant each entry point actually asks the source for.
 *
 * Asserted on the recorded LIMIT argument, not on the resulting window size: a window can come out
 * the right length for the wrong reason (a short conversation, a truncation), while the limit
 * argument is the choice the code made. The over-fetch of exactly one row is part of every
 * assertion — that extra row is what becomes the anchor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatNormalPaginationControllerPageSizeTest : PaginationTestBase() {

    // #67 — first screen from the read position pages on INITIAL_PAGE_SIZE.
    @Test
    fun `first screen from the read position asks for one more than the initial page`() = runTest {
        source.seed(buildMessageSequence(ROWS))
        source.roomRow = RoomAnchors(readPosition = 1_000L, sendStatus = ROOM_SEND_STATUS_NONE)
        val controller = controller()

        controller.initLoadMessage(null)

        assertEquals(INITIAL_PAGE_SIZE + 1, limitOf("newerThan"))
        assertEquals(INITIAL_PAGE_SIZE.toInt(), controller.window().size)
        assertNotNull("the over-fetched row becomes the after-anchor", controller.behavior().anchorMessageAfter)
    }

    // #68 — the #1103 failure-anchored first screen pages on INITIAL_PAGE_SIZE too, on its own `ge`
    // query. Its scroll command must stay ToMessage: that is the one action the call-header
    // compensation skips.
    @Test
    fun `first screen anchored at a failure asks for one more than the initial page`() = runTest {
        val failed = buildMessageModel(id = "failed", systemShowTimestamp = 3_000L, fromWho = MY_ID)
            .apply { sendType = MessageModel.SEND_TYPE_FAILED }
        val others = buildMessageSequence(ROWS).filterNot { it.systemShowTimestamp == 3_000L }
        source.seed(others + failed)
        source.roomRow = RoomAnchors(readPosition = 4_000L, sendStatus = ROOM_SEND_STATUS_FAILED)
        val controller = controller()

        controller.initLoadMessage(null)

        assertEquals(INITIAL_PAGE_SIZE + 1, limitOf("atOrNewerThan"))
        assertTrue(controller.behavior().scrollAction is ScrollAction.ToMessage)
    }

    // #69 — scroll paging backwards uses SCROLL_PAGE_SIZE, and the over-fetched row becomes the
    // before-anchor.
    @Test
    fun `loadPreviousPage asks for one more than the scroll page and anchors on the extra row`() = runTest {
        val rows = buildMessageSequence(ROWS)
        source.seed(rows)
        val controller = controller()
        controller.jumpToBottom()
        val oldestBefore = controller.window().first()
        val oldestIndex = rows.indexOfFirst { it.id == oldestBefore.id }

        controller.loadPreviousPage()

        assertEquals(SCROLL_PAGE_SIZE + 1, limitOf("olderThan"))
        val behavior = controller.behavior()
        // The page displays SCROLL_PAGE_SIZE rows; row 51 of the query is the anchor, not content.
        assertEquals(rows[oldestIndex - SCROLL_PAGE_SIZE.toInt()].id, behavior.messageList.first().id)
        assertEquals(rows[oldestIndex - SCROLL_PAGE_SIZE.toInt() - 1].id, behavior.anchorMessageBefore?.id)
    }

    // #69 (truncating half) — once the window is at the cap, the next backwards page drops rows off
    // the NEWEST end, and the first dropped row becomes the after-anchor.
    @Test
    fun `a backwards page past the cap truncates the newest end and anchors on the first dropped row`() = runTest {
        val rows = buildMessageSequence(ROWS)
        source.seed(rows)
        val controller = controller()
        controller.jumpToBottom()
        controller.fillWindowBelowCap()
        val newestBefore = controller.window().map { it.id }

        controller.loadPreviousPage()

        val behavior = controller.behavior()
        assertEquals(MAX_MESSAGE_COUNT, behavior.messageList.size)
        val kept = behavior.messageList.map { it.id }
        val firstDropped = newestBefore.first { it !in kept }
        assertEquals(firstDropped, behavior.anchorMessageAfter?.id)
    }

    // #70 — scroll paging forwards uses SCROLL_PAGE_SIZE, and its truncation drops rows off the
    // OLDEST end, so the before-anchor is the row immediately older than the surviving window.
    @Test
    fun `loadNextPage asks for one more than the scroll page and re-anchors after truncation`() = runTest {
        val rows = buildMessageSequence(ROWS)
        source.seed(rows)
        val controller = controller()
        controller.jumpToMessage(rows.first().timeStamp)

        var guard = 0
        while (controller.window().size < MAX_MESSAGE_COUNT && guard++ < MAX_PAGE_FORWARD_STEPS) {
            controller.loadNextPage()
        }

        assertEquals(SCROLL_PAGE_SIZE + 1, limitOf("newerThan"))
        val behavior = controller.behavior()
        assertEquals(MAX_MESSAGE_COUNT, behavior.messageList.size)
        val firstKeptIndex = rows.indexOfFirst { it.id == behavior.messageList.first().id }
        assertEquals(rows[firstKeptIndex - 1].id, behavior.anchorMessageBefore?.id)
    }

    // #71 — both jump entries page on JUMP_PAGE_SIZE, NOT on the scroll page: a jump is tuned for
    // landing latency, and reaching for the scroll page here is the easy mistake.
    @Test
    fun `jumpToMessage asks for one more than the jump page`() = runTest {
        val rows = buildMessageSequence(ROWS)
        source.seed(rows)
        val controller = controller()

        controller.jumpToMessage(rows[100].timeStamp)

        assertEquals(JUMP_PAGE_SIZE + 1, limitOf("atOrNewerThan"))
        assertEquals(JUMP_PAGE_SIZE.toInt(), controller.window().size)
    }

    @Test
    fun `jumpToBottom asks for one more than the jump page`() = runTest {
        source.seed(buildMessageSequence(ROWS))
        val controller = controller()

        controller.jumpToBottom()

        assertEquals(JUMP_PAGE_SIZE + 1, limitOf("latest"))
        assertEquals(JUMP_PAGE_SIZE.toInt(), controller.window().size)
    }

    /** The LIMIT argument of the last recorded [method] call. */
    private fun limitOf(method: String): Long =
        source.callLog.last { it.method == method }.args.last() as Long

    private companion object {
        /** More than twice the window cap, so every truncation branch is reachable. */
        const val ROWS = 400
        const val MAX_PAGE_FORWARD_STEPS = 20
    }
}
