package com.difft.android.call.ui.screenshare

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.ui.video.ScreenShareVisibility
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.flow
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.VideoSink

/**
 * Stable video renderer for the floating speaker window.
 *
 * Key differences from [com.difft.android.call.ui.VideoItemTrackSelector] + VideoRenderer:
 *
 * 1. **Single TextureViewRenderer**: Created once in [AndroidView.factory], never released until
 *    the composable leaves the tree. Camera toggles only trigger [VideoTrack.removeRenderer] /
 *    [VideoTrack.addRenderer] — no EGL context churn.
 *
 * 2. **Synchronous track binding**: [AndroidView.update] runs on Main thread during recomposition.
 *    Track swaps happen inline — no coroutine dispatch, no race conditions.
 *
 * 3. **Always-visible**: Uses [ScreenShareVisibility] so adaptive streaming never sends
 *    `disabled=true` for this renderer.
 */
@Composable
fun StableFloatingVideoRenderer(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
) {
    val trackPubs by participant::videoTrackPublications.flow
        .collectAsState(initial = emptyList())

    val videoTrack = remember(trackPubs) {
        participant.videoTrackPublications
            .firstOrNull { (pub, _) -> pub.subscribed && pub.source == Track.Source.CAMERA }
            ?.second as? VideoTrack
    }

    val visibility = remember { ScreenShareVisibility() }
    val viewRef = remember { arrayOfNulls<TextureViewRenderer>(1) }
    val trackRef = remember { arrayOfNulls<VideoTrack>(1) }

    DisposableEffect(Unit) {
        onDispose {
            val view = viewRef[0] ?: return@onDispose
            try { trackRef[0]?.removeRenderer(view as VideoSink) } catch (_: Exception) {}
            try { visibility.onDispose() } catch (_: Exception) {}
            try { view.release() } catch (_: Exception) {}
            viewRef[0] = null
            trackRef[0] = null
        }
    }

    AndroidView(
        factory = { context ->
            TextureViewRenderer(context).apply {
                room.initVideoRenderer(this)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                viewRef[0] = this
                val track = videoTrack
                trackRef[0] = track
                if (track != null) {
                    try {
                        if (track is RemoteVideoTrack) {
                            track.addRenderer(this as VideoSink, visibility)
                        } else {
                            track.addRenderer(this as VideoSink)
                        }
                    } catch (e: Exception) {
                        L.w { "[Call] StableFloatingRenderer factory addRenderer: ${e.message}" }
                    }
                }
            }
        },
        update = { view ->
            val current = trackRef[0]
            val target = videoTrack
            if (current !== target) {
                try {
                    current?.removeRenderer(view as VideoSink)
                } catch (e: Exception) {
                    L.w { "[Call] StableFloatingRenderer removeRenderer: ${e.message}" }
                }
                trackRef[0] = target
                if (target != null) {
                    try {
                        if (target is RemoteVideoTrack) {
                            target.addRenderer(view as VideoSink, visibility)
                        } else {
                            target.addRenderer(view as VideoSink)
                        }
                    } catch (e: Exception) {
                        L.w { "[Call] StableFloatingRenderer addRenderer: ${e.message}" }
                    }
                }
                L.d { "[Call] StableFloatingRenderer track swap: ${current?.name} -> ${target?.name}" }
            }
        },
        modifier = modifier.onGloballyPositioned { visibility.onGloballyPositioned(it) },
    )
}
