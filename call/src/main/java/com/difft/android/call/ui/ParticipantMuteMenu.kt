package com.difft.android.call.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.R
import com.difft.android.call.util.IdUtil
import com.difft.android.call.util.StringUtil
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant

/** Character budget shared by the name pill, the panel row and the mute menu before ellipsising. */
internal const val PARTICIPANT_NAME_MAX_LENGTH = 14

/** Display name for in-app UI: remark/name from the contact cache, else the base58 id, else the raw identity. */
fun participantDisplayName(identity: String?, displayName: String?): String =
    displayName ?: IdUtil.convertToBase58UserName(identity) ?: identity ?: ""

fun participantDisplayName(participant: Participant, displayName: String?): String =
    participantDisplayName(participant.identity?.value, displayName)

/**
 * [participantDisplayName] cached per (identity, name): the base58 encode — and the L.e it logs
 * for a malformed identity — must not re-run on every recomposition of every tile.
 */
@Composable
fun rememberParticipantDisplayName(identity: String?, displayName: String?): String =
    remember(identity, displayName) { participantDisplayName(identity, displayName) }

@Composable
fun rememberParticipantDisplayName(participant: Participant, displayName: String?): String =
    rememberParticipantDisplayName(participant.identity?.value, displayName)

/**
 * The local device cannot mute itself over RTM; another device of the same user is a
 * RemoteParticipant and stays mutable.
 */
fun Participant.isMuteMenuTarget(muteOtherEnabled: Boolean): Boolean = muteOtherEnabled && this !is LocalParticipant

/**
 * Places a popup at the finger the way the conversation-list long-press menu does
 * (`ChativePopupWindow.showAtTouchPosition`): horizontally centred on the touch point, top edge on
 * the touch point, both clamped so the popup stays fully inside the window.
 *
 * [touchInAnchor] is the touch position in the anchor composable's local pixels; the provider adds
 * the anchor's window offset itself, so callers only forward what `detectTapGestures` hands them.
 */
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

/**
 * Tap target that opens the mute menu for [participant]. Unlike `detectTapGestures`, the up event
 * is consumed only when the menu will actually open, so a tap on a muted participant's name pill
 * still falls through to the tile's own tap handler (overlay toggle) instead of dying in a dead
 * zone.
 *
 * Returns the receiver unchanged when the participant is not a mute target, so the pointer-input
 * node is dropped entirely. The node is keyed on the participant so a composition slot reused for
 * someone else never keeps the previous participant's closures.
 *
 * @param yieldToLongPress true only where a parent owns a long-press entry to the same menu (the
 *   grid tile): a press held past the long-press timeout is then not a tap here, otherwise the
 *   release would open the menu a second time. Surfaces without a long-press fallback (the
 *   screen-share panel) must leave this false, or a slow press becomes a dead zone.
 * @param onTap receives the touch position in this composable's local pixels.
 */
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
        // Mic state is a LiveKit property, not Compose state, so it is read at tap time.
        if (participant.isMicrophoneEnabled) {
            up.consume()
            onTap(down.position)
        }
    }
}

/**
 * Single-item "Mute" menu shared by the grid tile and the screen-share participants panel. Styled
 * after the conversation-list popup (`ChativePopupWindow`: small-radius corners, popup background,
 * medium elevation, 16sp label) and positioned at the finger via
 * [TouchPointPopupPositionProvider].
 *
 * A [Popup] (own window, focusable) so an outside tap dismisses it and is swallowed, matching the
 * View-based menu; the call surface is forced dark, so `backgroundPopup` resolves to the dark token.
 *
 * @param onOpenChanged true while the menu is showing, false when it leaves composition (mute,
 *   dismiss, or the participant disappearing). Hosts forward it to
 *   `CallUiController.setParticipantMenuOpen` so the screen-share auto-hide timer counts an open
 *   menu as interaction.
 */
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
        Surface(
            modifier = Modifier.testTag("call_participant_mute_menu"),
            shape = DifftTheme.shapes.small,
            color = DifftTheme.colors.backgroundPopup,
            shadowElevation = DifftTheme.spacing.elevationMedium,
        ) {
            // Text only, per the product spec (no icon, so the item matches iOS / Desktop). It names
            // its target so a crowded grid never mutes the wrong person.
            Text(
                text = stringResource(
                    id = R.string.call_mute_participant_menu_item,
                    StringUtil.truncateWithEllipsis(targetName, PARTICIPANT_NAME_MAX_LENGTH),
                ),
                modifier = Modifier
                    .clickable(onClick = onMute)
                    .padding(
                        horizontal = DifftTheme.spacing.insetLarge,
                        vertical = DifftTheme.spacing.insetMedium,
                    ),
                // 16sp like base_layout_chative_popup_view_item.xml's label.
                style = DifftTheme.typography.bodyLarge,
                color = DifftTheme.colors.icon,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
