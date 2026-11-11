package com.difft.android.call.handler

import android.os.SystemClock
import com.difft.android.call.BuildConfig
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.call.R
import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_SET_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.exception.DisconnectException
import com.difft.android.call.data.CallStatisticsEvent
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.CallStatisticsLogManager
import com.difft.android.call.manager.ParticipantManager
import com.difft.android.call.manager.SpeakerStateHolder
import com.difft.android.call.manager.TimerManager
import com.difft.android.call.network.NetworkQualityCoordinator
import com.difft.android.call.session.CallSessionUiEvent
import com.difft.android.call.session.CallSessionUiState
import com.difft.android.call.ui.screenshare.ScreenSharePreWarmer
import com.difft.android.call.util.IdUtil
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch







data class RoomEventHost(
    val showBarrageFn: (Participant, String, Int?) -> Unit,
    val setMicEnabledFn: (Boolean, Boolean, Boolean) -> Unit,
    val setCameraEnabledFn: (Boolean) -> Unit,
    val resetNoBodySpeakCheckFn: () -> Unit,
    val onRemoteEndRequestedFn: (String) -> Unit,
    val stopRingToneAndTimeoutCheckFn: () -> Unit,
    val stopCallerRingbackAndTimeoutCheckFn: () -> Unit,
    val resolveCallTypeFn: () -> Unit,
    val handleConnectedStateFn: () -> Unit,
    val startCallDurationTimerFn: () -> Unit,
    val onFeedbackIdentityResolvedFn: (String, String?, String?) -> Unit,
    val onNetworkPoorStateChangedFn: (Boolean) -> Unit,
    val getCurrentCallTypeFn: () -> String,
    val getCurrentRoomIdFn: () -> String?,
) {
    fun showBarrage(participant: Participant, message: String, type: Int? = RTM_MESSAGE_TYPE_DEFAULT) =
        showBarrageFn(participant, message, type)
    fun setMicEnabled(enabled: Boolean, publishMuted: Boolean = false, isShowBarrage: Boolean = true) =
        setMicEnabledFn(enabled, publishMuted, isShowBarrage)
    fun setCameraEnabled(enabled: Boolean) = setCameraEnabledFn(enabled)
    fun resetNoBodySpeakCheck() = resetNoBodySpeakCheckFn()
    fun onRemoteEndRequested(roomId: String) = onRemoteEndRequestedFn(roomId)
    fun stopRingToneAndTimeoutCheck() = stopRingToneAndTimeoutCheckFn()
    fun stopCallerRingbackAndTimeoutCheck() = stopCallerRingbackAndTimeoutCheckFn()

    




    fun resolveCallType() = resolveCallTypeFn()
    fun handleConnectedState() = handleConnectedStateFn()
    fun startCallDurationTimer() = startCallDurationTimerFn()
    fun onFeedbackIdentityResolved(userSid: String, userIdentity: String?, roomSid: String?) =
        onFeedbackIdentityResolvedFn(userSid, userIdentity, roomSid)
    fun onNetworkPoorStateChanged(poor: Boolean) = onNetworkPoorStateChangedFn(poor)
    fun getCurrentCallType(): String = getCurrentCallTypeFn()
    fun getCurrentRoomId(): String? = getCurrentRoomIdFn()
}










