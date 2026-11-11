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









@OptIn(ExperimentalCoroutinesApi::class)
class ChatNormalPaginationControllerEntryPointsTest : PaginationTestBase() {

    
    
    
    @Test
    fun `first screen from read position back-fills read rows and scrolls to the first unread`() = runTest {
        val messages = buildMessageSequence(40)
        source.seed(messages)
        source.roomRow = RoomAnchors(readPosition = 35_000L, sendStatus = ROOM_SEND_STATUS_NONE)
        val controller = controller()

        controller.initLoadMessage(null)

        val behavior = controller.behavior()
        
        assertEquals((20..39).map { "m$it" }, controller.windowIds())
        assertEquals(ScrollAction.ToPosition(15), behavior.scrollAction)
        assertEquals("m19", behavior.anchorMessageBefore?.id)
        assertNull(behavior.anchorMessageAfter)
        assertEquals(35_000L, behavior.readPosition)
        
        assertEquals(1, source.callCount("atOrOlderThan"))
    }

    
    
    
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
