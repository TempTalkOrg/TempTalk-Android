package com.difft.android.chat.group

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.difft.android.base.ui.compose.floatingSurface
import com.difft.android.base.ui.glass.GlassMode
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R


@Composable
internal fun GroupDissolveBanner(daysLeft: Int?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (daysLeft == null) return
    val spacing = DifftTheme.spacing
    Box(modifier.padding(top = spacing.insetMedium, start = spacing.insetLarge, end = spacing.insetLarge)) {
        Row(
            Modifier.fillMaxWidth()
                .testTag(GROUP_DISSOLVE_BANNER_TAG)
                .floatingSurface(cornerRadius = DifftTheme.radius.card, glass = GlassMode.isLive(LocalContext.current))
                .padding(start = spacing.insetMedium, top = spacing.insetXSmall, bottom = spacing.insetXSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.chat_ic_clock), contentDescription = null,
                tint = DifftTheme.colors.textCaution, modifier = Modifier.size(spacing.iconXSmall),
            )
            Column(Modifier.weight(1f).padding(start = spacing.inlineSmall)) {
                Text(
                    if (daysLeft == 0) stringResource(R.string.group_dissolve_today)
                    else pluralStringResource(R.plurals.group_dissolve_in_days, daysLeft, daysLeft),
                    style = DifftTheme.typography.h7, color = DifftTheme.colors.textPrimary,
                )
                Text(
                    stringResource(R.string.group_dissolve_keep_hint),
                    style = DifftTheme.typography.p4, color = DifftTheme.colors.textSecondary,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(spacing.minTouchTarget)) {
                Icon(
                    painterResource(R.drawable.chat_ic_close_24),
                    contentDescription = stringResource(com.difft.android.base.R.string.base_close),
                    tint = DifftTheme.colors.textSecondary, modifier = Modifier.size(spacing.iconXSmall),
                )
            }
        }
    }
}

internal const val GROUP_DISSOLVE_BANNER_TAG = "group_dissolve_banner"
