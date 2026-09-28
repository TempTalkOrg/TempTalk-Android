package com.difft.android.call.ui.screenshare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.LCallManager
import com.difft.android.call.R
import com.difft.android.call.data.AvatarData
import com.difft.android.call.util.StringUtil
import dagger.hilt.android.EntryPointAccessors
import com.difft.android.base.R as BaseR

@Composable
internal fun MicQueueList(waitingSpeakers: List<WaitingSpeakerDisplay>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(
            thickness = 1.dp,
            color = DifftTheme.colors.backgroundTertiary,
        )
        Text(
            text = stringResource(id = R.string.call_screen_share_mic_queue),
            style = DifftTheme.typography.p5,
            color = DifftTheme.colors.textSecondary,
        )
        waitingSpeakers.forEach { speaker ->
            key(speaker.sid) {
                MicQueueItem(speaker)
            }
        }
    }
}

@Composable
internal fun MicQueueItem(speaker: WaitingSpeakerDisplay) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE)
                .clip(CircleShape),
        ) {
            AvatarContent(speaker.avatarData)
        }
        Text(
            text = StringUtil.truncateWithEllipsis(
                speaker.displayName, MIC_QUEUE_NAME_MAX_LENGTH,
            ),
            style = DifftTheme.typography.p4,
            color = DifftTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun AvatarContent(avatarData: AvatarData?) {
    if (avatarData == null) {
        // Data not loaded yet — neutral disc, same footprint as the avatar it replaces.
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE)
                .background(DifftTheme.colors.backgroundSecondary),
        )
        return
    }
    val controller = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(
            ApplicationHelper.instance,
        ).callToChatController
    }
    controller.Avatar(
        data = avatarData,
        size = AVATAR_SIZE,
        letterTextSizeDp = AVATAR_LETTER_TEXT_SIZE_DP,
    )
}
