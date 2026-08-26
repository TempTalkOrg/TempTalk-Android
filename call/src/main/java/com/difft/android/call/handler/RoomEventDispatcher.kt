package com.difft.android.call.handler

import android.os.SystemClock
import com.difft.android.call.BuildConfig
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
import com.difft.android.call.exception.DisconnectException
import com.difft.android.call.data.CallStatisticsEvent
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.CallStatisticsLogManager
import com.difft.android.call.manager.ParticipantManager
import com.difft.android.call.manager.SpeakerStateHolder
import com.difft.android.call.manager.TimerManager
import com.difft.android.call.network.NetworkQualityCoordinator
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

/**
 * UI-layer side effects that cannot be moved cleanly out of the ViewModel.
 * Bundled as lambdas so `LCallViewModel` can construct one with method
 * references without having to implement an interface (~25 line savings in
 * the VM under the 500-line cap).
 */
data class RoomEventHost(
    val showBarrageFn: (Participant, String, Int?) -> Unit,
    val setMicEnabledFn: (Boolean, Boolean, Boolean) -> Unit,
    val setCameraEnabledFn: (Boolean) -> Unit,
    val resetNoBodySpeakCheckFn: () -> Unit,
    val sendHangUpBroadcastFn: (String) -> Unit,
    val stopRingToneAndTimeoutCheckFn: () -> Unit,
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
    fun sendHangUpBroadcast(roomId: String) = sendHangUpBroadcastFn(roomId)
    fun stopRingToneAndTimeoutCheck() = stopRingToneAndTimeoutCheckFn()

    /**
     * Re-runs the authoritative call-type decision synchronously (server `room.metadata.callType`
     * plus the live participant count). Must complete before any decision that depends on the
     * meeting type — notably the join-time microphone default in [RoomEventDispatcher.onConnected].
     */
    fun resolveCallType() = resolveCallTypeFn()
    fun handleConnectedState() = handleConnectedStateFn()
    fun startCallDurationTimer() = startCallDurationTimerFn()
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
    private val statisticsLogManager: CallStatisticsLogManager,
    private val mySelfId: String,
    private val networkQuality: NetworkQualityCoordinator,
    private val host: RoomEventHost,
) {

    private val goodQualities = setOf(ConnectionQuality.EXCELLENT, ConnectionQuality.GOOD)
    private val speakingWatchdogTimeoutMs = 3000L

    private var speakingWatchdogJob: Job? = null

    /**
     * Remote identities we've already surfaced a "mic on" barrage for, until they mute
     * or genuinely leave. Deduplicates the open-mic barrage across its two triggers
     * (first subscribe of an already-unmuted track in [onMicTrackSubscribed], and the
     * mute→unmute transition in [onTrackUnmuted]) and — crucially — across every
     * reconnect variant: a full reconnect, a manual server switch, or a staggered late
     * re-subscribe all re-deliver `TrackSubscribed` for existing unmuted tracks with no
     * `TrackUnmuted`, which would otherwise re-fire the barrage for everyone. Mutated only
     * from the single serial event-collector coroutine, so a plain set is thread-safe.
     */
    private val remoteMicOnAnnounced = mutableSetOf<String>()

    /**
     * Timing probe for the 1v1 "when can the remote actually hear me" question. Measures the gap
     * between the three milestones we currently conflate: local room connect, the remote joining
     * (signaling only — its PeerConnection may still be connecting), and the remote reaching
     * ACTIVE, which the SFU sets once that participant's primary transport is connected — the
     * point its subscriber path can actually carry our audio. Observation only; no behaviour
     * depends on these.
     */
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

    /**
     * 1v1 media-ready gate for the call duration timer.
     *
     * The timer starts when the remote subscribes to the local microphone. A
     * [MEDIA_READY_FALLBACK_MS] fallback covers calls without that signal so the UI never remains
     * on "connecting" indefinitely.
     */
    private var callTimerStarted = false
    private var callTimerGateArmed = false
    private var micTrackSubscribed = false
    private var callTimerFallbackJob: Job? = null

    /**
     * Armed only when this client is connected and a remote is present. The subscription signal is
     * sticky because it can arrive before this lifecycle gate is ready.
     */
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

    /** Sticky notification that the remote subscribed to the local microphone track. */
    private fun onLocalMicTrackSubscribed() {
        if (micTrackSubscribed) return
        micTrackSubscribed = true
        startCallTimerIfTrackSubscribed()
    }

    /**
     * The single release point. Group/instant never arm the gate, so they never take this path;
     * their timer keeps starting from room connect. The subscription notifier deliberately records
     * the signal before [callTimerGateArmed] because the SDK cannot query it afterwards.
     */
    private fun startCallTimerIfTrackSubscribed() {
        if (!callTimerGateArmed || !micTrackSubscribed) return
        startCallTimerOnce("trackSubscribed")
    }

    /** Idempotent: reconnects and server switches must not restart or reset a running timer. */
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
                participantManager.resortParticipants()
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
                callUiController.setSpeakingEnabled(false)
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
            // RoomMetadataChanged is deliberately absent: CallTypeCoordinator collects
            // `room.metadata` directly, which covers the initial value and every later change in one
            // place (and de-duplicates equal values, unlike an event hook).
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
            onServerEndCall = serverEndCall@{
                // RTM protocol §4.1. Downstream teardown is idempotent — server retries are safe.
                if (roomCtl.roomOrNull() !== room) {
                    L.w { "[Call] server-end-call ignored - inactive room" }
                    return@serverEndCall
                }
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
        // FIRST, and above the roomId guard below: the microphone bring-up further down is a
        // once-per-call decision driven by the meeting type, so the authoritative type has to be in
        // place before it runs. This resolve reads `room.metadata`, which the server populates while
        // handling JoinResponse and is therefore guaranteed present by the time Connected fires —
        // unlike `roomId` and the CallData entry, which an outbound call only assigns once
        // ttCallResp has been processed, on a different coroutine. Depending on those for the type
        // would make the mic default hostage to that race.
        host.resolveCallType()
        val rid = host.getCurrentRoomId() ?: return
        // The SDK re-emits RoomEvent.Connected (not Reconnected) after an ICE-restart / soft
        // resume, because a transient primary-PeerConnection DISCONNECTED clobbers the RESUMING
        // state. On such a resume the existing mic track is preserved (full reconnect republishes
        // it); only a first connect or an app-initiated server switch (disconnect + reconnect that
        // tears the track down) leaves no mic publication. So gate the mic bring-up on the absence
        // of a mic publication rather than on "is this the first connect": re-running it while a
        // publication still exists would force-unmute a self-muted user (setMicrophoneEnabled(true)
        // unmutes the published track, ignoring publishMuted) yet keep the UI showing muted, while
        // a server switch still re-publishes the track as needed.
        val hasMicPublication = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE) != null
        // Consume-on-use: only the first Connected of a manual-switch reconnect takes the restore
        // path; later ICE-restart re-emits (tracks preserved) fall through to the normal branch.
        val isManualSwitch = connectionCoordinator.consumeManualSwitchReconnecting()
        L.i { "[Call] RoomEventDispatcher room event connected. hasMicPublication=$hasMicPublication manualSwitch=$isManualSwitch" }
        val isFirstConnect = connectedAtMs == 0L
        if (isFirstConnect) connectedAtMs = SystemClock.elapsedRealtime()
        logCallTiming("roomConnected", " firstConnect=$isFirstConnect remotes=${room.remoteParticipants.size}")
        // The remote can already be in the room when we connect (callee joining a room the caller is
        // waiting in), in which case no ParticipantConnected will ever fire for it.
        room.remoteParticipants.values.firstOrNull()?.let { remote ->
            if (remoteJoinedAtMs == 0L) {
                remoteJoinedAtMs = SystemClock.elapsedRealtime()
                logCallTiming(
                    "remoteAlreadyPresent",
                    " alreadyActive=${remote.state == Participant.State.ACTIVE}",
                )
            }
        }
        host.onFeedbackIdentityResolved(
            userSid = room.localParticipant.sid.value,
            userIdentity = room.localParticipant.identity?.value,
            roomSid = room.sid?.sid,
        )
        callDataManager.updateCallingState(rid, isInCalling = true)
        // Outside the type branch below on purpose. This clears a timeout the teardown armed: the old
        // session emits a transient ParticipantDisconnected for the still-present remote, which on
        // reconnect comes back via initial state sync rather than a ParticipantConnected event, so
        // nothing else would ever clear it and it ends a live call with "对方未接听" ~60s later.
        // Arming happens only while the call is 1v1, but resolveCallType() ran at the top of this
        // method, so the switch window itself can have turned the call instant — a third participant
        // joined, or the server flipped callType for an invite whose RoomUpdate the switch missed.
        // Inside the 1v1 branch those cases would skip the cancel.
        if (isManualSwitch) timeoutMonitor.cancelIfActive()
        if (host.getCurrentCallType() == CallType.ONE_ON_ONE.type) {
            val presentRemote = room.remoteParticipants.values.firstOrNull()
            if (isManualSwitch) {
                // The call is already established; a manual switch is NOT a fresh outbound call,
                // so re-apply the user's media intent rather than the first-connect bring-up.
                restoreMediaAfterServerSwitch()
                // Upgrading the type on a crowded room is no longer decided here — resolveCallType()
                // at the top of this method already did it from the live participant count. All that
                // is left is: somebody is present → CONNECTED, nobody is → arm the 1v1 timeout.
                // Narrow race for the empty case: the remote genuinely left during the
                // disconnect/reconnect window. Don't mark the call CONNECTED with nobody in it — arm
                // the timeout so the empty call is detected and ended, matching first connect. Note
                // this arms AFTER the cancel above, which is the order that has to hold.
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

    /**
     * A user-initiated server-node / connection-mode switch does a full disconnect+reconnect
     * (see [CallConnectionCoordinator.connectToRoomManualSwitch]), which destroys the local
     * mic/camera publications. The reconnect starts a fresh session with no tracks, so the
     * control toggles — driven by `roomCtl.micEnabled/cameraEnabled`, which the switch path
     * never resets — would keep showing "on" while nothing is actually published/captured.
     *
     * Re-apply the user's pre-switch intent (read live from `roomCtl`). Only enable when desired is
     * `true`: a self-muted user is left muted (never force-unmuted), matching the guard the
     * first-connect bring-up relies on. For a muted group participant, still publish silence when
     * the server requests it, preserving first-connect presence semantics. Invoked only on the
     * single manual-switch reconnect (flag is consumed in `onConnected`), so it never runs on an
     * ICE-restart re-emit where tracks are preserved.
     */
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
        // After the "was this a 1v1" read above, since a third participant joining is exactly what
        // downgrades `1on1` to instant — re-resolving first would make the read miss the transition.
        host.resolveCallType()
        participantManager.updateAwaitingJoinInvitees()
    }

    private fun onParticipantDisconnected(participant: Participant) {
        // Above the switch/reconnect guard on purpose: a dropped verdict is re-seeded on the next
        // CONNECTED, while keeping one risks a stale badge for someone who really left.
        networkQuality.onParticipantLeft(participant.identity?.value)

        // A manual server switch / failover reconnect tears the old session down, which emits a
        // transient ParticipantDisconnected for the still-present remote. Arming the "participant
        // left" timeout here would fire "对方未接听" after reconnect, because the remote comes back
        // via initial state sync (not a ParticipantConnected event) and the timer is never
        // cancelled. Ignore disconnects only during the transient switch/reconnect window
        // (SWITCHING_SERVER status / failover retry) — NOT via the sticky isManualSwitchReconnecting
        // flag, which stays set after the switch and would swallow a genuine later remote leave.
        if (roomCtl.callStatus.value == CallStatus.SWITCHING_SERVER ||
            connectionCoordinator.isRetryUrlConnecting
        ) {
            L.i { "[Call] RoomEventDispatcher ignore ParticipantDisconnected during switch/reconnect" }
            return
        }
        // Genuine leave (not a transient switch disconnect): drop any mic-on dedup entry so a
        // later rejoin with the same identity announces its mic again.
        participant.identity?.value?.let { remoteMicOnAnnounced.remove(it) }
        timeoutMonitor.onParticipantDisconnected(host.getCurrentCallType() == CallType.ONE_ON_ONE.type)
    }

    private fun onTrackMuted(event: RoomEvent.TrackMuted) {
        if (event.publication.source == Track.Source.MICROPHONE &&
            event.publication.muted &&
            event.publication.subscribed &&
            event.participant is RemoteParticipant
        ) {
            // Mic-off ends this remote's on-episode: clear so the next mic-on barrages again.
            event.participant.identity?.value?.let { remoteMicOnAnnounced.remove(it) }
            if (shouldShowBarrageForRemoteParticipant(event.participant)) {
                host.showBarrage(event.participant, getString(R.string.call_barrage_message_close_mic))
            }
        }
    }

    private fun onTrackUnmuted(event: RoomEvent.TrackUnmuted) {
        if (event.publication.source == Track.Source.MICROPHONE && event.participant is RemoteParticipant) {
            // Dedup with the first-subscribe barrage (see [remoteMicOnAnnounced]): a reconnect
            // may re-deliver an unmute for an already-announced remote, which must not re-fire.
            // Check shouldShow first (matching onMicTrackSubscribed) so a filtered-out identity
            // is never marked "announced" as a side effect, which would suppress it forever.
            val identity = event.participant.identity?.value
            if (shouldShowBarrageForRemoteParticipant(event.participant) &&
                (identity == null || remoteMicOnAnnounced.add(identity))
            ) {
                host.showBarrage(event.participant, getString(R.string.call_barrage_message_open_mic))
            }
        }
        if (event.participant is RemoteParticipant && event.publication.source == Track.Source.CAMERA) {
            participantManager.resortParticipants()
        }
    }

    /**
     * A remote's first mic-ON has no mute→unmute transition: the track is subscribed
     * already-unmuted, so [onTrackUnmuted] never fires for it and the open-mic barrage
     * would be lost (mic-OFF and later ON still work via mute events). Emit it here for
     * a genuine new subscription.
     *
     * Dedup via [remoteMicOnAnnounced] rather than a connection-status guard: the status
     * is unreliable across reconnect variants (a manual server switch re-subscribes under
     * SWITCHING_SERVER/RECONNECTED, and a staggered late re-subscribe lands after the
     * pending-resubscription window closes), so a status check would still let those
     * re-subscribes spam a false open-mic barrage. The dedup set stays populated across
     * reconnects, so re-subscribing an already-announced remote is a no-op; remotes
     * subscribed while muted get their barrage later via [onTrackUnmuted].
     */
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
        // reconnectCount 自增作为"重绑代"信号：不再用于 compose key（不销毁重建 renderer、不黑闪），
        // 而是驱动 VideoRenderer 对当前 track 原地 removeRenderer+addRenderer 刷新 sink，确保全量
        // 重连（track 可能是新对象、或底层流已替换）后新帧能恢复，避免永久卡在最后一帧。
        callUiController.incrementReconnectCount()
        participantManager.screenSharingUser.value?.let { checkRemoteUserScreenShare(it) }
    }

    private fun onConnectionQualityChanged(participant: Participant, quality: ConnectionQuality) {
        // Local AND remote readings feed the verdict unit, which owns the hysteresis and logs real
        // tier transitions — no per-event log here: the SDK re-reports the same tier repeatedly.
        networkQuality.onQualityChanged(
            identity = participant.identity?.value,
            quality = quality,
            isLocal = participant is LocalParticipant,
        )
        // Local-only, and BELOW the feed: this drives the post-call rating trigger, whose "was MY
        // network bad" meaning must not change. `goodQualities` stays verbatim — it counts UNKNOWN as
        // poor, unlike the render-side mapping; re-classifying it would change the rating frequency.
        if (participant !is LocalParticipant) return
        host.onNetworkPoorStateChanged(quality !in goodQualities)
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

    companion object {
        /**
         * 设置为 true 可注入 20 个假参会人，用于测试竖屏多人通话列表滚动。
         * 测试完毕后务必改回 false。
         */
        private const val DEBUG_FAKE_PARTICIPANTS = false

        /** See [armCallTimerGate]. */
        private const val MEDIA_READY_FALLBACK_MS = 5_000L
    }
}
