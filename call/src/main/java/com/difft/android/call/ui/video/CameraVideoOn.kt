package com.difft.android.call.ui.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.flow.flowOf

/** Whether [participant]'s camera is subscribed and unmuted. */
@Composable
fun rememberCameraVideoOn(participant: Participant?): Boolean {
    if (participant == null) return false
    val publications by participant::videoTrackPublications.flow
        .collectAsState(initial = participant.videoTrackPublications)
    // Same filter as the tiles' own video state, so a shape never disagrees with what the tile shows.
    val camera = publications.firstOrNull { (pub) -> pub.subscribed && pub.source == Track.Source.CAMERA }?.first
    val mutedFlow = remember(camera) { camera?.let { it::muted.flow } ?: flowOf(true) }
    val muted by mutedFlow.collectAsState(initial = camera?.muted ?: true)
    return !muted
}
