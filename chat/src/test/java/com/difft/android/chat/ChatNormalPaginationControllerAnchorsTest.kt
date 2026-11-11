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





@OptIn(ExperimentalCoroutinesApi::class)
class ChatNormalPaginationControllerAnchorsTest : PaginationTestBase() {

    
    
    
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

    
    
    
    @Test
    fun `addOneMessage carries the before-anchor forward, nulls the after-anchor and queries nothing`() = runTest {
        val rows = buildMessageSequence(CAP_FILLING_ROWS)
        source.seed(rows)
        val controller = controller()
        controller.jumpToBottom()
        controller.fillWindowToCap()
        val before = controller.behavior()
        
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
        
        const val CAP_FILLING_ROWS = 400
    }
}
