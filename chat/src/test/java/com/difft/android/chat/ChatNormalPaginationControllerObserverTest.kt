package com.difft.android.chat

import com.difft.android.chat.ChatNormalPaginationController.Companion.MAX_MESSAGE_COUNT
import com.difft.android.chat.ChatNormalPaginationController.Companion.SCROLL_PAGE_SIZE
import com.difft.android.chat.pagination.testing.PaginationTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.difft.app.database.test.builders.buildMessageSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test





@OptIn(ExperimentalCoroutinesApi::class)
class ChatNormalPaginationControllerObserverTest : PaginationTestBase() {

    
    
    
    @Test
    fun `restarting the observer leaves exactly one live collector`() = runTest {
        source.seed(buildMessageSequence(MAX_MESSAGE_COUNT))
        val controller = controller()
        controller.loadPreviousPage()
        controller.loadPreviousPage()
        val before = controller.behavior()

        source.notifyChange()
        controller.awaitNextBehavior(before)

        assertEquals(1, source.activeChangeCollectors)
        assertEquals(1, source.callCount("ascendingFrom"))
    }

    
    
    
    
    @Test
    fun `a burst of new messages never drops the rows the viewport is showing`() = runTest {
        val seeded = buildMessageSequence(400)
        source.seed(seeded)
        val controller = controller()
        controller.jumpToBottom()
        
        
        
        controller.fillWindowBelowCap()
        assertTrue(
            "the window must be nearly full for this case to mean anything",
            controller.window().size > MAX_MESSAGE_COUNT - SCROLL_PAGE_SIZE,
        )

        
        val viewport = controller.window().subList(3, 13)

        val incoming = buildMessageSequence(
            count = 20,
            startTs = seeded.last().systemShowTimestamp + 1_000L,
            idPrefix = "incoming",
        )
        incoming.forEach { source.appendAndNotify(it) }
        val after = controller.awaitBehaviorWhere { behavior ->
            behavior.messageList.any { it.id == incoming.last().id }
        }

        assertViewportRetained(viewport, after.messageList)
    }
}
