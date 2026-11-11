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











class ScreenSharePreWarmer(
    private val scope: CoroutineScope,
) {
    
    @Volatile
    var isPendingResubscription: Boolean = false
        private set

    private var resubscriptionTimeoutJob: Job? = null
    private var screenSharePreWarmTrack: RemoteVideoTrack? = null
    private var screenSharePreWarmSink: VideoSink? = null
    private var cameraPreWarmTrack: RemoteVideoTrack? = null
    private var cameraPreWarmSink: VideoSink? = null

    
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

    
    fun cleanupAll() {
        cleanupScreenShare()
        cleanupCamera()
    }

    
    fun preWarmSharer(participant: RemoteParticipant) {
        preWarmScreenShare(participant)
        preWarmCamera(participant)
    }

    






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

    
    fun markReconnecting() {
        cleanupAll()
    }

    



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