internal class RoomEventDispatcher(
    private val scope: CoroutineScope,
    private val room: Room,
    private val roomCtl: CallRoomController,
    private val rtm: RtmMessageHandler,
    private val connectionCoordinator: CallConnectionCoordinator,
    private val uiState: CallSessionUiState,
    private val participantManager: ParticipantManager,
    private val screenSharePreWarmer: ScreenSharePreWarmer,
    private val timeoutMonitor: CallTimeoutMonitor,
    private val timerManager: TimerManager,
    private val speakerState: SpeakerStateHolder,
    private val callDataManager: CallDataManager,
    private val statisticsLogManager: CallStatisticsLogManager,
    private val mySelfId: String,
    private val networkQuality: NetworkQualityCoordinator,
    private val host: RoomEventHost,
) {

    private val goodQualities = setOf(ConnectionQuality.EXCELLENT, ConnectionQuality.GOOD)
    private val speakingWatchdogTimeoutMs = 3000L

    private var speakingWatchdogJob: Job? = null

    









    private val remoteMicOnAnnounced = mutableSetOf<String>()

    







    private var connectedAtMs: Long = 0L
    private var remoteJoinedAtMs: Long = 0L

    private fun logCallTiming(stage: String, extra: String = "") {
        val now = SystemClock.elapsedRealtime()
        val sinceConnected = if (connectedAtMs > 0L) now - connectedAtMs else -1L
        val sinceRemoteJoined = if (remoteJoinedAtMs > 0L) now - remoteJoinedAtMs else -1L
        L.i {
            "[Call][1v1Timing] stage=$stage sinceConnected=${sinceConnected}ms " +
                "sinceRemoteJoined=${sinceRemoteJoined}ms callType=${host.getCurrentCallType()}$extra"
        }
    }

    






    private var callTimerStarted = false
    private var callTimerGateArmed = false
    private var micTrackSubscribed = false
    private var callTimerFallbackJob: Job? = null

    



    private fun armCallTimerGate(remote: Participant?) {
        if (callTimerStarted || callTimerGateArmed) return
        if (room.state != Room.State.CONNECTED || remote == null) return
        callTimerGateArmed = true

        startCallTimerIfTrackSubscribed()
        if (callTimerStarted) return

        if (callTimerFallbackJob != null) return
        callTimerFallbackJob = scope.launch {
            delay(MEDIA_READY_FALLBACK_MS)
            startCallTimerOnce("fallbackTimeout")
        }
    }

    
    private fun onLocalMicTrackSubscribed() {
        if (micTrackSubscribed) return
        micTrackSubscribed = true
        startCallTimerIfTrackSubscribed()
    }

    




    private fun startCallTimerIfTrackSubscribed() {
        if (!callTimerGateArmed || !micTrackSubscribed) return
        startCallTimerOnce("trackSubscribed")
    }

    
    private fun startCallTimerOnce(reason: String) {
        if (callTimerStarted) return
        callTimerStarted = true
        callTimerFallbackJob?.cancel()
        callTimerFallbackJob = null
        logCallTiming(
            "callTimerStarted",
            " reason=$reason micTrackSubscribed=$micTrackSubscribed",
        )
        host.startCallDurationTimer()
    }

    fun startCollectingRoomEvents() {
        scope.launch {
            room.events.collect { event -> dispatch(event) }
        }
    }

    fun startCollectingParticipants() {
        scope.launch {
            room::remoteParticipants.flow.map { remoteParticipants ->
                val realParticipants = listOf<Participant>(room.localParticipant) +
                    remoteParticipants
                        .keys
                        .sortedBy { it.value }
                        .mapNotNull { remoteParticipants[it] }

                if (BuildConfig.DEBUG && DEBUG_FAKE_PARTICIPANTS) {
                    realParticipants + (1..20).map { i ->
                        Participant(
                            Participant.Sid("fake-sid-$i"),
                            Participant.Identity("fake-user-$i"),
                            Dispatchers.Unconfined,
                        )
                    }
                } else {
                    realParticipants
                }
            }.collectLatest { updatedParticipants ->
                participantManager.setParticipants(updatedParticipants)
            }
        }
    }

    fun cancelJobs() {
        speakingWatchdogJob?.cancel()
        speakingWatchdogJob = null
        callTimerFallbackJob?.cancel()
        callTimerFallbackJob = null
    }

    private fun dispatch(event: RoomEvent) {
        when (event) {
            is RoomEvent.Disconnected -> {
                L.i { "[Call] RoomEventDispatcher room event disconnected, message = ${event.reason}" }
                if (connectionCoordinator.isRetryUrlConnecting) return
                if (event.reason != DisconnectReason.CLIENT_INITIATED) {
                    if (event.reason == DisconnectReason.RECONNECT_FAILED) {
                        roomCtl.updateCallStatus(CallStatus.RECONNECT_FAILED)
                        statisticsLogManager.report(
                            CallStatisticsEvent.RoomReconnectFail(errorMsg = event.reason.name)
                        )
                    }
                    roomCtl.collectError(DisconnectException(event.reason.name))
                } else {
                    if (roomCtl.callStatus.value == CallStatus.SWITCHING_SERVER) return
                    roomCtl.updateCallStatus(CallStatus.DISCONNECTED)
                }
            }
            is RoomEvent.FailedToConnect -> {
                if (connectionCoordinator.isRetryUrlConnecting) return
                L.i { "[Call] RoomEventDispatcher room event failed to connect, message = ${event.error}." }
                roomCtl.updateCallStatus(CallStatus.CONNECTED_FAILED)
                roomCtl.collectError(event.error)
            }
            is RoomEvent.DataReceived -> handleDataReceived(event)
            is RoomEvent.ParticipantDisconnected -> onParticipantDisconnected(event.participant)
            is RoomEvent.ParticipantConnected -> onParticipantConnected(event.participant)
            is RoomEvent.Reconnected -> {
                L.i { "[Call] RoomEventDispatcher room event reconnected." }
                timeoutMonitor.cancelIfActive()
                roomCtl.updateCallStatus(CallStatus.RECONNECTED)
                screenSharePreWarmer.markReconnected(::onResubscriptionSettled)
            }
            is RoomEvent.Reconnecting -> {
                L.i { "[Call] RoomEventDispatcher room event reconnecting." }
                timeoutMonitor.cancelIfActive()
                roomCtl.updateCallStatus(CallStatus.RECONNECTING)
                uiState.setSpeakingEnabled(false)
                screenSharePreWarmer.markReconnecting()
            }
            is RoomEvent.Connected -> onConnected()
            is RoomEvent.ParticipantStateChanged -> {
                if (event.participant is RemoteParticipant) {
                    val isActive = event.newState == Participant.State.ACTIVE
                    logCallTiming(
                        if (isActive) "participantActive" else "participantState",
                        " identity=${event.participant.identity?.value} state=${event.newState}",
                    )
                }
            }
            is RoomEvent.LocalTrackSubscribed -> {
                logCallTiming("localTrackSubscribed", " source=${event.publication.source}")
                if (event.publication.source == Track.Source.MICROPHONE) onLocalMicTrackSubscribed()
            }
            is RoomEvent.TrackMuted -> onTrackMuted(event)
            is RoomEvent.TrackUnmuted -> onTrackUnmuted(event)
            is RoomEvent.TrackSubscribed -> {
                checkRemoteUserScreenShare(event.participant)
                onMicTrackSubscribed(event)
                screenSharePreWarmer.reWarmIfNeeded(
                    event.participant,
                    event.publication.source,
                    uiState.isShareScreening.value,
                    participantManager.screenSharingUser.value?.identity?.value,
                )
                screenSharePreWarmer.handleTrackSubscribedIfPending(::onResubscriptionSettled)
            }
            is RoomEvent.TrackUnsubscribed -> checkRemoteUserScreenShare(event.participant)
            
            
            
            is RoomEvent.ConnectionQualityChanged -> onConnectionQualityChanged(event.participant, event.quality)
            is RoomEvent.ActiveSpeakersChanged -> {
                if (!uiState.speakingEnabled.value) uiState.setSpeakingEnabled(true)
                resetSpeakingWatchdog(event.speakers.isNotEmpty())
            }
            else -> {}
        }
    }

    private fun handleDataReceived(event: RoomEvent.DataReceived) {
        rtm.handleDataReceived(
            event = event,
            onChat = { p, text, type -> host.showBarrage(p, text, type) },
            onMuteMe = { host.setMicEnabled(false) },
            onResumeMe = {
                if (host.getCurrentCallType() == CallType.ONE_ON_ONE.type) host.resetNoBodySpeakCheck()
            },
            onEndCall = {
                host.getCurrentRoomId()?.let { rid ->
                    callDataManager.removeCallData(rid)
                    host.onRemoteEndRequested(rid)
                }
            },
            onServerEndCall = serverEndCall@{
                
                if (roomCtl.roomOrNull() !== room) {
                    L.w { "[Call] server-end-call ignored - inactive room" }
                    return@serverEndCall
                }
                host.getCurrentRoomId()?.let { rid ->
                    callDataManager.removeCallData(rid)
                    host.onRemoteEndRequested(rid)
                }
            },
            onCountDown = { data, topic ->
                when (topic) {
                    RTM_MESSAGE_TOPIC_SET_COUNTDOWN, RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN -> {
                        val left = calculateCountDownDuration(data.expiredTimeMs, data.currentTimeMs)
                        data.operatorIdentity.takeIf { it.isNotEmpty() }?.let { opId ->
                            room.remoteParticipants[Participant.Identity(opId)]?.let { p ->
                                host.showBarrage(p, getString(R.string.call_barrage_message_countdown_timer))
                            }
                        }
                        timerManager.startCountdown(left, onEnded = { }, onTick = uiState::setCountDownDurationStr)
                    }
                    RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN -> {
                        val left = calculateCountDownDuration(data.expiredTimeMs, data.currentTimeMs)
                        timerManager.startCountdown(left, onEnded = { }, onTick = uiState::setCountDownDurationStr)
                    }
                    RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN -> timerManager.stopCountdown()
                }
            },
        )
    }

    private fun onConnected() {
        
        
        
        
        
        
        
        host.resolveCallType()
        val rid = host.getCurrentRoomId() ?: return
        
        
        
        
        
        
        
        
        
        val hasMicPublication = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE) != null
        
        
        val isManualSwitch = connectionCoordinator.consumeManualSwitchReconnecting()
        L.i { "[Call] RoomEventDispatcher room event connected. hasMicPublication=$hasMicPublication manualSwitch=$isManualSwitch" }
        val isFirstConnect = connectedAtMs == 0L
        if (isFirstConnect) connectedAtMs = SystemClock.elapsedRealtime()
        logCallTiming("roomConnected", " firstConnect=$isFirstConnect remotes=${room.remoteParticipants.size}")
        
        
        room.remoteParticipants.values.firstOrNull()?.let { remote ->
            if (remoteJoinedAtMs == 0L) {
                remoteJoinedAtMs = SystemClock.elapsedRealtime()
                logCallTiming(
                    "remoteAlreadyPresent",
                    " alreadyActive=${remote.state == Participant.State.ACTIVE}",
                )
            }
        }
        
        
        
        
        
        val callerStillRinging = roomCtl.startedOneOnOne && roomCtl.callStatus.value == CallStatus.CALLING
        if (callerStillRinging && room.remoteParticipants.isNotEmpty()) {
            host.stopCallerRingbackAndTimeoutCheck()
        }
        host.onFeedbackIdentityResolved(
            userSid = room.localParticipant.sid.value,
            userIdentity = room.localParticipant.identity?.value,
            roomSid = room.sid?.sid,
        )
        callDataManager.updateCallingState(rid, isInCalling = true)
        
        
        
        
        
        
        
        
        if (isManualSwitch) timeoutMonitor.cancelIfActive()
        if (host.getCurrentCallType() == CallType.ONE_ON_ONE.type) {
            val presentRemote = room.remoteParticipants.values.firstOrNull()
            if (isManualSwitch) {
                
                
                restoreMediaAfterServerSwitch()
                
                
                
                
                
                
                if (presentRemote != null) {
                    host.handleConnectedState()
                    armCallTimerGate(presentRemote)
                } else timeoutMonitor.start1V1Timeout(rid)
            } else {
                if (!hasMicPublication) host.setMicEnabled(true)
                if (presentRemote != null) {
                    host.handleConnectedState()
                    armCallTimerGate(presentRemote)
                } else timeoutMonitor.start1V1Timeout(rid)
            }
        } else {
            host.handleConnectedState()
            if (isManualSwitch) {
                restoreMediaAfterServerSwitch()
            } else if (!hasMicPublication) {
                room::ttCallResp.get()?.let { response ->
                    val autoPublishSilenceAudio = response.callOptions.autoPublishSilenceAudio
                    L.i { "[call] RoomEventDispatcher room event connected, autoPublishSilenceAudio=$autoPublishSilenceAudio" }
                    if (autoPublishSilenceAudio) host.setMicEnabled(true, publishMuted = true, isShowBarrage = false)
                }
            }
        }
    }

    













    private fun restoreMediaAfterServerSwitch() {
        val desiredMic = roomCtl.micEnabled.value
        val desiredCamera = roomCtl.cameraEnabled.value
        L.i { "[Call] RoomEventDispatcher restore media after server switch mic=$desiredMic camera=$desiredCamera" }
        if (desiredMic) {
            host.setMicEnabled(true, isShowBarrage = false)
        } else if (host.getCurrentCallType() != CallType.ONE_ON_ONE.type) {
            room::ttCallResp.get()?.let { response ->
                if (response.callOptions.autoPublishSilenceAudio) {
                    host.setMicEnabled(true, publishMuted = true, isShowBarrage = false)
                }
            }
        }
        if (desiredCamera) host.setCameraEnabled(true)
    }

    private fun onParticipantConnected(participant: Participant) {
        if (remoteJoinedAtMs == 0L) remoteJoinedAtMs = SystemClock.elapsedRealtime()
        logCallTiming("remoteJoined", " identity=${participant.identity?.value}")
        host.showBarrage(participant, getString(R.string.call_barrage_message_join))
        host.stopRingToneAndTimeoutCheck()
        if (host.getCurrentCallType() == CallType.ONE_ON_ONE.type) {
            host.handleConnectedState()
            armCallTimerGate(participant)
        }
        
        
        host.resolveCallType()
        participantManager.updateAwaitingJoinInvitees()
    }

    private fun onParticipantDisconnected(participant: Participant) {
        
        
        networkQuality.onParticipantLeft(participant.identity?.value)

        
        
        
        
        
        
        
        if (roomCtl.callStatus.value == CallStatus.SWITCHING_SERVER ||
            connectionCoordinator.isRetryUrlConnecting
        ) {
            L.i { "[Call] RoomEventDispatcher ignore ParticipantDisconnected during switch/reconnect" }
            return
        }
        
        
        participant.identity?.value?.let { remoteMicOnAnnounced.remove(it) }
        timeoutMonitor.onParticipantDisconnected(host.getCurrentCallType() == CallType.ONE_ON_ONE.type)
    }

    private fun onTrackMuted(event: RoomEvent.TrackMuted) {
        if (event.publication.source == Track.Source.MICROPHONE &&
            event.publication.muted &&
            event.publication.subscribed &&
            event.participant is RemoteParticipant
        ) {
            
            event.participant.identity?.value?.let { remoteMicOnAnnounced.remove(it) }
            if (shouldShowBarrageForRemoteParticipant(event.participant)) {
                host.showBarrage(event.participant, getString(R.string.call_barrage_message_close_mic))
            }
        }
    }

    private fun onTrackUnmuted(event: RoomEvent.TrackUnmuted) {
        if (event.publication.source == Track.Source.MICROPHONE && event.participant is RemoteParticipant) {
            
            
            
            
            val identity = event.participant.identity?.value
            if (shouldShowBarrageForRemoteParticipant(event.participant) &&
                (identity == null || remoteMicOnAnnounced.add(identity))
            ) {
                host.showBarrage(event.participant, getString(R.string.call_barrage_message_open_mic))
            }
        }
    }

    













    private fun onMicTrackSubscribed(event: RoomEvent.TrackSubscribed) {
        if (event.publication.source != Track.Source.MICROPHONE) return
        if (event.publication.muted) return
        if (event.participant !is RemoteParticipant) return
        if (!shouldShowBarrageForRemoteParticipant(event.participant)) return
        val identity = event.participant.identity?.value ?: return
        if (!remoteMicOnAnnounced.add(identity)) return
        host.showBarrage(event.participant, getString(R.string.call_barrage_message_open_mic))
    }

    private fun shouldShowBarrageForRemoteParticipant(participant: Participant): Boolean {
        val participantUid = IdUtil.getUidByIdentity(participant.identity?.value)
        return participantUid != null && participantUid != mySelfId
    }

    private fun checkRemoteUserScreenShare(participant: Participant) {
        if (participant !is RemoteParticipant) return
        val isSharing = participant.getTrackPublication(Track.Source.SCREEN_SHARE) != null
        if (isSharing && !uiState.isShareScreening.value) {
            L.i { "[Call] RoomEventDispatcher ${participant.identity?.value} start screen sharing." }
            screenSharePreWarmer.preWarmSharer(participant)
            participantManager.setScreenSharingUser(participant)
            uiState.setShareScreening(true)
            uiState.emit(CallSessionUiEvent.RevealCallChrome)
            host.showBarrage(participant, getString(R.string.call_barrage_message_screensharing))
        }
        if (participantManager.screenSharingUser.value?.identity?.value == participant.identity?.value && !isSharing) {
            if (roomCtl.callStatus.value == CallStatus.RECONNECTING || screenSharePreWarmer.isPendingResubscription) return
            L.i { "[Call] RoomEventDispatcher ${participant.identity?.value} stop screen sharing." }
            screenSharePreWarmer.cleanupAll()
            uiState.setShareScreening(false)
            participantManager.setScreenSharingUser(null)
            uiState.emit(CallSessionUiEvent.RevealCallChrome)
        }
    }

    private fun onResubscriptionSettled() {
        
        
        uiState.incrementReconnectCount()
        participantManager.screenSharingUser.value?.let { checkRemoteUserScreenShare(it) }
    }

    private fun onConnectionQualityChanged(participant: Participant, quality: ConnectionQuality) {
        
        
        networkQuality.onQualityChanged(
            identity = participant.identity?.value,
            quality = quality,
            isLocal = participant is LocalParticipant,
        )
        
        
        
        if (participant !is LocalParticipant) return
        host.onNetworkPoorStateChanged(quality !in goodQualities)
    }

    private fun resetSpeakingWatchdog(hasSpeakers: Boolean) {
        speakingWatchdogJob?.cancel()
        if (hasSpeakers) {
            speakingWatchdogJob = scope.launch {
                delay(speakingWatchdogTimeoutMs)
                uiState.setSpeakingEnabled(false)
            }
        }
    }

    private fun calculateCountDownDuration(expiredTimeMs: Long, currentTimeMs: Long): Long =
        if (expiredTimeMs < currentTimeMs) 0 else (expiredTimeMs - currentTimeMs) / 1000

    companion object {
        
        private const val DEBUG_FAKE_PARTICIPANTS = false

        
        private const val MEDIA_READY_FALLBACK_MS = 5_000L
    }
}
