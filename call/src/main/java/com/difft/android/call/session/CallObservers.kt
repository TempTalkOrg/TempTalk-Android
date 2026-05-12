package com.difft.android.call.session

import com.difft.android.base.user.CallConfig
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.core.CallUiController
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

/**
 * Registers the ViewModel's background Flow collectors (contacts / groups /
 * speaker change) so the VM does not carry three near-identical
 * `registerX()` methods.
 *
 * NOTE: Intentionally does NOT collect `roomCtl.error` here. That stream is a
 * `Channel.receiveAsFlow()` — a single-subscriber fan-out — and must be
 * consumed exclusively by `CallLifecycleObserver.observeErrorState()` which
 * delegates to `CallErrorHandler`. Adding a second collector would cause
 * errors to race between the logger and the real handler, silently dropping
 * user-facing error UI. See fix commit `8563529e` for history.
 */
object CallObservers {
    fun register(
        scope: CoroutineScope,
        room: Room,
        participantManager: ParticipantManager,
        callUiController: CallUiController,
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
                    if (gid == callIntent.conversationId) callUiController.setCriticalAlertEnable(it.criticalAlert)
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
