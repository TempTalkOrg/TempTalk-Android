package com.difft.android.call.session

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.appScope
import com.difft.android.call.LCallToChatController
import com.difft.android.call.R
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.handler.CallExitHandler
import com.difft.android.call.handler.CallExitSession
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.CallRingtoneManager
import com.difft.android.call.session.command.HangupReason
import com.difft.android.call.session.harness.CallIntentFixture
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.state.OngoingCallPublisher
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [CallEndController] with recording collaborators and a real [OnGoingCallStateManager]: the remote
 * end order (foreground-service stop, caller ringback stop, hang-up tone, then the sticky finish
 * request), the out-of-page hang-up guards, the no-page finish path and the exit-context snapshot.
 * The page-less leave and the exit fallback are in [CallEndControllerExitFallbackTest].
 * Rows: T4-01..T4-09, T5-24, T5-25, T5-26. The graph-built rows (toast on main, E2 exit, late
 * collector) are in [CallEndControllerSessionTest].
 */
class CallEndControllerTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `T4-01 remote HANGUP stops the service, then plays the tone, and requests a hang-up finish`() {
        val rig = EndControllerRig().inCall().pageAttached()

        rig.controller.onRemoteControl(CallActionType.HANGUP, ROOM_ID)

        verifyOrder {
            rig.stopForegroundService.invoke(any())
            rig.ringtone.playHangupRingTone()
        }
        verify(exactly = 1) { rig.stopForegroundService.invoke(any()) }
        verify(exactly = 0) { rig.ringtone.stopRingTone(any()) }
        assertEquals(CallFinishRequest(CallFinishRequest.Reason.REMOTE_HANGUP, R.string.call_callee_action_hangup), rig.controller.finishRequest.value)
        assertTrue(rig.ogsm.isInCallEnding())
    }

    @Test
    fun `T4-02 a callee REJECT before connect stops the caller ringback before the tone`() {
        val rig = EndControllerRig(role = CallRole.CALLER).inCall().pageAttached()
        rig.durationSeconds = 0

        rig.controller.onRemoteControl(CallActionType.REJECT, ROOM_ID)

        verifyOrder {
            rig.stopForegroundService.invoke(any())
            rig.ringtone.stopRingTone(tag = "reject: callee reject the call")
            rig.ringtone.playHangupRingTone()
        }
        assertEquals(R.string.call_callee_action_reject, rig.controller.finishRequest.value?.tipRes)
        assertEquals(CallFinishRequest.Reason.REMOTE_REJECT, rig.controller.finishRequest.value?.reason)
    }

    @Test
    fun `T4-03 a CALLEND after the call connected keeps the ringback alone and shows the hang-up tip`() {
        val rig = EndControllerRig(role = CallRole.CALLER).inCall().pageAttached()
        rig.durationSeconds = 5

        rig.controller.onRemoteControl(CallActionType.CALLEND, ROOM_ID)

        verify(exactly = 0) { rig.ringtone.stopRingTone(any()) }
        verify(exactly = 1) { rig.ringtone.playHangupRingTone() }
        assertEquals(
            CallFinishRequest(CallFinishRequest.Reason.REMOTE_CALLEND, R.string.call_callee_action_hangup),
            rig.controller.finishRequest.value,
        )
    }

    @Test
    fun `T4-04 a remote end for another room changes nothing`() {
        val rig = EndControllerRig().inCall()

        rig.controller.onRemoteControl(CallActionType.HANGUP, "room-2")

        verify(exactly = 0) { rig.stopForegroundService.invoke(any()) }
        verify(exactly = 0) { rig.ringtone.playHangupRingTone() }
        assertNull(rig.controller.finishRequest.value)
        assertFalse(rig.ogsm.isInCallEnding())
    }

    @Test
    fun `T4-05 duplicate remote ends play the tone once and keep the first request`() {
        val rig = EndControllerRig().inCall()

        rig.controller.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        val first = rig.controller.finishRequest.value
        rig.controller.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        rig.controller.onRemoteControl(CallActionType.CALLEND, ROOM_ID)

        verify(exactly = 1) { rig.ringtone.playHangupRingTone() }
        verify(exactly = 1) { rig.stopForegroundService.invoke(any()) }
        assertSame(first, rig.controller.finishRequest.value)
        assertEquals(CallFinishRequest.Reason.REMOTE_HANGUP, first?.reason)
    }

    @Test
    fun `T4-06 JOINED and CANCEL are not remote ends`() {
        val rig = EndControllerRig().inCall()

        rig.controller.onRemoteControl(CallActionType.JOINED, ROOM_ID)
        rig.controller.onRemoteControl(CallActionType.CANCEL, ROOM_ID)

        verify(exactly = 0) { rig.stopForegroundService.invoke(any()) }
        verify(exactly = 0) { rig.ringtone.playHangupRingTone() }
        assertNull(rig.controller.finishRequest.value)
        assertFalse(rig.ogsm.isInCallEnding())
    }

    @Test
    fun `T4-07 an out-of-page hang-up runs the exit handler once with LEAVE, with no page attached`() {
        val rig = EndControllerRig().inCall()
        rig.callType.value = CallType.ONE_ON_ONE.type

        rig.controller.hangup(HangupReason.NOTIFICATION)

        val expected = CallExitParams(
            roomId = ROOM_ID,
            callerId = CALLER_ID,
            callRole = CallRole.CALLER,
            callType = CallType.ONE_ON_ONE.type,
            conversationId = "conv-1",
        )
        verify(exactly = 1) { rig.exitHandler.handleExit(expected, CallEndType.LEAVE) }
        verify(exactly = 1) { rig.exitHandler.handleExit(any(), any()) }
    }

    @Test
    fun `T4-08a no published room id drops the hang-up`() {
        val rig = EndControllerRig().inCall(roomId = null)

        rig.controller.hangup(HangupReason.NOTIFICATION)

        verify(exactly = 0) { rig.exitHandler.handleExit(any(), any()) }
        assertNull(rig.controller.finishRequest.value)
    }

    @Test
    fun `T4-08b a hang-up while the call is already ending is dropped`() {
        val rig = EndControllerRig().inCall()
        rig.controller.markEnding()

        rig.controller.hangup(HangupReason.NOTIFICATION)

        verify(exactly = 0) { rig.exitHandler.handleExit(any(), any()) }
    }

    @Test
    fun `T4-08c a second hang-up while the first is in flight is dropped`() {
        val rig = EndControllerRig().inCall()

        rig.controller.hangup(HangupReason.NOTIFICATION)
        rig.controller.hangup(HangupReason.NOTIFICATION)

        verify(exactly = 1) { rig.exitHandler.handleExit(any(), any()) }
    }

    @Test
    fun `T4-09 finish with no page attached shows the tip once and ends the session once`() {
        val rig = EndControllerRig().inCall()

        rig.controller.finish(CallFinishRequest.remote(CallActionType.HANGUP))
        rig.controller.finish(CallFinishRequest.LOCAL)

        verify(exactly = 1) { rig.showToast.invoke(R.string.call_callee_action_hangup) }
        verify(exactly = 1) { rig.showToast.invoke(any()) }
        verify(exactly = 1) { rig.endSessionWithoutUi.invoke() }
        val request = rig.controller.finishRequest.value
        assertEquals(CallFinishRequest.Reason.REMOTE_HANGUP, request?.reason)
        assertEquals(true, request?.handledWithoutUi)
        assertTrue(rig.ogsm.isInCallEnding())
    }

    @Test
    fun `T5-24 a remote end during a pending in-page exit wins and the late local finish is a no-op`() {
        val signaling = TestScope(StandardTestDispatcher())
        every { appScope } returns signaling
        val chat = mockk<LCallToChatController>(relaxed = true)
        val callDataManager = CallDataManager().apply { addCallData(oneOnOneCallData()) }
        val rig = EndControllerRig(
            handlerFactory = { r, ctx ->
                CallExitHandler(
                    session = ConnectedExitSession,
                    callToChatController = chat,
                    onGoingCallStateManager = r.ogsm,
                    publisher = r.publisher,
                    callDataManager = callDataManager,
                    callIntent = r.params.callIntent,
                    callRole = r.params.callRole,
                    context = ctx,
                    onEndCall = { r.controller.finish(CallFinishRequest.LOCAL) },
                )
            },
        ).inCall()
        rig.controller.attachUi()

        // E6: connected 1v1 exit; the hang-up signaling waits on the unstarted appScope.
        rig.controller.requestExit(exitParams(), CallEndType.END)
        assertNull("the page exit has not finished yet", rig.controller.finishRequest.value)

        rig.controller.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        signaling.testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { chat.hangUpCall(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { rig.ringtone.playHangupRingTone() }
        assertEquals(CallFinishRequest.remote(CallActionType.HANGUP), rig.controller.finishRequest.value)
    }

    @Test
    fun `T5-25 the exit context comes from the call intent while the exit params use the live type`() {
        val rig = EndControllerRig().inCall()
        rig.callType.value = CallType.INSTANT.type
        val params = slot<CallExitParams>()
        every { rig.exitHandler.handleExit(capture(params), any()) } returns Unit

        rig.controller.hangup(HangupReason.NOTIFICATION)

        assertEquals(CallType.INSTANT.type, params.captured.callType)
        assertEquals(CallExitContext("conv-1", CallType.ONE_ON_ONE.type), rig.boundContexts.single())
        assertEquals("conv-1", params.captured.conversationId)
    }

    @Test
    fun `the exit handler is built once and only when first used`() {
        val rig = EndControllerRig().inCall()
        assertTrue(rig.boundContexts.isEmpty())

        rig.controller.requestExit(exitParams(), CallEndType.LEAVE)
        rig.controller.requestExit(exitParams(), CallEndType.END)

        assertEquals(1, rig.boundContexts.size)
    }

    @Test
    fun `the last page detaching keeps the exit handler for a page-less hang-up`() {
        val rig = EndControllerRig().inCall()
        rig.controller.attachUi()
        rig.controller.requestExit(exitParams(), CallEndType.LEAVE)
        rig.controller.detachUi()

        rig.controller.hangup(HangupReason.NOTIFICATION)

        verify(exactly = 2) { rig.exitHandler.handleExit(any(), any()) }
        assertEquals(1, rig.boundContexts.size)
        assertNull(rig.controller.finishRequest.value)
    }

    @Test
    fun `T5-27 a timeout with a page requests the no-answer tip with no tone, no service stop and no signaling`() {
        val rig = EndControllerRig().inCall().pageAttached()

        rig.controller.onTimeout(ROOM_ID)

        assertEquals(CallFinishRequest.TIMEOUT, rig.controller.finishRequest.value)
        assertEquals(R.string.call_callee_action_noanswer, rig.controller.finishRequest.value?.tipRes)
        assertEquals(false, rig.controller.finishRequest.value?.handledWithoutUi)
        assertTrue(rig.ogsm.isInCallEnding())
        verify(exactly = 0) { rig.ringtone.playHangupRingTone() }
        verify(exactly = 0) { rig.ringtone.stopRingTone(any()) }
        verify(exactly = 0) { rig.stopForegroundService.invoke(any()) }
        verify(exactly = 0) { rig.exitHandler.handleExit(any(), any()) }
        verify(exactly = 0) { rig.endSessionWithoutUi.invoke() }
    }

    @Test
    fun `T5-27 a timeout with no page shows the tip as a toast and ends the session`() {
        val rig = EndControllerRig().inCall()

        rig.controller.onTimeout(ROOM_ID)

        verify(exactly = 1) { rig.showToast.invoke(R.string.call_callee_action_noanswer) }
        verify(exactly = 1) { rig.endSessionWithoutUi.invoke() }
        assertEquals(true, rig.controller.finishRequest.value?.handledWithoutUi)
        verify(exactly = 0) { rig.ringtone.playHangupRingTone() }
    }

    @Test
    fun `T5-27 a timeout for another room, outside a call or while ending changes nothing`() {
        val otherRoom = EndControllerRig().inCall().pageAttached()
        otherRoom.controller.onTimeout("room-2")
        assertNull(otherRoom.controller.finishRequest.value)

        val notInCall = EndControllerRig().pageAttached()
        notInCall.controller.onTimeout(ROOM_ID)
        assertNull(notInCall.controller.finishRequest.value)

        val ending = EndControllerRig().inCall().pageAttached()
        ending.controller.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        val first = ending.controller.finishRequest.value
        ending.controller.onTimeout(ROOM_ID)
        assertSame(first, ending.controller.finishRequest.value)
    }

    @Test
    fun `T5-26 a recreated page sees the same sticky request and the tone is not replayed`() {
        val rig = EndControllerRig().inCall()
        rig.controller.attachUi()
        rig.controller.onRemoteControl(CallActionType.HANGUP, ROOM_ID)
        val request = rig.controller.finishRequest.value

        rig.controller.detachUi()
        rig.controller.attachUi()

        assertSame(request, rig.controller.finishRequest.value)
        assertEquals(false, request?.handledWithoutUi)
        verify(exactly = 1) { rig.ringtone.playHangupRingTone() }
        verify(exactly = 0) { rig.endSessionWithoutUi.invoke() }
        verify(exactly = 0) { rig.showToast.invoke(any()) }
    }

    private fun exitParams() = CallExitParams(
        roomId = ROOM_ID,
        callerId = CALLER_ID,
        callRole = CallRole.CALLER,
        callType = CallType.ONE_ON_ONE.type,
        conversationId = "conv-1",
    )

    private fun oneOnOneCallData() = CallData(
        type = CallType.ONE_ON_ONE.type,
        version = 0,
        createdAt = 0L,
        roomId = ROOM_ID,
        caller = CallDataCaller(uid = CALLER_ID, did = 1),
        conversation = "conv-1",
        encMeta = null,
    )

    /** Connected call whose end-call RTM settles at once. */
    private object ConnectedExitSession : CallExitSession {
        override val callStatus = MutableStateFlow(CallStatus.CONNECTED)
        override fun getCurrentCallUidList(): List<String> = listOf("+10002")
        override fun sendEndCall(onComplete: (Boolean) -> Unit) = onComplete(true)
    }

    companion object {
        const val ROOM_ID = "r1"
        const val CALLER_ID = "caller-1"
    }
}

/**
 * A [CallEndController] over recording collaborators and a real [OnGoingCallStateManager]. Also
 * used by the projection tests that need the controller's `markEnding`.
 */
internal class EndControllerRig(
    role: CallRole = CallRole.CALLER,
    val ogsm: OnGoingCallStateManager = OnGoingCallStateManager(),
    handlerFactory: ((EndControllerRig, CallExitContext) -> CallExitHandler)? = null,
) {
    /** The controller's fenced writer; owns the projection under the detached test session id. */
    val publisher = OngoingCallPublisher(OgsmTestState.DETACHED_SESSION_ID, ogsm).apply { bind() }
    val ringtone = mockk<CallRingtoneManager>(relaxed = true)
    val stopForegroundService = mockk<(String) -> Unit>(relaxed = true)
    val showToast = mockk<(Int) -> Unit>(relaxed = true)
    val endSessionWithoutUi = mockk<() -> Unit>(relaxed = true)
    var durationSeconds = 0L
    val callType = MutableStateFlow(CallType.ONE_ON_ONE.type)
    val boundContexts = mutableListOf<CallExitContext>()

    /** The session main scope stand-in; the exit fallback runs on its scheduler. */
    val scope = TestScope(StandardTestDispatcher())

    /** The handler the default factory hands out; its `context` is the last bound one. */
    val exitHandler = mockk<CallExitHandler>(relaxed = true) {
        every { context } answers { boundContexts.last() }
    }

    val params = CallSessionParams(
        e2eeEnable = false,
        callIntent = CallIntentFixture.build(callerId = CallEndControllerTest.CALLER_ID),
        callConfig = CallConfig(),
        callRole = role,
        initialVoicePreset = VoicePreset.ORIGINAL,
    )

    val controller: CallEndController = CallEndController(
        params = params,
        ogsm = ogsm,
        publisher = publisher,
        ringtone = ringtone,
        callDurationSeconds = { durationSeconds },
        currentCallType = { callType.value },
        stopForegroundService = stopForegroundService,
        exitHandlerFactory = { ctx ->
            boundContexts += ctx
            handlerFactory?.invoke(this, ctx) ?: exitHandler
        },
        endSessionWithoutUi = endSessionWithoutUi,
        showToast = showToast,
        scope = scope,
    )

    /** A call page is attached, so a finish is left to the page. */
    fun pageAttached(): EndControllerRig = apply { controller.attachUi() }

    /** The page is up for [roomId]. */
    fun inCall(roomId: String? = CallEndControllerTest.ROOM_ID): EndControllerRig = apply {
        OgsmTestState.enterCall(ogsm, roomId)
    }
}
