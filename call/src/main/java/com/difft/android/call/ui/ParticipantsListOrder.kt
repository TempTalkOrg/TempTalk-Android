package com.difft.android.call.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

private const val AUDIO_LEVEL_THRESHOLD = 0.1f

/** How long the open participants panel waits for state changes to settle before it re-sorts. */
private const val PANEL_RESORT_DEBOUNCE_MS = 300L

/**
 * The participants panel's order (design §4.6: the call grid follows the layout controller, the
 * panel keeps this one). Highest to lowest:
 * 1. Local participant (always first)
 * 2. Participants with screen sharing enabled
 * 3. Participants with camera enabled
 *    — Within video-active tiers (1-3), current list position is preserved to avoid visual jumps.
 * 4. Speaking participants (sorted by bucketed audio level)
 * 5. Participants with microphone enabled
 * 6. Recently active speakers (by last spoke time)
 * 7. Current list position / join time
 */
fun sortParticipantsByPriority(participants: List<Participant>): List<Participant> {
    val positionOf = participants.withIndex().associate { (index, p) -> p to index }

    return participants.sortedWith(Comparator { a, b ->
        // 1. Local participant first
        val localCmp = (a !is LocalParticipant).compareTo(b !is LocalParticipant)
        if (localCmp != 0) return@Comparator localCmp

        // 2. Screen share first
        val screenCmp = b.isScreenShareEnabled.compareTo(a.isScreenShareEnabled)
        if (screenCmp != 0) return@Comparator screenCmp

        // 3. Camera enabled first
        val cameraCmp = b.isCameraEnabled.compareTo(a.isCameraEnabled)
        if (cameraCmp != 0) return@Comparator cameraCmp

        // Video-active participants (screen share or camera on):
        // preserve current relative order to prevent list jumps when new participants toggle video.
        if (a.isScreenShareEnabled || a.isCameraEnabled) {
            return@Comparator (positionOf[a] ?: Int.MAX_VALUE).compareTo(positionOf[b] ?: Int.MAX_VALUE)
        }

        // Non-video participants: sort by dynamic activity criteria
        // Negate bucket so louder speakers (higher audioLevel) sort first; non-speaking stays last.
        val levelA = if (a.isSpeaking) -(a.audioLevel / AUDIO_LEVEL_THRESHOLD).toInt() else Int.MAX_VALUE
        val levelB = if (b.isSpeaking) -(b.audioLevel / AUDIO_LEVEL_THRESHOLD).toInt() else Int.MAX_VALUE
        val speakCmp = levelA.compareTo(levelB)
        if (speakCmp != 0) return@Comparator speakCmp

        val micCmp = b.isMicrophoneEnabled.compareTo(a.isMicrophoneEnabled)
        if (micCmp != 0) return@Comparator micCmp

        val lastSpokeCmp = (b.lastSpokeAt ?: 0L).compareTo(a.lastSpokeAt ?: 0L)
        if (lastSpokeCmp != 0) return@Comparator lastSpokeCmp

        // Final tiebreaker: preserve current list position
        (positionOf[a] ?: Int.MAX_VALUE).compareTo(positionOf[b] ?: Int.MAX_VALUE)
    })
}

/**
 * [participants] in the panel's [sortParticipantsByPriority] order, starting from the order shown
 * before (newcomers last) so equal ranks keep their place. Re-sorted on a list change and, while
 * [open], [PANEL_RESORT_DEBOUNCE_MS] after anyone's speaking, mic or camera changes; held while a
 * participant menu is open ([menuOpen]), so the row under the finger does not move.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun rememberParticipantsListOrder(
    participants: List<Participant>,
    open: Boolean,
    menuOpen: StateFlow<Boolean>,
): List<Participant> {
    var order by remember { mutableStateOf(sortParticipantsByPriority(participants)) }
    LaunchedEffect(participants, open) {
        fun resort() {
            if (menuOpen.value) return
            val kept = order.filter { it in participants }
            val sorted = sortParticipantsByPriority(kept + participants.filter { it !in kept })
            if (sorted != order) order = sorted
        }
        resort()
        if (!open) return@LaunchedEffect
        merge(sortSignals(participants), menuOpen.filter { !it }.map { })
            .debounce(PANEL_RESORT_DEBOUNCE_MS)
            .collect { resort() }
    }
    // Never show someone who left before the effect runs.
    return order.filter { it in participants } + participants.filter { it !in order }
}

/** A tick whenever anyone's speaking, mic or camera state changes: what the panel sorts by. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun sortSignals(participants: List<Participant>): Flow<Unit> = participants.map { p ->
    merge(
        p::isSpeaking.flow.map { },
        p::audioTrackPublications.flow.flatMapLatest { pubs -> mutedSignals(pubs.map { it.first }) },
        p::videoTrackPublications.flow.flatMapLatest { pubs -> mutedSignals(pubs.map { it.first }) },
    )
}.merge()

private fun mutedSignals(publications: List<TrackPublication>): Flow<Unit> =
    if (publications.isEmpty()) flowOf(Unit) else publications.map { pub -> pub::muted.flow.map { } }.merge()
