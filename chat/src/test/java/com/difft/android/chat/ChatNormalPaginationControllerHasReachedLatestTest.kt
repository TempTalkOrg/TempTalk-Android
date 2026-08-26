package com.difft.android.chat

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both `hasReachedLatest` branches, plus one case per controller emission site that derives the
 * flag — every one of which must produce it with ZERO extra queries.
 *
 * The dangerous site is `loadPreviousPage`: it is the only one that drops rows off the NEWEST end
 * (`take(MAX_MESSAGE_COUNT)` keeps the OLDEST N), so it must AND the carried-forward value with
 * "nothing was truncated". Copying the plain carry-forward used everywhere else permanently
 * suppresses `loadNextPage` once the window has been filled — "scroll up a few pages, come back
 * down, never see new messages again".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatNormalPaginationControllerHasReachedLatestTest : PaginationTestBase() {

    private val scrollPageSize = SCROLL_PAGE_SIZE.toInt()

    // #2 (i) — NOT truncated: the carry-forward is allowed through.
    @Test
    fun `loadPreviousPage keeps hasReachedLatest when the newest end is not truncated`() = runTest {
        source.seed(buildMessageSequence(MAX_MESSAGE_COUNT))
        val controller = controller()
        controller.jumpToBottom()
        assertTrue("precondition: the user is at the bottom", controller.behavior().hasReachedLatest)

        val hasOlder = controller.loadPreviousPage()

        assertTrue(hasOlder)
        val behavior = controller.behavior()
        assertNull(behavior.anchorMessageAfter)
        assertTrue(behavior.hasReachedLatest)
        assertFalse(behavior.hasReachedHistoryStart)
    }

    // #2 (ii) — truncated at the newest end: the carry-forward MUST be vetoed.
    @Test
    fun `loadPreviousPage clears hasReachedLatest when the newest end is truncated`() = runTest {
        source.seed(buildMessageSequence(MAX_MESSAGE_COUNT + 2 * scrollPageSize))
        val controller = controller()
        controller.jumpToBottom()
        // Page back while the next page still fits under the cap: no truncation yet, so the flag
        // set by jumpToBottom survives all of these steps.
        controller.fillWindowBelowCap()
        assertTrue("precondition: still below the cap", controller.window().size < MAX_MESSAGE_COUNT)
        assertTrue(controller.behavior().hasReachedLatest)

        controller.loadPreviousPage()

        val behavior = controller.behavior()
        assertEquals(MAX_MESSAGE_COUNT, behavior.messageList.size)
        assertNotNull("the newest end really was truncated", behavior.anchorMessageAfter)
        assertFalse("a carried-forward true here deadlocks loadNextPage", behavior.hasReachedLatest)
    }

    // jumpToBottom: the window is built from the newest row down, so the flag is true by construction.
    @Test
    fun `jumpToBottom reaches the latest by construction`() = runTest {
        source.seed(buildMessageSequence(100))
        val controller = controller()

        controller.jumpToBottom()

        assertTrue(controller.behavior().hasReachedLatest)
    }

    // jumpToMessage: over-fetch verdict on the `ge` query — mid-conversation target.
    @Test
    fun `jumpToMessage to the middle does not reach the latest`() = runTest {
        val rows = buildMessageSequence(100)
        source.seed(rows)
        val controller = controller()

        controller.jumpToMessage(rows[40].timeStamp)

        assertFalse(controller.behavior().hasReachedLatest)
    }

    // jumpToMessage: target close enough to the end that the `ge` query returns less than a page.
    @Test
    fun `jumpToMessage near the end reaches the latest`() = runTest {
        val rows = buildMessageSequence(100)
        source.seed(rows)
        val controller = controller()

        controller.jumpToMessage(rows[95].timeStamp)

        val behavior = controller.behavior()
        assertTrue(behavior.hasReachedLatest)
        assertEquals(rows.last().id, behavior.messageList.last().id)
    }

    // loadFirstScreenFromReadPosition: fewer unread rows than a page ⇒ they are all in the window.
    @Test
    fun `first screen from read position reaches the latest when the unread tail fits`() = runTest {
        source.seed(buildMessageSequence(40))
        source.roomRow = RoomAnchors(readPosition = 35_000L, sendStatus = ROOM_SEND_STATUS_NONE)
        val controller = controller()

        controller.initLoadMessage(null)

        assertTrue(controller.behavior().hasReachedLatest)
    }

    // …and does NOT when the unread tail overflows the page.
    @Test
    fun `first screen from read position does not reach the latest when unread overflows`() = runTest {
        source.seed(buildMessageSequence(100))
        source.roomRow = RoomAnchors(readPosition = 1_000L, sendStatus = ROOM_SEND_STATUS_NONE)
        val controller = controller()

        controller.initLoadMessage(null)

        val behavior = controller.behavior()
        assertFalse(behavior.hasReachedLatest)
        assertNotNull(behavior.anchorMessageAfter)
    }

    // loadFirstScreenAnchoredAtFailure (#1103 path): same over-fetch verdict on its own `ge` query.
    @Test
    fun `first screen anchored at a failure does not reach the latest with a full page after it`() = runTest {
        val failed = buildMessageModel(id = "failed", systemShowTimestamp = 3_000L, fromWho = MY_ID)
            .apply { sendType = MessageModel.SEND_TYPE_FAILED }
        val others = buildMessageSequence(100).filterNot { it.systemShowTimestamp == 3_000L }
        source.seed(others + failed)
        source.roomRow = RoomAnchors(readPosition = 4_000L, sendStatus = ROOM_SEND_STATUS_FAILED)
        val controller = controller()

        controller.initLoadMessage(null)

        assertFalse(controller.behavior().hasReachedLatest)
    }

    // loadNextPage: reuses the countNewerThan it already ran for its return value — no new query.
    @Test
    fun `loadNextPage reaches the latest exactly when no newer row is left`() = runTest {
        val rows = buildMessageSequence(2 * scrollPageSize + 5)
        source.seed(rows)
        val controller = controller()
        controller.jumpToMessage(rows.first().timeStamp)
        val countsBefore = source.callCount("countNewerThan")

        val hasNewerAfterOneStep = controller.loadNextPage()

        assertTrue(hasNewerAfterOneStep)
        assertFalse(controller.behavior().hasReachedLatest)
        assertEquals("one COUNT, reused for both the flag and the return value",
            countsBefore + 1, source.callCount("countNewerThan"))

        // Page forward until the newest row is in the window.
        var guard = 0
        while (controller.loadNextPage() && guard++ < MAX_PAGE_FORWARD_STEPS) Unit
        assertEquals(rows.last().id, controller.window().last().id)
        assertTrue(controller.behavior().hasReachedLatest)
    }

    // addOneMessage: the append happens AT the newest end, so the flag carries forward untouched and
    // still costs nothing.
    @Test
    fun `addOneMessage carries hasReachedLatest forward without querying`() = runTest {
        val rows = buildMessageSequence(100)
        source.seed(rows)
        val controller = controller()
        controller.jumpToBottom()
        assertTrue(controller.behavior().hasReachedLatest)
        val callsBefore = source.callLog.size

        controller.addOneMessage(
            buildMessageModel(id = "sent", systemShowTimestamp = rows.last().systemShowTimestamp + 1_000L),
        )

        assertTrue(controller.behavior().hasReachedLatest)
        assertEquals(callsBefore, source.callLog.size)
    }

    // …and a false value is carried forward just as faithfully (no accidental optimism).
    @Test
    fun `addOneMessage does not invent hasReachedLatest on a mid-conversation window`() = runTest {
        val rows = buildMessageSequence(100)
        source.seed(rows)
        val controller = controller()
        controller.jumpToMessage(rows[40].timeStamp)
        assertFalse(controller.behavior().hasReachedLatest)

        controller.addOneMessage(buildMessageModel(id = "sent", systemShowTimestamp = 1L))

        assertFalse(controller.behavior().hasReachedLatest)
    }

    private companion object {
        const val MAX_PAGE_FORWARD_STEPS = 10
    }
}
