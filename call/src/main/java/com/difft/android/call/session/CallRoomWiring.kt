package com.difft.android.call.session

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.MediaSendIssueState
import com.difft.android.call.media.CallMediaController
import io.livekit.android.room.Room
import io.livekit.android.util.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Phase B: create the room off-main (LiveKit.create) then wire all room-dependent collaborators;
 * runs once. Runs synchronously on the caller's thread ([CallSession.start] launches it on the
 * session's Default scope) so the caller sees any exception. Both `isReleaseIntended()` checks
 * abort the wiring when a release raced in.
 */
internal fun CallSessionGraph.startRoomDependentWiring() {
    if (!roomWiringStarted.compareAndSet(false, true)) return
    val r = roomCtl.createRoom()
    activeSpeakers = r::activeSpeakers.flow
    mediaCtl = CallMediaController(
        room = r,
        roomCtl = roomCtl,
        audioProcessor = audio.processor,
        audioDeviceManager = audio.deviceManager,
        scope = mainScope,
        showBarrage = { p, m -> showBarrage(p, m) },
        showToast = ::showToast,
    )
    rtm = createRtmHandler(room, mainScope, deps.callToChatController, deps.messageEncryptor) { e2eeKey }
    if (roomCtl.isReleaseIntended()) { L.i { "[Call] Phase B abort: release in flight" }; return }
    applyInitialVoicePreset()
    // Before the room events collector: the coordinator subscribes to room.metadata, whose
    // current value already carries the authoritative callType by the time Connected is handled.
    callTypeCoordinator.start()
    // Constructed here (the `room` getter is fail-loud once released) but started on main: the
    // coordinator drives a lock-free state machine and is main-confined, while this whole Phase B
    // runs on Dispatchers.Default. Order against the event collector is irrelevant — a quality
    // event arriving first is simply recorded, and the first room-state frame re-seeds anyway.
    val networkQuality = networkQualityCoordinator
    mainScope.launch(Dispatchers.Main.immediate) { networkQuality.start() }
    roomEventDispatcher.startCollectingRoomEvents()
    roomEventDispatcher.startCollectingParticipants()
    // Local `r`, not the `room` getter: the latter is fail-loud once the room is released.
    audio.setup.bindRoomState(r::state.flow)
    observeMediaSendConnectionState(r)
    CallObservers.register(
        scope = mainScope,
        room = r,
        participantManager = participantManager,
        uiState = uiState,
        contactorCacheManager = deps.contactorCacheManager,
        callToChatController = deps.callToChatController,
        speakerState = speakerState,
        callConfig = params.callConfig,
        callIntent = params.callIntent,
        activeSpeakers = activeSpeakers,
    )
    connectionCoordinator.observeManualSwitchReconnect(
        scope = mainScope,
        callIntent = params.callIntent,
        roomIdGetter = { roomId },
        showToast = ::showToast,
    )
    if (roomCtl.isReleaseIntended()) { L.i { "[Call] Phase B abort: release in flight (pre-connect re-check)" }; return }
    // Forces criticalAlertDispatcher → room getter; keep under the guard.
    criticalAlertDispatcher.refreshGroupStatus(params.callIntent)
    sessionStarter.start()
}

/** Mapping semantics live in [MediaSendIssueState.resolve] (pure, directly unit-tested). */
private fun CallSessionGraph.observeMediaSendConnectionState(r: Room) {
    mainScope.launch {
        combine(r::state.flow, r::mediaSendConnectionState.flow, MediaSendIssueState::resolve)
            .distinctUntilChanged().collect { issue ->
                L.i { "[Call] mediaSendIssue=$issue sendState=${r.mediaSendConnectionState} roomState=${r.state}" }
                mediaSendIssueState.value = issue
            }
    }
}
