package com.difft.android.chat.compose

import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.difft.android.chat.common.AvatarView

@Composable
fun AvatarComposeView(
    url: String? = null,
    key: String? = null,
    firstLetter: String? = null,
    id: String = "",
    statusIconSize: Int? = -1,
    statusIconMargin: Int? = -1,
    showStatusTag: Boolean = true,
    letterTextSizeDp: Int = 22,
    localPath: String? = null,
    resId: Int? = null,
    modifier: Modifier = Modifier
) {
    if (LocalInspectionMode.current) {
        Image(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = "Avatar Placeholder",
            modifier = modifier.size(48.dp),
            colorFilter = ColorFilter.tint(color = colorResource(id = com.difft.android.base.R.color.t_primary))
        )
    } else {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                AvatarView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            },
            update = { avatarView ->
                // Configure the AvatarView based on the parameters passed
                when {
                    localPath != null -> avatarView.setAvatar(localPath)
                    resId != null -> avatarView.setAvatar(resId)
                    else -> avatarView.setAvatar(
                        url = url,
                        key = key,
                        firstLetter = firstLetter,
                        id = id,
                        letterTextSizeDp = letterTextSizeDp
                    )
                }
            }
        )
    }
}

@Preview
@Composable
fun AvatarComposeViewPreview() {
    AvatarComposeView(
        url = "https://example.com/avatar.jpg",
        key = "unique_key",
        firstLetter = "A",
        id = "user_id_123",
        statusIconSize = 24,
        statusIconMargin = 8,
        showStatusTag = true,
        letterTextSizeDp = 24,
        modifier = Modifier.size(48.dp)
    )
}