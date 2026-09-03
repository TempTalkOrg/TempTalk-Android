package com.difft.android.chat

import com.difft.android.chat.ChatNormalPaginationController.Companion.MAX_MESSAGE_COUNT
import com.difft.android.chat.pagination.testing.PaginationTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.difft.app.database.hydration.MessageHydrator
import org.difft.app.database.test.builders.buildMessageModel
import org.difft.app.database.test.builders.buildMessageSequence
import org.difft.app.database.test.fakes.FakeMessageChildRowLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases #48..#50 and #52 — anchor completeness on the two emission paths that used to drop the
 * anchors entirely.
 *
 * Losing them is visibly wrong, not merely untidy: with no before-anchor the window's first row
 * decides its day header and name against "nothing", so after any DB change the first bubble grows a
 * long date header and a re-shown nickname it did not have a moment earlier.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatNormalPaginationControllerAnchorsTest : PaginationTestBase() {

    // #48 — observer tick over a mid-conversation window: both anchors are RE-RESOLVED (the tick's
    // own re-query can have moved either edge), and the after-anchor probe doubles as the
    // hasReachedLatest answer.
    @Test
    fun `an observer tick re-resolves both anchors and reports more newer rows exist`() = runTest {
        val rows = buildMessageSequence(100)
        source.seed(rows)
        val controller = controller()
        controller.jumpToMessage(rows[40].timeStamp)
        val before = controller.behavior()
        assertEquals(rows[40].id, before.messageList.first().id)

        source.notifyChange()
        val after = controller.awaitNextBehavior(before)

        assertEquals("m39", after.anchorMessageBefore?.id)
        assertEquals("m60", after.anchorMessageAfter?.id)
        assertFalse(after.hasReachedLatest)
    }

    // #49 — same tick, opposite polarity: the window already ends on the conversation's newest row,
    // so the after probe finds nothing. null here means "no such row", not "we did not look".
    @Test
    fun `an observer tick on a window at the newest end reports no after-anchor`() = runTest {
        val rows = buildMessageSequence(100)
        source.seed(rows)
        val controller = controller()
        controller.jumpToBottom()
        val before = controller.behavior()

        source.notifyChange()
        val after = controller.awaitNextBehavior(before)

        assertEquals("m79", after.anchorMessageBefore?.id)
        assertNull(after.anchorMessageAfter)
        assertTrue(after.hasReachedLatest)
    }

    // #50 — addOneMessage: carry the before-anchor forward (the oldest row did not move), NULL the
    // after-anchor (the appended row IS the newest known one, so any carried-forward after-anchor
    // would be older than it and would corrupt the last row's showTime), and query nothing.
    @Test
    fun `addOneMessage carries the before-anchor forward, nulls the after-anchor and queries nothing`() = runTest {
        val rows = buildMessageSequence(CAP_FILLING_ROWS)
        source.seed(rows)
        val controller = controller()
        controller.jumpToBottom()
        controller.fillWindowToCap()
        val before = controller.behavior()
        // Precondition: a window with BOTH anchors — only the truncating loadPreviousPage produces it.
        assertEquals(MAX_MESSAGE_COUNT, before.messageList.size)
        val anchorBefore = requireNotNull(before.anchorMessageBefore)
        requireNotNull(before.anchorMessageAfter)
        val callsBefore = source.callLog.size

        controller.addOneMessage(buildMessageModel(id = "sent", systemShowTimestamp = 999_999L))

        val after = controller.behavior()
        assertSame(anchorBefore, after.anchorMessageBefore)
        assertNull(after.anchorMessageAfter)
        assertEquals(before.hasReachedHistoryStart, after.hasReachedHistoryStart)
        assertEquals(callsBefore, source.callLog.size)
    }

    // #52 — seam 1 end to end: the anchors an observer tick resolved really do reach the batch
    // hydration `IN` set, through `messagesToConvert()`. An anchor outside that set gets empty
    // sub-data and compares unequal to the same row rendered inside the window.
    @Test
    fun `re-resolved anchors reach the hydration IN set`() = runTest {
        val rows = buildMessageSequence(100)
        source.seed(rows)
        val controller = controller()
        controller.jumpToMessage(rows[40].timeStamp)
        val before = controller.behavior()
        source.notifyChange()
        val after = controller.awaitNextBehavior(before)
        val loader = FakeMessageChildRowLoader()

        MessageHydrator(loader).hydrate(after.messagesToConvert())

        val hydratedIds = loader.keysPassedTo("attachmentsByMessageId").single()
        assertTrue("before-anchor missing from the IN set", "m39" in hydratedIds)
        assertTrue("after-anchor missing from the IN set", "m60" in hydratedIds)
        assertEquals(after.messageList.size + 2, hydratedIds.size)
    }

    private companion object {
        /** Comfortably more rows than the window cap, so paging back really does truncate. */
        const val CAP_FILLING_ROWS = 400
    }
}
