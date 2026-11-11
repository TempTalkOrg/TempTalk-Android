package com.difft.android.chat.ui

import app.cash.turbine.test
import com.difft.android.chat.ChatMessageListBehavior
import com.difft.android.chat.ScrollAction
import com.difft.android.chat.message.EncryptionHeaderChatMessage
import difft.android.messageserialization.For
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.getGroupMemberCount
import org.difft.app.database.hydration.MessageChildRowLoader
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.test.builders.buildMessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config






@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessageViewModelHydrationPipelineTest : ChatMessageViewModelTestBase() {

    
    
    @Test
    fun `hydration covers the whole window plus both anchors`() = runTest {
        val rows = (0..4).map { buildMessageModel(id = "m$it", systemShowTimestamp = DAY_ONE + it * 1_000L) }
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(
                messageList = rows.subList(1, 4),
                anchorMessageBefore = rows.first(),
                anchorMessageAfter = rows.last(),
            )
            pumpMainLooper()
            val state = awaitItem()

            assertEquals(3, state.chatMessages.size)
            val hydratedIds = childRowLoader.keysPassedTo("attachmentsByMessageId").first()
            assertEquals(listOf("m0", "m1", "m2", "m3", "m4"), hydratedIds)
        }
    }

    
    
    @Test
    fun `an empty window issues no child-row query`() = runTest {
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(messageList = emptyList())
            pumpMainLooper()
            awaitItem()

            assertEquals(emptyList<Any>(), childRowLoader.callLog)
        }
    }

    
    
    
    @Test
    fun `decoration output matches the golden values for a two-day window`() = runTest {
        val anchorBefore = peerMessage("a0", DAY_ONE)
        val m1 = peerMessage("m1", DAY_ONE + 1_000L)
        val m2 = peerMessage("m2", DAY_TWO)
        val m3 = peerMessage("m3", DAY_TWO + 1_000L)
        val anchorAfter = peerMessage("a4", DAY_TWO + 2_000L)
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(
                messageList = listOf(m1, m2, m3),
                scrollAction = ScrollAction.ToPosition(1),
                updateTimestamp = 1L,
                anchorMessageBefore = anchorBefore,
                anchorMessageAfter = anchorAfter,
                readPosition = DAY_ONE + 500L,
                hasReachedHistoryStart = true,
            )
            pumpMainLooper()
            val state = awaitItem()

            
            assertEquals(4, state.chatMessages.size)
            assertTrue(state.chatMessages[0] is EncryptionHeaderChatMessage)
            assertEquals(listOf("__e2ee_header__", "m1", "m2", "m3"), state.chatMessages.map { it.id })
            assertEquals(ScrollAction.ToPosition(2), state.scrollAction)

            assertEquals(listOf(true, true, true, false), state.chatMessages.map { it.showName })
            assertEquals(listOf(false, false, true, false), state.chatMessages.map { it.showDayTime })
            assertEquals(listOf(true, true, false, false), state.chatMessages.map { it.showTime })
            
            assertEquals(
                listOf(false, true, false, false),
                state.chatMessages.map { it.showNewMsgDivider },
            )
        }
    }

    
    
    
    
    
    
    
    
    
    @Test
    fun `all database reads of one assembly share a single IO hop`() = runTest {
        val hydrationThreads = mutableListOf<Thread>()
        val contactThreads = mutableListOf<Thread>()
        val memberCountThreads = mutableListOf<Thread>()
        wrapChildRowLoader = { delegate -> ThreadRecordingLoader(delegate, hydrationThreads) }
        every { wcdb.getContactorsFromAllTable(any(), any()) } answers {
            contactThreads += Thread.currentThread()
            emptyList()
        }
        every { wcdb.getGroupMemberCount(any()) } answers {
            memberCountThreads += Thread.currentThread()
            0
        }
        val vm = viewModel(For.Group(GROUP_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(
                messageList = listOf(peerMessage("m1", DAY_ONE)),
            )
            pumpMainLooper()
            awaitItem()

            val hydrationThread = hydrationThreads.first()
            assertEquals(hydrationThread, contactThreads.first())
            assertEquals(hydrationThread, memberCountThreads.first())
            assertNotEquals(Thread.currentThread(), hydrationThread)
            assertFalse(hydrationThread.name.contains("main", ignoreCase = true))
        }
    }

    private fun peerMessage(id: String, timestamp: Long) =
        buildMessageModel(id = id, systemShowTimestamp = timestamp, fromWho = PEER_ID)

    



    private class ThreadRecordingLoader(
        private val delegate: MessageChildRowLoader,
        private val threads: MutableList<Thread>,
    ) : MessageChildRowLoader by delegate {
        override fun attachmentsByMessageId(ids: List<String>): List<AttachmentModel> {
            threads += Thread.currentThread()
            return delegate.attachmentsByMessageId(ids)
        }
    }

    private companion object {
        const val PEER_ID = "peer-uid"
        const val GROUP_ID = "group-id"

        
        const val DAY_ONE = 1_700_000_000_000L
        const val DAY_TWO = DAY_ONE + 24 * 60 * 60 * 1_000L
    }
}
