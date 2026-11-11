package com.difft.android.call.handler

import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import com.difft.android.call.session.CallSession
import com.difft.android.call.session.CallSessionUiEvent
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.call.ui.barrage.BarrageRenderable
import com.difft.android.network.BaseResponse
import com.difft.android.network.responses.CriticalAlertResponse
import difft.android.messageserialization.For
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.util.Collections
import kotlin.time.Duration.Companion.seconds





class CriticalAlertDispatcherOutputTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness
    private lateinit var session: CallSession
    private val collector = CoroutineScope(testDispatcher)
    private val events = Collections.synchronizedList(mutableListOf<CallSessionUiEvent>())

    @Before
    fun setUp() {
        mockkObject(ResUtils)
        every { ResUtils.getString(R.string.call_barrage_message_critical_alert_limited) } returns "limited"
        every { ResUtils.getString(R.string.call_barrage_message_critical_alert_success) } returns "sent"
        h = CallSessionTestHarness(testDispatcher)
        every { h.components.room.localParticipant } returns mockk<LocalParticipant>(relaxed = true) {
            every { identity } returns Participant.Identity("self-uid.1")
        }
        session = h.start()
        collector.launch { session.uiState.events.collect { events += it } }
        testDispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        collector.cancel()
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `T3-23 a 413 emits the limited toast`() {
        coEvery { h.deps.httpClient.get().httpService.sendCriticalAlertNew(any()) } throws
            HttpException(Response.error<Any>(413, "".toResponseBody(null)))

        val sent = runBlocking { session.handleCriticalAlertNew() }
        testDispatcher.scheduler.runCurrent()

        assertFalse(sent)
        assertEquals(listOf<CallSessionUiEvent>(CallSessionUiEvent.Toast("limited")), events)
    }

    @Test
    fun `T3-23 status 0 emits the success barrage`() {
        val response = mockk<BaseResponse<CriticalAlertResponse>>(relaxed = true) {
            every { status } returns 0
            every { serverTimestamp } returns null
        }
        coEvery { h.deps.httpClient.get().httpService.sendCriticalAlertNew(any()) } returns response

        val sent = runBlocking { session.handleCriticalAlertNew() }
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (events.isEmpty() && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(AWAIT_POLL_MS)
        }

        assertTrue(sent)
        val barrage = events.single() as CallSessionUiEvent.Barrage
        assertEquals("sent", (barrage.renderable as BarrageRenderable.Default).message.message)
    }

    @Test
    fun `while messaging is paused no critical alert is sent`() {
        every { h.deps.messagingGate } returns TestMessagingPause().open().pauseMessaging().gate

        val sent = runBlocking { session.handleCriticalAlertNew() }
        testDispatcher.scheduler.runCurrent()

        assertFalse(sent)
        coVerify(exactly = 0) { h.deps.httpClient.get().httpService.sendCriticalAlertNew(any()) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `an admitted critical alert persists its delivery rows before its ticket is released`() {
        val pause = TestMessagingPause().open()
        every { h.deps.messagingGate } returns pause.gate
        val response = mockk<BaseResponse<CriticalAlertResponse>>(relaxed = true) {
            every { status } returns 0
            every { serverTimestamp } returns SERVER_TS
            every { data } returns CriticalAlertResponse(result = true, delivers = emptyList())
        }
        coEvery { h.deps.httpClient.get().httpService.sendCriticalAlertNew(any()) } returns response

        val sent = runBlocking { session.handleCriticalAlertNew(gid = "g1") }

        assertTrue(sent)
        
        coVerify(exactly = 1) {
            h.deps.callToChatController.createCriticalAlertMessage(SERVER_TS, any(), any(), For.Group("g1"), any())
        }
        pause.pauseMessaging()
        assertTrue(runBlocking { pause.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 1.seconds) })
    }

    private companion object {
        const val SERVER_TS = 1_700_000_000_000L
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val AWAIT_POLL_MS = 5L
    }
}
