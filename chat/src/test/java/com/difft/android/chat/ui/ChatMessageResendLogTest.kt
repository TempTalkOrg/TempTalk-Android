package com.difft.android.chat.ui

import app.cash.turbine.test
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.message.ChatMessage
import difft.android.messageserialization.For
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.difft.app.database.WCDB
import org.difft.app.database.hydration.MessageHydrator
import org.difft.app.database.test.fakes.FakeMessageChildRowLoader

/**
 * T1-13 — `reSendMessage` is the single funnel both retry entry points go through (the out-of-bubble
 * status-row tap and the long-press Resend action), and it is the one place the resend flow is
 * observable. Adding the funnel log must not change its emission behaviour: still exactly one
 * `messageResend` emission per call, still non-suspending `tryEmit`.
 *
 * `globalServices`/`wcdb` (both Hilt-EntryPoint-backed globals) are mocked here because
 * [ChatMessageViewModel]'s constructor now eagerly evaluates `isE2eeHintEligible` and
 * `initE2eeHintObservers()` (Task 2), both of which read `globalServices.myId` synchronously at
 * construction time — without this stub, constructing the ViewModel in a plain (non-Robolectric)
 * unit test throws `UninitializedPropertyAccessException` (no Android Application present).
 *
 * Fixture uses `For.Group`, NOT `For.Account`, deliberately: `initE2eeHintObservers()`'s
 * non-self/non-official-1v1 branch calls `DBContactorModel.id.eq(...)`, a real WCDB winq
 * `Expression` — those classes extend `CppObject` and load a native library via
 * `System.loadLibrary` in their static initializer, unavailable on the host JVM (same constraint
 * documented on `database/.../QuoteAttachmentRoundTripTest`'s `@Ignore`). The crash is
 * asynchronous (inside a `viewModelScope.launch(Dispatchers.IO)`), so it does not fail the
 * triggering test itself but leaks an uncaught exception that fails the NEXT `runTest` anywhere
 * in the same JVM worker — `For.Group` conversations skip that branch entirely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageResendLogTest {

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        val globalServicesMock: GlobalHiltEntryPoint = mockk(relaxed = true)
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns "my-uid"
        val wcdbMock: WCDB = mockk(relaxed = true)
        every { org.difft.app.database.wcdb } returns wcdbMock
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
        unmockkStatic("org.difft.app.database.WCDBExtensionsKt")
    }

    private fun viewModel(): ChatMessageViewModel = ChatMessageViewModel(
        forWhat = For.Group("g1"),
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
        callDataManager = mockk(relaxed = true),
        messageHydrator = MessageHydrator(FakeMessageChildRowLoader())
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
