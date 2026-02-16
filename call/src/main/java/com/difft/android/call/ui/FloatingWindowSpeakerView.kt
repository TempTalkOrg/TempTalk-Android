package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.data.CallUserDisplayInfo
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FloatingWindowSpeakerView(modifier: Modifier = Modifier, room: Room, showSpeaker: Participant, userDisplayInfo: CallUserDisplayInfo) {

    var speakerVideoMuted by remember { mutableStateOf(true) }

    var currentSpeaker by remember { mutableStateOf<Participant?>(null) }

    var currentSpeakerName by remember { mutableStateOf<String?>(null) }

    var delayJob by remember { mutableStateOf<Job?>(null) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(showSpeaker) {
        showSpeaker::isCameraEnabled.flow.collect {
            speakerVideoMuted = !it
        }
    }

    LaunchedEffect(userDisplayInfo) { // 用于处理视频切换时底部状态view更新不同步问题
        delayJob?.cancel()
        if(currentSpeaker?.isCameraEnabled == true && showSpeaker.isCameraEnabled && showSpeaker !is LocalParticipant){
            delayJob = scope.launch {
                delay(700)
                currentSpeaker = showSpeaker
                currentSpeakerName = userDisplayInfo.name
            }
        }else{
            currentSpeaker = showSpeaker
            currentSpeakerName = userDisplayInfo.name
        }
    }

    ConstraintLayout(
        modifier = modifier
            .fillMaxSize()
            .clip(shape = RoundedCornerShape(8.dp))
    ){
        val (userView, statusView) = createRefs()

        val speakerStatusViewWithIn = LCallUiConstants.SCREEN_SHARE_FLOATING_VIEW_WIDTH.dp - 8.dp

        Column(
            modifier = Modifier
                .constrainAs(userView) {
                    top.linkTo(parent.top)
                    start.linkTo(parent.start)
                    end.linkTo(parent.end)
                }
                .background(colorResource(id = com.difft.android.base.R.color.bg2_night)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                userDisplayInfo.avatar?.let { avatar ->
                    AvatarImageView(avatar)
                }
                if (!speakerVideoMuted) {
                    VideoItemTrackSelector(
                        coroutineScope = scope,
                        modifier = Modifier.background(Color.Transparent),
                        room = room,
                        participant = showSpeaker,
                        sourceType = Track.Source.CAMERA,
                        scaleType = ScaleType.Fill,
                        viewType = ViewType.Texture,
                        draggable = false
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .constrainAs(statusView) {
                    start.linkTo(parent.start, 4.dp)
                    bottom.linkTo(parent.bottom, 4.dp)
                }
                .widthIn(max = speakerStatusViewWithIn),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ){
            if (currentSpeaker != null && currentSpeakerName != null) {
                ScreenShareSpeakerStatusView(modifier, currentSpeaker!!, currentSpeakerName)
            }
        }
    }


}


@Composable
private fun AvatarImageView(avatar: ConstraintLayout?){
    avatar?.let { avatarImage ->
        key (avatar){
            AndroidView(
                factory = {
                    avatarImage
                },
                modifier = Modifier
                    .height(56.dp)
                    .width(56.dp)
            )
        }
    }
}