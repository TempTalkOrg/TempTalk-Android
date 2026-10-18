package com.difft.android.call.handler

import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.appScope
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.CallStatus
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.session.CallExitContext
import com.difft.android.call.session.harness.CallIntentFixture
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.state.OngoingCallPublisher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exit semantics of [CallExitHandler] around the outbound hangup control message.
 *
 * An instant meeting has no owning conversation — `conversationId` is null by design all the way
 * down ([com.difft.android.websocket.api.util.CallMessageCreator] resolves instant recipients from
 * the participant uid list instead). A blanket "conversationId must be present" precondition
 * therefore swallowed end-for-all on instant meetings entirely, while 1v1 and group genuinely need
 * it to address the message. Both halves of that split are pinned here, together with the meeting
 * type the handler trusts and the point at which the uid list is sampled.
 *
 * The handler is owned by the call session (row T4-25): every exit branch E1-E7 ends through
 * `onEndCall`, and E1 (the initiator leaving before the call connects) sets the
 * pre-connect cancel latch synchronously, before its signaling coroutine is even started. A
 * signaling send that throws still ends the call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallExitHandlerTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var session: FakeCallExitSession
    private lateinit var callToChatController: LCallToChatController
    private lateinit var callDataManager: CallDataManager

    private var endCallCount = 0
    private val ogsm = OnGoingCallStateManager()
    private val publisher = OngoingCallPublisher(OgsmTestState.DETACHED_SESSION_ID, ogsm).apply { bind() }

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns testScope

        session = FakeCallExitSession(remoteUids = listOf(REMOTE_UID))
        callToChatController = mockk(relaxed = true)
        // Real instance: a dependency-free holder, so the handler's own removeCallData runs for real.
        callDataManager = CallDataManager()
        endCallCount = 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `instant meeting sends hangup even though it has no conversationId`() {
        val handler = buildHandler(callDataType = CallType.INSTANT.type, conversationId = null)

        handler.handleExit(exitParams(CallType.INSTANT.type), CallEndType.END)

        coVerify(exactly = 1) {
            callToChatController.hangUpCall(
                callerId = CALLER_ID,
                callRole = CallRole.CALLER,
                type = CallType.INSTANT.type,
                roomId = ROOM_ID,
                conversationId = null,
                callUidList = any(),
            )
        }
    }

    @Test
    fun `group call without conversationId ends locally instead of sending hangup`() {
        val handler = buildHandler(callDataType = CallType.GROUP.type, conversationId = null)

        handler.handleExit(exitParams(CallType.GROUP.type), CallEndType.END)

        coVerify(exactly = 0) { callToChatController.hangUpCall(any(), any(), any(), any(), any(), any()) }
        assertTrue("call must still be torn down locally", endCallCount > 0)
    }

    /**
     * `CallExitParams.callType` is built from the intent-seeded state holder, which stays empty
     * until [com.difft.android.call.session.CallTypeCoordinator] resolves the server type; the
     * resolved value lands on the `CallData` entry, so that is what decides instant-ness here.
     */
    @Test
    fun `resolved call data type wins over an unresolved params type`() {
        val handler = buildHandler(
            callDataType = CallType.INSTANT.type,
            conversationId = null,
            // Intent-seeded fallback still carries the pre-join type; it must not win.
            fallbackType = CallType.ONE_ON_ONE.type,
        )

        handler.handleExit(exitParams(callType = ""), CallEndType.END)

        coVerify(exactly = 1) {
            callToChatController.hangUpCall(
                callerId = any(),
                callRole = any(),
                type = CallType.INSTANT.type,
                roomId = ROOM_ID,
                conversationId = null,
                callUidList = any(),
            )
        }
    }

    @Test
    fun `participant uid list is sampled before the end-call rtm empties the room`() {
        val handler = buildHandler(callDataType = CallType.INSTANT.type, conversationId = null)

        handler.handleExit(exitParams(CallType.INSTANT.type), CallEndType.END)

        coVerify(exactly = 1) {
            callToChatController.hangUpCall(
                callerId = any(),
                callRole = any(),
                type = any(),
                roomId = any(),
                conversationId = any(),
                callUidList = listOf(REMOTE_UID),
            )
        }
    }

    @Test
    fun `leaving a multi-party call sends no control message`() {
        val handler = buildHandler(callDataType = CallType.INSTANT.type, conversationId = null)

        handler.handleExit(exitParams(CallType.INSTANT.type), CallEndType.LEAVE)

        coVerify(exactly = 0) { callToChatController.hangUpCall(any(), any(), any(), any(), any(), any()) }
        assertTrue("call must still be torn down locally", endCallCount > 0)
    }

    @Test
    fun `T4-25 E1 initiator exit before connect latches the cancel before any signaling launches`() {
        val pending = TestScope(StandardTestDispatcher())
        every { appScope } returns pending
        session.status.value = CallStatus.CALLING
        OgsmTestState.enterCall(ogsm, ROOM_ID)
        val handler = buildHandler(
            callDataType = CallType.ONE_ON_ONE.type,
            conversationId = "conv-1",
            callIntent = CallIntentFixture.build(CallIntent.Action.START_CALL, callerId = CALLER_ID),
        )

        handler.handleExit(exitParams(CallType.ONE_ON_ONE.type), CallEndType.LEAVE)

        assertTrue("latched synchronously", ogsm.isInitiatorPreConnectCancelled())
        coVerify(exactly = 0) { callToChatController.cancelCall(any(), any(), any(), any(), any()) }
        assertEquals(0, endCallCount)

        pending.testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { callToChatController.cancelCall(CALLER_ID, CallRole.CALLER, CallType.ONE_ON_ONE.type, ROOM_ID, "conv-1") }
        assertEquals(1, endCallCount)
    }

    @Test
    fun `T4-25 E2 no call data ends at once without signaling`() {
        val handler = CallExitHandler(
            session = session,
            callToChatController = callToChatController,
            onGoingCallStateManager = ogsm,
            publisher = publisher,
            callDataManager = callDataManager,
            callIntent = mockk(relaxed = true),
            callRole = CallRole.CALLER,
            context = CallExitContext(conversationId = "conv-1", callType = CallType.ONE_ON_ONE.type),
            onEndCall = { endCallCount++ },
        )

        handler.handleExit(exitParams(CallType.ONE_ON_ONE.type), CallEndType.END)

        assertEquals(1, endCallCount)
        coVerify(exactly = 0) { callToChatController.hangUpCall(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { callToChatController.cancelCall(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `T4-25 E4 a non-initiator still calling sends cancel for the published room, then ends`() {
        session.status.value = CallStatus.CALLING
        OgsmTestState.enterCall(ogsm, ROOM_ID)
        val handler = buildHandler(
            callDataType = CallType.ONE_ON_ONE.type,
            conversationId = "conv-1",
            callIntent = CallIntentFixture.build(CallIntent.Action.JOIN_CALL),
        )

        handler.handleExit(exitParams(CallType.ONE_ON_ONE.type), CallEndType.LEAVE)

        coVerify(exactly = 1) { callToChatController.cancelCall(any(), CallRole.CALLER, CallType.ONE_ON_ONE.type, ROOM_ID, "conv-1") }
        assertEquals(1, endCallCount)
    }

    @Test
    fun `a connected hang-up whose send throws still ends the call`() {
        coEvery { callToChatController.hangUpCall(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("send failed")
        val handler = buildHandler(callDataType = CallType.INSTANT.type, conversationId = null)

        handler.handleExit(exitParams(CallType.INSTANT.type), CallEndType.END)

        assertEquals(1, endCallCount)
    }

    @Test
    fun `a ringing cancel whose send throws still ends the call`() {
        coEvery { callToChatController.cancelCall(any(), any(), any(), any(), any()) } throws IllegalStateException("send failed")
        session.status.value = CallStatus.CALLING
        OgsmTestState.enterCall(ogsm, ROOM_ID)
        val handler = buildHandler(
            callDataType = CallType.ONE_ON_ONE.type,
            conversationId = "conv-1",
            callIntent = CallIntentFixture.build(CallIntent.Action.JOIN_CALL),
        )

        handler.handleExit(exitParams(CallType.ONE_ON_ONE.type), CallEndType.LEAVE)

        assertEquals(1, endCallCount)
    }

    @Test
    fun `an initiator's pre-connect end whose send throws still ends the call`() {
        coEvery { callToChatController.cancelCall(any(), any(), any(), any(), any()) } throws IllegalStateException("send failed")
        session.status.value = CallStatus.CALLING
        val handler = buildHandler(
            callDataType = CallType.ONE_ON_ONE.type,
            conversationId = "conv-1",
            callIntent = CallIntentFixture.build(CallIntent.Action.START_CALL),
        )

        handler.handleExit(exitParams(CallType.ONE_ON_ONE.type), CallEndType.LEAVE)

        assertEquals(1, endCallCount)
    }

    @Test
    fun `T4-25 E5 joining ends at once without signaling`() {
        session.status.value = CallStatus.JOINING
        val handler = buildHandler(callDataType = CallType.ONE_ON_ONE.type, conversationId = "conv-1")

        handler.handleExit(exitParams(CallType.ONE_ON_ONE.type), CallEndType.LEAVE)

        assertEquals(1, endCallCount)
        coVerify(exactly = 0) { callToChatController.cancelCall(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { callToChatController.hangUpCall(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `T4-25 E7 any other status ends at once without signaling`() {
        session.status.value = CallStatus.DISCONNECTED
        val handler = buildHandler(callDataType = CallType.ONE_ON_ONE.type, conversationId = "conv-1")

        handler.handleExit(exitParams(CallType.ONE_ON_ONE.type), CallEndType.LEAVE)

        assertEquals(1, endCallCount)
        coVerify(exactly = 0) { callToChatController.hangUpCall(any(), any(), any(), any(), any(), any()) }
    }

    private fun buildHandler(
        callDataType: String?,
        conversationId: String?,
        fallbackType: String = callDataType.orEmpty(),
        callIntent: CallIntent = mockk(relaxed = true),
    ): CallExitHandler {
        callDataManager.addCallData(
            CallData(
                type = callDataType,
                version = 0,
                createdAt = 0L,
                roomId = ROOM_ID,
                caller = CallDataCaller(uid = CALLER_ID, did = 1),
                conversation = conversationId,
                encMeta = null,
            )
        )
        return CallExitHandler(
            session = session,
            callToChatController = callToChatController,
            onGoingCallStateManager = ogsm,
            publisher = publisher,
            callDataManager = callDataManager,
            callIntent = callIntent,
            callRole = CallRole.CALLER,
            context = CallExitContext(conversationId = conversationId, callType = fallbackType),
            onEndCall = { endCallCount++ },
        )
    }

    /**
     * The session side of the exit decision. Remote peers leave the room as soon as the end-call RTM
     * lands, which is what empties the uid list: sampling it after that point is the race the
     * handler must not lose.
     */
    private class FakeCallExitSession(private var remoteUids: List<String>) : CallExitSession {
        val status = MutableStateFlow(CallStatus.CONNECTED)
        override val callStatus: StateFlow<CallStatus> = status
        override fun getCurrentCallUidList(): List<String> = remoteUids
        override fun sendEndCall(onComplete: (Boolean) -> Unit) {
            remoteUids = emptyList()
            onComplete(true)
        }
    }

    private fun exitParams(callType: String) = CallExitParams(
        roomId = ROOM_ID,
        callerId = CALLER_ID,
        callRole = CallRole.CALLER,
        callType = callType,
        conversationId = null,
    )

    private companion object {
        const val ROOM_ID = "room-1"
        const val CALLER_ID = "+10001"
        const val REMOTE_UID = "+10002"
    }
}
