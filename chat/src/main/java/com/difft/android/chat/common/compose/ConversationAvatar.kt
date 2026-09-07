package com.difft.android.chat.common.compose

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.common.GroupAvatarView
import com.difft.android.network.group.GroupAvatarData
import org.difft.app.database.models.ContactorModel

/**
 * Compose wrappers over the legacy avatar views so Compose screens render exactly the same
 * bitmap/letter/remark chain the XML screens do. Glide gets an explicit target size because the
 * AndroidView measure pass runs after the first `update`, which otherwise yields a 0-size request.
 */
@Composable
fun ContactAvatar(
    contactor: ContactorModel,
    size: Dp,
    modifier: Modifier = Modifier,
    letterTextSizeDp: Int = 22,
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    AndroidView(
        factory = { ctx -> AvatarView(ctx) },
        update = { view -> view.setAvatar(contactor, letterTextSizeDp, sizePx) },
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    )
}

@Composable
fun GroupAvatar(
    avatarData: GroupAvatarData?,
    gid: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    localPath: String? = null,
) {
    AndroidView(
        factory = { ctx -> GroupAvatarView(ctx) },
        update = { view ->
            if (localPath != null) view.setAvatar(localPath) else view.setAvatar(avatarData, gid = gid)
        },
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    )
}
