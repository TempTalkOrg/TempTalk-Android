package com.difft.android.call.ui.screenshare

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.widget.ConstraintLayout
import coil3.compose.AsyncImage
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.ui.LocalImageLoaderProvider

private val avatarDiameter = 56.dp

@Composable
fun AvatarSurface(
    modifier: Modifier = Modifier,
    uiState: ScreenShareFloatingSpeakerUiState,
) {
    val model = uiState.avatarModel
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when {
            model == null -> AvatarPlaceholder()
            model is ConstraintLayout -> LegacyAvatarConstraintLayout(model)
            model is String -> {
                val imageLoader = LocalImageLoaderProvider.localImageLoader()
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    modifier = Modifier
                        .size(avatarDiameter)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
            else -> AvatarPlaceholder()
        }
    }
}

@Composable
private fun AvatarPlaceholder() {
    Box(
        modifier = Modifier
            .size(avatarDiameter)
            .clip(CircleShape)
            .background(DifftTheme.colors.backgroundSecondary),
    )
}

@Composable
private fun LegacyAvatarConstraintLayout(avatar: ConstraintLayout) {
    key(avatar) {
        AndroidView(
            factory = {
                (avatar.parent as? ViewGroup)?.removeView(avatar)
                avatar
            },
            modifier = Modifier.size(avatarDiameter),
        )
    }
}
