package com.difft.android.call.handler

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallType
import com.difft.android.call.LCallManager
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.CriticalAlertManager
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.command.CallSessionCommands
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.test.rules.GlobalStaticMockRule
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import dagger.Lazy
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * Where [CallMessageHandler] sends a remote end: a CALLEND / HANGUP / REJECT for the call this device
 * is in goes straight to the live session through [CallSessionCommands]; a signal for the call the
 * incoming page is ringing for stays on `OnGoingCallStateManager.controlMessage`, which only that
 * page reads. A reject from the user's own other device never ends this device's call.
 * Robolectric only so `LCallManager` (whose object init reads `ApplicationHelper`) can be stubbed.
 * Rows: T4-16, T4-17, T5-22.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallMessageHandlerControlRoutingTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule(testUserId = SELF_ID)

    private val ogsm = OnGoingCallStateManager()
    private val incoming = InComingCallStateManager()
    private val callDataManager = CallDataManager()
    private val commands = mockk<CallSessionCommands>(relaxed = true)
    private val criticalAlertManager = mockk<CriticalAlertManager>(relaxed = true)
    private lateinit var handler: CallMessageHandler

    @Before
    fun setUp() {
        mockkObject(LCallManager)
        every { LCallManager.stopIncomingCallService(any(), any()) } just Runs
        handler = CallMessageHandler(
            callHttpClient = mockk(relaxed = true),
            callDataManager = callDataManager,
            onGoingCallStateManager = ogsm,
            inComingCallStateManager = incoming,
            contactorCacheManager = mockk(relaxed = true),
            callToChatController = mockk(relaxed = true),
            groupRepo = mockk(relaxed = true),
            dbRoomStore = mockk(relaxed = true),
            criticalAlertManager = criticalAlertManager,
            callSessionCommands = Lazy { commands },
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `T4-16 a server call-end for the ongoing call goes to the session, not to controlMessage`() {
        OgsmTestState.enterCall(ogsm, ROOM_ID)

        handler.handleCallEndNotification(ROOM_ID)

        verify(exactly = 1) { commands.onRemoteControl(CallActionType.CALLEND, ROOM_ID) }
        verify(exactly = 1) { commands.onRemoteControl(any(), any()) }
        assertNull(ogsm.controlMessage.value)
    }

    @Test
    fun `T4-17 a hangup for the call the incoming page rings for stays on controlMessage`() {
        OgsmTestState.enterCall(ogsm, ROOM_ID)
        incoming.setIsActivityShowing(true)

        handler.handleHangupMessage(callMessage(), OTHER_ROOM_ID)

        assertEquals(OnGoingCallStateManager.ControlMessage(CallActionType.HANGUP, OTHER_ROOM_ID), ogsm.controlMessage.value)
        verify(exactly = 0) { commands.onRemoteControl(any(), any()) }
    }

    @Test
    fun `T5-22 a peer reject of the ongoing 1v1 call reaches the session once and drops its call data`() {
        OgsmTestState.enterCall(ogsm, ROOM_ID)
        callDataManager.addCallData(oneOnOne())

        handler.handleRejectMessage(message(senderId = PEER_ID), callMessage(), ROOM_ID)

        verify(exactly = 1) { commands.onRemoteControl(CallActionType.REJECT, ROOM_ID) }
        verify(exactly = 1) { commands.onRemoteControl(any(), any()) }
        assertNull(callDataManager.getCallData(ROOM_ID))
    }

    @Test
    fun `T5-22 a reject from the user's own other device never ends the call here`() {
        OgsmTestState.enterCall(ogsm, ROOM_ID)

        handler.handleRejectMessage(message(senderId = SELF_ID), callMessage(), ROOM_ID)

        verify(exactly = 0) { commands.onRemoteControl(any(), any()) }
    }

    @Test
    fun `T5-22 a hangup of the ongoing call reaches the session once`() {
        OgsmTestState.enterCall(ogsm, ROOM_ID)

        handler.handleHangupMessage(callMessage(), ROOM_ID)

        verify(exactly = 1) { commands.onRemoteControl(CallActionType.HANGUP, ROOM_ID) }
        verify(exactly = 1) { commands.onRemoteControl(any(), any()) }
    }

    @Test
    fun `1387 a caller cancel dismisses the critical alerts of that room only`() {
        handler.handleCancelMessage(callMessage(), ROOM_ID)

        verify(exactly = 1) { criticalAlertManager.dismissForRoom(ROOM_ID) }
        verify(exactly = 1) { criticalAlertManager.dismissForRoom(any()) }
    }

    @Test
    fun `1387 a server call end dismisses the critical alerts of that room, also when it is not the ongoing call`() {
        handler.handleCallEndNotification(ROOM_ID)

        verify(exactly = 1) { criticalAlertManager.dismissForRoom(ROOM_ID) }
        verify(exactly = 1) { criticalAlertManager.dismissForRoom(any()) }
    }

    @Test
    fun `1387 a call end without a room id dismisses nothing`() {
        handler.handleCallEndNotification("")

        verify(exactly = 0) { criticalAlertManager.dismissForRoom(any()) }
    }

    @Test
    fun `1387 a hangup keeps its current behaviour and does not touch critical alerts`() {
        handler.handleHangupMessage(callMessage(), ROOM_ID)

        verify(exactly = 0) { criticalAlertManager.dismissForRoom(any()) }
    }

    private fun message(senderId: String) =mockk<SignalServiceDataClass>(relaxed = true) {
        every { this@mockk.senderId } returns senderId
    }

    private fun callMessage() = mockk<SignalServiceProtos.CallMessage>(relaxed = true)

    private fun oneOnOne() = CallData(
        type = CallType.ONE_ON_ONE.type,
        version = 0,
        createdAt = 0L,
        roomId = ROOM_ID,
        caller = CallDataCaller(uid = SELF_ID, did = 1),
        conversation = PEER_ID,
        encMeta = null,
    )

    private companion object {
        const val ROOM_ID = "r1"
        const val OTHER_ROOM_ID = "r9"
        const val SELF_ID = "+10001"
        const val PEER_ID = "+10002"
    }
}
