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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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


private val SPEAKING_STROKE = 3.dp

private val STATUS_PILL_HEIGHT = 24.dp
private val STATUS_PILL_INSET_START = 4.dp
private val STATUS_PILL_INSET_BOTTOM = 4.33.dp
private val STATUS_PILL_HIT_SLOP_END = 12.dp
private val BADGE_INSET = 6.dp


private val FULL_WINDOW_AVATAR = 96.dp
private val FULL_WINDOW_AVATAR_GAP = 8.dp






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


internal enum class TileMenuAnchor { PILL, CARD }


@Stable
internal class TileMuteMenuState {
    var anchor: TileMenuAnchor? by mutableStateOf(null)
        private set
    var touch: Offset by mutableStateOf(Offset.Zero)
        private set

    
    fun open(anchor: TileMenuAnchor, touch: Offset) {
        if (this.anchor != null) return
        this.touch = touch
        this.anchor = anchor
    }

    fun close() {
        anchor = null
    }
}


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











@Composable
internal fun BoxScope.TileDecorations(
    viewModel: LCallViewModel,
    participant: Participant,
    uid: String,
    displayName: String,
    participantCount: Int,
    corners: TileCorners,
    muteOtherEnabled: Boolean,
    menu: TileMuteMenuState?,
) {
    val speakingEnabled by viewModel.callUiController.speakingEnabled.collectAsState()
    val isSpeaking by participant::isSpeaking.flow.collectAsState()
    if (isSpeaking && speakingEnabled) {
        val outline = if (corners.concentric) {
            Modifier.cardOutline(SPEAKING_STROKE, DifftTheme.colors.info, corners::shapeNow)
        } else {
            Modifier.border(SPEAKING_STROKE, DifftTheme.colors.info, corners.shape)
        }
        Box(Modifier.matchParentSize().then(outline))
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .then(
                if (corners.concentric) {
                    Modifier.pillInsets(
                        start = { cornerShift(STATUS_PILL_INSET_START.value.toDouble(), STATUS_PILL_INSET_BOTTOM.value.toDouble(), corners.radii()?.bottomStart(rtl)) },
                        end = { cornerShift(STATUS_PILL_HIT_SLOP_END.value.toDouble(), STATUS_PILL_INSET_BOTTOM.value.toDouble(), corners.radii()?.bottomEnd(rtl)) },
                    )
                } else {
                    Modifier
                },
            )
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
        modifier = Modifier
            .align(Alignment.TopEnd)
            .then(
                if (corners.concentric) {
                    Modifier.sideInset(end = true) {
                        cornerShift(BADGE_INSET.value.toDouble(), BADGE_INSET.value.toDouble(), corners.radii()?.topEnd(rtl))
                    }
                } else {
                    Modifier
                },
            )
            .padding(BADGE_INSET),
    )
}






private fun Modifier.cardOutline(width: Dp, color: Color, shape: () -> Shape): Modifier = drawWithCache {
    val path = Path().apply { addOutline(shape().createOutline(size, layoutDirection, this@drawWithCache)) }
    val stroke = Stroke(width.toPx() * 2)
    onDrawBehind { clipPath(path) { drawPath(path, color, style = stroke) } }
}
