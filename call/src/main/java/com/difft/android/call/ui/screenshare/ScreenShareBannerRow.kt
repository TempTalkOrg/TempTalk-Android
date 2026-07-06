package com.difft.android.call.ui.screenshare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.R
import com.difft.android.call.ui.LocalImageLoaderProvider

import com.difft.android.base.R as BaseR

private val INLINE_AVATAR_OVERLAP_OFFSET = 12.dp

@Composable
internal fun BannerRow(
    speakerUiState: ScreenShareFloatingSpeakerUiState,
    showInlineAvatars: Boolean = false,
    waitingSpeakers: List<WaitingSpeakerDisplay> = emptyList(),
) {
    val imageLoader = LocalImageLoaderProvider.localImageLoader()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (speakerUiState.isScreenSharing) {
            Icon(
                painter = painterResource(id = R.drawable.tabler_aspect_ratio),
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE),
                tint = Color.White,
            )
        }
        SpeakingIndicatorIcon(
            micMuted = speakerUiState.micMuted,
            isSpeaking = speakerUiState.isSpeaking,
            imageLoader = imageLoader,
        )
        Text(
            text = speakerUiState.displayName.orEmpty(),
            modifier = Modifier.weight(1f),
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = DifftTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showInlineAvatars) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(width = 1.dp, height = 12.dp)
                    .background(DifftTheme.colors.backgroundTertiary),
            )
            InlineWaitingAvatars(waitingSpeakers.take(2))
        }
    }
}

@Composable
private fun InlineWaitingAvatars(speakers: List<WaitingSpeakerDisplay>) {
    if (speakers.isEmpty()) return
    val totalWidth = if (speakers.size >= 2) {
        INLINE_AVATAR_OVERLAP_OFFSET + AVATAR_SIZE
    } else {
        AVATAR_SIZE
    }
    Box(modifier = Modifier.size(width = totalWidth, height = AVATAR_SIZE)) {
        if (speakers.size >= 2) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = INLINE_AVATAR_OVERLAP_OFFSET)
                    .size(AVATAR_SIZE)
                    .clip(CircleShape),
            ) {
                AvatarContent(speakers[1].avatarModel)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(AVATAR_SIZE)
                .clip(CircleShape),
        ) {
            AvatarContent(speakers[0].avatarModel)
        }
    }
}

@Composable
internal fun SpeakingIndicatorIcon(
    micMuted: Boolean,
    isSpeaking: Boolean,
    imageLoader: coil3.ImageLoader,
    modifier: Modifier = Modifier,
) {
    val painter = when {
        micMuted -> painterResource(id = R.drawable.call_icon_microphone_close)
        !isSpeaking -> painterResource(id = R.drawable.ic_silent)
        else -> rememberAsyncImagePainter(
            model = R.drawable.speaking,
            imageLoader = imageLoader,
        )
    }
    val tint = when {
        micMuted || isSpeaking -> Color.Unspecified
        else -> Color.White
    }
    Icon(
        painter = painter,
        contentDescription = null,
        modifier = modifier.size(ICON_SIZE),
        tint = tint,
    )
}
