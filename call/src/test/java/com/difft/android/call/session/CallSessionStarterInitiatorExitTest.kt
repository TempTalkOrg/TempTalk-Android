package com.difft.android.call.session

import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.manager.CallRingtoneManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.state.OngoingCallPublisher
import difft.android.messageserialization.For
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import livekit.LivekitTemptalk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behavioral coverage for [CallSessionStarter.onStartCallSucceeded]'s pre-connect-exit guard —
 * the client half of PR #1111's "quick-exit leaves remote ringing" fix.
 *
 * `processStartCallResponse()` cannot be driven end-to-end in a unit test: it awaits
 * `room::ttCallResp.flow`, which resolves the property delegate through a ThreadLocal only the
 * real LiveKit getter populates (a mocked [io.livekit.android.room.Room] never registers it — see
 * [CallTypeCoordinatorTest]'s note). The success-side effects were therefore extracted into the
 * Room-independent [CallSessionStarter.onStartCallSucceeded], exercised directly here.
 *
 * The invariant under protection: when the initiator has already exited during window W
 * (`OnGoingCallStateManager.isInitiatorPreConnectCancelled()` is true), the late-arriving start
 * response must NOT ring / build local CallData / send the start-call message, and must instead
 * re-issue the authoritative termination against the now-known roomId — 1v1 `cancel`, group
 * `hangup` (end-for-all, empty uid list in window W).
 */
class CallSessionStarterInitiatorExitTest {

    private val onGoingCallStateManager = OnGoingCallStateManager()
    private val publisher = OngoingCallPublisher(SESSION_ID, onGoingCallStateManager).apply { bind() }
    private val callToChatController = mockk<LCallToChatController>(relaxed = true)
    private val callIntent = mockk<CallIntent>(relaxed = true)
    private val ringtone = mockk<CallRingtoneManager>(relaxed = true)
    private var sessionEnding = false

    private fun buildStarter(scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)): CallSessionStarter {
        every { callIntent.conversationId } returns CONVERSATION_ID
        return CallSessionStarter(
            scope = scope,
            room = mockk(relaxed = true),
            roomCtl = mockk(relaxed = true),
            connectionCoordinator = mockk(relaxed = true),
            onGoingCallStateManager = onGoingCallStateManager,
            publisher = publisher,
            callRingtoneManager = ringtone,
            callDataManager = mockk(relaxed = true),
            contactorCacheManager = mockk(relaxed = true),
            callToChatController = callToChatController,
            messageEncryptor = mockk(relaxed = true),
            callIntent = callIntent,
            e2eeEnable = false,
            mySelfId = SELF_ID,
            createCallMsgConfig = false,
            onRoomIdAssigned = {},
            onE2eeKeyAssigned = {},
            isSessionEnding = { sessionEnding },
        )
    }

    @Test
    fun `1v1 re-cancels with authoritative roomId when initiator already exited`() = runTest {
        every { callIntent.callType } returns CallType.ONE_ON_ONE.type
        val starter = buildStarter()
        publisher.publish { markInitiatorPreConnectCancelled() }

        val sentStartSideEffects = starter.onStartCallSucceeded(
            forWhat = For.Account(CONVERSATION_ID),
            callerId = SELF_ID,
            callType = CallType.ONE_ON_ONE,
            roomId = ROOM_ID,
            createdAt = 0L,
            systemShowTimestamp = 0L,
        )

        // Guard fired: no start-call side effects were sent.
        assertFalse(sentStartSideEffects)
        // Authoritative cancel re-issued against the now-known roomId.
        coVerify(exactly = 1) {
            callToChatController.cancelCall(
                callerId = SELF_ID,
                callRole = CallRole.CALLER,
                type = any(),
                roomId = ROOM_ID,
                conversationId = CONVERSATION_ID,
            )
        }
        coVerify(exactly = 0) {
            callToChatController.hangUpCall(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `group upgrades to end-for-all hangup when initiator already exited`() = runTest {
        every { callIntent.callType } returns CallType.GROUP.type
        val starter = buildStarter()
        publisher.publish { markInitiatorPreConnectCancelled() }

        val sentStartSideEffects = starter.onStartCallSucceeded(
            forWhat = For.Group(CONVERSATION_ID),
            callerId = SELF_ID,
            callType = CallType.GROUP,
            roomId = ROOM_ID,
            createdAt = 0L,
            systemShowTimestamp = 0L,
        )

        assertFalse(sentStartSideEffects)
        // Group exit upgrades to hangup (end-for-all); no remote has joined in window W → empty uid list.
        coVerify(exactly = 1) {
            callToChatController.hangUpCall(
                callerId = SELF_ID,
                callRole = CallRole.CALLER,
                type = any(),
                roomId = ROOM_ID,
                conversationId = CONVERSATION_ID,
                callUidList = emptyList(),
            )
        }
        coVerify(exactly = 0) {
            callToChatController.cancelCall(any(), any(), any(), any(), any())
        }
    }

    /**
     * The session's teardown clears the cancel latch ([OnGoingCallStateManager.endPresence]) before
     * its cleanup releases the room, so a response landing in between sees no latch. The ending
     * session must still skip the start side effects. It sends nothing either: the exit path owns the
     * end signal, and for a group a re-sent hangup would end the call for everyone.
     */
    @Test
    fun `response after the teardown cleared the latch does not ring while the session is ending`() = runTest {
        every { callIntent.callType } returns CallType.ONE_ON_ONE.type
        val starter = buildStarter()
        publisher.publish { markInitiatorPreConnectCancelled() }
        sessionEnding = true
        onGoingCallStateManager.endPresence(SESSION_ID)
        assertFalse(onGoingCallStateManager.isInitiatorPreConnectCancelled())

        val sentStartSideEffects = starter.onStartCallSucceeded(
            forWhat = For.Account(CONVERSATION_ID),
            callerId = SELF_ID,
            callType = CallType.ONE_ON_ONE,
            roomId = ROOM_ID,
            createdAt = 0L,
            systemShowTimestamp = 0L,
        )

        assertFalse(sentStartSideEffects)
        coVerify(exactly = 0) { ringtone.startRingTone(any()) }
        coVerify(exactly = 0) { callToChatController.cancelCall(any(), any(), any(), any(), any()) }
    }

    /** A group caller's LEAVE while the response is still in flight must not become an end-for-all. */
    @Test
    fun `group leave while the response is in flight does not hang up for everyone`() = runTest {
        every { callIntent.callType } returns CallType.GROUP.type
        val starter = buildStarter()
        sessionEnding = true

        val sentStartSideEffects = starter.onStartCallSucceeded(
            forWhat = For.Group(CONVERSATION_ID),
            callerId = SELF_ID,
            callType = CallType.GROUP,
            roomId = ROOM_ID,
            createdAt = 0L,
            systemShowTimestamp = 0L,
        )

        assertFalse(sentStartSideEffects)
        coVerify(exactly = 0) { ringtone.startRingTone(any()) }
        coVerify(exactly = 0) { callToChatController.hangUpCall(any(), any(), any(), any(), any(), any()) }
    }

    /**
     * T5-09 (L12): the re-cancel is best-effort and runs only while the session is alive. The latch is
     * set, the session `Job` is cancelled (the session's last cleanup step) while the start flow awaits the response, then
     * the response arrives: nothing is sent and nothing rings. The await-then-handle coroutine has the
     * shape of `start()` (`scope.launch(IO) { …; handleStartCallResponse(response) }`).
     */
    @Test
    fun `T5-09 late response after the session is cancelled re-cancels nothing and does not ring`() = runTest {
        every { callIntent.callType } returns CallType.ONE_ON_ONE.type
        every { callIntent.action } returns CallIntent.Action.START_CALL
        every { callIntent.callerId } returns SELF_ID
        val sessionScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val starter = buildStarter(sessionScope)
        publisher.publish { markInitiatorPreConnectCancelled() }
        val response = CompletableDeferred<LivekitTemptalk.TTCallResponse?>()
        sessionScope.launch { starter.handleStartCallResponse(response.await()) }
        advanceUntilIdle()

        sessionScope.cancel() // the session's last cleanup step cancels its Job
        response.complete(startResponse(ROOM_ID))
        advanceUntilIdle()

        coVerify(exactly = 0) { callToChatController.cancelCall(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { callToChatController.hangUpCall(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { ringtone.startRingTone(any()) }
        assertNull(onGoingCallStateManager.getCurrentRoomId())
    }

    private fun startResponse(roomId: String?): LivekitTemptalk.TTCallResponse = mockk(relaxed = true) {
        every { base.status } returns 0
        every { body.roomId } returns roomId
    }

    private companion object {
        const val SESSION_ID = 1L
        const val SELF_ID = "self-uid"
        const val ROOM_ID = "room-123"
        const val CONVERSATION_ID = "conv-456"
    }
}
