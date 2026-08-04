package com.difft.android.chat.ui

import app.cash.turbine.test
import com.difft.android.chat.message.ChatMessage
import difft.android.messageserialization.For
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1-13 — `reSendMessage` is the single funnel both retry entry points go through (the out-of-bubble
 * status-row tap and the long-press Resend action), and it is the one place the resend flow is
 * observable. Adding the funnel log must not change its emission behaviour: still exactly one
 * `messageResend` emission per call, still non-suspending `tryEmit`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageResendLogTest {

    private fun viewModel(): ChatMessageViewModel = ChatMessageViewModel(
        forWhat = For.Account("peer-uid"),
        jumpMessageTimeStamp = null,
        dbMessageStore = mockk(relaxed = true),
        dbRoomStore = mockk(relaxed = true),
        chatPaginationControllerFactory = mockk(relaxed = true),
        callManager = mockk(relaxed = true),
        translateManager = mockk(relaxed = true),
        speechToTextManager = mockk(relaxed = true),
        pushReadReceiptSendJobFactory = mockk(relaxed = true),
        activityNoticeDispatcher = mockk(relaxed = true),
        onGoingCallStateManager = mockk(relaxed = true),
        callDataManager = mockk(relaxed = true)
    )

    private fun message(id: String): ChatMessage = mockk(relaxed = true) {
        every { this@mockk.id } returns id
    }

    @Test
    fun `T1-13 reSendMessage emits the message exactly once`() = runTest {
        val vm = viewModel()
        val msg = message("msg-1")

        vm.messageResend.test {
            vm.reSendMessage(msg)

            assertEquals(msg, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `T1-13 two resends of different messages both reach the funnel`() = runTest {
        val vm = viewModel()
        val first = message("msg-1")
        val second = message("msg-2")

        vm.messageResend.test {
            vm.reSendMessage(first)
            assertEquals(first, awaitItem())

            vm.reSendMessage(second)
            assertEquals(second, awaitItem())

            expectNoEvents()
        }
    }
}
