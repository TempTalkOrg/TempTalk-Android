package com.difft.android.call.manager

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.util.IdUtil
import com.difft.android.call.util.sortParticipantsByPriority
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ParticipantManager(private val scope: CoroutineScope) {
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

    /**
     * Re-sorts the participants list based on their priority.
     */
    fun resortParticipants() {
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val sortedList = sortParticipantsByPriority(participants.value)
                    setParticipants(sortedList)
                }
            } catch (e: Exception) {
                L.e { "[Call] ParticipantManager resortParticipants error = ${e.message}" }
            }
        }
    }

    /**
     * Sorts a list of participants by their speaking activity.
     */
    fun sortParticipantsBySpeaker(speakers: List<Participant>) {
        val participantsList = participants.value
        if(participantsList.isEmpty()){
            return
        }
        // Check each speaker to see if it needs to be sorted
        for (speaker in speakers) {
            val speakerIndex = participantsList.indexOf(speaker)
            if (speakerIndex == 0) continue // The first speaker does not need to be ranked
            try {
                if (speakerIndex > 0) {
                    val previousParticipant = participantsList[speakerIndex - 1]
                    if (isParticipantInactive(previousParticipant)){
                        resortParticipants()
                        return
                    }
                    // Check if there are any participants in front of you who have not turned on their microphones.
                    val previousParticipants = participantsList.subList(0, speakerIndex)
                    if (previousParticipants.isNotEmpty() && previousParticipants.any { isParticipantInactive(it) }) {
                        resortParticipants()
                        return
                    }
                }
            } catch (e: Exception){
                L.e { "[Call] ParticipantManager sortParticipantsBySpeaker error = ${e.message}." }
                return
            }
        }
    }

    /**
     * Checks whether the specified participant is currently inactive.
     */
    private fun isParticipantInactive(participant: Participant): Boolean {
        return participant !is LocalParticipant && !participant.isScreenShareEnabled && !participant.isCameraEnabled && !participant.isMicrophoneEnabled
    }

    fun updateAwaitingJoinInvitees() {
        if (_awaitingJoinInvitees.value.isEmpty()) return

        // Remove all invitees that have joined
        val joinedUserIds = participants.value.mapNotNull { participant ->
            IdUtil.getUidByIdentity(participant.identity?.value)
        }.toSet()
        if (joinedUserIds.isEmpty()) return
        _awaitingJoinInvitees.value = _awaitingJoinInvitees.value.filterNot { userId ->
            userId in joinedUserIds
        }
    }

    fun addAwaitingJoinInvitees(inviteeIds: List<String>) {
        _awaitingJoinInvitees.value = (_awaitingJoinInvitees.value + inviteeIds).distinct()
    }

}