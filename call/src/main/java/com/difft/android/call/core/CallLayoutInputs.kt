package com.difft.android.call.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.layout.CallLayoutController
import com.difft.android.call.layout.CallMember
import com.difft.android.call.layout.ControllerState
import com.difft.android.call.layout.LayoutMode
import com.difft.android.call.layout.ScreenShare
import com.difft.android.call.ui.video.FrameSizeProbe
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.util.flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.difft.android.base.call.CallType as AppCallType
import com.difft.android.call.layout.CallType as LayoutCallType


internal fun callMemberId(participant: Participant): String = participant.identity?.value ?: participant.sid.value


fun interface CallFrameSizeSink {
    fun onFrameSize(id: String, width: Int, height: Int, seenAtNanos: Long)
}


private const val DEFAULT_SHARE_WIDTH = 16.0
private const val DEFAULT_SHARE_HEIGHT = 9.0







internal class CallLayoutInputs(
    private val controller: CallLayoutController,
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
) : CallFrameSizeSink {
    private val frames = CallFrameSizeCache(clock)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var jobs: List<Job> = emptyList()
    private var localTrack: LocalVideoTrack? = null
    private var localProbe: FrameSizeProbe? = null
    private var localCameraOn = false
    private var members: List<CallMember> = emptyList()
    private var oneOnOne = false
    private val shareSize = ShareSize()
    private var layoutState = ControllerState()
    private val _pipContentAspect = MutableStateFlow<Float?>(null)

    




    val pipContentAspect: StateFlow<Float?> = _pipContentAspect.asStateFlow()

    fun start(
        scope: CoroutineScope,
        room: Room,
        callType: StateFlow<String>,
        participants: StateFlow<List<Participant>>,
        screenSharingUser: StateFlow<RemoteParticipant?>,
        schedulerHold: StateFlow<Boolean>,
    ) {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launchLogged("members") {
                
                combine(callType, members(participants)) { type, list -> type to list }.collect { (type, list) ->
                    oneOnOne = type == AppCallType.ONE_ON_ONE.type
                    controller.setCallType(if (oneOnOne) LayoutCallType.ONE_ON_ONE else LayoutCallType.GROUP)
                    onMembers(list)
                }
            },
            scope.launchLogged("speakers") {
                
                room::activeSpeakers.flow.map { speakers -> speakers.sortedByDescending { it.audioLevel }.map(::callMemberId) }
                    .distinctUntilChanged()
                    .collect(controller::setSpeakers)
            },
            scope.launchLogged("share") { share(screenSharingUser).collect(::onShare) },
            scope.launchLogged("hold") { schedulerHold.collect(controller::setSchedulerHold) },
            scope.launchLogged("localProbe") {
                room.localParticipant::videoTrackPublications.flow
                    .map { pubs -> pubs.firstOrNull { (pub) -> pub.source == Track.Source.CAMERA }?.second as? LocalVideoTrack }
                    .distinctUntilChanged()
                    .collect { track -> attachLocalProbe(track, room.localParticipant) }
            },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        attachLocalProbe(null, null)
        controller.setSchedulerHold(false)
    }

    override fun onFrameSize(id: String, width: Int, height: Int, seenAtNanos: Long) {
        frames.onFrameSize(id, width, height, seenAtNanos)?.let { controller.setVideoSize(it.id, it.width.toDouble(), it.height.toDouble()) }
        refreshPipAspect()
    }

    
    internal fun onShare(published: ScreenShare?) {
        controller.setShare(shareSize.onPublished(published))
        refreshPipAspect()
    }

    
    fun onShareFrameSize(sharerId: String, width: Int, height: Int) {
        val share = shareSize.onFrame(sharerId, width, height) ?: return
        controller.setShare(share)
        refreshPipAspect()
    }

    
    fun onLayoutState(state: ControllerState) {
        layoutState = state
        refreshPipAspect()
    }

    private fun refreshPipAspect() {
        val waiting = oneOnOne && members.none { !it.isLocal }
        _pipContentAspect.value = pipContentAspect(layoutState, shareSize.current, waiting, frames::sizeOf)
    }

    private fun onMembers(list: List<CallMember>) {
        val local = list.firstOrNull { it.isLocal }?.cameraOn == true
        
        if (localCameraOn && !local) localProbe?.resetDedupe()
        localCameraOn = local
        val turnedOn = frames.onMembers(list)
        members = list
        controller.setParticipants(list)
        turnedOn.forEach { controller.setVideoSize(it.id, it.width.toDouble(), it.height.toDouble()) }
        refreshPipAspect()
    }

    
    private fun attachLocalProbe(track: LocalVideoTrack?, local: LocalParticipant?) {
        if (track === localTrack) return
        localProbe?.let { probe ->
            probe.close()
            try {
                localTrack?.removeRenderer(probe)
            } catch (e: Exception) {
                L.w { "[Call] layout local probe remove failed: ${e.stackTraceToString()}" }
            }
        }
        localTrack = track
        localProbe = null
        if (track == null || local == null) return
        val probe = FrameSizeProbe(target = null, post = { mainHandler.post(it) }, clock = clock) { width, height, seenAt ->
            onFrameSize(callMemberId(local), width, height, seenAt)
        }
        try {
            track.addRenderer(probe)
            localProbe = probe
        } catch (e: Exception) {
            probe.close()
            L.w { "[Call] layout local probe add failed: ${e.stackTraceToString()}" }
        }
    }
}






