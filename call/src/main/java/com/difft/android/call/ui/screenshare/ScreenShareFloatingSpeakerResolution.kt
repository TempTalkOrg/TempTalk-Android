package com.difft.android.call.ui.screenshare

import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant

internal data class ResolutionInputs(
    val primary: Participant?,
    val participants: List<Participant>,
    val sharer: RemoteParticipant?,
    val fallback: Participant,
    val speakingOn: Boolean,
)

internal fun Participant.isStillIn(participants: List<Participant>): Boolean =
    participants.any { it.sid == this.sid }

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

internal fun meaningfulHoldRestart(current: ResolutionInputs, baseline: ResolutionInputs): Boolean {
    if (current.sharer?.sid != baseline.sharer?.sid) return true
    if (current.fallback.sid != baseline.fallback.sid) return true
    val currentSids = current.participants.map { it.sid.value }.toSet()
    val baselineSids = baseline.participants.map { it.sid.value }.toSet()
    return currentSids != baselineSids
}
