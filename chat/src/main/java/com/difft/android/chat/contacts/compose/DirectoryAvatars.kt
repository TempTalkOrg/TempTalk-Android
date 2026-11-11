package com.difft.android.chat.contacts.compose

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.difft.android.chat.common.AvatarView


@Composable
internal fun FavoritesAvatar(size: Dp, modifier: Modifier = Modifier) {
    AndroidView(factory = { AvatarView(it) }, update = { it.showFavorites() },
        modifier = modifier.size(size).clip(CircleShape))
}
