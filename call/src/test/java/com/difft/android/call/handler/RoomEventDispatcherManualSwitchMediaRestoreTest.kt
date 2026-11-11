package com.difft.android.call.handler

import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.data.CallStatus
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import livekit.LivekitTemptalk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class RoomEventDispatcherManualSwitchMediaRestoreTest {

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule()

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var f: RoomEventDispatcherFixture
    private val room get() = f.room
    private val roomCtl get() = f.roomCtl
    private val coordinator get() = f.connectionCoordinator
    private val timeoutMonitor get() = f.timeoutMonitor

    
    private val setMicCalls get() = f.setMicCalls

    
    private val setCameraCalls get() = f.setCameraCalls

    
    private val startCallTimerCalls get() = f.startCallTimerCalls

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext())
        f = RoomEventDispatcherFixture(CoroutineScope(dispatcherRule.testDispatcher))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun buildDispatcher(callType: String): RoomEventDispatcher = f.build(callType)

    private fun invokeRestore(dispatcher: RoomEventDispatcher) {
        val method = RoomEventDispatcher::class.java
            .getDeclaredMethod("restoreMediaAfterServerSwitch")
        method.isAccessible = true
        method.invoke(dispatcher)
    }

    private fun dispatch(dispatcher: RoomEventDispatcher, event: RoomEvent) = f.dispatch(dispatcher, event)

    private fun armCallTimerGate(
        dispatcher: RoomEventDispatcher,
        remote: Participant? = mockk<RemoteParticipant>(relaxed = true),
    ) {
        val method = RoomEventDispatcher::class.java
            .getDeclaredMethod("armCallTimerGate", Participant::class.java)
        method.isAccessible = true
        method.invoke(dispatcher, remote)
    }

    private fun localTrackSubscribed(source: Track.Source): RoomEvent.LocalTrackSubscribed {
        val publication = mockk<LocalTrackPublication>(relaxed = true)
        every { publication.source } returns source
        return RoomEvent.LocalTrackSubscribed(
            room,
            publication,
            mockk<LocalParticipant>(relaxed = true),
        )
    }

    private fun stubMediaState(mic: Boolean, camera: Boolean) {
        every { roomCtl.micEnabled } returns MutableStateFlow(mic)
        every { roomCtl.cameraEnabled } returns MutableStateFlow(camera)
    }

    private fun stubAutoPublishSilence(enabled: Boolean) {
        val resp = mockk<LivekitTemptalk.TTCallResponse>(relaxed = true)
        every { resp.callOptions.autoPublishSilenceAudio } returns enabled
        every { room.ttCallResp } returns resp
    }

    
    @Test
    fun `group manual switch with mic on and camera on re-enables both`() {
        stubMediaState(mic = true, camera = true)

        invokeRestore(buildDispatcher(CallType.GROUP.type))

        assertEquals(listOf(Triple(true, false, false)), setMicCalls)
        assertEquals(listOf(true), setCameraCalls)
    }

    
    @Test
    fun `group manual switch with mic on and camera off leaves camera off`() {
        stubMediaState(mic = true, camera = false)

        invokeRestore(buildDispatcher(CallType.GROUP.type))

        assertEquals(listOf(Triple(true, false, false)), setMicCalls)
        assertEquals(emptyList<Boolean>(), setCameraCalls)
    }

    
    
    @Test
    fun `group manual switch with mic off publishes muted silence when server requests it`() {
        stubMediaState(mic = false, camera = false)
        stubAutoPublishSilence(enabled = true)

        invokeRestore(buildDispatcher(CallType.GROUP.type))

        assertEquals(listOf(Triple(true, true, false)), setMicCalls)
        assertEquals(emptyList<Boolean>(), setCameraCalls)
    }

    
    @Test
    fun `group manual switch with mic off and no silence option publishes nothing`() {
        stubMediaState(mic = false, camera = false)
        stubAutoPublishSilence(enabled = false)

        invokeRestore(buildDispatcher(CallType.GROUP.type))

        assertEquals(emptyList<Triple<Boolean, Boolean, Boolean>>(), setMicCalls)
        assertEquals(emptyList<Boolean>(), setCameraCalls)
    }

    
    
    @Test
    fun `one-on-one manual switch with mic off does not force unmute`() {
        stubMediaState(mic = false, camera = false)

        invokeRestore(buildDispatcher(CallType.ONE_ON_ONE.type))

        assertEquals(emptyList<Triple<Boolean, Boolean, Boolean>>(), setMicCalls)
        assertEquals(emptyList<Boolean>(), setCameraCalls)
    }

    
    @Test
    fun `one-on-one manual switch with mic on and camera on re-enables both`() {
        stubMediaState(mic = true, camera = true)

        invokeRestore(buildDispatcher(CallType.ONE_ON_ONE.type))

        assertEquals(listOf(Triple(true, false, false)), setMicCalls)
        assertEquals(listOf(true), setCameraCalls)
    }

    
    
    
    @Test
    fun `participant disconnect while switching server does not arm timeout`() {
        every { roomCtl.callStatus } returns MutableStateFlow(CallStatus.SWITCHING_SERVER)
        val dispatcher = buildDispatcher(CallType.ONE_ON_ONE.type)

        dispatch(dispatcher, RoomEvent.ParticipantDisconnected(room, mockk<RemoteParticipant>(relaxed = true)))

        verify(exactly = 0) { timeoutMonitor.onParticipantDisconnected(any()) }
    }

    
    @Test
    fun `participant disconnect during failover retry does not arm timeout`() {
        every { roomCtl.callStatus } returns MutableStateFlow(CallStatus.RECONNECTING)
        every { coordinator.isRetryUrlConnecting } returns true
        val dispatcher = buildDispatcher(CallType.ONE_ON_ONE.type)

        dispatch(dispatcher, RoomEvent.ParticipantDisconnected(room, mockk<RemoteParticipant>(relaxed = true)))

        verify(exactly = 0) { timeoutMonitor.onParticipantDisconnected(any()) }
    }

    
    
    @Test
    fun `participant disconnect while connected arms one-on-one timeout`() {
        every { roomCtl.callStatus } returns MutableStateFlow(CallStatus.CONNECTED)
        every { coordinator.isRetryUrlConnecting } returns false
        val dispatcher = buildDispatcher(CallType.ONE_ON_ONE.type)

        dispatch(dispatcher, RoomEvent.ParticipantDisconnected(room, mockk<RemoteParticipant>(relaxed = true)))

        verify(exactly = 1) { timeoutMonitor.onParticipantDisconnected(true) }
    }

    @Test
    fun `call teardown cancels pending media-ready fallback`() {
        val dispatcher = buildDispatcher(CallType.ONE_ON_ONE.type)
        armCallTimerGate(dispatcher)

        dispatcher.cancelJobs()
        dispatcherRule.testDispatcher.scheduler.advanceTimeBy(5_000)
        dispatcherRule.testDispatcher.scheduler.runCurrent()

        assertEquals(0, startCallTimerCalls)
    }

    @Test
    fun `microphone subscription after gate arm starts timer once`() {
        val dispatcher = buildDispatcher(CallType.ONE_ON_ONE.type)
        armCallTimerGate(dispatcher)

        dispatch(dispatcher, localTrackSubscribed(Track.Source.MICROPHONE))
        dispatch(dispatcher, localTrackSubscribed(Track.Source.MICROPHONE))

        assertEquals(1, startCallTimerCalls)
    }

    @Test
    fun `early microphone subscription starts timer when gate arms`() {
        val dispatcher = buildDispatcher(CallType.ONE_ON_ONE.type)

        dispatch(dispatcher, localTrackSubscribed(Track.Source.MICROPHONE))
        assertEquals(0, startCallTimerCalls)
        armCallTimerGate(dispatcher)

        assertEquals(1, startCallTimerCalls)
    }

    @Test
    fun `remote active alone does not start timer`() {
        val dispatcher = buildDispatcher(CallType.ONE_ON_ONE.type)
        val remote = mockk<RemoteParticipant>(relaxed = true)
        armCallTimerGate(dispatcher, remote)

        dispatch(
            dispatcher,
            RoomEvent.ParticipantStateChanged(
                room,
                remote,
                Participant.State.ACTIVE,
                Participant.State.JOINED,
            ),
        )

        assertEquals(0, startCallTimerCalls)
    }

    @Test
    fun `camera and screen share subscriptions never start timer`() {
        val dispatcher = buildDispatcher(CallType.ONE_ON_ONE.type)
        armCallTimerGate(dispatcher)

        dispatch(dispatcher, localTrackSubscribed(Track.Source.CAMERA))
        dispatch(dispatcher, localTrackSubscribed(Track.Source.SCREEN_SHARE))

        assertEquals(0, startCallTimerCalls)
    }

    @Test
    fun `gate does not arm while room is disconnected`() {
        every { room.state } returns Room.State.DISCONNECTED
        val dispatcher = buildDispatcher(CallType.ONE_ON_ONE.type)

        armCallTimerGate(dispatcher)
        dispatch(dispatcher, localTrackSubscribed(Track.Source.MICROPHONE))
        dispatcherRule.testDispatcher.scheduler.advanceTimeBy(5_000)
        dispatcherRule.testDispatcher.scheduler.runCurrent()

        assertEquals(0, startCallTimerCalls)
    }

    
    
    
    @Test
    fun `manual switch cancels the armed timeout even when the type upgraded to instant`() {
        stubConnectedRoom(remoteCount = 2)
        every { coordinator.consumeManualSwitchReconnecting() } returns true

        dispatch(buildDispatcher(CallType.INSTANT.type), RoomEvent.Connected(room))

        verify(exactly = 1) { timeoutMonitor.cancelIfActive() }
    }

    
    @Test
    fun `manual switch cancels the armed timeout when the call is still one-on-one`() {
        stubConnectedRoom(remoteCount = 1)
        every { coordinator.consumeManualSwitchReconnecting() } returns true

        dispatch(buildDispatcher(CallType.ONE_ON_ONE.type), RoomEvent.Connected(room))

        verify(exactly = 1) { timeoutMonitor.cancelIfActive() }
    }

    
    
    @Test
    fun `a non-switch connect does not cancel any timeout`() {
        stubConnectedRoom(remoteCount = 2)
        every { coordinator.consumeManualSwitchReconnecting() } returns false

        dispatch(buildDispatcher(CallType.INSTANT.type), RoomEvent.Connected(room))

        verify(exactly = 0) { timeoutMonitor.cancelIfActive() }
    }

    









    private fun stubConnectedRoom(remoteCount: Int) {
        stubMediaState(mic = false, camera = false)
        val local = mockk<LocalParticipant>(relaxed = true)
        every { local.sid } returns Participant.Sid("local-sid")
        every { local.identity } returns Participant.Identity("local-identity")
        every { local.getTrackPublication(any()) } returns null
        every { room.localParticipant } returns local
        every { room.sid } returns Room.Sid("room-sid")
        val remotes = mockk<Map<Participant.Identity, RemoteParticipant>>(relaxed = true)
        every { remotes.size } returns remoteCount
        every { remotes.isEmpty() } returns (remoteCount == 0)
        every { room.remoteParticipants } returns remotes
    }
}
