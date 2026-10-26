package com.difft.android.call.session

import com.difft.android.base.call.CallType
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallEngine
import com.difft.android.call.LCallToChatController
import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.state.OngoingCallPublisher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import livekit.LivekitTemptalk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `Room.connect` returns only after the first PeerConnection is up and emits `RoomEvent.Connected`
 * just before returning, so [CallSessionStarter.start] must handle `ttCallResp` while connect is
 * still in flight — the Connected handler reads the room id assigned from it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallSessionStarterResponseOrderTest {

    private val ogsm = OnGoingCallStateManager()
    private val publisher = OngoingCallPublisher(SESSION_ID, ogsm).apply { bind() }
    private val callToChatController = mockk<LCallToChatController>(relaxed = true)
    private val coordinator = mockk<CallConnectionCoordinator>()
    private val responses = MutableStateFlow<LivekitTemptalk.TTCallResponse?>(null)
    private val connectResult = CompletableDeferred<Boolean>()
    private val assignedRoomIds = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        mockkObject(LCallEngine)
        every { LCallEngine.isUseQuicSignal() } returns false
    }

    @After
    fun tearDown() {
        if (!connectResult.isCompleted) connectResult.complete(true)
        runBlocking { withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.children.toList().joinAll() } }
        scope.cancel()
        unmockkAll()
    }

    @Test
    fun `response from the first join is handled while connect is still waiting for media`() {
        val joined = CompletableDeferred<Unit>()
        coEvery { coordinator.connectToRoomWithFailover(any(), any()) } coAnswers {
            responses.value = response(ROOM_ID)
            joined.complete(Unit)
            connectResult.await()
        }

        starter().start()
        runBlocking { withTimeout(TIMEOUT_MS) { joined.await() } }

        verify(timeout = TIMEOUT_MS) { callToChatController.cancelNotificationById(ROOM_ID.hashCode()) }
        assertEquals(listOf(ROOM_ID), assignedRoomIds)
        assertEquals(ROOM_ID, ogsm.getCurrentRoomId())
        assertEquals(false, connectResult.isCompleted)
    }

    @Test
    fun `a later attempt's response is not handled again`() {
        coEvery { coordinator.connectToRoomWithFailover(any(), any()) } coAnswers {
            responses.value = response(ROOM_ID)
            verify(timeout = TIMEOUT_MS) { callToChatController.cancelNotificationById(ROOM_ID.hashCode()) }
            responses.value = response(ROOM_ID)
            true
        }

        starter().start()

        verify(timeout = TIMEOUT_MS) { callToChatController.syncJoinedMessage(any(), any(), any(), any(), ROOM_ID, any(), any()) }
        verify(exactly = 1) { callToChatController.cancelNotificationById(ROOM_ID.hashCode()) }
        assertEquals(listOf(ROOM_ID), assignedRoomIds)
    }

    @Test
    fun `connect failing before any join handles no response`() {
        coEvery { coordinator.connectToRoomWithFailover(any(), any()) } returns false

        starter().start()
        runBlocking { withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.children.toList().joinAll() } }
        responses.value = response(ROOM_ID)

        verify(exactly = 0) { callToChatController.cancelNotificationById(any()) }
        assertEquals(emptyList<String>(), assignedRoomIds)
        assertNull(ogsm.getCurrentRoomId())
    }

    @Test
    fun `join response after session starts ending has no side effects`() {
        runBlocking {
            starter(isSessionEnding = { true }).handleStartCallResponse(response(ROOM_ID))
        }

        verify(exactly = 0) { callToChatController.syncJoinedMessage(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { callToChatController.cancelNotificationById(any()) }
        assertEquals(emptyList<String>(), assignedRoomIds)
        assertNull(ogsm.getCurrentRoomId())
    }

    private fun starter(isSessionEnding: () -> Boolean = { false }): CallSessionStarter {
        val intent = mockk<CallIntent>(relaxed = true) {
            every { action } returns CallIntent.Action.JOIN_CALL
            every { callType } returns CallType.ONE_ON_ONE.type
            every { conversationId } returns CONVERSATION_ID
            every { callerId } returns CALLER_ID
        }
        return CallSessionStarter(
            scope = scope,
            room = mockk(relaxed = true),
            roomCtl = mockk(relaxed = true),
            connectionCoordinator = coordinator,
            onGoingCallStateManager = ogsm,
            publisher = publisher,
            callRingtoneManager = mockk(relaxed = true),
            callDataManager = mockk(relaxed = true),
            contactorCacheManager = mockk(relaxed = true),
            callToChatController = callToChatController,
            messageEncryptor = mockk(relaxed = true),
            callIntent = intent,
            e2eeEnable = false,
            mySelfId = SELF_ID,
            createCallMsgConfig = false,
            onRoomIdAssigned = { assignedRoomIds += it },
            onE2eeKeyAssigned = {},
            isSessionEnding = isSessionEnding,
            startCallResponses = { responses },
        )
    }

    private fun response(roomId: String): LivekitTemptalk.TTCallResponse = mockk(relaxed = true) {
        every { base.status } returns 0
        every { body.roomId } returns roomId
    }

    private companion object {
        const val SESSION_ID = 1L
        const val ROOM_ID = "r1"
        const val SELF_ID = "self"
        const val CALLER_ID = "caller"
        const val CONVERSATION_ID = "conv"
        const val TIMEOUT_MS = 2_000L
    }
}
