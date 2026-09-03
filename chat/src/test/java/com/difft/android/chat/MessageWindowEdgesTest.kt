package com.difft.android.chat

import com.difft.android.chat.ChatNormalPaginationController.Companion.MAX_MESSAGE_COUNT
import com.difft.android.chat.pagination.testing.FakeChatMessageWindowSource
import org.difft.app.database.models.MessageModel
import org.difft.app.database.test.builders.buildMessageSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Cases #43..#47 — the two pure functions in `MessageWindowEdges.kt`.
 *
 * [takeLatestWindow] is an extract of `loadNextPage`'s inline truncation, so #45 pins it against a
 * verbatim copy of the pre-extraction algorithm; [resolveWindowEdges] is the anchor probe the
 * observer tick uses, so #46/#47 pin both the query count (exactly one LIMIT-1 probe per side) and
 * the meaning of a null result ("no such row exists", never "we did not look").
 */
class MessageWindowEdgesTest {

    // #43 — exactly at the cap: nothing dropped, and the input list comes back BY REFERENCE so a
    // caller can distinguish "no truncation" from "truncated to the same size".
    @Test
    fun `a window at the cap is returned unchanged and by reference`() {
        val rows = buildMessageSequence(MAX_MESSAGE_COUNT)

        val window = takeLatestWindow(rows, MAX_MESSAGE_COUNT)

        assertNull(window.droppedNeighbour)
        assertSame(rows, window.pageMessages)
    }

    // #44 — over the cap: the newest `max` survive and the row immediately older than them becomes
    // the dropped neighbour (the future before-anchor).
    @Test
    fun `over the cap keeps the newest rows and reports the adjacent dropped row`() {
        val rows = buildMessageSequence(MAX_MESSAGE_COUNT + 3)

        val window = takeLatestWindow(rows, MAX_MESSAGE_COUNT)

        assertEquals(rows.takeLast(MAX_MESSAGE_COUNT).map { it.id }, window.pageMessages.map { it.id })
        assertEquals(rows[2].id, window.droppedNeighbour?.id)
    }

    // #45 — equivalence with the algorithm this was extracted from, across every size class.
    @Test
    fun `takeLatestWindow matches the pre-extraction loadNextPage algorithm`() {
        listOf(0, 1, MAX_MESSAGE_COUNT - 1, MAX_MESSAGE_COUNT, MAX_MESSAGE_COUNT + 1, 3 * MAX_MESSAGE_COUNT)
            .forEach { size ->
                val rows = buildMessageSequence(size)

                val window = takeLatestWindow(rows, MAX_MESSAGE_COUNT)

                assertEquals(
                    "pageMessages for size=$size",
                    legacyMessageList(rows).map { it.id },
                    window.pageMessages.map { it.id },
                )
                assertEquals(
                    "droppedNeighbour for size=$size",
                    legacyAnchorBefore(rows)?.id,
                    window.droppedNeighbour?.id,
                )
            }
    }

    // #46 — a window in the middle of the conversation: one probe per side, both hit.
    @Test
    fun `resolveWindowEdges finds both neighbours with one probe each`() {
        val rows = buildMessageSequence(10)
        val source = FakeChatMessageWindowSource()
        source.seed(rows)

        val edges = source.resolveWindowEdges(
            oldestTs = rows[3].systemShowTimestamp,
            newestTs = rows[6].systemShowTimestamp,
        )

        assertEquals("m2", edges.anchorBefore?.id)
        assertEquals("m7", edges.anchorAfter?.id)
        assertEquals(1, source.callCount("olderThan"))
        assertEquals(1, source.callCount("newerThan"))
    }

    // #47 — the window spans the whole conversation: null on both sides means "no such row",
    // which is exactly what makes `anchorAfter == null` usable as the hasReachedLatest signal.
    @Test
    fun `resolveWindowEdges returns null on both sides at the conversation bounds`() {
        val rows = buildMessageSequence(10)
        val source = FakeChatMessageWindowSource()
        source.seed(rows)

        val edges = source.resolveWindowEdges(
            oldestTs = rows.first().systemShowTimestamp,
            newestTs = rows.last().systemShowTimestamp,
        )

        assertNull(edges.anchorBefore)
        assertNull(edges.anchorAfter)
    }

    // --- verbatim copy of ChatNormalPaginationController.loadNextPage before the extraction ---

    private fun legacyMessageList(allMessages: List<MessageModel>): List<MessageModel> =
        allMessages.takeLast(MAX_MESSAGE_COUNT)

    private fun legacyAnchorBefore(allMessages: List<MessageModel>): MessageModel? =
        if (allMessages.size > MAX_MESSAGE_COUNT) {
            allMessages[allMessages.size - MAX_MESSAGE_COUNT - 1]
        } else {
            null
        }
}
