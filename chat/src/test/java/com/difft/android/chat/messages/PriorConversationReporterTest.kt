package com.difft.android.chat.messages

import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.common.SendMessageUtils
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.requests.SetPriorConversationRequestBody
import dagger.Lazy
import difft.android.messageserialization.For
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the setPriorConversation contract: the call fires only inside a replay window, at most once
 * per conversation per window, with `{number}` for 1:1 and `{gid}` for groups, and a failure never
 * escapes into the receive path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PriorConversationReporterTest {

    private companion object {
        const val ACCOUNT_ID = "+10000000031"
        const val OTHER_ACCOUNT_ID = "+10000000032"
        const val GROUP_ID = "e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1"
        const val VERIFY_TIMEOUT_MS = 5_000L
    }

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var httpService: HttpService
    private lateinit var reporter: PriorConversationReporter

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val globalServicesMock: GlobalHiltEntryPoint = mockk(relaxed = true)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock

        httpService = mockk(relaxed = true)
        coEvery { httpService.setPriorConversation(any()) } returns okResponse()
        val httpClient: ChativeHttpClient = mockk(relaxed = true)
        every { httpClient.httpService } returns httpService

        reporter = PriorConversationReporter(Lazy { httpClient })
        reporter.start(CoroutineScope(testDispatcher))
    }

    @After
    fun tearDown() {
        SendMessageUtils.removeFromCurrentChat(ACCOUNT_ID)
        SendMessageUtils.removeFromCurrentChat(GROUP_ID)
        reporter.onQueueEmpty()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun okResponse() = BaseResponse<Any>(ver = 1, status = 0, reason = null, data = null)

    @Test
    fun `opening a chat during a replay reports it as a 1 to 1 conversation`() = runTest(testDispatcher) {
        reporter.onReplayStarted()
        val body = slot<SetPriorConversationRequestBody>()

        reporter.onConversationOpened(For.Account(ACCOUNT_ID))

        coVerify(exactly = 1) { httpService.setPriorConversation(capture(body)) }
        assertEquals(ACCOUNT_ID, body.captured.number)
        assertNull(body.captured.gid)
    }

    @Test
    fun `opening a group during a replay reports it as a gid`() = runTest(testDispatcher) {
        reporter.onReplayStarted()
        val body = slot<SetPriorConversationRequestBody>()

        reporter.onConversationOpened(For.Group(GROUP_ID))

        coVerify(exactly = 1) { httpService.setPriorConversation(capture(body)) }
        assertEquals(GROUP_ID, body.captured.gid)
        assertNull(body.captured.number)
    }

    @Test
    fun `opening a chat outside a replay window reports nothing`() = runTest(testDispatcher) {
        reporter.onConversationOpened(For.Account(ACCOUNT_ID))

        coVerify(exactly = 0) { httpService.setPriorConversation(any()) }
    }

    @Test
    fun `the same conversation is reported once per replay window`() = runTest(testDispatcher) {
        reporter.onReplayStarted()
        reporter.onConversationOpened(For.Account(ACCOUNT_ID))
        reporter.onConversationOpened(For.Account(ACCOUNT_ID))

        coVerify(exactly = 1) { httpService.setPriorConversation(any()) }

        // A new window is a new opportunity to report the same conversation.
        reporter.onQueueEmpty()
        reporter.onReplayStarted()
        reporter.onConversationOpened(For.Account(ACCOUNT_ID))

        coVerify(exactly = 2) { httpService.setPriorConversation(any()) }
    }

    @Test
    fun `a different conversation in the same window is reported too`() = runTest(testDispatcher) {
        reporter.onReplayStarted()
        reporter.onConversationOpened(For.Account(ACCOUNT_ID))
        reporter.onConversationOpened(For.Account(OTHER_ACCOUNT_ID))

        coVerify(exactly = 2) { httpService.setPriorConversation(any()) }
    }

    @Test
    fun `switching back to a conversation reports it again because the server keeps only the latest hint`() = runTest(testDispatcher) {
        reporter.onReplayStarted()
        reporter.onConversationOpened(For.Account(ACCOUNT_ID))
        reporter.onConversationOpened(For.Account(OTHER_ACCOUNT_ID))
        reporter.onConversationOpened(For.Account(ACCOUNT_ID))

        coVerify(exactly = 3) { httpService.setPriorConversation(any()) }
    }

    @Test
    fun `a replay starting while a chat is open reports that chat`() = runTest(testDispatcher) {
        SendMessageUtils.addToCurrentChat(ACCOUNT_ID)

        reporter.onReplayStarted()

        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) { httpService.setPriorConversation(any()) }
    }

    @Test
    fun `a replay starting while a group is open reports it as a gid whatever the id shape`() = runTest(testDispatcher) {
        // A 36-char UUID-shaped gid: only the "not a number" rule classifies it as a group.
        val uuidGid = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        SendMessageUtils.addToCurrentChat(uuidGid)
        val body = slot<SetPriorConversationRequestBody>()
        try {
            reporter.onReplayStarted()

            coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) { httpService.setPriorConversation(capture(body)) }
            assertEquals(uuidGid, body.captured.gid)
            assertNull(body.captured.number)
        } finally {
            SendMessageUtils.removeFromCurrentChat(uuidGid)
        }
    }

    @Test
    fun `a report started inside the window still lands when the backlog ends right after`() = runTest(testDispatcher) {
        // Reporter scope on a standard dispatcher so the launched report is still pending when the
        // window closes; Main stays unconfined.
        reporter.start(CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)))
        SendMessageUtils.addToCurrentChat(ACCOUNT_ID)

        reporter.onReplayStarted()
        reporter.onQueueEmpty()
        advanceUntilIdle()

        coVerify(exactly = 1) { httpService.setPriorConversation(any()) }
    }

    @Test
    fun `a report from a superseded window is dropped`() = runTest(testDispatcher) {
        reporter.start(CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)))
        SendMessageUtils.addToCurrentChat(ACCOUNT_ID)

        reporter.onReplayStarted() // launches a report for window 1, still pending
        reporter.onReplayStarted() // window 2 opens before it ran
        advanceUntilIdle()

        // Only window 2's own report goes out; window 1's is stale and dropped.
        coVerify(exactly = 1) { httpService.setPriorConversation(any()) }
    }

    @Test
    fun `a replay starting with no chat open reports nothing`() = runTest(testDispatcher) {
        reporter.onReplayStarted()

        coVerify(exactly = 0) { httpService.setPriorConversation(any()) }
    }

    @Test
    fun `opening a chat after the backlog is over reports nothing`() = runTest(testDispatcher) {
        reporter.onReplayStarted()
        reporter.onQueueEmpty()

        reporter.onConversationOpened(For.Account(ACCOUNT_ID))

        coVerify(exactly = 0) { httpService.setPriorConversation(any()) }
    }

    @Test
    fun `a failed call is swallowed`() = runTest(testDispatcher) {
        coEvery { httpService.setPriorConversation(any()) } throws RuntimeException("boom")
        reporter.onReplayStarted()

        reporter.onConversationOpened(For.Account(ACCOUNT_ID))

        coVerify(exactly = 1) { httpService.setPriorConversation(any()) }
    }

    @Test
    fun `a non-zero server status is not treated as a crash`() = runTest(testDispatcher) {
        coEvery { httpService.setPriorConversation(any()) } returns
            BaseResponse(ver = 1, status = 10101, reason = "nope", data = null)
        reporter.onReplayStarted()

        reporter.onConversationOpened(For.Account(ACCOUNT_ID))

        coVerify(exactly = 1) { httpService.setPriorConversation(any()) }
    }
}
