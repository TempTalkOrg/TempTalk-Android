package com.difft.android.call.ui.screenshare

import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn






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
