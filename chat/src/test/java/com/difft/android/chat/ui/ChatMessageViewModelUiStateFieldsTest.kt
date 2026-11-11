package com.difft.android.chat.ui

import app.cash.turbine.test
import com.difft.android.chat.ChatMessageListBehavior
import com.difft.android.chat.message.EncryptionHeaderChatMessage
import com.google.gson.Gson
import difft.android.messageserialization.For
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import org.difft.app.database.models.MessageModel
import org.difft.app.database.test.builders.buildMessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config









@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessageViewModelUiStateFieldsTest : ChatMessageViewModelTestBase() {

    
    @Test
    fun `a missing before-anchor degrades the first row's day header and name`() = runTest {
        val previous = peerMessage("a0", DAY_ONE)
        val window = listOf(peerMessage("m1", DAY_ONE + 1_000L), peerMessage("m2", DAY_ONE + 2_000L))
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(messageList = window, updateTimestamp = 1L)
            pumpMainLooper()
            val withoutAnchor = awaitItem()
            assertTrue("no anchor: the first row has nothing to compare against", withoutAnchor.chatMessages[0].showName)
            assertTrue(withoutAnchor.chatMessages[0].showDayTime)

            behaviorFlow.value = ChatMessageListBehavior(
                messageList = window,
                updateTimestamp = 2L,
                anchorMessageBefore = previous,
            )
            pumpMainLooper()
            val withAnchor = awaitItem()
            assertFalse("same day, same author: no day header", withAnchor.chatMessages[0].showDayTime)
            assertFalse("same author: no repeated nickname", withAnchor.chatMessages[0].showName)
        }
    }

    
    
    @Test
    fun `windowSize reports the loaded window and both gate flags pass through verbatim`() = runTest {
        
        every { globalServicesMock.gson } returns Gson()
        val window = listOf(
            peerMessage("m1", DAY_ONE),
            emptyNotify("n1", DAY_ONE + 1_000L),
            peerMessage("m2", DAY_ONE + 2_000L),
            emptyNotify("n2", DAY_ONE + 3_000L),
        )
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(
                messageList = window,
                hasReachedHistoryStart = true,
                hasReachedLatest = true,
            )
            pumpMainLooper()
            val state = awaitItem()

            
            assertEquals(3, state.chatMessages.size)
            assertTrue(state.chatMessages[0] is EncryptionHeaderChatMessage)
            assertEquals(4, state.windowSize)
            assertTrue(state.hasReachedHistoryStart)
            assertTrue(state.hasReachedLatest)
        }
    }

    @Test
    fun `both gate flags pass through their false values too`() = runTest {
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(
                messageList = listOf(peerMessage("m1", DAY_ONE)),
                hasReachedHistoryStart = false,
                hasReachedLatest = false,
            )
            pumpMainLooper()
            val state = awaitItem()

            assertEquals(1, state.windowSize)
            assertFalse(state.hasReachedHistoryStart)
            assertFalse(state.hasReachedLatest)
        }
    }

    private fun peerMessage(id: String, timestamp: Long) =
        buildMessageModel(id = id, systemShowTimestamp = timestamp, fromWho = PEER_ID)

    
    private fun emptyNotify(id: String, timestamp: Long) = buildMessageModel(
        id = id,
        systemShowTimestamp = timestamp,
        fromWho = PEER_ID,
        type = MessageModel.TYPE_NOTIFY,
        messageText = """{"showContent":""}""",
    )

    private companion object {
        const val PEER_ID = "peer-uid"
        const val DAY_ONE = 1_700_000_000_000L
    }
}
