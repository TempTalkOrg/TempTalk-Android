package com.difft.android.call.manager

import com.difft.android.call.util.IdUtil
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ParticipantManager {
    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    val participants = _participants.asStateFlow()

    private val _awaitingJoinInvitees = MutableStateFlow<List<String>>(emptyList())
    val awaitingJoinInvitees = _awaitingJoinInvitees.asStateFlow()

    private val _primary = MutableStateFlow<Participant?>(null)
    val primary = _primary.asStateFlow()

    private val _screenSharingUser = MutableStateFlow<RemoteParticipant?>(null)
    val screenSharingUser = _screenSharingUser.asStateFlow()

    /**
     * Updates the internal participants list with the provided value.
     */
    fun setParticipants(list: List<Participant>) {
        _participants.value = list
    }

    /**
     * Updates the currently screen-sharing participant in the call.
     */
    fun setScreenSharingUser(user: RemoteParticipant?) {
        _screenSharingUser.value = user
    }

    /**
     * Updates the primary speaker based on the current active speakers list.
     */
    fun onActiveSpeakersChanged(speakers: List<Participant>) {
        _primary.value = when {
            speakers.isNotEmpty() -> speakers.maxByOrNull { it.audioLevel }
            else -> null
        }
    }

    fun updateAwaitingJoinInvitees() {
        val joinedUserIds = participants.value.mapNotNull { participant ->
            IdUtil.getUidByIdentity(participant.identity?.value)
        }.toSet()
        if (joinedUserIds.isEmpty()) return

        _awaitingJoinInvitees.update { current ->
            current.filterNot { userId -> userId in joinedUserIds }
        }
    }

    fun addAwaitingJoinInvitees(inviteeIds: List<String>) {
        _awaitingJoinInvitees.update { current ->
            (current + inviteeIds).distinct()
        }
    }
}