internal fun pipContentAspect(
    state: ControllerState,
    share: ScreenShare?,
    waiting: Boolean,
    sizeOf: (String) -> FrameSize?,
): Float? {
    if (state.plan?.mode == LayoutMode.SHARE) {
        return share?.takeIf { it.width > 0 && it.height > 0 }?.let { (it.width / it.height).toFloat() }
    }
    if (waiting) return null
    val focus = state.focus ?: return null
    return sizeOf(focus)?.let { it.width.toFloat() / it.height }
}


private fun CoroutineScope.launchLogged(input: String, block: suspend () -> Unit): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        L.e { "[Call] layout input failed input=$input: ${e.stackTraceToString()}" }
    }
}


@OptIn(ExperimentalCoroutinesApi::class)
private fun members(participants: StateFlow<List<Participant>>): Flow<List<CallMember>> =
    participants.flatMapLatest { list ->
        if (list.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(list.map { p -> cameraOn(p).map { on -> CallMember(callMemberId(p), p is LocalParticipant, on) } }) { it.toList() }
        }
    }.distinctUntilChanged { old, new -> old.toSet() == new.toSet() }






@OptIn(ExperimentalCoroutinesApi::class)
private fun cameraOn(participant: Participant): Flow<Boolean> =
    participant::videoTrackPublications.flow
        .map { pubs -> pubs.firstOrNull { (pub) -> pub.subscribed && pub.source == Track.Source.CAMERA }?.first }
        .distinctUntilChanged()
        .flatMapLatest { pub -> pub?.let { it::muted.flow.map { muted -> !muted } } ?: flowOf(false) }
        .distinctUntilChanged()


@OptIn(ExperimentalCoroutinesApi::class)
private fun share(screenSharingUser: StateFlow<RemoteParticipant?>): Flow<ScreenShare?> =
    screenSharingUser.flatMapLatest { sharer ->
        if (sharer == null) {
            flowOf(null)
        } else {
            sharer::videoTrackPublications.flow.map { pubs ->
                shareOf(callMemberId(sharer), pubs.firstOrNull { (pub) -> pub.source == Track.Source.SCREEN_SHARE }?.first)
            }
        }
    }.distinctUntilChanged()

private fun shareOf(sharerId: String, publication: TrackPublication?): ScreenShare {
    val dims = publication?.dimensions?.takeIf { it.width > 0 && it.height > 0 }
    return if (dims == null) {
        ScreenShare(sharerId, DEFAULT_SHARE_WIDTH, DEFAULT_SHARE_HEIGHT)
    } else {
        ScreenShare(sharerId, dims.width.toDouble(), dims.height.toDouble())
    }
}
