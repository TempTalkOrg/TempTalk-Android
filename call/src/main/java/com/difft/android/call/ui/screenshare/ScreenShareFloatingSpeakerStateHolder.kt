package com.difft.android.call.ui.screenshare

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallToChatController
import com.difft.android.call.data.AvatarData
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.manager.ParticipantManager
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.flow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val context: Context,
    private val contactorCacheManager: ContactorCacheManager,
    private val explicitFallback: StateFlow<Participant>,
) : ScreenShareFloatingSpeakerStatePort {

    private var holdJob: Job? = null
    private var holdBaseline: ResolutionInputs? = null
    private var trackCollectJob: Job? = null
    private var displayInfoJob: Job? = null
    private var resolutionCollectJob: Job? = null

    private var lastValidatedPrimaryWhileSpeaking: Participant? = null

    private val callToChatController: LCallToChatController by lazy {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(
            ApplicationHelper.instance
        ).callToChatController
    }

    private fun initialUiState(): ScreenShareFloatingSpeakerUiState {
        val p = explicitFallback.value
        return ScreenShareFloatingSpeakerUiState(
            videoParticipant = p,
            participantKey = p.sid.value,
            isLocal = p is LocalParticipant,
            selectionSource = FloatingSpeakerSelectionSource.FALLBACK_SCREEN_SHARER,
            displayName = null,
            avatarModel = null,
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
                participantManager.primary,
                participantManager.participants,
                participantManager.screenSharingUser,
                explicitFallback,
                speakingEnabled,
            ) { primary, participants, sharer, fallback, speakingOn ->
                ResolutionInputs(
                    primary = primary,
                    participants = participants,
                    sharer = sharer,
                    fallback = fallback,
                    speakingOn = speakingOn,
                )
            }.collect { onResolutionTick(it) }
        }
    }

    private fun latestInputs(): ResolutionInputs = ResolutionInputs(
        primary = participantManager.primary.value,
        participants = participantManager.participants.value,
        sharer = participantManager.screenSharingUser.value,
        fallback = explicitFallback.value,
        speakingOn = speakingEnabled.value,
    )

    private fun onResolutionTick(inputs: ResolutionInputs) {
        val validatedPrimary = inputs.primary?.takeIf { it.isStillIn(inputs.participants) }
        if (validatedPrimary != null) {
            holdJob?.cancel()
            holdBaseline = null
            val cur = _uiState.value
            if (cur.videoParticipant.sid == validatedPrimary.sid &&
                cur.selectionSource == FloatingSpeakerSelectionSource.PRIMARY_ACTIVE_SPEAKER
            ) {
                lastValidatedPrimaryWhileSpeaking = validatedPrimary
                return
            }
            emitResolved(validatedPrimary, FloatingSpeakerSelectionSource.PRIMARY_ACTIVE_SPEAKER)
            return
        }

        val stillHeld = holdJob?.isActive == true
        val baseline = holdBaseline
        if (stillHeld && baseline != null && !meaningfulHoldRestart(inputs, baseline)) {
            return
        }

        holdJob?.cancel()
        holdBaseline = inputs
        val held = lastValidatedPrimaryWhileSpeaking?.takeIf { it.isStillIn(inputs.participants) }
        if (held != null) {
            emitResolved(held, FloatingSpeakerSelectionSource.HOLD_LAST_PRIMARY)
        }

        holdJob = scope.launch {
            delay(ScreenShareSpeakerConstants.FALLBACK_HOLD_MS)
            val latest = latestInputs()
            holdBaseline = null
            val vp = latest.primary?.takeIf { it.isStillIn(latest.participants) }
            val fallbackNow = resolveFallback(latest.sharer, latest.fallback, latest.participants)
            if (vp != null) {
                emitResolved(vp, FloatingSpeakerSelectionSource.PRIMARY_ACTIVE_SPEAKER)
            } else {
                emitResolved(fallbackNow, FloatingSpeakerSelectionSource.FALLBACK_SCREEN_SHARER)
            }
        }
    }

    private fun emitResolved(
        participant: Participant,
        source: FloatingSpeakerSelectionSource,
    ) {
        if (source == FloatingSpeakerSelectionSource.PRIMARY_ACTIVE_SPEAKER) {
            lastValidatedPrimaryWhileSpeaking = participant
        }
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
                    avatarModel = null,
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
                if (_uiState.value.videoParticipant.sid != participant.sid) return@launch
                val avatarView = withContext(Dispatchers.Main) {
                    when (val data = info.avatarData) {
                        is AvatarData.FromContactor ->
                            callToChatController.getAvatarByContactor(context, data.contactor)
                        is AvatarData.FromNameOrUid ->
                            callToChatController.createAvatarByNameOrUid(context, data.name, data.userId)
                        null -> null
                    }
                }
                if (_uiState.value.videoParticipant.sid != participant.sid) return@launch
                _uiState.update { prev ->
                    if (prev.videoParticipant.sid != participant.sid) prev
                    else prev.copy(displayName = info.name, avatarModel = avatarView)
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
        holdJob?.cancel()
        trackCollectJob?.cancel()
        displayInfoJob?.cancel()
        holdBaseline = null
        lastValidatedPrimaryWhileSpeaking = null
    }
}
