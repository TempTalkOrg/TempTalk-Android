package com.difft.android.call.util

import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant

/**
 * Sorting priority (highest to lowest):
 * 1. Local participant (always first)
 * 2. Participants with screen sharing enabled
 * 3. Participants with camera enabled
 * 4. Speaking participants (sorted by audio level)
 * 5. Participants with microphone enabled
 * 6. Recently active speakers (by last spoke time)
 * 7. Participants who joined earlier
 */
fun sortParticipantsByPriority(participants: List<Participant>): List<Participant> {
    // Audio level change threshold to consider significant (in dB)
    val audioLevelThreshold = 0.1f

    return participants.sortedWith(
        compareBy<Participant> { it !is LocalParticipant } // Local participant first
            .thenBy { !it.isScreenShareEnabled } // Screen sharing first
            .thenBy { !it.isCameraEnabled } // Camera enabled first
            .thenBy { participant ->
                // Apply threshold to audio level to reduce flickering
                if (participant.isSpeaking) (participant.audioLevel / audioLevelThreshold).toInt()
                else Int.MAX_VALUE
            }
            .thenBy { !it.isMicrophoneEnabled } // Mic enabled first
            .thenByDescending { it.lastSpokeAt ?: 0L } // Recently active first
            .thenBy { it.joinedAt ?: 0L } // Joined earlier first
    )
}