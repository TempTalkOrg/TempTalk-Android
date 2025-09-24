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

package com.difft.android.call

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.flow

/**
 * This widget primarily serves as a way to observe changes in [Participant.videoTrackPublications].
 */
@Composable
fun VideoItemTrackSelector(
    room: Room,
    participant: Participant,
    sourceType: Track.Source, // Track.Source.SCREEN_SHARE or Track.Source.CAMERA
    modifier: Modifier = Modifier,
    scaleType: ScaleType = ScaleType.Fill,
    viewType: ViewType,
    draggable: Boolean = true,
) {
    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs = videoTrackMap.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }

    // Find video stream by sourceType to show
    val videoPub = videoPubs.firstOrNull { pub -> pub.source == sourceType }

//    val videoTrack = videoPub?.track as? VideoTrack
    val videoTrack = participant.videoTrackPublications.firstOrNull{ it.first.subscribed && it.first.source == sourceType}?.second as? VideoTrack

    var videoMuted by remember { mutableStateOf(false) }
    var cameraFacingFront by remember { mutableStateOf(false) }

    // monitor muted state
    LaunchedEffect(videoPub) {
        if (videoPub != null) {
            videoPub::muted.flow.collect { muted -> videoMuted = muted }
        }
    }

    // monitor camera facing for local participant
    LaunchedEffect(participant, videoTrack) {
        if (room.localParticipant == participant && videoTrack as? LocalVideoTrack != null) {
            videoTrack::options.flow.collect { options ->
                cameraFacingFront = options.position == CameraPosition.FRONT
            }
        }
    }

    if (videoTrack != null && !videoMuted) {
        VideoRenderer(
            room = room,
            videoTrack = videoTrack,
            mirror = room.localParticipant == participant && cameraFacingFront,
            modifier = modifier,
            scaleType = scaleType,
            viewType = viewType,
            draggable = draggable,
        )
    }
}
