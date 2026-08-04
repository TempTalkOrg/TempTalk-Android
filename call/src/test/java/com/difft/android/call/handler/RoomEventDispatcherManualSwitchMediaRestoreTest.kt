package com.difft.android.call.handler

import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.CallStatus
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * Regression coverage for [RoomEventDispatcher.restoreMediaAfterServerSwitch] — the fix for the
 * "mic/camera UI-vs-actual mismatch after a manual meeting server-node / connection-mode switch"
 * bug.
 *
 * Background: a manual switch does a full disconnect+reconnect
 * ([CallConnectionCoordinator.connectToRoomManualSwitch]) which destroys the local mic/camera
 * publications. The control toggles are driven by `roomCtl.micEnabled/cameraEnabled`, which the
 * switch never resets, so without a restore the UI keeps showing "on" while nothing is actually
 * published. On reconnect `onConnected` detects the manual switch (via
 * `connectionCoordinator.isManualSwitchReconnecting`) and calls `restoreMediaAfterServerSwitch()`
 * to re-apply the user's live intent.
 *
 * `restoreMediaAfterServerSwitch()` is `private` and its only production entry (`onConnected`) needs
 * heavy `Room` mocking (value-class `sid`/`identity`, participant maps). We invoke the REAL method
 * via reflection so the test exercises production logic directly (no re-implementation) while
 * keeping the mock surface small and robust. The assertions check exactly which
 * `setMicEnabled(enabled, publishMuted, isShowBarrage)` / `setCameraEnabled(enabled)` calls the
 * decision matrix produces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class RoomEventDispatcherManualSwitchMediaRestoreTest {

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule()

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var room: Room
    private lateinit var roomCtl: CallRoomController
    private lateinit var coordinator: CallConnectionCoordinator
    private lateinit var timeoutMonitor: CallTimeoutMonitor

    /** Records every host.setMicEnabled(enabled, publishMuted, isShowBarrage) invocation. */
    private val setMicCalls = mutableListOf<Triple<Boolean, Boolean, Boolean>>()

    /** Records every host.setCameraEnabled(enabled) invocation. */
    private val setCameraCalls = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext())
        room = mockk(relaxed = true)
        roomCtl = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
        timeoutMonitor = mockk(relaxed = true)
        every { coordinator.isRetryUrlConnecting } returns false
        every { roomCtl.callStatus } returns MutableStateFlow(CallStatus.CONNECTED)
    }

    @After
    fun tearDown() {
        unmockkAll()
        setMicCalls.clear()
        setCameraCalls.clear()
    }

    private fun buildDispatcher(callType: String): RoomEventDispatcher {
        val host = RoomEventHost(
            showBarrageFn = { _, _, _ -> },
            setMicEnabledFn = { enabled, publishMuted, isShowBarrage ->
                setMicCalls.add(Triple(enabled, publishMuted, isShowBarrage))
            },
            setCameraEnabledFn = { enabled -> setCameraCalls.add(enabled) },
            resetNoBodySpeakCheckFn = {},
            sendHangUpBroadcastFn = {},
            stopRingToneAndTimeoutCheckFn = {},
            resolveCallTypeFn = {},
            handleConnectedStateFn = {},
            onFeedbackIdentityResolvedFn = { _, _, _ -> },
            onNetworkPoorStateChangedFn = {},
            getCurrentCallTypeFn = { callType },
            getCurrentRoomIdFn = { "room-1" },
        )
        return RoomEventDispatcher(
            scope = CoroutineScope(Dispatchers.Unconfined),
            room = room,
            roomCtl = roomCtl,
            rtm = mockk(relaxed = true),
            connectionCoordinator = coordinator,
            callUiController = mockk(relaxed = true),
            participantManager = mockk(relaxed = true),
            screenSharePreWarmer = mockk(relaxed = true),
            timeoutMonitor = timeoutMonitor,
            timerManager = mockk(relaxed = true),
            speakerState = mockk(relaxed = true),
            callDataManager = mockk(relaxed = true),
            statisticsLogManager = mockk(relaxed = true),
            mySelfId = "self",
            host = host,
        )
    }

    private fun invokeRestore(dispatcher: RoomEventDispatcher) {
        val method = RoomEventDispatcher::class.java
            .getDeclaredMethod("restoreMediaAfterServerSwitch")
        method.isAccessible = true
        method.invoke(dispatcher)
    }

    private fun dispatch(dispatcher: RoomEventDispatcher, event: RoomEvent) {
        val method = RoomEventDispatcher::class.java
            .getDeclaredMethod("dispatch", RoomEvent::class.java)
        method.isAccessible = true
        method.invoke(dispatcher, event)
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

    // ---------------------------------------------------------------------------------
    // Group meeting: mic ON + camera ON — the reported scenario. Both must be re-applied:
    // mic unmuted (publishMuted=false) and silently (isShowBarrage=false), camera enabled.
    // ---------------------------------------------------------------------------------
    @Test
    fun `group manual switch with mic on and camera on re-enables both`() {
        stubMediaState(mic = true, camera = true)

        invokeRestore(buildDispatcher(CallType.GROUP.type))

        assertEquals(listOf(Triple(true, false, false)), setMicCalls)
        assertEquals(listOf(true), setCameraCalls)
    }

    // ---------------------------------------------------------------------------------
    // Camera OFF before the switch must NOT be spuriously enabled after reconnect.
    // ---------------------------------------------------------------------------------
    @Test
    fun `group manual switch with mic on and camera off leaves camera off`() {
        stubMediaState(mic = true, camera = false)

        invokeRestore(buildDispatcher(CallType.GROUP.type))

        assertEquals(listOf(Triple(true, false, false)), setMicCalls)
        assertEquals(emptyList<Boolean>(), setCameraCalls)
    }

    // ---------------------------------------------------------------------------------
    // Group + self-muted + server wants silence: re-publish a MUTED silence track so the
    // participant keeps its presence slot, matching first-connect behavior (publishMuted=true).
    // ---------------------------------------------------------------------------------
    @Test
    fun `group manual switch with mic off publishes muted silence when server requests it`() {
        stubMediaState(mic = false, camera = false)
        stubAutoPublishSilence(enabled = true)

        invokeRestore(buildDispatcher(CallType.GROUP.type))

        assertEquals(listOf(Triple(true, true, false)), setMicCalls)
        assertEquals(emptyList<Boolean>(), setCameraCalls)
    }

    // ---------------------------------------------------------------------------------
    // Group + self-muted + server does NOT request silence: publish nothing.
    // ---------------------------------------------------------------------------------
    @Test
    fun `group manual switch with mic off and no silence option publishes nothing`() {
        stubMediaState(mic = false, camera = false)
        stubAutoPublishSilence(enabled = false)

        invokeRestore(buildDispatcher(CallType.GROUP.type))

        assertEquals(emptyList<Triple<Boolean, Boolean, Boolean>>(), setMicCalls)
        assertEquals(emptyList<Boolean>(), setCameraCalls)
    }

    // ---------------------------------------------------------------------------------
    // 1v1 + self-muted: must NOT be force-unmuted on a node switch (no silence fallback for
    // 1v1). Guards against re-introducing the "switch silently unmutes a muted user" bug.
    // ---------------------------------------------------------------------------------
    @Test
    fun `one-on-one manual switch with mic off does not force unmute`() {
        stubMediaState(mic = false, camera = false)

        invokeRestore(buildDispatcher(CallType.ONE_ON_ONE.type))

        assertEquals(emptyList<Triple<Boolean, Boolean, Boolean>>(), setMicCalls)
        assertEquals(emptyList<Boolean>(), setCameraCalls)
    }

    // ---------------------------------------------------------------------------------
    // 1v1 + mic ON + camera ON: both re-applied (mic unmuted & silent, camera enabled).
    // ---------------------------------------------------------------------------------
    @Test
    fun `one-on-one manual switch with mic on and camera on re-enables both`() {
        stubMediaState(mic = true, camera = true)

        invokeRestore(buildDispatcher(CallType.ONE_ON_ONE.type))

        assertEquals(listOf(Triple(true, false, false)), setMicCalls)
        assertEquals(listOf(true), setCameraCalls)
    }

    // ---------------------------------------------------------------------------------
    // ParticipantDisconnected during a manual switch (status == SWITCHING_SERVER) is the
    // old session tearing down — it must NOT arm the "participant left" timeout, otherwise it
    // fires "对方未接听" ~60s after reconnect (the remote returns via initial sync, no
    // ParticipantConnected event to cancel it).
    // ---------------------------------------------------------------------------------
    @Test
    fun `participant disconnect while switching server does not arm timeout`() {
        every { roomCtl.callStatus } returns MutableStateFlow(CallStatus.SWITCHING_SERVER)
        val dispatcher = buildDispatcher(CallType.ONE_ON_ONE.type)

        dispatch(dispatcher, RoomEvent.ParticipantDisconnected(room, mockk<RemoteParticipant>(relaxed = true)))

        verify(exactly = 0) { timeoutMonitor.onParticipantDisconnected(any()) }
    }

    // ---------------------------------------------------------------------------------
    // Same guard for the failover retry window.
    // ---------------------------------------------------------------------------------
    @Test
    fun `participant disconnect during failover retry does not arm timeout`() {
        every { roomCtl.callStatus } returns MutableStateFlow(CallStatus.RECONNECTING)
        every { coordinator.isRetryUrlConnecting } returns true
        val dispatcher = buildDispatcher(CallType.ONE_ON_ONE.type)

        dispatch(dispatcher, RoomEvent.ParticipantDisconnected(room, mockk<RemoteParticipant>(relaxed = true)))

        verify(exactly = 0) { timeoutMonitor.onParticipantDisconnected(any()) }
    }

    // ---------------------------------------------------------------------------------
    // A genuine remote leave during a live (CONNECTED) 1v1 call MUST still arm the timeout —
    // the guard uses only the transient switch window, never the sticky manual-switch flag.
    // ---------------------------------------------------------------------------------
    @Test
    fun `participant disconnect while connected arms one-on-one timeout`() {
        every { roomCtl.callStatus } returns MutableStateFlow(CallStatus.CONNECTED)
        every { coordinator.isRetryUrlConnecting } returns false
        val dispatcher = buildDispatcher(CallType.ONE_ON_ONE.type)

        dispatch(dispatcher, RoomEvent.ParticipantDisconnected(room, mockk<RemoteParticipant>(relaxed = true)))

        verify(exactly = 1) { timeoutMonitor.onParticipantDisconnected(true) }
    }

    // ---------------------------------------------------------------------------------
    // Cancelling the teardown-armed timeout on a manual-switch Connected must not depend on
    // the type the call resolves to afterwards.
    //
    // The timeout is armed only while the call is 1v1, but `onConnected` resolves the
    // authoritative type before it branches, so the switch window itself can turn the call
    // into instant — a third participant joins, or the server flips callType for an invite
    // whose RoomUpdate the switch missed. If the cancel lived in the 1v1 branch, those cases
    // would skip it and fire "对方未接听" ~60s later on a live call.
    // ---------------------------------------------------------------------------------
    @Test
    fun `manual switch cancels the armed timeout even when the type upgraded to instant`() {
        stubConnectedRoom(remoteCount = 2)
        every { coordinator.consumeManualSwitchReconnecting() } returns true

        dispatch(buildDispatcher(CallType.INSTANT.type), RoomEvent.Connected(room))

        verify(exactly = 1) { timeoutMonitor.cancelIfActive() }
    }

    /** The pre-existing path, kept honest: a still-1v1 manual switch cancels as it always did. */
    @Test
    fun `manual switch cancels the armed timeout when the call is still one-on-one`() {
        stubConnectedRoom(remoteCount = 1)
        every { coordinator.consumeManualSwitchReconnecting() } returns true

        dispatch(buildDispatcher(CallType.ONE_ON_ONE.type), RoomEvent.Connected(room))

        verify(exactly = 1) { timeoutMonitor.cancelIfActive() }
    }

    // A first connect arms nothing, so there is nothing to cancel; cancelling anyway would be
    // harmless today but would hide a genuinely leaked timeout from this test.
    @Test
    fun `a non-switch connect does not cancel any timeout`() {
        stubConnectedRoom(remoteCount = 2)
        every { coordinator.consumeManualSwitchReconnecting() } returns false

        dispatch(buildDispatcher(CallType.INSTANT.type), RoomEvent.Connected(room))

        verify(exactly = 0) { timeoutMonitor.cancelIfActive() }
    }

    /**
     * Minimal `Room` surface that `onConnected` touches: the value-class sid/identity it reports for
     * feedback, and a remote-participant map of the given size. A relaxed mock cannot supply the
     * value classes, hence the explicit stubs.
     *
     * Media state is stubbed too because a manual-switch Connected always reaches
     * `restoreMediaAfterServerSwitch`, which reads `roomCtl.micEnabled.value` — a relaxed StateFlow
     * hands back an Object there and the cast fails. Muted with the camera off is the quiet choice:
     * it drives no media calls, leaving the timeout assertions as the only thing under test.
     */
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
