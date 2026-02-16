package com.difft.android.call.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import com.difft.android.call.R
import com.difft.android.call.util.IdUtil
import com.difft.android.call.util.StringUtil
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow


@Composable
fun ShowSpeakerStatusView(participant: Participant, userName: String?) {

    val imageLoader = LocalImageLoaderProvider.localImageLoader()
    val isSpeaking by participant::isSpeaking.flow.collectAsState()

    val audioTrackMap by participant::audioTrackPublications.flow.collectAsState(initial = emptyList())
    val audioPubs = audioTrackMap.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }

    val audioPub = audioPubs.firstOrNull { pub -> pub.source == Track.Source.MICROPHONE }

    var audioMuted by remember { mutableStateOf(true) }

    // monitor audio muted state
    LaunchedEffect(audioPub) {
        if (audioPub != null) {
            audioPub::muted.flow.collect { muted -> audioMuted = muted }
        }
    }

    Row(
        modifier = Modifier
            .wrapContentWidth()
            .height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 使用when表达式简化条件判断
        val painter = when {
            audioMuted -> painterResource(id = R.drawable.microphone_off)
            !isSpeaking -> painterResource(id = R.drawable.ic_silent)
            else -> rememberAsyncImagePainter(model = R.drawable.speaking, imageLoader = imageLoader)
        }

        val tintColor = when {
            audioMuted -> Color.Unspecified // 不设置颜色，或者根据需要设置
            else ->  colorResource(id = com.difft.android.base.R.color.t_info_night)
        }

        Icon(
            painter = painter,
            contentDescription = "",
            modifier = Modifier
                .padding(0.5.dp)
                .width(12.dp)
                .height(12.dp),
            tint = tintColor,
        )

        val username = "${userName ?: IdUtil.convertToBase58UserName(participant.identity?.value)}"

        Text(
            text = StringUtil.truncateWithEllipsis(username, 14),
            // SF/P4
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight(400),
                color = colorResource(id = com.difft.android.base.R.color.t_primary_night),
            )
        )
    }
}