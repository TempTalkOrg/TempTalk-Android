package com.difft.android.call.ui.screenshare

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.manager.ParticipantManager
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.flow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private data class TrackFieldSnapshot(
    val cameraOn: Boolean,
    val micOn: Boolean,
    val speaking: Boolean,
    val screenSharing: Boolean,
)








class ScreenShareFloatingSpeakerStateHolder(
    private val scope: CoroutineScope,
    private val participantManager: ParticipantManager,
    private val speakingEnabled: StateFlow<Boolean>,
    private val contactorCacheManager: ContactorCacheManager,
    private val explicitFallback: StateFlow<Participant>,
    private val layoutSpeaker: StateFlow<String?>,
) : ScreenShareFloatingSpeakerStatePort {

    private var trackCollectJob: Job? = null
    private var displayInfoJob: Job? = null
    private var resolutionCollectJob: Job? = null

    private fun initialUiState(): ScreenShareFloatingSpeakerUiState {
        val p = explicitFallback.value
        return ScreenShareFloatingSpeakerUiState(
            videoParticipant = p,
            participantKey = p.sid.value,
            isLocal = p is LocalParticipant,
            selectionSource = FloatingSpeakerSelectionSource.FALLBACK_SCREEN_SHARER,
            displayName = null,
            cameraEnabled = p.isCameraEnabled,
            micMuted = !p.isMicrophoneEnabled,
            isSpeaking = p.isSpeaking && speakingEnabled.value,
            isScreenSharing = p.isScreenShareEnabled,
        )
    }

    private val _uiState = MutableStateFlow(initialUiState())
    override val uiState: StateFlow<ScreenShareFloatingSpeakerUiState> = _uiState.asStateFlow()

    init {
        resolutionCollectJob = scope.launch {
            yield()
            combine(
                layoutSpeaker,
                participantManager.participants,
                participantManager.screenSharingUser,
                explicitFallback,
            ) { speakerId, participants, sharer, fallback ->
                resolveShareSpeaker(speakerId, participants, sharer, fallback)
            }.collect { (participant, source) -> emitResolved(participant, source) }
        }
    }

    private fun emitResolved(
        participant: Participant,
        source: FloatingSpeakerSelectionSource,
    ) {
        val prev = _uiState.value
        val sidChanged = prev.videoParticipant.sid != participant.sid
        if (sidChanged) {
            L.i { "[Call][FloatSpeaker] source=$source key=${participant.sid.value}" }
            displayInfoJob?.cancel()
            _uiState.update {
                ScreenShareFloatingSpeakerUiState(
                    videoParticipant = participant,
                    participantKey = participant.sid.value,
                    isLocal = participant is LocalParticipant,
                    selectionSource = source,
                    displayName = null,
                    cameraEnabled = participant.isCameraEnabled,
                    micMuted = !participant.isMicrophoneEnabled,
                    isSpeaking = participant.isSpeaking && speakingEnabled.value,
                    isScreenSharing = participant.isScreenShareEnabled,
                )
            }
            bindTrackFlows(participant)
            startDisplayInfoLoad(participant)
        } else if (prev.selectionSource != source) {
            L.i { "[Call][FloatSpeaker] source=$source key=${participant.sid.value}" }
            _uiState.update { it.copy(selectionSource = source) }
        }
    }

    private fun bindTrackFlows(participant: Participant) {
        trackCollectJob?.cancel()
        trackCollectJob = scope.launch {
            combine(
                participant::isCameraEnabled.flow,
                participant::isMicrophoneEnabled.flow,
                participant::isSpeaking.flow,
                participant::isScreenShareEnabled.flow,
                speakingEnabled,
            ) { camOn, micOn, lkSpeaking, screenOn, speakingOn ->
                TrackFieldSnapshot(
                    cameraOn = camOn,
                    micOn = micOn,
                    speaking = lkSpeaking && speakingOn,
                    screenSharing = screenOn,
                )
            }.collect { snap ->
                if (!isActive) return@collect
                if (_uiState.value.videoParticipant.sid != participant.sid) return@collect
                _uiState.update { prev ->
                    prev.copy(
                        cameraEnabled = snap.cameraOn,
                        micMuted = !snap.micOn,
                        isSpeaking = snap.speaking,
                        isScreenSharing = snap.screenSharing,
                    )
                }
            }
        }
    }

    private fun startDisplayInfoLoad(participant: Participant) {
        val uid = participant.identity?.value ?: return
        displayInfoJob?.cancel()
        displayInfoJob = scope.launch {
            try {
                val info = contactorCacheManager.getParticipantDisplayInfo(uid)
                _uiState.update { prev ->
                    if (prev.videoParticipant.sid != participant.sid) prev
                    else prev.copy(displayName = info.name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[Call][FloatSpeaker] displayInfo error: ${e.stackTraceToString()}" }
            }
        }
    }

    fun cancel() {
        resolutionCollectJob?.cancel()
        trackCollectJob?.cancel()
        displayInfoJob?.cancel()
    }
}
