package com.difft.android.call.handler

import android.os.SystemClock
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.call.R
import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_SET_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.data.RoomMetadata
import com.difft.android.call.exception.DisconnectException
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.exception.NetworkConnectionPoorException
import com.difft.android.call.manager.ParticipantManager
import com.difft.android.call.manager.SpeakerStateHolder
import com.difft.android.call.manager.TimerManager
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
import kotlinx.serialization.json.Json

/**
 * UI-layer side effects that cannot be moved cleanly out of the ViewModel.
 * Bundled as lambdas so `LCallViewModel` can construct one with method
 * references without having to implement an interface (~25 line savings in
 * the VM under the 500-line cap).
 */
data class RoomEventHost(
    val showBarrageFn: (Participant, String, Int?) -> Unit,
    val setMicEnabledFn: (Boolean, Boolean, Boolean) -> Unit,
    val resetNoBodySpeakCheckFn: () -> Unit,
    val sendHangUpBroadcastFn: (String) -> Unit,
    val stopRingToneAndTimeoutCheckFn: () -> Unit,
    val switchToInstantCallFn: () -> Unit,
    val handleConnectedStateFn: () -> Unit,
    val onFeedbackIdentityResolvedFn: (String, String?, String?) -> Unit,
    val onNetworkPoorStateChangedFn: (Boolean) -> Unit,
    val getCurrentCallTypeFn: () -> String,
    val getCurrentRoomIdFn: () -> String?,
) {
    fun showBarrage(participant: Participant, message: String, type: Int? = RTM_MESSAGE_TYPE_DEFAULT) =
        showBarrageFn(participant, message, type)
    fun setMicEnabled(enabled: Boolean, publishMuted: Boolean = false, isShowBarrage: Boolean = true) =
        setMicEnabledFn(enabled, publishMuted, isShowBarrage)
    fun resetNoBodySpeakCheck() = resetNoBodySpeakCheckFn()
    fun sendHangUpBroadcast(roomId: String) = sendHangUpBroadcastFn(roomId)
    fun stopRingToneAndTimeoutCheck() = stopRingToneAndTimeoutCheckFn()
    fun switchToInstantCall() = switchToInstantCallFn()
    fun handleConnectedState() = handleConnectedStateFn()
    fun onFeedbackIdentityResolved(userSid: String, userIdentity: String?, roomSid: String?) =
        onFeedbackIdentityResolvedFn(userSid, userIdentity, roomSid)
    fun onNetworkPoorStateChanged(poor: Boolean) = onNetworkPoorStateChangedFn(poor)
    fun getCurrentCallType(): String = getCurrentCallTypeFn()
    fun getCurrentRoomId(): String? = getCurrentRoomIdFn()
}

/**
 * Consumes `Room.events` and dispatches them to the relevant collaborators.
 *
 * Extracted from `LCallViewModel.handleRoomEvents` so the ViewModel can stay
 * within the 500-line project limit. Owns:
 *  - The single event-collector coroutine
 *  - Network-quality debounce state
 *  - Active-speaker → UI enable watchdog
 *  - Screen share state transitions (start/stop + resubscription verification)
 */
