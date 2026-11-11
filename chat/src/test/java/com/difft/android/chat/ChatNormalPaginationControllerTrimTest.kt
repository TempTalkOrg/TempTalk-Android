package com.difft.android.chat

import com.difft.android.chat.ChatNormalPaginationController.Companion.MAX_MESSAGE_COUNT
import com.difft.android.chat.ChatNormalPaginationController.Companion.TRIM_SLACK
import com.difft.android.chat.pagination.testing.PaginationTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.difft.app.database.models.MessageModel
import org.difft.app.database.test.builders.buildMessageModel
import org.difft.app.database.test.builders.buildMessageSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test








@OptIn(ExperimentalCoroutinesApi::class)
class ChatNormalPaginationControllerTrimTest : PaginationTestBase() {

    
    
    @Test
    fun `trimToLatest re-slices to the cap, re-anchors, preserves position and queries nothing`() = runTest {
        val controller = controller()
        val oversized = growWindowTo(controller, MAX_MESSAGE_COUNT + TRIM_SLACK)
        val before = controller.behavior()
        val viewport = oversized.takeLast(VIEWPORT_ROWS)
        val queriesBefore = windowQueryCounts()
        val restartsBefore = source.callCount("latestMessageId")

        controller.trimToLatest()

        val after = controller.behavior()
        assertEquals(MAX_MESSAGE_COUNT, after.messageList.size)
        assertEquals(oversized[TRIM_SLACK].id, after.messageList.first().id)
        assertEquals(oversized[TRIM_SLACK - 1].id, after.anchorMessageBefore?.id)
        assertPreservePositionOnly(after)
        assertFalse("rows were dropped off the oldest end, so older rows provably exist", after.hasReachedHistoryStart)
        assertEquals(before.anchorMessageAfter, after.anchorMessageAfter)
        assertEquals(before.hasReachedLatest, after.hasReachedLatest)
        assertViewportRetained(viewport, after.messageList)
        assertEquals("the re-slice is pure memory", queriesBefore, windowQueryCounts())
        assertEquals("only the observer restart queries", restartsBefore + 1, source.callCount("latestMessageId"))
    }

    
    @Test
    fun `trimToLatest on a window at the cap emits nothing and restarts nothing`() = runTest {
        val controller = controller()
        growWindowTo(controller, MAX_MESSAGE_COUNT)
        val before = controller.behavior()
        val restartsBefore = source.callCount("latestMessageId")

        controller.trimToLatest()

        assertSame("no emission at all", before, controller.behavior())
        assertEquals(restartsBefore, source.callCount("latestMessageId"))
    }

    
    
    @Test
    fun `trimToLatest restarts the observer, which then re-queries from the new window minimum`() = runTest {
        val controller = controller()
        growWindowTo(controller, MAX_MESSAGE_COUNT + TRIM_SLACK)
        val restartsBefore = source.callCount("latestMessageId")

        controller.trimToLatest()

        assertEquals(restartsBefore + 1, source.callCount("latestMessageId"))
        val trimmed = controller.behavior()
        val newMinimum = trimmed.messageList.first().systemShowTimestamp

        source.notifyChange()
        controller.awaitNextBehavior(trimmed)

        val lastWindowQuery = source.callLog.last { it.method == "ascendingFrom" }
        assertEquals("re-queries from the trimmed minimum", newMinimum, lastWindowQuery.args[0])
        assertNull("still the unbounded ge(min) branch", lastWindowQuery.args[1])
        assertEquals(MAX_MESSAGE_COUNT, controller.window().size)
    }

    
    
    
    
    @Test
    fun `a row inserted older than the window changes only hasReachedHistoryStart`() = runTest {
        source.seed(buildMessageSequence(5))
        val controller = controller()
        controller.loadPreviousPage()
        val before = controller.behavior()
        assertTrue(before.hasReachedHistoryStart)

        source.insertOlderAndNotify(buildMessageModel(id = "out-of-order", systemShowTimestamp = 1L))
        val after = controller.awaitNextBehavior(before)

        assertEquals(before.messageList.map { it.id }, after.messageList.map { it.id })
        assertFalse(after.hasReachedHistoryStart)
    }

    



    private suspend fun growWindowTo(
        controller: ChatNormalPaginationController,
        targetSize: Int,
    ): List<MessageModel> {
        val seeded = buildMessageSequence(SEEDED_ROWS)
        source.seed(seeded)
        controller.jumpToBottom()
        val opened = controller.behavior()
        val incoming = buildMessageSequence(
            count = targetSize - opened.messageList.size,
            startTs = seeded.last().systemShowTimestamp + 1_000L,
            idPrefix = "incoming",
        )
        source.appendAndNotify(*incoming.toTypedArray())
        controller.awaitBehaviorWhere { it.messageList.size == targetSize }
        return controller.window()
    }

    
    private fun windowQueryCounts(): Map<String, Int> = listOf(
        "roomAnchors", "earliestFailedOutgoing", "firstUnreadFromOthers", "countOlderThan",
        "countNewerThan", "newerThan", "atOrNewerThan", "olderThan", "atOrOlderThan", "latest",
        "byTimeStamp", "ascendingFrom",
    ).associateWith { source.callCount(it) }

    private companion object {
        const val SEEDED_ROWS = 200
        const val VIEWPORT_ROWS = 10
    }
}
