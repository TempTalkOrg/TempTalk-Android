/*
 * Copyright 2023-2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.difft.android.call.ui.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import com.difft.android.call.core.callMemberId
import com.difft.android.call.ui.layout.LocalCallFrameSizeSink
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay




private const val RECONNECT_HOLD_GRACE_MS = 5_000L







@Composable
private fun rememberDisplayVideoTrack(
    participant: Participant,
    sourceType: Track.Source,
    liveVideoTrack: VideoTrack?,
    roomState: Room.State,
    isLocalParticipant: Boolean,
): VideoTrack? {
    var stickyVideoTrack by remember(participant, sourceType) { mutableStateOf<VideoTrack?>(null) }
    var graceArmed by remember(participant, sourceType) { mutableStateOf(false) }
    LaunchedEffect(roomState) {
        if (roomState == Room.State.RECONNECTING) graceArmed = true
    }
    LaunchedEffect(liveVideoTrack, roomState, isLocalParticipant) {
        when {
            liveVideoTrack != null -> {
                stickyVideoTrack = liveVideoTrack
                
                
                if (roomState == Room.State.CONNECTED) graceArmed = false
            }
            isLocalParticipant -> stickyVideoTrack = null
            roomState == Room.State.CONNECTED && graceArmed -> {
                
                delay(RECONNECT_HOLD_GRACE_MS)
                stickyVideoTrack = null
                graceArmed = false
            }
            roomState == Room.State.CONNECTED -> {
                
                stickyVideoTrack = null
            }
            
        }
    }
    return if (isLocalParticipant) liveVideoTrack else (liveVideoTrack ?: stickyVideoTrack)
}




@Composable
fun VideoItemTrackSelector(
    coroutineScope: CoroutineScope,
    room: Room,
    participant: Participant,
    sourceType: Track.Source, 
    modifier: Modifier = Modifier,
    scaleType: ScaleType = ScaleType.Fill,
    viewType: ViewType,
    draggable: Boolean = false,
    reconnectCount: Int = 0,
    onFrameAspect: ((Float) -> Unit)? = null,
    onFrameSize: FrameSizeListener? = null,
    zoomResetKey: Any? = null,
    zoomResetRequest: Int = 0,
    firstFrameGate: FirstFrameGate? = null,
    hidden: Boolean = false,
    panSlack: Offset = Offset.Zero,
    onEdgeColors: ((EdgeColors) -> Unit)? = null,
) {
    
    val layoutFrameSizes = LocalCallFrameSizeSink.current
    val memberId = callMemberId(participant)
    val reportFrameSize: FrameSizeListener? = remember(onFrameSize, layoutFrameSizes, memberId, sourceType) {
        val sink = layoutFrameSizes?.takeIf { sourceType == Track.Source.CAMERA }
        when {
            sink == null -> onFrameSize
            onFrameSize == null -> { width, height, seenAt -> sink.onFrameSize(memberId, width, height, seenAt) }
            else -> { width, height, seenAt ->
                onFrameSize(width, height, seenAt)
                sink.onFrameSize(memberId, width, height, seenAt)
            }
        }
    }
    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs by remember { derivedStateOf { videoTrackMap.filter { (pub) -> pub.subscribed }.map { (pub) -> pub } } }

    val videoPub by remember(sourceType) { derivedStateOf { videoPubs.firstOrNull { pub -> pub.source == sourceType } } }

    val isLocalParticipant = room.localParticipant == participant

    
    val liveVideoTrack = videoTrackMap.firstOrNull { it.first.subscribed && it.first.source == sourceType }?.second as? VideoTrack

    val roomState by room::state.flow.collectAsState(initial = room.state)
    val videoTrack = rememberDisplayVideoTrack(participant, sourceType, liveVideoTrack, roomState, isLocalParticipant)

    var videoMuted by remember { mutableStateOf(false) }
    var cameraFacingFront by remember { mutableStateOf(false) }
    var cameraUnmuteKey by remember { mutableIntStateOf(0) }

    
    LaunchedEffect(videoPub) {
        val pub = videoPub ?: return@LaunchedEffect
        var previousMuted = pub.muted
        pub::muted.flow.collect { muted ->
            videoMuted = muted
            if (previousMuted && !muted && sourceType == Track.Source.CAMERA) {
                cameraUnmuteKey++
            }
            previousMuted = muted
        }
    }

    LaunchedEffect(participant, videoTrack) {
        if (room.localParticipant == participant && videoTrack as? LocalVideoTrack != null) {
            videoTrack::options.flow.collect { options ->
                cameraFacingFront = options.position == CameraPosition.FRONT
            }
        }
    }

    if (videoTrack != null && (sourceType == Track.Source.CAMERA || !videoMuted)) {
        
        
        val localReconnectKey = if (isLocalParticipant) reconnectCount else 0
        key (participant, sourceType, cameraUnmuteKey, localReconnectKey) {
            VideoRenderer(
                coroutineScope = coroutineScope,
                modifier = modifier,
                room = room,
                videoTrack = videoTrack,
                sourceType = sourceType,
                mirror = room.localParticipant == participant && cameraFacingFront,
                scaleType = scaleType,
                viewType = viewType,
                draggable = draggable,
                reconnectGeneration = reconnectCount,
                onFrameAspect = onFrameAspect,
                onFrameSize = reportFrameSize,
                zoomResetKey = zoomResetKey,
                zoomResetRequest = zoomResetRequest,
                firstFrameGate = firstFrameGate,
                hidden = hidden,
                panSlack = panSlack,
                onEdgeColors = onEdgeColors,
            )
        }
    } else if (sourceType == Track.Source.SCREEN_SHARE) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.Top),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(132.dp)
                    .height(47.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.tabler_aspect_ratio),
                    contentDescription = "screen_sharing_placeholder",
                    contentScale = ContentScale.None,
                    modifier = Modifier
                        .padding(0.83333.dp)
                        .width(20.dp)
                        .height(20.dp)
                )

                Text(
                    text = ResUtils.getString(R.string.call_screen_sharing_placeholder),
                    style = DifftTheme.typography.p3,
                    color = DifftTheme.colors.textTertiary,
                )
            }
        }
    }
}
