package com.difft.android.call.session

import com.difft.android.base.user.CallConfig
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.manager.ParticipantManager
import com.difft.android.call.manager.SpeakerStateHolder
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch






object CallObservers {
    fun register(
        scope: CoroutineScope,
        room: Room,
        participantManager: ParticipantManager,
        uiState: CallSessionUiState,
        contactorCacheManager: ContactorCacheManager,
        callToChatController: LCallToChatController,
        speakerState: SpeakerStateHolder,
        callConfig: CallConfig,
        callIntent: CallIntent,
        activeSpeakers: Flow<List<Participant>>,
    ) {
        scope.launch(Dispatchers.IO) {
            callToChatController.getContactsUpdateListener().collect { contactorCacheManager.updateCallContactorCache(it) }
        }
        scope.launch(Dispatchers.IO) {
            callToChatController.getGroupsUpdateListener().collect {
                it.gid?.let { gid ->
                    if (gid == callIntent.conversationId) uiState.setGroupCriticalAlert(it.criticalAlert)
                }
            }
        }
        scope.launch {
            combine(participantManager.participants, activeSpeakers) { p, s -> p to s }.collect { (pList, speakers) ->
                val hasRemote = room.remoteParticipants.isNotEmpty()
                val isSilent = pList.firstOrNull { it.isMicrophoneEnabled } == null || speakers.isEmpty()
                speakerState.checkNoSpeakOrOnePersonTimeout(hasRemote, isSilent, callConfig)
            }
        }
    }
}
