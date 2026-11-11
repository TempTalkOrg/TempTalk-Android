package com.difft.android.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.ReadinessStatus
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.R as CallR
import com.difft.android.chat.R as ChatR

internal const val TAG_ROW_FULL_SCREEN = "row_full_screen"
internal const val TAG_ROW_OEM_POPUP = "row_oem_popup"

internal enum class LockScreenSubject(@StringRes val fullScreenNote: Int) {
    CALLS(ChatR.string.notification_full_screen_note_calls),
    CRITICAL_ALERTS(ChatR.string.notification_full_screen_note_critical),
}






@Composable
internal fun LockScreenRows(
    state: NotificationSettingsUiState,
    subject: LockScreenSubject,
    onFix: (NotificationFix) -> Unit,
    onGuide: () -> Unit,
) {
    val context = LocalContext.current
    state.fullScreenItem?.let { item ->
        ValueRow(
            label = stringResource(ChatR.string.notification_full_screen_display),
            value = NotificationReadinessText.itemStatusLabel(context, item.status),
            warning = item.status == ReadinessStatus.PROBLEM,
            note = stringResource(subject.fullScreenNote),
            onClick = { onFix(NotificationFix.FULL_SCREEN_INTENT) },
            modifier = Modifier.testTag(TAG_ROW_FULL_SCREEN),
        )
    }
    if (state.oemPopupItem != null) {
        ValueRow(
            label = stringResource(oemPopupLabel(state)),
            note = stringResource(oemPopupNote(state)),
            onClick = { if (state.oemPopupOpensGuide) onGuide() else onFix(NotificationFix.OEM_POPUP) },
            modifier = Modifier.testTag(TAG_ROW_OEM_POPUP),
        )
    }
}

private fun oemPopupNote(state: NotificationSettingsUiState): Int = when {
    state.showXiaomiPopupHint -> CallR.string.notification_hint_oem_xiaomi
    state.showVivoPopupHint -> CallR.string.notification_hint_oem_vivo
    else -> ChatR.string.notification_oem_popup_note
}

private fun oemPopupLabel(state: NotificationSettingsUiState): Int =
    if (state.oemPopupOpensGuide) ChatR.string.notification_vendor_settings else ChatR.string.notification_oem_popup


@Composable
internal fun StatusValueRow(label: String, status: SettingStatus?, tag: String, onClick: () -> Unit, note: String? = null) {
    val context = LocalContext.current
    ValueRow(
        label = label,
        value = status?.let { NotificationReadinessText.statusLabel(context, it.label) },
        warning = status?.warning == true,
        note = note,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}







@Composable
internal fun ValueRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    warning: Boolean = false,
    note: String? = null,
    onClick: (() -> Unit)? = null,
) {
    DifftSettingsRow(modifier = modifier, onClick = onClick) {
        if (note == null) {
            LabelValueLine(label, value, warning)
        } else {
            
            Column(Modifier.weight(1f)) {
                Text(text = label, style = DifftSettingsDefaults.LabelTextStyle, color = DifftTheme.colors.textPrimary)
                Text(text = note, style = DifftSettingsDefaults.CaptionTextStyle, color = DifftTheme.colors.textTertiary)
            }
            if (value != null) {
                Spacer(Modifier.width(DifftSettingsDefaults.TrailingGap))
                Text(
                    text = value,
                    style = DifftSettingsDefaults.LabelTextStyle,
                    color = if (warning) DifftTheme.colors.textWarning else DifftTheme.colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RowScope.LabelValueLine(label: String, value: String?, warning: Boolean) {
    Text(
        text = label,
        style = DifftSettingsDefaults.LabelTextStyle,
        color = DifftTheme.colors.textPrimary,
    )
    if (value != null) {
        Spacer(Modifier.width(DifftSettingsDefaults.TrailingGap))
        Text(
            text = value,
            style = DifftSettingsDefaults.LabelTextStyle,
            color = if (warning) DifftTheme.colors.textWarning else DifftTheme.colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    } else {
        Spacer(Modifier.weight(1f))
    }
}


@Composable
internal fun CaptionWithLink(caption: String, link: String, tag: String, onLink: () -> Unit) {
    val linkStyles = TextLinkStyles(style = SpanStyle(color = DifftTheme.colors.textInfo, textDecoration = TextDecoration.Underline))
    Text(
        text = buildAnnotatedString {
            append(caption)
            withLink(LinkAnnotation.Clickable(tag = tag, styles = linkStyles) { onLink() }) { append(link) }
        },
        style = DifftSettingsDefaults.CaptionTextStyle,
        color = DifftTheme.colors.textTertiary,
        modifier = Modifier.testTag(tag),
    )
}
