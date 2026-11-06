package com.difft.android.call.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.ui.ParticipantMuteMenu
import com.difft.android.call.ui.ParticipantWeakNetworkBadge
import com.difft.android.call.ui.ShowSpeakerStatusView
import com.difft.android.call.ui.muteMenuTapTarget
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.flow

/** Speaking outline width (guide §5.1). */
private val SPEAKING_STROKE = 3.dp

private val STATUS_PILL_HEIGHT = 24.dp
private val STATUS_PILL_INSET_START = 4.dp
private val STATUS_PILL_INSET_BOTTOM = 4.33.dp
private val STATUS_PILL_HIT_SLOP_END = 12.dp
private val BADGE_INSET = 6.dp

/** Full-window avatar column: the 1v1 and incoming-call avatar size, name and mic underneath. */
private val FULL_WINDOW_AVATAR = 96.dp
private val FULL_WINDOW_AVATAR_GAP = 8.dp

/**
 * The camera-off content of a card that fills the window (solo, 1v1 main): avatar centred with
 * the name and mic status under it, as the 1v1 page always drew it (#1366 for the solo card).
 * Such a card has no decorations: a pill's corner would be the screen's corner.
 */
@Composable
internal fun FullWindowAvatarInfo(
    participant: Participant,
    displayInfo: CallUserDisplayInfo,
    displayName: String,
    speakingEnabled: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        displayInfo.avatarData?.let { data ->
            val entryPoint = remember {
                EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance)
            }
            entryPoint.callToChatController.Avatar(data = data, size = FULL_WINDOW_AVATAR)
        }
        Spacer(Modifier.height(FULL_WINDOW_AVATAR_GAP))
        ShowSpeakerStatusView(participant, displayName, speakingEnabled = speakingEnabled)
    }
}

/** Where a tile's mute menu opened: the name pill (a tap) or the whole card (a long press). */
internal enum class TileMenuAnchor { PILL, CARD }

/** The open mute menu of one tile, if any. */
@Stable
internal class TileMuteMenuState {
    var anchor: TileMenuAnchor? by mutableStateOf(null)
        private set
    var touch: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Idempotent while open: a second finger or a racing tap must not re-anchor the menu. */
    fun open(anchor: TileMenuAnchor, touch: Offset) {
        if (this.anchor != null) return
        this.touch = touch
        this.anchor = anchor
    }

    fun close() {
        anchor = null
    }
}

/** The mute menu anchored at [anchor]; holds the layout scheduler while it shows. */
@Composable
internal fun TileMuteMenu(
    viewModel: LCallViewModel,
    participant: Participant,
    displayName: String,
    state: TileMuteMenuState,
    anchor: TileMenuAnchor,
) {
    ParticipantMuteMenu(
        visible = state.anchor == anchor,
        touchInAnchor = state.touch,
        targetName = displayName,
        onDismissRequest = state::close,
        onMute = {
            state.close()
            viewModel.toggleMute(participant, displayName)
        },
        onOpenChanged = viewModel.callUiController::setParticipantMenuOpen,
    )
}

/**
 * A card's decorations (guide §5.1): the name and mic pill bottom left (a tap opens the mute
 * menu), the weak-network badge top right and the speaking outline. A card that fills the window
 * draws none of them. Collects its own speaking state so a change never recomposes the tile's
 * renderer.
 */
@Composable
internal fun BoxScope.TileDecorations(
    viewModel: LCallViewModel,
    participant: Participant,
    uid: String,
    displayName: String,
    participantCount: Int,
    shape: Shape,
    muteOtherEnabled: Boolean,
    menu: TileMuteMenuState?,
) {
    val speakingEnabled by viewModel.callUiController.speakingEnabled.collectAsState()
    val isSpeaking by participant::isSpeaking.flow.collectAsState()
    if (isSpeaking && speakingEnabled) {
        Box(Modifier.matchParentSize().border(SPEAKING_STROKE, DifftTheme.colors.textInfo, shape))
    }

    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .testTag("call_tile_status")
            .then(
                if (menu == null) {
                    Modifier
                } else {
                    Modifier.muteMenuTapTarget(
                        participant = participant,
                        muteOtherEnabled = muteOtherEnabled,
                        yieldToLongPress = true,
                        onTap = { touch -> menu.open(TileMenuAnchor.PILL, touch) },
                    )
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .padding(
                    start = STATUS_PILL_INSET_START,
                    // Tops the pill and its bottom inset up to the minimum touch target.
                    top = DifftTheme.spacing.minTouchTarget - STATUS_PILL_HEIGHT - STATUS_PILL_INSET_BOTTOM,
                    end = STATUS_PILL_HIT_SLOP_END,
                    bottom = STATUS_PILL_INSET_BOTTOM,
                )
                .wrapContentWidth()
                .height(STATUS_PILL_HEIGHT)
                .background(color = DifftTheme.colors.backgroundPagePrimary, shape = DifftTheme.radius.mediumShape)
                .padding(horizontal = DifftTheme.spacing.insetSmall, vertical = DifftTheme.spacing.insetXSmall),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            ShowSpeakerStatusView(participant, displayName, speakingEnabled = speakingEnabled)
        }
        if (menu != null) TileMuteMenu(viewModel, participant, displayName, menu, TileMenuAnchor.PILL)
    }

    ParticipantWeakNetworkBadge(
        controller = viewModel.callUiController,
        localIdentity = remember { globalServices.myId },
        identity = uid,
        participantCount = participantCount,
        modifier = Modifier.align(Alignment.TopEnd).padding(BADGE_INSET),
    )
}
