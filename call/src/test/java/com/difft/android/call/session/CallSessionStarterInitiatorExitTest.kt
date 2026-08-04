package com.difft.android.call.session

import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.state.OnGoingCallStateManager
import difft.android.messageserialization.For
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
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
    private val callToChatController = mockk<LCallToChatController>(relaxed = true)
    private val callIntent = mockk<CallIntent>(relaxed = true)

    private fun buildStarter(): CallSessionStarter {
        every { callIntent.conversationId } returns CONVERSATION_ID
        return CallSessionStarter(
            scope = CoroutineScope(Dispatchers.Unconfined),
            room = mockk(relaxed = true),
            roomCtl = mockk(relaxed = true),
            connectionCoordinator = mockk(relaxed = true),
            onGoingCallStateManager = onGoingCallStateManager,
            callRingtoneManager = mockk(relaxed = true),
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
        )
    }

    @Test
    fun `1v1 re-cancels with authoritative roomId when initiator already exited`() = runTest {
        every { callIntent.callType } returns CallType.ONE_ON_ONE.type
        val starter = buildStarter()
        onGoingCallStateManager.markInitiatorPreConnectCancelled()

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
        onGoingCallStateManager.markInitiatorPreConnectCancelled()

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

    private companion object {
        const val SELF_ID = "self-uid"
        const val ROOM_ID = "room-123"
        const val CONVERSATION_ID = "conv-456"
    }
}
