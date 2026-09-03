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

/**
 * Case #16 and its siblings — E2EE header injection + the `ScrollAction` +1 correction, inside the
 * REAL [ChatMessageViewModel.assembleMessagesUIData] / `initialize()` combine pipeline.
 *
 * Drives the real reactive pipeline end to end (`vm.initialize()` + a controllable fake pagination
 * controller) rather than reflecting into the private method, so the last case here exercises the
 * actual `combine()` re-trigger wiring.
 *
 * Every case except the self-conversation one used to be `@Ignore`d: a non-self `For.Account` made
 * `initE2eeHintObservers()` build a winq `Expression`, which loads a native library absent on the
 * host JVM. `WCDB.isKnownContact` moved that expression behind a stubbable extension — see
 * [ChatMessageViewModelTestBase].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessageViewModelHeaderInjectionTest : ChatMessageViewModelTestBase() {

    // Header injected, friend variant.
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

    // #16 — non-friend variant, reached through the `isKnownContact` seam.
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

    // hasReachedHistoryStart = false -> no header.
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

    // Self conversation: isE2eeHintEligible = false despite hasReachedHistoryStart = true.
    @Test
    fun `no header for self conversation even when history start reached`() = runTest {
        val vm = viewModel(For.Account(MY_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(hasReachedHistoryStart = true)
            // The pipeline's `.flowOn(Dispatchers.Default)` hop computes on a real background
            // thread, then re-dispatches onto `Dispatchers.Main` to write `_chatMessageListUIState`
            // — Robolectric's Main looper defaults to PAUSED, so it must be pumped for that posted
            // task to actually run before Turbine's `awaitItem()` sees the emission.
            pumpMainLooper()
            val state = awaitItem()
            assertTrue(state.chatMessages.none { it is EncryptionHeaderChatMessage })
        }
    }

    // The header always sits above the archive tombstone (sentinel systemShowTimestamp = 1L). Uses
    // a plain TYPE_TEXT row as the tombstone stand-in: a NotifyChatMessage row would additionally
    // exercise the empty-content notify filter, which is orthogonal to what this pins — the header
    // is unconditionally prepended before whatever survives that filter.
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

    // ScrollAction.ToPosition shifted +1 when the header shows.
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

    // No header -> no correction.
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

    // Regression guard — a friendStatus/contactsUpdate-driven flip of isNonFriendOneToOne that
    // resolves AFTER the first chatMessagesStateFlow emission must still re-render the header, with
    // NO further chatMessagesStateFlow emission. Without the third combine() source, this second
    // UI-state emission would never happen and the header would stay stale forever.
    @Test
    fun `isNonFriendOneToOne resolving after the first emission still re-renders the header`() = runTest {
        // First contact check resolves slowly (still in flight when the first behavior is emitted).
        every { wcdb.isKnownContact(PEER_ID) } answers {
            Thread.sleep(120)
            false // not a contact -> non-friend
        }
        val vm = viewModel(For.Account(PEER_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(hasReachedHistoryStart = true)

            pumpMainLooper()
            val first = awaitItem()
            assertFalse((first.chatMessages[0] as EncryptionHeaderChatMessage).isNonFriendVariant)

            // The second emission arrives solely because isNonFriendOneToOne flipped — no second
            // behaviorFlow write happens in this test.
            pumpMainLooper()
            val second = awaitItem()
            assertTrue((second.chatMessages[0] as EncryptionHeaderChatMessage).isNonFriendVariant)
        }
    }

    private companion object {
        const val PEER_ID = "peer-uid"
    }
}
