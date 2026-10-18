package com.difft.android.call.handler

import com.difft.android.base.call.CallType
import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.CallStatus
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ParticipantManager
import com.difft.android.call.manager.TimerManager
import com.difft.android.call.network.NetworkQualityCoordinator
import com.difft.android.call.session.CallSessionUiState
import com.difft.android.call.ui.screenshare.ScreenSharePreWarmer
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single owner of the [RoomEventHost] + [RoomEventDispatcher] construction shared by the
 * `RoomEventDispatcher*Test` classes. Collaborators are relaxed mocks a test can stub further (or
 * replace, for the `var`s, before [build]); the UI output goes to a real [CallSessionUiState]
 * (a session's own one when a test passes it).
 * Host calls are recorded in the lists below.
 *
 * Defaults: not retry-connecting, call status CONNECTED, room state CONNECTED, room id `room-1`.
 */
internal class RoomEventDispatcherFixture(
    val scope: CoroutineScope,
    val uiState: CallSessionUiState = CallSessionUiState(),
) {
    val room: Room = mockk(relaxed = true)
    val roomCtl: CallRoomController = mockk(relaxed = true)
    val connectionCoordinator: CallConnectionCoordinator = mockk(relaxed = true)
    val timeoutMonitor: CallTimeoutMonitor = mockk(relaxed = true)
    val networkQuality: NetworkQualityCoordinator = mockk(relaxed = true)
    val screenSharePreWarmer: ScreenSharePreWarmer = mockk(relaxed = true)
    var rtm: RtmMessageHandler = mockk(relaxed = true)
    var callDataManager: CallDataManager = mockk(relaxed = true)
    var participantManager: ParticipantManager = mockk(relaxed = true)
    var timerManager: TimerManager = mockk(relaxed = true)

    /** `(participant, message, type)` of every host barrage, in order. */
    val barrages = mutableListOf<Triple<Participant, String, Int?>>()

    /** Every host `setMicEnabled(enabled, publishMuted, isShowBarrage)`, in order. */
    val setMicCalls = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
    val setCameraCalls = mutableListOf<Boolean>()

    /** Every host `onNetworkPoorStateChanged(poor)`, in order. */
    val networkPoorCalls = mutableListOf<Boolean>()
    var startCallTimerCalls = 0

    /** `stopRingToneAndTimeoutCheck` / `handleConnectedState` host calls, in order. */
    val connectStepCalls = mutableListOf<String>()

    /** Room ids of every host `onRemoteEndRequested`, in order. */
    val remoteEndRequests = mutableListOf<String>()

    /** Runs after each recorded `onRemoteEndRequested`; a test binds the session's real end seam here. */
    var onRemoteEndRequested: (String) -> Unit = {}

    init {
        every { connectionCoordinator.isRetryUrlConnecting } returns false
        every { roomCtl.callStatus } returns MutableStateFlow(CallStatus.CONNECTED)
        every { room.state } returns Room.State.CONNECTED
    }

    fun build(callType: String = CallType.ONE_ON_ONE.type, roomId: String? = ROOM_ID): RoomEventDispatcher {
        val host = RoomEventHost(
            showBarrageFn = { p, m, t -> barrages += Triple(p, m, t) },
            setMicEnabledFn = { enabled, publishMuted, isShowBarrage ->
                setMicCalls += Triple(enabled, publishMuted, isShowBarrage)
            },
            setCameraEnabledFn = { setCameraCalls += it },
            resetNoBodySpeakCheckFn = {},
            onRemoteEndRequestedFn = { rid ->
                remoteEndRequests += rid
                onRemoteEndRequested(rid)
            },
            stopRingToneAndTimeoutCheckFn = { connectStepCalls += STOP_RING },
            resolveCallTypeFn = {},
            handleConnectedStateFn = { connectStepCalls += CONNECTED },
            startCallDurationTimerFn = { startCallTimerCalls++ },
            onFeedbackIdentityResolvedFn = { _, _, _ -> },
            onNetworkPoorStateChangedFn = { networkPoorCalls += it },
            getCurrentCallTypeFn = { callType },
            getCurrentRoomIdFn = { roomId },
        )
        return RoomEventDispatcher(
            scope = scope,
            room = room,
            roomCtl = roomCtl,
            rtm = rtm,
            connectionCoordinator = connectionCoordinator,
            uiState = uiState,
            participantManager = participantManager,
            screenSharePreWarmer = screenSharePreWarmer,
            timeoutMonitor = timeoutMonitor,
            timerManager = timerManager,
            speakerState = mockk(relaxed = true),
            callDataManager = callDataManager,
            statisticsLogManager = mockk(relaxed = true),
            mySelfId = SELF_ID,
            networkQuality = networkQuality,
            host = host,
        )
    }

    /** Runs the private `dispatch(event)`, the body of the room-event collector. */
    fun dispatch(dispatcher: RoomEventDispatcher, event: RoomEvent) {
        val method = RoomEventDispatcher::class.java.getDeclaredMethod("dispatch", RoomEvent::class.java)
        method.isAccessible = true
        method.invoke(dispatcher, event)
    }

    companion object {
        const val ROOM_ID = "room-1"
        const val SELF_ID = "self"
        const val STOP_RING = "stopRingToneAndTimeoutCheck"
        const val CONNECTED = "handleConnectedState"
    }
}
