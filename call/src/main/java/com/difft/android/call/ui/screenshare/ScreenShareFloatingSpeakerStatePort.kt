package com.difft.android.call.ui.screenshare

import kotlinx.coroutines.flow.StateFlow

interface ScreenShareFloatingSpeakerStatePort {
    val uiState: StateFlow<ScreenShareFloatingSpeakerUiState>
}
