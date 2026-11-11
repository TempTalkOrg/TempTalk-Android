package com.difft.android.chat.contacts.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.common.compose.GroupAvatar
import com.difft.android.chat.group.getDisplayAvatarData
import org.difft.app.database.models.GroupModel


@Composable
fun GroupAvatarWithCount(
    group: GroupModel,
    size: Dp = DifftTheme.contacts.rowAvatar,
    showCount: Boolean = true,
    memberCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val tokens = DifftTheme.contacts
    val label = if (showCount) memberCountLabel(memberCount) else null
    Box(modifier.size(size)) {
        
        GroupAvatar(group.getDisplayAvatarData(), group.gid, size)
        if (label != null) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .height(tokens.countHeight).widthIn(min = tokens.countHeight)
                    .background(tokens.countBackground, CircleShape)
                    .border(tokens.countBorder, tokens.countBorderColor, CircleShape)
                    .padding(horizontal = tokens.countPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = tokens.countStyle, color = tokens.countForeground, maxLines = 1)
            }
        }
    }
}
