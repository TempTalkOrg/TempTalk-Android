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

/**
 * Cases #39..#41 — `assembleMessagesUIData` driven end to end through the real
 * `combine` → `assembleMessagesUIData` → `generateMessageTwo` pipeline, with a REAL
 * `MessageHydrator` over an in-memory loader (see [ChatMessageViewModelTestBase]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessageViewModelHydrationPipelineTest : ChatMessageViewModelTestBase() {

    // #39 — the hydration `IN` set is "window + both anchors", deduped. An anchor left out of it
    // would be decorated from empty sub-data.
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

    // #39 (dedupe half) — one message, no anchors: exactly one key, and no query at all for an
    // empty window.
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

    // #40 — the decoration steps (4~7) are untouched by the hydration change. Golden values for a
    // window spanning a day boundary, with both anchors present, the E2EE header injected and a
    // readPosition-driven divider.
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

            // header + 3 rows; the header shifts the scroll target by one.
            assertEquals(4, state.chatMessages.size)
            assertTrue(state.chatMessages[0] is EncryptionHeaderChatMessage)
            assertEquals(listOf("__e2ee_header__", "m1", "m2", "m3"), state.chatMessages.map { it.id })
            assertEquals(ScrollAction.ToPosition(2), state.scrollAction)

            assertEquals(listOf(true, true, true, false), state.chatMessages.map { it.showName })
            assertEquals(listOf(false, false, true, false), state.chatMessages.map { it.showDayTime })
            assertEquals(listOf(true, true, false, false), state.chatMessages.map { it.showTime })
            // Divider on the first not-mine row past readPosition, and only there.
            assertEquals(
                listOf(false, true, false, false),
                state.chatMessages.map { it.showNewMsgDivider },
            )
        }
    }

    // #41 — every DB read of one assembly happens inside ONE `withContext(Dispatchers.IO)` block:
    // the sender-contact batch, the group member count and the child-row hydration all observe the
    // same thread, which is neither the test thread nor Main.
    //
    // The complementary half — "the decoration stage does not run on IO" — is structural rather than
    // observable: the IO block closes lexically before `generateMessageTwo`, and the flow's
    // `flowOn(Dispatchers.Default)` is unchanged. `Dispatchers.IO` and `Dispatchers.Default` share
    // one worker pool, so a host-JVM assertion on thread identity could not distinguish them; this
    // case asserts the positive, checkable property instead.
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

    /**
     * Records where the hydrator's first batch query runs. Interface delegation keeps this to the one
     * method that needs observing — the other thirteen pass straight through.
     */
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

        /** 2023-11-14T22:13:20Z — real dates, so `isSameDay` is not trivially true against 0L. */
        const val DAY_ONE = 1_700_000_000_000L
        const val DAY_TWO = DAY_ONE + 24 * 60 * 60 * 1_000L
    }
}
