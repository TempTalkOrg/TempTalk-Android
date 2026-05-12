package com.difft.android.call.ui.screenshare

import com.difft.android.base.log.lumberjack.L
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.video.VideoSinkVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.org.webrtc.VideoSink

/**
 * Owns the screen share / camera pre-warm rendering state and the
 * resubscription watchdog during reconnection.
 *
 * Solves: `RemoteTrackPublication.track` setter reads `lastVisibility=false`
 * (sinkVisibilityMap empty) and sends `disabled=true` before the UI renderer
 * can attach (blocked by orientation change > 100 ms). By attaching a no-op
 * renderer immediately on `TrackSubscribed` (within ~5 ms), `recalculateVisibility()`
 * sees `isVisible=true` and replaces `disabled=true` within the 100 ms debounce.
 */
class ScreenSharePreWarmer(
    private val scope: CoroutineScope,
) {
    /** Whether we are currently waiting for post-reconnect resubscription to settle. */
    @Volatile
    var isPendingResubscription: Boolean = false
        private set

    private var resubscriptionTimeoutJob: Job? = null
    private var screenSharePreWarmTrack: RemoteVideoTrack? = null
    private var screenSharePreWarmSink: VideoSink? = null
    private var cameraPreWarmTrack: RemoteVideoTrack? = null
    private var cameraPreWarmSink: VideoSink? = null

    /** Attach a no-op renderer with always-visible visibility to the screen share track. */
    fun preWarmScreenShare(participant: RemoteParticipant) {
        cleanupScreenShare()
        val track = participant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? RemoteVideoTrack ?: return
        val sink = VideoSink { }
        val visibility = alwaysVisible()
        try {
            track.addRenderer(sink, visibility)
            screenSharePreWarmTrack = track
            screenSharePreWarmSink = sink
        } catch (e: Exception) {
            L.w { "[Call] preWarmScreenShareRenderer failed: ${e.message}" }
        }
    }

    fun cleanupScreenShare() {
        screenSharePreWarmSink?.let { sink ->
            try {
                screenSharePreWarmTrack?.removeRenderer(sink)
            } catch (e: Exception) {
                L.w { "[Call] cleanupScreenSharePreWarm failed: ${e.message}" }
            }
        }
        screenSharePreWarmTrack = null
        screenSharePreWarmSink = null
    }

    /** Keep a no-op renderer on the screen sharer's camera track so that speaker
     *  switches in the floating window don't trigger disabled=true → resume delay. */
    fun preWarmCamera(participant: RemoteParticipant) {
        cleanupCamera()
        val track = participant.getTrackPublication(Track.Source.CAMERA)?.track as? RemoteVideoTrack ?: return
        val sink = VideoSink { }
        val visibility = alwaysVisible()
        try {
            track.addRenderer(sink, visibility)
            cameraPreWarmTrack = track
            cameraPreWarmSink = sink
        } catch (e: Exception) {
            L.w { "[Call] preWarmCameraRenderer failed: ${e.message}" }
        }
    }

    fun cleanupCamera() {
        cameraPreWarmSink?.let { sink ->
            try {
                cameraPreWarmTrack?.removeRenderer(sink)
            } catch (e: Exception) {
                L.w { "[Call] cleanupCameraPreWarm failed: ${e.message}" }
            }
        }
        cameraPreWarmTrack = null
        cameraPreWarmSink = null
    }

    /** Convenience: clean up both pre-warm tracks. */
    fun cleanupAll() {
        cleanupScreenShare()
        cleanupCamera()
    }

    /** Start / restart pre-warm renderers for the given sharer. */
    fun preWarmSharer(participant: RemoteParticipant) {
        preWarmScreenShare(participant)
        preWarmCamera(participant)
    }

    /**
     * Re-attach pre-warm sinks when a track is (re-)subscribed during an active screen share.
     * Covers: reconnection re-subscription and camera republish by the screen sharer.
     *
     * Uses track identity comparison (not null-check) because the old pre-warm refs may
     * still point to a defunct track after unpublish/republish.
     */
    fun reWarmIfNeeded(
        participant: Participant,
        source: Track.Source?,
        isShareScreening: Boolean,
        sharerIdentityValue: String?,
    ) {
        if (!isShareScreening) return
        if (sharerIdentityValue == null) return
        if (participant.identity?.value != sharerIdentityValue) return
        if (participant !is RemoteParticipant) return

        when (source) {
            Track.Source.SCREEN_SHARE -> {
                val currentTrack = participant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track
                if (screenSharePreWarmTrack !== currentTrack) preWarmScreenShare(participant)
            }
            Track.Source.CAMERA -> {
                val currentTrack = participant.getTrackPublication(Track.Source.CAMERA)?.track
                if (cameraPreWarmTrack !== currentTrack) preWarmCamera(participant)
            }
            else -> {}
        }
    }

    /** Called on `RoomEvent.Reconnecting`. */
    fun markReconnecting() {
        cleanupAll()
    }

    /**
     * Called on `RoomEvent.Reconnected`. Arms a 10 s watchdog that will fire
     * [onComplete] once resubscription stabilizes (or the deadline lapses).
     */
    fun markReconnected(onComplete: () -> Unit) {
        isPendingResubscription = true
        resubscriptionTimeoutJob?.cancel()
        resubscriptionTimeoutJob = scope.launch {
            delay(WATCHDOG_TIMEOUT_MS)
            if (isPendingResubscription) {
                isPendingResubscription = false
                onComplete()
            }
        }
    }

    /**
     * Called on `RoomEvent.TrackSubscribed` while resubscription is pending.
     * Coalesces a fast 500 ms tail so subsequent subscriptions in the same batch
     * are absorbed before [onComplete] is invoked exactly once.
     */
    fun handleTrackSubscribedIfPending(onComplete: () -> Unit) {
        if (!isPendingResubscription) return
        resubscriptionTimeoutJob?.cancel()
        resubscriptionTimeoutJob = scope.launch {
            delay(COALESCE_DELAY_MS)
            isPendingResubscription = false
            onComplete()
        }
    }

    fun cancelJobs() {
        resubscriptionTimeoutJob?.cancel()
        resubscriptionTimeoutJob = null
    }

    private fun alwaysVisible(): VideoSinkVisibility = object : VideoSinkVisibility() {
        override fun isVisible() = true
        override fun size() = Track.Dimensions(0, 0)
    }

    private companion object {
        const val WATCHDOG_TIMEOUT_MS = 10_000L
        const val COALESCE_DELAY_MS = 500L
    }
}
