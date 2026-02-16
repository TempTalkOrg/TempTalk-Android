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

package com.difft.android.call.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope

/**
 * This widget primarily serves as a way to observe changes in [Participant.videoTrackPublications].
 */
@Composable
fun VideoItemTrackSelector(
    coroutineScope: CoroutineScope,
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
        key (videoTrack, room.state) {
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
            )
        }
    } else {
        if (sourceType == Track.Source.SCREEN_SHARE) {
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
                        style = TextStyle(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight(400),
                            color = colorResource(id = com.difft.android.base.R.color.t_third),
                        )
                    )
                }
            }
        }
    }
}
