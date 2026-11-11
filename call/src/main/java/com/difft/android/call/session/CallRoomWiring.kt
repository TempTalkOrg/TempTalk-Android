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
    
    
    callTypeCoordinator.start()
    
    
    
    
    val networkQuality = networkQualityCoordinator
    mainScope.launch(Dispatchers.Main.immediate) { networkQuality.start() }
    roomEventDispatcher.startCollectingRoomEvents()
    roomEventDispatcher.startCollectingParticipants()
    
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
    
    criticalAlertDispatcher.refreshGroupStatus(params.callIntent)
    sessionStarter.start()
}


private fun CallSessionGraph.observeMediaSendConnectionState(r: Room) {
    mainScope.launch {
        combine(r::state.flow, r::mediaSendConnectionState.flow, MediaSendIssueState::resolve)
            .distinctUntilChanged().collect { issue ->
                L.i { "[Call] mediaSendIssue=$issue sendState=${r.mediaSendConnectionState} roomState=${r.state}" }
                mediaSendIssueState.value = issue
            }
    }
}
