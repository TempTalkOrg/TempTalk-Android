package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import coil3.compose.rememberAsyncImagePainter
import com.difft.android.call.LCallManager
import com.difft.android.call.LocalImageLoaderProvider
import com.difft.android.call.R
import com.difft.android.call.util.StringUtil
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow


@Composable
fun ScreenShareSpeakerStatusView(modifier: Modifier, activeSpeaker: Participant, userName: String?) {

    val imageLoader = LocalImageLoaderProvider.localImageLoader()

    val activeSpeakerVideoTracks by activeSpeaker::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs = activeSpeakerVideoTracks.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }
    val screenSharePub = videoPubs.firstOrNull { pub -> pub.source == Track.Source.SCREEN_SHARE }

    val activeSpeakerAudioTracks by activeSpeaker::audioTrackPublications.flow.collectAsState(initial = emptyList())
    val audioPubs = activeSpeakerAudioTracks.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }

    val audioPub = audioPubs.firstOrNull { pub -> pub.source == Track.Source.MICROPHONE }

    var isScreenSharing by remember { mutableStateOf(false) }
    var audioMuted by remember { mutableStateOf(true) }
    val isActiveSpeakerSpeaking by activeSpeaker::isSpeaking.flow.collectAsState()

    // monitor audio muted state
    LaunchedEffect(audioPub) {
        if (audioPub != null) {
            audioPub::muted.flow.collect { muted -> audioMuted = muted }
        }
    }

    LaunchedEffect(screenSharePub) {
        if (screenSharePub != null) {
            isScreenSharing = true
        }else {
            isScreenSharing = false
        }
    }

    ConstraintLayout(
        modifier = modifier
            .height(24.dp)
            .wrapContentWidth()
            .clip(shape = RoundedCornerShape(4.dp))
            .background(
                colorResource(id = com.difft.android.base.R.color.bg1_night).copy(alpha = 0.8f)
            )
    ){
        val (speakStatusView, shareStatusView, userNameView) = createRefs()

        if(isScreenSharing){
            val shareIconPainter= painterResource(id = R.drawable.tabler_aspect_ratio)
            Icon(
                painter = shareIconPainter,
                contentDescription = "",
                modifier = Modifier
                    .constrainAs(shareStatusView) {
                        start.linkTo(parent.start, 6.dp)
                        centerVerticallyTo(parent)
                    }
                    .size(16.dp),
                tint = Color.White
            )
        }

        // 使用when表达式简化条件判断
        val painter = when {
            audioMuted -> painterResource(id = R.drawable.call_icon_microphone_close)
            !isActiveSpeakerSpeaking -> painterResource(id = R.drawable.ic_silent)
            else -> rememberAsyncImagePainter(model = R.drawable.speaking, imageLoader = imageLoader)
        }

        val tintColor = when {
            audioMuted -> Color.Unspecified // 不设置颜色，或者根据需要设置
            else -> Color(0xFF82C1FC)
        }

        Icon(
            painter = painter,
            contentDescription = "",
            modifier = Modifier
                .constrainAs(speakStatusView) {
                    start.linkTo(
                        if (activeSpeaker.isScreenShareEnabled) shareStatusView.end else parent.start,
                        4.dp
                    )
                    end.linkTo(userNameView.start, 4.dp)
                    centerVerticallyTo(parent)
                }
                .size(16.dp),
            tint = tintColor
        )

        val username = "${userName ?: LCallManager.convertToBase58UserName(activeSpeaker.identity?.value)}"

        Text(
            modifier = Modifier
                .constrainAs(userNameView){
                    start.linkTo(speakStatusView.end)
                    end.linkTo(parent.end, 6.dp)
                    centerVerticallyTo(parent)
                },
            text = StringUtil.getShowUserName(username, 14),
            fontSize = 12.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}