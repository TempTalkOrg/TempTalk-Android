package com.difft.android.call.ui.screenshare

import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn

/**
 * Holds the explicit screen-share fallback participant as a hot [StateFlow]
 * with the local participant as the initial value. Extracted from the VM to
 * keep the field + Flow plumbing out of the VM body.
 */
class ScreenShareFallbackSource(
    scope: CoroutineScope,
    localParticipant: Participant,
) {
    private val explicit = MutableStateFlow<Participant?>(null)

    val flow: StateFlow<Participant> = explicit
        .filterNotNull()
        .stateIn(scope, SharingStarted.Eagerly, localParticipant)

    fun update(participant: Participant) { explicit.value = participant }
}
