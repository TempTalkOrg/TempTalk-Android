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
import com.difft.android.call.LCallManager
import com.difft.android.call.ui.video.VideoItemTrackSelector
import com.difft.android.call.ui.video.ViewType
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.AvatarData
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.data.MUTE_ACTION_INDEX
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
    muteOtherEnabled: Boolean,
    onClickMute: () -> Unit,
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

    var expanded by remember { mutableStateOf(false) }

    fun onClickItem(index: Int, setExpanded: (Boolean) -> Unit, onClickMute: () -> Unit) {
        setExpanded(false)
        when (index) {
            MUTE_ACTION_INDEX -> onClickMute()
            else -> {}
        }
    }

    fun handleClickScreen() {
        viewModel.callUiController.toggleOverlays()
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
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        if (participant.isMicrophoneEnabled && muteOtherEnabled) {
                            expanded = true
                        }
                    },
                    onTap = {
                        handleClickScreen()
                    }
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

            Row(
                modifier = Modifier
                    .constrainAs(statusView) {
                        start.linkTo(parent.start, 4.dp)
                        bottom.linkTo(parent.bottom, 4.33.dp)
                    }
                    .wrapContentWidth()
                    .height(24.dp)
                    .background(color = DifftTheme.colors.backgroundElevate, shape = RoundedCornerShape(size = 4.dp))
                    .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
                verticalAlignment = Alignment.Bottom,
            ) {
                ShowSpeakerStatusView(participant, userDisplayInfo.name, speakingEnabled = speakingEnabled)
            }
        }

        ShowItemOnClickView(listOf("Mute"), expanded, setExpanded = { value -> expanded = value },
            onClickItem = { index ->
                onClickItem(index,
                    setExpanded = { value -> expanded = value },
                    onClickMute = { onClickMute() }
                )
            }
        )
    }
}
