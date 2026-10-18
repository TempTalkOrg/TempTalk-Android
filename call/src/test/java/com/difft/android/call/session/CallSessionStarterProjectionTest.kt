package com.difft.android.call.session

import com.difft.android.base.call.CallType
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallToChatController
import com.difft.android.call.manager.CallRingtoneManager
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.state.OngoingCallPublisher
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
 * [CallSessionStarter] writes the room id from the start/join response into the
 * OnGoingCallStateManager projection through the session's publisher (rows T5-01, T5-02).
 *
 * Drives [CallSessionStarter.handleStartCallResponse] directly: the LiveKit `ttCallResp` flow that
 * feeds it cannot be resolved on a mocked `Room`. Robolectric because the caller ringback builds an
 * `android.content.Intent` and the start-call message reaches `LCallManager`, whose object init reads
 * `ApplicationHelper`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallSessionStarterProjectionTest {

    private val ogsm = OnGoingCallStateManager()
    private val publisher = OngoingCallPublisher(SESSION_ID, ogsm).apply { bind() }
    private val ringtone = mockk<CallRingtoneManager>(relaxed = true)
    private val callToChatController = mockk<LCallToChatController>(relaxed = true)
    private val assignedRoomIds = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        mockkObject(LCallManager)
        every {
            LCallManager.sendOrLocalCallTextMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } just Runs
    }

    /** The starter's ringback and start-call message run on Dispatchers.IO: let them finish before unmocking. */
    @After
    fun tearDown() {
        runBlocking { withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.children.toList().joinAll() } }
        scope.cancel()
        unmockkAll()
    }

    @Test
    fun `T5-01 START response publishes the room id and rings back once`() = runTest {
        val starter = starter(CallIntent.Action.START_CALL)

        starter.handleStartCallResponse(response(ROOM_ID))

        assertEquals(ROOM_ID, ogsm.getCurrentRoomId())
        assertEquals(listOf(ROOM_ID), assignedRoomIds)
        verify(timeout = TIMEOUT_MS, exactly = 1) { ringtone.startRingTone(any()) }
    }

    @Test
    fun `T5-01 a response without a room id publishes null`() = runTest {
        publisher.publish { setCurrentRoomId("stale") }
        val starter = starter(CallIntent.Action.START_CALL)

        starter.handleStartCallResponse(response(null))

        assertNull(ogsm.getCurrentRoomId())
        assertEquals(emptyList<String>(), assignedRoomIds)
    }

    @Test
    fun `T5-02 JOIN response publishes the room id, syncs the join once, no ringback, no client call id`() = runTest {
        val starter = starter(CallIntent.Action.JOIN_CALL)

        starter.handleStartCallResponse(response(ROOM_ID))

        assertEquals(ROOM_ID, ogsm.getCurrentRoomId())
        verify(timeout = TIMEOUT_MS, exactly = 1) {
            callToChatController.syncJoinedMessage(SELF_ID, any(), CALLER_ID, any(), ROOM_ID, any(), any())
        }
        verify(exactly = 0) { ringtone.startRingTone(any()) }
        assertNull(ogsm.getClientCallId())
    }

    private fun starter(action: CallIntent.Action): CallSessionStarter {
        val intent = mockk<CallIntent>(relaxed = true) {
            every { this@mockk.action } returns action
            every { callType } returns CallType.ONE_ON_ONE.type
            every { conversationId } returns CONVERSATION_ID
            every { callerId } returns CALLER_ID
            every { roomName } returns "room"
        }
        return CallSessionStarter(
            scope = scope,
            room = mockk(relaxed = true),
            roomCtl = mockk(relaxed = true) { every { callType } returns MutableStateFlow("") },
            connectionCoordinator = mockk(relaxed = true),
            onGoingCallStateManager = ogsm,
            publisher = publisher,
            callRingtoneManager = ringtone,
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
            isSessionEnding = { false },
        )
    }

    private fun response(roomId: String?): LivekitTemptalk.TTCallResponse = mockk(relaxed = true) {
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
