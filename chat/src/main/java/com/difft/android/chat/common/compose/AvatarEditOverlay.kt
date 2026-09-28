package com.difft.android.chat.common.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.chat.R

/**
 * Edit-mode scrim laid over a circular avatar: 45% black + a white outline camera. The whole
 * circle is the "change avatar" hit area, so this draws only; the caller owns the click.
 */
@Composable
fun AvatarEditOverlay(
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = OVERLAY_ALPHA)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.chat_ic_camera_outline),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = Color.White
        )
    }
}

private const val OVERLAY_ALPHA = 0.45f
