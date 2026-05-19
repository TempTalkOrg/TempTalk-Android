package com.difft.android.call.util

import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant

private const val AUDIO_LEVEL_THRESHOLD = 0.1f

/**
 * Sorting priority (highest to lowest):
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