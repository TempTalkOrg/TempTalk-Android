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
import org.difft.app.database.isKnownContact
import org.difft.app.database.models.MessageModel
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
class ChatMessageViewModelHeaderInjectionTest : ChatMessageViewModelTestBase() {

    
    @Test
    fun `header injected as first item when eligible and history start reached`() = runTest {
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(hasReachedHistoryStart = true)
            pumpMainLooper()
            val state = awaitItem()
            val header = state.chatMessages[0] as EncryptionHeaderChatMessage
            assertFalse(header.isNonFriendVariant)
        }
    }

    
    @Test
    fun `header shows non-friend variant when peer is not a contact`() = runTest {
        every { wcdb.isKnownContact(PEER_ID) } returns false
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(hasReachedHistoryStart = true)
            pumpMainLooper()
            var header = awaitItem().chatMessages.getOrNull(0) as? EncryptionHeaderChatMessage
            while (header?.isNonFriendVariant != true) {
                pumpMainLooper()
                header = awaitItem().chatMessages.getOrNull(0) as? EncryptionHeaderChatMessage
            }
            assertTrue(header.isNonFriendVariant)
        }
    }

    
    @Test
    fun `no header when history start not reached`() = runTest {
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(hasReachedHistoryStart = false)
            pumpMainLooper()
            val state = awaitItem()
            assertTrue(state.chatMessages.none { it is EncryptionHeaderChatMessage })
        }
    }

    
    @Test
    fun `no header for self conversation even when history start reached`() = runTest {
        val vm = viewModel(For.Account(MY_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(hasReachedHistoryStart = true)
            
            
            
            
            pumpMainLooper()
            val state = awaitItem()
            assertTrue(state.chatMessages.none { it is EncryptionHeaderChatMessage })
        }
    }

    
    
    
    
    @Test
    fun `header is index 0 and tombstone is index 1`() = runTest {
        val tombstone = MessageModel().apply {
            id = "tombstone"
            type = MessageModel.TYPE_TEXT
            systemShowTimestamp = 1L
            timeStamp = 1L
        }
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(
                messageList = listOf(tombstone),
                hasReachedHistoryStart = true,
            )
            pumpMainLooper()
            val state = awaitItem()
            assertTrue(state.chatMessages[0] is EncryptionHeaderChatMessage)
            assertEquals("tombstone", state.chatMessages[1].id)
        }
    }

    
    @Test
    fun `ToPosition scroll action is shifted by one when header shows`() = runTest {
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(
                hasReachedHistoryStart = true,
                scrollAction = ScrollAction.ToPosition(3),
                updateTimestamp = 111L,
            )
            pumpMainLooper()
            val state = awaitItem()
            assertEquals(ScrollAction.ToPosition(4), state.scrollAction)
        }
    }

    
    @Test
    fun `ToPosition scroll action unchanged when no header shows`() = runTest {
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(
                hasReachedHistoryStart = false,
                scrollAction = ScrollAction.ToPosition(3),
                updateTimestamp = 222L,
            )
            pumpMainLooper()
            val state = awaitItem()
            assertEquals(ScrollAction.ToPosition(3), state.scrollAction)
        }
    }

    
    
    
    
    @Test
    fun `isNonFriendOneToOne resolving after the first emission still re-renders the header`() = runTest {
        
        every { wcdb.isKnownContact(PEER_ID) } answers {
            Thread.sleep(120)
            false 
        }
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(hasReachedHistoryStart = true)

            pumpMainLooper()
            val first = awaitItem()
            assertFalse((first.chatMessages[0] as EncryptionHeaderChatMessage).isNonFriendVariant)

            
            
            pumpMainLooper()
            val second = awaitItem()
            assertTrue((second.chatMessages[0] as EncryptionHeaderChatMessage).isNonFriendVariant)
        }
    }

    private companion object {
        const val PEER_ID = "peer-uid"
    }
}
