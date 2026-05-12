package com.difft.android.call.ui.screenshare

import io.livekit.android.room.participant.Participant

enum class FloatingSpeakerSelectionSource {
    PRIMARY_ACTIVE_SPEAKER,
    HOLD_LAST_PRIMARY,
    FALLBACK_SCREEN_SHARER,
}

data class ScreenShareFloatingSpeakerUiState(
    val videoParticipant: Participant,
    val participantKey: String,
    val isLocal: Boolean,
    val selectionSource: FloatingSpeakerSelectionSource,
    val displayName: String?,
    val avatarModel: Any?,
    val cameraEnabled: Boolean,
    val micMuted: Boolean,
    val isSpeaking: Boolean,
    val isScreenSharing: Boolean,
)

data class ScreenShareFloatingSpeakerStatusUi(
    val displayName: String?,
    val micMuted: Boolean,
    val isSpeaking: Boolean,
    val isScreenSharing: Boolean,
)

fun ScreenShareFloatingSpeakerUiState.toStatusUi(): ScreenShareFloatingSpeakerStatusUi =
    ScreenShareFloatingSpeakerStatusUi(
        displayName = displayName,
        micMuted = micMuted,
        isSpeaking = isSpeaking,
        isScreenSharing = isScreenSharing,
    )
