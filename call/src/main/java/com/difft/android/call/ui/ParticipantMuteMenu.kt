package com.difft.android.call.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.difft.android.base.ui.compose.menu.DifftDropdownMenuItem
import com.difft.android.base.ui.compose.menu.DifftDropdownMenuPanel
import com.difft.android.call.R
import com.difft.android.call.util.IdUtil
import com.difft.android.call.util.StringUtil
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant


internal const val PARTICIPANT_NAME_MAX_LENGTH = 14


internal const val NAME_MEASURE_CAP = 64


fun participantDisplayName(identity: String?, displayName: String?): String =
    displayName ?: IdUtil.convertToBase58UserName(identity) ?: identity ?: ""

fun participantDisplayName(participant: Participant, displayName: String?): String =
    participantDisplayName(participant.identity?.value, displayName)





@Composable
fun rememberParticipantDisplayName(identity: String?, displayName: String?): String =
    remember(identity, displayName) { participantDisplayName(identity, displayName) }

@Composable
fun rememberParticipantDisplayName(participant: Participant, displayName: String?): String =
    rememberParticipantDisplayName(participant.identity?.value, displayName)





fun Participant.isMuteMenuTarget(muteOtherEnabled: Boolean): Boolean = muteOtherEnabled && this !is LocalParticipant









class TouchPointPopupPositionProvider(private val touchInAnchor: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val touchX = anchorBounds.left + touchInAnchor.x
        val touchY = anchorBounds.top + touchInAnchor.y
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(
            x = (touchX - popupContentSize.width / 2).coerceIn(0, maxX),
            y = touchY.coerceIn(0, maxY),
        )
    }
}

















fun Modifier.muteMenuTapTarget(
    participant: Participant,
    muteOtherEnabled: Boolean,
    yieldToLongPress: Boolean = false,
    onTap: (Offset) -> Unit,
): Modifier = if (!participant.isMuteMenuTarget(muteOtherEnabled)) this else pointerInput(participant.sid) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val up = if (yieldToLongPress) {
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
        } else {
            waitForUpOrCancellation()
        } ?: return@awaitEachGesture
        
        if (participant.isMicrophoneEnabled) {
            up.consume()
            onTap(down.position)
        }
    }
}














@Composable
fun ParticipantMuteMenu(
    visible: Boolean,
    touchInAnchor: Offset,
    targetName: String,
    onDismissRequest: () -> Unit,
    onMute: () -> Unit,
    onOpenChanged: (Boolean) -> Unit = {},
) {
    if (!visible) return
    DisposableEffect(Unit) {
        onOpenChanged(true)
        onDispose { onOpenChanged(false) }
    }
    val positionProvider = remember(touchInAnchor) {
        TouchPointPopupPositionProvider(touchInAnchor.round())
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        DifftDropdownMenuPanel(modifier = Modifier.testTag("call_participant_mute_menu")) {
            
            DifftDropdownMenuItem(
                text = stringResource(
                    id = R.string.call_mute_participant_menu_item,
                    StringUtil.truncateWithEllipsis(targetName, PARTICIPANT_NAME_MAX_LENGTH),
                ),
                onClick = onMute,
            )
        }
    }
}
