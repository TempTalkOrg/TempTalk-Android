package com.difft.android.call.ui.screenshare

import com.difft.android.call.core.callMemberId
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant

internal fun Participant.isStillIn(participants: List<Participant>): Boolean =
    participants.any { it.sid == this.sid }





internal fun resolveShareSpeaker(
    speakerId: String?,
    participants: List<Participant>,
    screenSharingUser: RemoteParticipant?,
    explicit: Participant,
): Pair<Participant, FloatingSpeakerSelectionSource> {
    val speaker = speakerId?.let { id -> participants.firstOrNull { callMemberId(it) == id } }
    return if (speaker != null) {
        speaker to FloatingSpeakerSelectionSource.LAYOUT_SPEAKER
    } else {
        resolveFallback(screenSharingUser, explicit, participants) to FloatingSpeakerSelectionSource.FALLBACK_SCREEN_SHARER
    }
}

internal fun resolveFallback(
    screenSharingUser: RemoteParticipant?,
    explicit: Participant,
    participants: List<Participant>,
): Participant {
    screenSharingUser?.let { remote ->
        participants.firstOrNull { it.sid == remote.sid }?.let { return it }
    }
    return explicit.takeIf { it.isStillIn(participants) }
        ?: participants.firstOrNull()
        ?: explicit
}