internal class RoomEventDispatcher(
    private val scope: CoroutineScope,
    private val room: Room,
    private val roomCtl: CallRoomController,
    private val rtm: RtmMessageHandler,
    private val connectionCoordinator: CallConnectionCoordinator,
    private val callUiController: CallUiController,
    private val participantManager: ParticipantManager,
    private val screenSharePreWarmer: ScreenSharePreWarmer,
    private val timeoutMonitor: CallTimeoutMonitor,
    private val timerManager: TimerManager,
    private val speakerState: SpeakerStateHolder,
    private val callDataManager: CallDataManager,
    private val json: Json,
    private val mySelfId: String,
    private val host: RoomEventHost,
) {

    private val goodQualities = setOf(ConnectionQuality.EXCELLENT, ConnectionQuality.GOOD)
    private val networkPoorInterval = 60_000L
    private val speakingWatchdogTimeoutMs = 3000L

    private var lastLocalPoorErrorTime: Long = 0L
    private var speakingWatchdogJob: Job? = null

    fun startCollectingRoomEvents() {
        scope.launch {
            room.events.collect { event -> dispatch(event) }
        }
    }

    fun startCollectingParticipants() {
        scope.launch {
            room::remoteParticipants.flow.map { remoteParticipants ->
                (listOf<Participant>(room.localParticipant) +
                    remoteParticipants
                        .keys
                        .sortedBy { it.value }
                        .mapNotNull { remoteParticipants[it] })
            }.collectLatest { updatedParticipants ->
                participantManager.setParticipants(updatedParticipants)
                participantManager.resortParticipants()
            }
        }
    }

    fun cancelJobs() {
        speakingWatchdogJob?.cancel()
        speakingWatchdogJob = null
    }

    private fun dispatch(event: RoomEvent) {
        when (event) {
            is RoomEvent.Disconnected -> {
                L.i { "[Call] RoomEventDispatcher room event disconnected, message = ${event.reason}" }
                if (connectionCoordinator.isRetryUrlConnecting) return
                if (event.reason != DisconnectReason.CLIENT_INITIATED) {
                    if (event.reason == DisconnectReason.RECONNECT_FAILED) {
                        roomCtl.updateCallStatus(CallStatus.RECONNECT_FAILED)
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
            is RoomEvent.ParticipantDisconnected -> onParticipantDisconnected()
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
                callUiController.setSpeakingEnabled(false)
                screenSharePreWarmer.markReconnecting()
            }
            is RoomEvent.Connected -> onConnected()
            is RoomEvent.TrackMuted -> onTrackMuted(event)
            is RoomEvent.TrackUnmuted -> onTrackUnmuted(event)
            is RoomEvent.TrackSubscribed -> {
                checkRemoteUserScreenShare(event.participant)
                if (event.publication.source == Track.Source.CAMERA && !event.publication.muted) {
                    participantManager.resortParticipants()
                }
                screenSharePreWarmer.reWarmIfNeeded(
                    event.participant,
                    event.publication.source,
                    callUiController.isShareScreening.value,
                    participantManager.screenSharingUser.value?.identity?.value,
                )
                screenSharePreWarmer.handleTrackSubscribedIfPending(::onResubscriptionSettled)
            }
            is RoomEvent.TrackUnsubscribed -> checkRemoteUserScreenShare(event.participant)
            is RoomEvent.RoomMetadataChanged -> refreshRoomMetadata()
            is RoomEvent.ConnectionQualityChanged -> onConnectionQualityChanged(event.participant, event.quality)
            is RoomEvent.ActiveSpeakersChanged -> {
                if (!callUiController.speakingEnabled.value) callUiController.setSpeakingEnabled(true)
                resetSpeakingWatchdog(event.speakers.isNotEmpty())
                speakerState.onActiveSpeakersChanged(event.speakers)
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
                    host.sendHangUpBroadcast(rid)
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
                        timerManager.startCountdown(left, onEnded = { }, onTick = { callUiController.setCountDownDurationStr(it) })
                    }
                    RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN -> {
                        val left = calculateCountDownDuration(data.expiredTimeMs, data.currentTimeMs)
                        timerManager.startCountdown(left, onEnded = { }, onTick = { callUiController.setCountDownDurationStr(it) })
                    }
                    RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN -> timerManager.stopCountdown()
                }
            },
        )
    }

    private fun onConnected() {
        val rid = host.getCurrentRoomId() ?: return
        L.i { "[Call] RoomEventDispatcher room event connected." }
        host.onFeedbackIdentityResolved(
            userSid = room.localParticipant.sid.value,
            userIdentity = room.localParticipant.identity?.value,
            roomSid = room.sid?.sid,
        )
        callDataManager.updateCallingState(rid, isInCalling = true)
        if (host.getCurrentCallType() == CallType.ONE_ON_ONE.type) {
            host.setMicEnabled(true)
            when {
                room.remoteParticipants.size > 1 -> {
                    host.switchToInstantCall()
                    host.handleConnectedState()
                }
                room.remoteParticipants.size == 1 -> host.handleConnectedState()
                else -> timeoutMonitor.start1V1Timeout(rid)
            }
        } else {
            host.handleConnectedState()
            room::ttCallResp.get()?.let { response ->
                val autoPublishSilenceAudio = response.callOptions.autoPublishSilenceAudio
                L.i { "[call] RoomEventDispatcher room event connected, autoPublishSilenceAudio=$autoPublishSilenceAudio" }
                if (autoPublishSilenceAudio) host.setMicEnabled(true, publishMuted = true, isShowBarrage = false)
            }
        }
        refreshRoomMetadata()
    }

    private fun onParticipantConnected(participant: Participant) {
        host.showBarrage(participant, getString(R.string.call_barrage_message_join))
        host.stopRingToneAndTimeoutCheck()
        if (host.getCurrentCallType() == CallType.ONE_ON_ONE.type) {
            host.handleConnectedState()
            if (room.remoteParticipants.size > 1) host.switchToInstantCall()
        }
        refreshRoomMetadata()
        participantManager.updateAwaitingJoinInvitees()
    }

    private fun onParticipantDisconnected() {
        timeoutMonitor.onParticipantDisconnected(host.getCurrentCallType() == CallType.ONE_ON_ONE.type)
    }

    private fun onTrackMuted(event: RoomEvent.TrackMuted) {
        if (event.publication.source == Track.Source.MICROPHONE &&
            event.publication.muted &&
            event.publication.subscribed &&
            event.participant is RemoteParticipant
        ) {
            if (shouldShowBarrageForRemoteParticipant(event.participant)) {
                host.showBarrage(event.participant, getString(R.string.call_barrage_message_close_mic))
            }
        }
    }

    private fun onTrackUnmuted(event: RoomEvent.TrackUnmuted) {
        if (event.publication.source == Track.Source.MICROPHONE && event.participant is RemoteParticipant) {
            if (shouldShowBarrageForRemoteParticipant(event.participant)) {
                host.showBarrage(event.participant, getString(R.string.call_barrage_message_open_mic))
            }
        }
        if (event.participant is RemoteParticipant && event.publication.source == Track.Source.CAMERA) {
            participantManager.resortParticipants()
        }
    }

    private fun shouldShowBarrageForRemoteParticipant(participant: Participant): Boolean {
        val participantUid = IdUtil.getUidByIdentity(participant.identity?.value)
        return participantUid != null && participantUid != mySelfId
    }

    private fun checkRemoteUserScreenShare(participant: Participant) {
        if (participant !is RemoteParticipant) return
        val isSharing = participant.getTrackPublication(Track.Source.SCREEN_SHARE) != null
        if (isSharing && !callUiController.isShareScreening.value) {
            L.i { "[Call] RoomEventDispatcher ${participant.identity?.value} start screen sharing." }
            screenSharePreWarmer.preWarmSharer(participant)
            participantManager.resortParticipants()
            participantManager.setScreenSharingUser(participant)
            callUiController.setShareScreening(true)
            callUiController.setShowTopStatusViewEnabled(true)
            callUiController.setShowBottomToolBarViewEnabled(true)
            host.showBarrage(participant, getString(R.string.call_barrage_message_screensharing))
        }
        if (participantManager.screenSharingUser.value?.identity?.value == participant.identity?.value && !isSharing) {
            if (roomCtl.callStatus.value == CallStatus.RECONNECTING || screenSharePreWarmer.isPendingResubscription) return
            L.i { "[Call] RoomEventDispatcher ${participant.identity?.value} stop screen sharing." }
            screenSharePreWarmer.cleanupAll()
            participantManager.resortParticipants()
            callUiController.setShareScreening(false)
            participantManager.setScreenSharingUser(null)
            callUiController.setShowTopStatusViewEnabled(true)
            callUiController.setShowBottomToolBarViewEnabled(true)
        }
    }

    private fun onResubscriptionSettled() {
        callUiController.incrementReconnectCount()
        participantManager.screenSharingUser.value?.let { checkRemoteUserScreenShare(it) }
    }

    private fun refreshRoomMetadata() {
        val metadata = room.metadata?.takeIf { it.isNotBlank() } ?: return
        scope.launch(Dispatchers.Default) {
            runCatching { json.decodeFromString<RoomMetadata>(metadata) }
                .onSuccess { decoded -> roomCtl.updateRoomMetadata(decoded) }
                .onFailure { e -> L.e(e) { "[Call] RoomEventDispatcher metadata parse failed" } }
        }
    }

    private fun onConnectionQualityChanged(participant: Participant, quality: ConnectionQuality) {
        L.i { "[Call] RoomEventDispatcher ConnectionQualityChanged ${participant.identity?.value} quality = ${quality.name}." }
        if (participant !is LocalParticipant) return
        val isPoorNow = quality !in goodQualities
        val now = SystemClock.elapsedRealtime()
        if (isPoorNow) {
            val shouldNotify = (now - lastLocalPoorErrorTime > networkPoorInterval)
            if (shouldNotify) {
                roomCtl.collectError(NetworkConnectionPoorException(getString(R.string.call_myself_network_poor_tip)))
                lastLocalPoorErrorTime = now
            }
            host.onNetworkPoorStateChanged(true)
        } else {
            host.onNetworkPoorStateChanged(false)
        }
    }

    private fun resetSpeakingWatchdog(hasSpeakers: Boolean) {
        speakingWatchdogJob?.cancel()
        if (hasSpeakers) {
            speakingWatchdogJob = scope.launch {
                delay(speakingWatchdogTimeoutMs)
                callUiController.setSpeakingEnabled(false)
            }
        }
    }

    private fun calculateCountDownDuration(expiredTimeMs: Long, currentTimeMs: Long): Long =
        if (expiredTimeMs < currentTimeMs) 0 else (expiredTimeMs - currentTimeMs) / 1000
}
