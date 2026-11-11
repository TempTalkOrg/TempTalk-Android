package com.difft.android.call.ui.screenshare

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import com.difft.android.base.log.lumberjack.L
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class VideoSlot(val participant: Participant, val key: String)

private enum class ActiveSlot { A, B }















@Composable
fun ScreenShareFloatingSpeakerVideoTransition(
    modifier: Modifier = Modifier,
    room: Room,
    uiState: ScreenShareFloatingSpeakerUiState,
    reconnectCount: Int,
) {
    var slotA: VideoSlot? by remember { mutableStateOf(null) }
    var slotB: VideoSlot? by remember { mutableStateOf(null) }
    var activeSlot by remember { mutableStateOf(ActiveSlot.A) }
    var committedKey: String? by remember { mutableStateOf(null) }
    var committedCameraOn by remember { mutableStateOf(false) }
    val fadeAlpha = remember { Animatable(1f) }
    var inTransition by remember { mutableStateOf(false) }

    LaunchedEffect(reconnectCount) {
        slotA = null
        slotB = null
        activeSlot = ActiveSlot.A
        committedKey = null
        committedCameraOn = false
        inTransition = false
        fadeAlpha.snapTo(1f)
    }

    LaunchedEffect(uiState.participantKey, uiState.cameraEnabled, reconnectCount) {
        val key = uiState.participantKey
        val incoming = uiState.videoParticipant
        val camOn = uiState.cameraEnabled

        if (!camOn) {
            committedKey = key
            committedCameraOn = false
            inTransition = false
            fadeAlpha.snapTo(1f)
            return@LaunchedEffect
        }

        
        
        val cachedSlot = if (activeSlot == ActiveSlot.A) slotB else slotA
        if (cachedSlot?.key == key) {
            L.d { "[Call] FloatingSpeaker cache hit, instant swap to $key" }
            activeSlot = if (activeSlot == ActiveSlot.A) ActiveSlot.B else ActiveSlot.A
            committedKey = key
            committedCameraOn = true
            inTransition = false
            fadeAlpha.snapTo(1f)
            return@LaunchedEffect
        }

        val needsTransition = committedKey != null &&
            committedKey != key &&
            committedCameraOn

        if (!needsTransition) {
            if (activeSlot == ActiveSlot.A) slotA = VideoSlot(incoming, key)
            else slotB = VideoSlot(incoming, key)
            committedKey = key
            committedCameraOn = true
            inTransition = false
            fadeAlpha.snapTo(1f)
            return@LaunchedEffect
        }

        L.d { "[Call] FloatingSpeaker transition start outgoing=$committedKey incoming=$key" }

        
        if (activeSlot == ActiveSlot.A) slotB = VideoSlot(incoming, key)
        else slotA = VideoSlot(incoming, key)
        fadeAlpha.snapTo(1f)
        inTransition = true

        var completed = false
        try {
            delay(ScreenShareSpeakerConstants.MIN_OVERLAP_MS)
            fadeAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = ScreenShareSpeakerConstants.FADE_MS.toInt()),
            )
            
            activeSlot = if (activeSlot == ActiveSlot.A) ActiveSlot.B else ActiveSlot.A
            committedKey = key
            committedCameraOn = true
            completed = true
            L.d { "[Call] FloatingSpeaker transition completed incoming=$key" }
        } finally {
            inTransition = false
            if (!completed) {
                if (activeSlot == ActiveSlot.A) slotB = null
                else slotA = null
                L.d { "[Call] FloatingSpeaker transition cancelled, reverted incoming=$key" }
            }
            withContext(NonCancellable) {
                fadeAlpha.snapTo(1f)
            }
        }
    }

    val alphaValue = fadeAlpha.value
    val camVisible = uiState.cameraEnabled
    Box(modifier = modifier) {
        slotA?.let { slot ->
            val isActive = activeSlot == ActiveSlot.A
            key(slot.key) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (inTransition && isActive) 1f else 0f)
                        .graphicsLayer {
                            alpha = when {
                                !camVisible -> 0f
                                inTransition && isActive -> alphaValue
                                isActive -> 1f
                                inTransition -> 1f
                                else -> 0f
                            }
                        },
                ) {
                    StableFloatingVideoRenderer(
                        room = room,
                        participant = slot.participant,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        slotB?.let { slot ->
            val isActive = activeSlot == ActiveSlot.B
            key(slot.key) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (inTransition && isActive) 1f else 0f)
                        .graphicsLayer {
                            alpha = when {
                                !camVisible -> 0f
                                inTransition && isActive -> alphaValue
                                isActive -> 1f
                                inTransition -> 1f
                                else -> 0f
                            }
                        },
                ) {
                    StableFloatingVideoRenderer(
                        room = room,
                        participant = slot.participant,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
