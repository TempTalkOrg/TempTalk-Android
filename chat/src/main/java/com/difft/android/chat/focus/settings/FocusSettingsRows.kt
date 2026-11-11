package com.difft.android.chat.focus.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsRow
import com.difft.android.base.ui.compose.settings.DifftSwitchRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.chat.R





object FocusRowDefaults {
    val SubIndent: Dp = 32.dp
    val DashWidth: Dp = 16.dp
    val SubContentPadding = PaddingValues(start = SubIndent, end = 16.dp, top = 10.dp, bottom = 10.dp)
    val SubDetailContentPadding = PaddingValues(start = SubIndent, end = 16.dp, top = 12.dp, bottom = 12.dp)
}






@Composable
fun FocusRowBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(DifftSettingsDefaults.RowSurfaceColor), content = content)
}


@Composable
fun InsetDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = FocusRowDefaults.SubIndent + FocusRowDefaults.DashWidth)
            .height(DifftSettingsDefaults.separatorThickness)
            .background(DifftTheme.colors.divider),
    )
}

@Composable
private fun SubLabel(text: String, modifier: Modifier = Modifier) {
    Box(Modifier.width(FocusRowDefaults.DashWidth)) {
        Text(text = "-", style = DifftSettingsDefaults.LabelTextStyle, color = DifftTheme.colors.textTertiary)
    }
    Text(
        text = text,
        style = DifftSettingsDefaults.LabelTextStyle,
        color = DifftTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}


@Composable
fun FocusSubRow(label: String, value: String?, onClick: () -> Unit) {
    DifftSettingsRow(onClick = onClick, contentPadding = FocusRowDefaults.SubContentPadding) {
        SubLabel(label)
        Text(
            text = value.orEmpty(),
            style = DifftSettingsDefaults.LabelTextStyle,
            color = DifftTheme.colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = DifftSettingsDefaults.TrailingGap),
            textAlign = TextAlign.End,
        )
    }
}





@Composable
fun FocusSubDetailRow(label: String, detail: String, onClick: () -> Unit) {
    val hasDetail = detail.isNotEmpty()
    DifftSettingsRow(
        onClick = onClick,
        contentPadding = if (hasDetail) FocusRowDefaults.SubDetailContentPadding else FocusRowDefaults.SubContentPadding,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(FocusRowDefaults.DashWidth)) {
                Text(text = "-", style = DifftSettingsDefaults.LabelTextStyle, color = DifftTheme.colors.textTertiary)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = DifftSettingsDefaults.LabelTextStyle,
                    color = DifftTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasDetail) {
                    Text(text = detail, style = DifftTheme.typography.p3, color = DifftTheme.colors.textTertiary)
                }
            }
        }
    }
}


@Composable
fun FocusSubSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    DifftSwitchRow(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.fillMaxWidth().background(DifftSettingsDefaults.RowSurfaceColor),
        contentPadding = FocusRowDefaults.SubContentPadding,
    ) {
        SubLabel(label, Modifier.weight(1f))
    }
}





@Composable
fun PausedRow(label: String, onClick: () -> Unit) {
    DifftSettingsRow(onClick = onClick, showChevron = false) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = DifftSettingsDefaults.LabelTextStyle,
                color = DifftTheme.colors.textDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DifftSettingsDefaults.TrailingGap))
            Text(
                text = stringResource(R.string.focus_paused),
                style = DifftSettingsDefaults.LabelTextStyle,
                color = DifftTheme.colors.textTertiary,
                maxLines = 1,
            )
        }
    }
}


@Composable
fun stillNotifySummary(items: List<StillNotifyItem>): String =
    items.map { stringResource(it.labelRes()) }.joinToString(stringResource(R.string.focus_item_separator))

fun StillNotifyItem.labelRes(): Int = when (this) {
    StillNotifyItem.MENTION_ME -> R.string.focus_item_mention_me
    StillNotifyItem.MENTION_ALL -> R.string.focus_item_mention_all
    StillNotifyItem.GROUP_CALL -> R.string.focus_item_group_call
}


@Composable
fun notificationLevelLabel(level: GlobalNotificationType): String = stringResource(
    when (level) {
        GlobalNotificationType.ALL -> R.string.notification_all
        GlobalNotificationType.MENTION -> R.string.notification_mention_only
        GlobalNotificationType.OFF -> R.string.notification_off
    }
)


@Composable
fun focusEndText(end: FocusEnd): String = when (end) {
    FocusEnd.Manual -> stringResource(R.string.focus_duration_manual)
    is FocusEnd.At -> if (end.tomorrow) stringResource(R.string.focus_end_tomorrow, end.time) else end.time
}


@Composable
fun focusCaptionText(caption: FocusCaption): String = when (caption) {
    FocusCaption.Off -> stringResource(R.string.focus_caption_off)
    FocusCaption.OthersMuted -> stringResource(R.string.focus_caption_on)
    FocusCaption.Invalid -> stringResource(R.string.focus_caption_invalid)
    is FocusCaption.OtherGroup -> when (val end = caption.end) {
        FocusEnd.Manual -> stringResource(R.string.focus_caption_other, caption.name)
        is FocusEnd.At -> stringResource(R.string.focus_caption_other_until, caption.name, focusEndText(end))
    }
}


@Composable
fun memberCountText(count: Int): String = pluralStringResource(R.plurals.focus_picker_members, count, count)
