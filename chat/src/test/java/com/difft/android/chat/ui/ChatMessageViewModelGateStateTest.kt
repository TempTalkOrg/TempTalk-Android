package com.difft.android.chat.ui

import app.cash.turbine.test
import com.difft.android.chat.ChatMessageListBehavior
import difft.android.messageserialization.For
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import org.difft.app.database.test.builders.buildMessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Case #61 — the CRIT-1 regression at the layer where the gate reads its input.
 *
 * `checkAndLoadMessages` suppresses a page load by reading `hasReachedHistoryStart` /
 * `hasReachedLatest` off the CURRENT UI state, so the mechanism that unlocks a gated edge is: the
 * controller recomputes the flag on every emission, and the resulting UI state must reach the
 * Fragment. `distinctUntilChanged` sits between the two.
 *
 * The self conversation is deliberate. It is not E2EE-hint eligible, so no header is injected and
 * `chatMessages` is byte-identical across the two emissions below — which is exactly the case where
 * the flag flip is the ONLY difference. Before the flags entered `ChatMessageListUIState` this second
 * state would have been dropped as a duplicate, and the top gate could never re-open: "scrolled to
 * the top once, can never pull older messages again, even after a failed retry re-inserts one".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessageViewModelGateStateTest : ChatMessageViewModelTestBase() {

    @Test
    fun `a hasReachedHistoryStart flip alone still reaches the UI state`() = runTest {
        val window = listOf(buildMessageModel(id = "m1", systemShowTimestamp = DAY_ONE, fromWho = MY_ID))
        val vm = viewModel(For.Account(MY_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(
                messageList = window,
                updateTimestamp = 1L,
                hasReachedHistoryStart = true,
            )
            pumpMainLooper()
            val gated = awaitItem()
            assertTrue(gated.hasReachedHistoryStart)
            val renderedIds = gated.chatMessages.map { it.id }

            // Same window, same transformed list — only the flag moves (a row re-inserted OLDER than
            // the window is invisible to the observer's window query, by design).
            behaviorFlow.value = ChatMessageListBehavior(
                messageList = window,
                updateTimestamp = 2L,
                hasReachedHistoryStart = false,
            )
            pumpMainLooper()
            val reopened = awaitItem()

            assertEquals("no header, so the rendered list really is identical", renderedIds, reopened.chatMessages.map { it.id })
            assertFalse("the top gate must re-open", reopened.hasReachedHistoryStart)
        }
    }

    @Test
    fun `a hasReachedLatest flip alone still reaches the UI state`() = runTest {
        val window = listOf(buildMessageModel(id = "m1", systemShowTimestamp = DAY_ONE, fromWho = MY_ID))
        val vm = viewModel(For.Account(MY_ID))

        vm.chatMessageListUIState.filterNotNull().test {
            behaviorFlow.value = ChatMessageListBehavior(
                messageList = window,
                updateTimestamp = 1L,
                hasReachedLatest = true,
            )
            pumpMainLooper()
            assertTrue(awaitItem().hasReachedLatest)

            behaviorFlow.value = ChatMessageListBehavior(
                messageList = window,
                updateTimestamp = 2L,
                hasReachedLatest = false,
            )
            pumpMainLooper()

            assertFalse("the bottom gate must re-open", awaitItem().hasReachedLatest)
        }
    }

    private companion object {
        const val DAY_ONE = 1_700_000_000_000L
    }
}
