package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.call.ui.video.VideoItemTrackSelector
import com.difft.android.call.ui.video.ViewType
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.AvatarData
import com.difft.android.call.data.CallUserDisplayInfo
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope

@Composable
fun MultiParticipantItem(
    viewModel: LCallViewModel,
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
    uid: String,
    userDisplayInfo: CallUserDisplayInfo,
    participantIndex: Int,
    participantCount: Int,
    muteOtherEnabled: Boolean,
    onClickMute: (displayName: String) -> Unit,
    coroutineScope: CoroutineScope,
    cornerRadius: Dp = 8.dp
) {
    val entryPoint = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance)
    }
    val callToChatController = entryPoint.callToChatController

    val speakingEnabled by viewModel.callUiController.speakingEnabled.collectAsState()
    val reconnectCount by viewModel.callUiController.reconnectCount.collectAsState()

    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs by remember { derivedStateOf { videoTrackMap.filter { (pub) -> pub.subscribed }.map { (pub) -> pub } } }

    val videoPub by remember { derivedStateOf { videoPubs.firstOrNull { pub -> pub.source == Track.Source.CAMERA } } }

    var videoMuted by remember { mutableStateOf(true) }

    // Which surface the menu is anchored to: the name pill (single tap) or the whole tile
    // (long-press compatibility entry). Both open the same menu at the finger.
    var muteMenuAnchor by remember { mutableStateOf<MuteMenuAnchor?>(null) }
    var muteMenuTouch by remember { mutableStateOf(Offset.Zero) }
    val muteMenuAvailable = participant.isMuteMenuTarget(muteOtherEnabled)
    val displayName = rememberParticipantDisplayName(participant, userDisplayInfo.name)

    fun handleClickScreen() {
        viewModel.callUiController.toggleOverlays()
    }

    // Opening is idempotent: any second entry while the menu shows (a second finger on the pill,
    // a tap racing the Popup window's attach) must not re-anchor or re-create it.
    fun openMuteMenu(anchor: MuteMenuAnchor, touch: Offset) {
        if (muteMenuAnchor != null) return
        muteMenuTouch = touch
        muteMenuAnchor = anchor
    }

    @Composable
    fun MuteMenuFor(anchor: MuteMenuAnchor) {
        ParticipantMuteMenu(
            visible = muteMenuAnchor == anchor,
            touchInAnchor = muteMenuTouch,
            targetName = displayName,
            onDismissRequest = { muteMenuAnchor = null },
            onMute = {
                muteMenuAnchor = null
                onClickMute(displayName)
            },
            onOpenChanged = viewModel.callUiController::setParticipantMenuOpen,
        )
    }

    LaunchedEffect(videoPub) {
        val pub = videoPub
        if (pub != null) {
            pub::muted.flow.collect { muted -> videoMuted = muted }
        } else {
            videoMuted = true
        }
    }

    Box(
        modifier = Modifier
            .testTag("call_render_participant_$participantIndex")
            .pointerInput(muteMenuAvailable) {
                detectTapGestures(
                    onTap = { handleClickScreen() },
                    // Long-press anywhere on the tile stays as the compatibility entry and opens
                    // the same menu; it never mutes directly.
                    onLongPress = { touch ->
                        if (muteMenuAvailable && participant.isMicrophoneEnabled) {
                            openMuteMenu(MuteMenuAnchor.TILE, touch)
                        }
                    },
                )
            }
    ) {
        ConstraintLayout(
            modifier = modifier
                .clip(shape = RoundedCornerShape(cornerRadius))
                .background(color = DifftTheme.colors.background)
        ) {
            val (userView, statusView) = createRefs()

            Column(
                modifier = Modifier
                    .constrainAs(userView) {
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                        width = Dimension.fillToConstraints
                        height = Dimension.fillToConstraints
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    VideoItemTrackSelector(
                        coroutineScope = coroutineScope,
                        modifier = Modifier.background(Color.Transparent),
                        room = room,
                        participant = participant,
                        sourceType = Track.Source.CAMERA,
                        viewType = ViewType.Surface,
                        draggable = false,
                        reconnectCount = reconnectCount,
                    )
                    if (videoMuted) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(DifftTheme.colors.background),
                            contentAlignment = Alignment.Center
                        ) {
                            userDisplayInfo.avatarData?.let { avatarData ->
                                key(avatarData) {
                                    // No avatarSizeDp on purpose: the 96dp tile avatar keeps the
                                    // same 22dp letter as the 1v1 and incoming-call 96dp avatars;
                                    // scaling large avatars is a design decision, not a fix.
                                    AndroidView(
                                        factory = { ctx ->
                                            when (avatarData) {
                                                is AvatarData.FromContactor ->
                                                    callToChatController.getAvatarByContactor(ctx, avatarData.contactor)
                                                is AvatarData.FromNameOrUid ->
                                                    callToChatController.createAvatarByNameOrUid(
                                                        ctx,
                                                        avatarData.name,
                                                        avatarData.userId
                                                    )
                                            }
                                        },
                                        modifier = Modifier
                                            .height(96.dp)
                                            .width(96.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // The name pill is the single-tap mute entry; the rest of the tile keeps toggling the
            // overlays. The inner padding is transparent hit area only (minTouchTarget tall, bounded
            // by the tile edge below) — the pill keeps its 4dp / 4.33dp visual inset. The menu is
            // a child of the OUTER box so its Popup anchor shares the tap target's coordinate
            // space; inside the padded box the anchor would sit 20dp below the finger.
            Box(
                modifier = Modifier
                    .constrainAs(statusView) {
                        start.linkTo(parent.start)
                        bottom.linkTo(parent.bottom)
                    }
                    .testTag("call_render_participant_status_$participantIndex")
                    .muteMenuTapTarget(
                        participant = participant,
                        muteOtherEnabled = muteOtherEnabled,
                        yieldToLongPress = true,
                        onTap = { touch -> openMuteMenu(MuteMenuAnchor.PILL, touch) },
                    )
            ) {
                Row(
                    modifier = Modifier
                        .padding(
                            start = STATUS_PILL_INSET_START,
                            // Tops the pill + bottom inset up to the minimum touch target.
                            top = DifftTheme.spacing.minTouchTarget - STATUS_PILL_HEIGHT - STATUS_PILL_INSET_BOTTOM,
                            end = STATUS_PILL_HIT_SLOP_END,
                            bottom = STATUS_PILL_INSET_BOTTOM,
                        )
                        .wrapContentWidth()
                        .height(STATUS_PILL_HEIGHT)
                        .background(color = DifftTheme.colors.backgroundElevate, shape = RoundedCornerShape(size = 4.dp))
                        .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    ShowSpeakerStatusView(participant, displayName, speakingEnabled = speakingEnabled)
                }
                MuteMenuFor(MuteMenuAnchor.PILL)
            }
        }
        MuteMenuFor(MuteMenuAnchor.TILE)

        // Declared after the ConstraintLayout: inside a Box the later sibling draws on top.
        ParticipantWeakNetworkBadge(
            controller = viewModel.callUiController,
            localIdentity = remember { globalServices.myId },
            identity = uid,
            // Headcount comes from the parent layout, which already collects the participant list it
            // is rendering — this cell must not open a collector of its own (see [WeakNetworkBadge]).
            participantCount = participantCount,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        )
    }
}

private enum class MuteMenuAnchor { PILL, TILE }

private val STATUS_PILL_HEIGHT = 24.dp
private val STATUS_PILL_INSET_START = 4.dp
private val STATUS_PILL_INSET_BOTTOM = 4.33.dp
private val STATUS_PILL_HIT_SLOP_END = 12.dp
