package com.difft.android.chat

import com.difft.android.chat.ChatNormalPaginationController.Companion.MAX_MESSAGE_COUNT
import com.difft.android.chat.pagination.testing.PaginationTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.difft.app.database.test.builders.buildMessageModel
import org.difft.app.database.test.builders.buildMessageSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test





@OptIn(ExperimentalCoroutinesApi::class)
class ChatNormalPaginationControllerHasReachedHistoryStartTest : PaginationTestBase() {

    
    
    @Test
    fun `loadPreviousPage with no older row sets hasReachedHistoryStart true and returns false`() = runTest {
        source.seed(listOf(buildMessageModel("m1", 1_000L)))
        val controller = controller()

        val hasOlder = controller.loadPreviousPage()

        assertFalse(hasOlder)
        assertTrue(controller.behavior().hasReachedHistoryStart)
    }

    
    
    
    
    
    @Test
    fun `loadPreviousPage with older rows left sets hasReachedHistoryStart false and returns true`() = runTest {
        source.seed(buildMessageSequence(MAX_MESSAGE_COUNT))
        val controller = controller()
        controller.jumpToBottom()

        val hasOlder = controller.loadPreviousPage()

        assertTrue(hasOlder)
        assertFalse(controller.behavior().hasReachedHistoryStart)
        
        assertTrue(controller.window().size <= MAX_MESSAGE_COUNT)
        assertEquals(null, controller.behavior().anchorMessageAfter)
    }

    
    
    @Test
    fun `loadNextPage flips hasReachedHistoryStart false once the true-oldest message is evicted`() = runTest {
        val oldest = buildMessageModel("old", 100L)
        source.seed(listOf(oldest) + buildMessageSequence(MAX_MESSAGE_COUNT, startTs = 200L, idPrefix = "new"))
        val controller = controller()

        
        assertTrue(controller.jumpToMessage(oldest.timeStamp))
        assertTrue(controller.behavior().hasReachedHistoryStart)

        var guard = 0
        while ("old" in controller.windowIds() && guard++ < MAX_PAGE_FORWARD_STEPS) {
            controller.loadNextPage()
        }

        val finalWindow = controller.window()
        assertEquals(MAX_MESSAGE_COUNT, finalWindow.size)
        assertFalse("old" in controller.windowIds())
        assertFalse(controller.behavior().hasReachedHistoryStart)
    }

    
    @Test
    fun `addOneMessage carries hasReachedHistoryStart forward without querying`() = runTest {
        source.seed(listOf(buildMessageModel("m1", 1_000L)))
        val controller = controller()
        controller.loadPreviousPage()
        assertTrue(controller.behavior().hasReachedHistoryStart)
        val countsBefore = source.callCount("countOlderThan")

        controller.addOneMessage(buildMessageModel("m2", 2_000L))

        assertTrue(controller.behavior().hasReachedHistoryStart)
        assertEquals(countsBefore, source.callCount("countOlderThan"))
    }

    
    
    @Test
    fun `an insert older than the window flips hasReachedHistoryStart back and re-runs the count`() = runTest {
        source.seed(buildMessageSequence(5))
        val controller = controller()
        controller.loadPreviousPage()
        assertTrue(controller.behavior().hasReachedHistoryStart)
        val before = controller.behavior()
        val countsBefore = source.callCount("countOlderThan")

        source.insertOlderAndNotify(buildMessageModel("older", 1L))
        val after = controller.awaitNextBehavior(before)

        assertFalse(after.hasReachedHistoryStart)
        assertEquals(countsBefore + 1, source.callCount("countOlderThan"))
    }

    private companion object {
        
        const val MAX_PAGE_FORWARD_STEPS = 10
    }
}
