package com.difft.android.chat.contacts.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.base.R as BaseR

data class CardAction(
    val label: String,
    @DrawableRes val iconRes: Int,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun CardActions(actions: List<CardAction>) {
    val tokens = DifftTheme.contacts
    
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DifftTheme.spacing.inlineMedium),
    ) {
        actions.forEach { action ->
            val color = if (action.destructive) DifftTheme.colors.textError else DifftTheme.colors.textPrimary
            val iconTint = if (action.destructive) DifftTheme.colors.textError else DifftTheme.colors.icon
            Column(
                modifier = Modifier
                    .width(tokens.actionCell)
                    .alpha(if (action.enabled) 1f else tokens.disabledAlpha)
                    .clip(RoundedCornerShape(tokens.gapMedium))
                    .clickable(enabled = action.enabled, onClick = action.onClick),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(tokens.gapSmall),
            ) {
                Box(
                    modifier = Modifier.size(tokens.actionSize).background(
                        if (DifftTheme.colors.isDark) DifftTheme.colors.backgroundTertiary else DifftTheme.colors.backgroundSecondary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(action.iconRes), contentDescription = null, tint = iconTint, modifier = Modifier.size(tokens.cardIcon))
                }
                Text(action.label, style = DifftTheme.typography.p4, color = color)
            }
        }
    }
}

@Composable
fun CardInfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    CardInfoRow(label, value, onClick, onLongClick = null, showChevron = onClick != null)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardInfoRow(
    label: String,
    value: String,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    showChevron: Boolean,
) {
    val tokens = DifftTheme.contacts
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = tokens.cardRowMinHeight)
            .then(if (onLongClick != null) Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
                else if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(tokens.cardLabelWidth), style = DifftTheme.typography.p3, color = DifftTheme.colors.textTertiary)
        Text(value, modifier = Modifier.weight(1f), style = DifftTheme.typography.p3, color = DifftTheme.colors.textPrimary)
        if (showChevron) {
            Icon(
                painterResource(BaseR.drawable.base_ic_chevron_right), contentDescription = null,
                tint = DifftTheme.colors.textSecondary, modifier = Modifier.size(tokens.bannerIcon),
            )
        }
    }
}

@Composable
fun CardBanner(title: String, hint: String) {
    val tokens = DifftTheme.contacts
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(DifftTheme.colors.backgroundSecondary, RoundedCornerShape(tokens.bannerRadius))
            .padding(tokens.gapLarge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.chat_ic_clock), contentDescription = null, tint = DifftTheme.colors.textCaution, modifier = Modifier.size(tokens.bannerIcon))
        Spacer(Modifier.width(tokens.gapMedium))
        Column {
            Text(title, style = DifftTheme.typography.h6, color = DifftTheme.colors.textPrimary)
            Text(hint, style = DifftTheme.typography.p4, color = DifftTheme.colors.textSecondary)
        }
    }
}

@Composable
fun CardDivider() {
    HorizontalDivider(
        thickness = DifftTheme.contacts.divider,
        color = DifftTheme.colors.divider,
    )
}
