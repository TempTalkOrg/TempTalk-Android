package com.difft.android.call.ui.screenshare

import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.call.R
import com.difft.android.call.util.StringUtil
import com.difft.android.base.R as BaseR

@Composable
internal fun MicQueueList(waitingSpeakers: List<WaitingSpeakerDisplay>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(
            thickness = 1.dp,
            color = colorResource(id = BaseR.color.bg3_night),
        )
        Text(
            text = stringResource(id = R.string.call_screen_share_mic_queue),
            style = TextStyle(
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = colorResource(id = BaseR.color.t_secondary_night),
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
            AvatarContent(speaker.avatarModel)
        }
        Text(
            text = StringUtil.truncateWithEllipsis(
                speaker.displayName, MIC_QUEUE_NAME_MAX_LENGTH,
            ),
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = colorResource(id = BaseR.color.t_secondary_night),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun AvatarContent(avatarModel: Any?) {
    when (avatarModel) {
        is ConstraintLayout -> {
            key(avatarModel) {
                AndroidView(
                    factory = {
                        (avatarModel.parent as? ViewGroup)?.removeView(avatarModel)
                        avatarModel.apply {
                            clipToOutline = true
                            clipChildren = true
                        }
                        scaleLetterAvatarText(avatarModel)
                        avatarModel
                    },
                    modifier = Modifier.size(AVATAR_SIZE),
                )
            }
        }
        else -> {
            Box(
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .background(colorResource(id = BaseR.color.bg2_night)),
            )
        }
    }
}

internal fun scaleLetterAvatarText(view: ViewGroup) {
    for (i in 0 until view.childCount) {
        val child = view.getChildAt(i)
        if (child is CardView) {
            for (j in 0 until child.childCount) {
                val textView = child.getChildAt(j) as? TextView ?: continue
                textView.textSize = AVATAR_LETTER_TEXT_SIZE_DP
            }
        }
    }
}
