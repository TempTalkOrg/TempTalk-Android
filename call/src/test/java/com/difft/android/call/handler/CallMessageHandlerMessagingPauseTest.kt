package com.difft.android.call.handler

import android.os.Looper
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallType
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallToChatController
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.response.RoomState
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.test.rules.GlobalStaticMockRule
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import dagger.Lazy
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds






@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallMessageHandlerMessagingPauseTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule(testUserId = SELF_ID)

    private val messaging = TestMessagingPause().open()
    private val callDataManager = CallDataManager()
    private val chat = mockk<LCallToChatController>(relaxed = true)
    private val callService = mockk<LCallHttpService>()
    private val dbRoomStore = mockk<DBRoomStore>(relaxed = true)
    private lateinit var handler: CallMessageHandler

    @Before
    fun setUp() {
        mockkObject(LCallManager)
        every { LCallManager.startIncomingCallService(any()) } answers { ringCount++ }
        every { LCallManager.stopIncomingCallService(any(), any()) } just Runs
        every { LCallManager.dismissCriticalAlert(any()) } just Runs
        val httpClient = mockk<ChativeHttpClient> {
            every { getService(LCallHttpService::class.java) } returns callService
        }
        handler = CallMessageHandler(
            callHttpClient = httpClient,
            callDataManager = callDataManager,
            onGoingCallStateManager = OnGoingCallStateManager(),
            inComingCallStateManager = InComingCallStateManager(),
            contactorCacheManager = mockk(relaxed = true),
            callToChatController = chat,
            groupRepo = mockk(relaxed = true),
            dbRoomStore = dbRoomStore,
            callSessionCommands = Lazy { mockk(relaxed = true) },
            criticalAlertManager = mockk(relaxed = true),
            messagingGate = messaging.gate,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `T13 the pause waits until an admitted incoming call's record is stored`() = runBlocking<Unit> {
        val stored = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { chat.saveReceivedCallTextMessage(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            stored.complete(Unit)
            release.await()
        }

        receive(callingMessage())
        messaging.pauseMessaging()

        withTimeout(5.seconds) { stored.await() }
        assertFalse(
            messaging.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 200.milliseconds),
            "the call record is still being written",
        )
        release.complete(Unit)
        assertTrue(messaging.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 5.seconds))
        coVerify(exactly = 1) {
            chat.saveReceivedCallTextMessage(CallActionType.JOINED, any(), any(), CALL_TIMESTAMP, any(), any(), any())
        }
        
        coVerify(exactly = 0) { callService.checkCall(any()) }
        verify(exactly = 0) { LCallManager.startIncomingCallService(any()) }
    }

    @Test
    fun `T13 a live call does not ring when the pause begins during checkCall`() = runBlocking<Unit> {
        val checked = CompletableDeferred<Unit>()
        coEvery { callService.checkCall(ROOM_ID) } coAnswers {
            messaging.pauseMessaging()
            checked.complete(Unit)
            liveCall()
        }

        receive(callingMessage())

        withTimeout(5.seconds) { checked.await() }
        assertTrue(messaging.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 5.seconds))
        
        assertTrue(pumpMainUntil(5_000) { callDataManager.getCallData(ROOM_ID) != null })
        pumpMainUntil(300) { false }
        verify(exactly = 0) { LCallManager.startIncomingCallService(any()) }
    }

    @Test
    fun `a live call still rings while messaging is open`() {
        coEvery { callService.checkCall(ROOM_ID) } returns liveCall()

        receive(callingMessage())

        assertTrue(pumpMainUntil(5_000) { rings() == 1 })
    }

    @Test
    fun `the pause waits for a joined message's critical-alert room write`() = runBlocking<Unit> {
        callDataManager.addCallData(oneOnOne())
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        every { dbRoomStore.clearCriticalAlert(PEER_ID) } answers {
            writing.complete(Unit)
            runBlocking { release.await() }
        }

        receive(joinedMessage())
        messaging.pauseMessaging()

        withTimeout(5.seconds) { writing.await() }
        assertFalse(messaging.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 200.milliseconds))
        release.complete(Unit)
        assertTrue(messaging.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 5.seconds))
    }

    private var ringCount = 0

    private fun rings(): Int = ringCount

    
    private fun pumpMainUntil(timeoutMs: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return true
            Thread.sleep(10)
        }
        return done()
    }

    
    private fun receive(message: SignalServiceDataClass) {
        val admission: MessagingTicket = assertNotNull(messaging.gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        admission.use { handler.handleCallMessage(message, it) }
    }

    private fun callingMessage(): SignalServiceDataClass {
        val calling = SignalServiceProtos.CallMessage.Calling.newBuilder()
            .setRoomId(ROOM_ID)
            .setCaller(PEER_ID)
            .setCreateCallMsg(true)
            .setControlType(CallActionType.START.type)
            .setTimestamp(CALL_TIMESTAMP)
            .setConversationId(SignalServiceProtos.ConversationId.newBuilder().setNumber(PEER_ID))
        return message(SignalServiceProtos.CallMessage.newBuilder().setCalling(calling).build())
    }

    private fun joinedMessage(): SignalServiceDataClass {
        val joined = SignalServiceProtos.CallMessage.Joined.newBuilder().setRoomId(ROOM_ID)
        return message(SignalServiceProtos.CallMessage.newBuilder().setJoined(joined).build())
    }

    private fun message(callMessage: SignalServiceProtos.CallMessage): SignalServiceDataClass {
        val envelope = SignalServiceProtos.Envelope.newBuilder()
            .setSource(PEER_ID)
            .setSourceDevice(1)
            .setRoomId(ROOM_ID)
            .setTimestamp(CALL_TIMESTAMP)
            .setSystemShowTimestamp(CALL_TIMESTAMP)
            .build()
        val content = SignalServiceProtos.Content.newBuilder().setCallMessage(callMessage).build()
        return mockk(relaxed = true) {
            every { signalServiceEnvelope } returns envelope
            every { signalServiceContent } returns content
            every { senderId } returns PEER_ID
        }
    }

    private fun liveCall() = BaseResponse(
        ver = 1,
        status = CallMessageHandler.RESPONSE_STATUS_SUCCESS,
        reason = null,
        data = RoomState(createdAt = CALL_TIMESTAMP, anotherDeviceJoined = false, userStopped = false),
    )

    private fun oneOnOne() = CallData(
        type = CallType.ONE_ON_ONE.type,
        version = 0,
        createdAt = 0L,
        roomId = ROOM_ID,
        caller = CallDataCaller(uid = PEER_ID, did = 1),
        conversation = PEER_ID,
        encMeta = null,
    )

    private companion object {
        const val ROOM_ID = "room-1382"
        const val SELF_ID = "+10001"
        const val PEER_ID = "+10002"
        const val CALL_TIMESTAMP = 1_700_000_000_000L
    }
}
