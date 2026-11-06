package com.difft.android.chat.focus.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsRow
import com.difft.android.base.ui.compose.settings.DifftSwitchRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.focus.settings.GroupFocusSettingsContract.Intent
import com.difft.android.chat.focus.settings.GroupFocusSettingsContract.State

/**
 * The notification section of the group settings page, stateless: the focus card (when the server
 * feature is on), then the Mute / Notifications / Sound card with its takeover caption.
 */
@Composable
fun GroupFocusSettingsSection(state: State, onIntent: (Intent) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (state.featureEnabled) {
            FocusCard(state, onIntent)
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }
        NotificationCard(state, onIntent)
    }
}

@Composable
private fun FocusCard(state: State, onIntent: (Intent) -> Unit) {
    val captionColor = if (state.focusCaption == FocusCaption.Invalid) DifftTheme.colors.textError else DifftTheme.colors.textTertiary
    DifftSettingsGroup(
        footer = { DifftSettingsCaption(text = focusCaptionText(state.focusCaption), color = captionColor) },
    ) {
        FocusRowBlock {
            DifftSwitchRow(
                label = stringResource(R.string.focus_this_group),
                checked = state.focusOn,
                onCheckedChange = { onIntent(Intent.ToggleFocus(it)) },
                modifier = Modifier.fillMaxWidth().background(DifftSettingsDefaults.RowSurfaceColor),
                contentPadding = DifftSettingsDefaults.RowContentPadding,
            )
            if (state.focusOn) {
                InsetDivider()
                FocusSubRow(
                    label = stringResource(R.string.focus_end_time),
                    value = state.focusEnd?.let { focusEndText(it) },
                    onClick = { onIntent(Intent.TapEndTime) },
                )
                InsetDivider()
                FocusSubDetailRow(
                    label = stringResource(R.string.focus_others_still_notify),
                    detail = stillNotifySummary(state.othersStillNotify),
                    onClick = { onIntent(Intent.TapOthersStillNotify) },
                )
                InsetDivider()
                FocusSubSwitchRow(
                    label = stringResource(R.string.focus_fold_others),
                    checked = state.foldOthers,
                    onCheckedChange = { onIntent(Intent.SetFoldOthers(it)) },
                )
            }
        }
    }
}

@Composable
private fun NotificationCard(state: State, onIntent: (Intent) -> Unit) {
    val footerText = takeoverText(state.takeoverCaption)
    DifftSettingsGroup(
        footer = footerText?.let { text -> { DifftSettingsCaption(text = text) } },
    ) {
        FocusRowBlock {
            if (state.mutePaused) {
                PausedRow(stringResource(R.string.chat_settings_mute)) { onIntent(Intent.TapMutePaused) }
            } else {
                DifftSwitchRow(
                    label = stringResource(R.string.chat_settings_mute),
                    checked = state.muted,
                    onCheckedChange = { onIntent(Intent.ToggleMute(it)) },
                    modifier = Modifier.fillMaxWidth().background(DifftSettingsDefaults.RowSurfaceColor),
                    contentPadding = DifftSettingsDefaults.RowContentPadding,
                )
            }
            if (state.showMuteExceptions) {
                InsetDivider()
                FocusSubDetailRow(
                    label = stringResource(R.string.focus_still_notify),
                    detail = stillNotifySummary(state.muteExceptions),
                    onClick = { onIntent(Intent.TapMuteExceptions) },
                )
            }
        }
        if (state.notifyPaused) {
            PausedRow(stringResource(R.string.notification_message_notification)) { onIntent(Intent.TapNotification) }
        } else {
            DifftSettingsRow(
                label = stringResource(R.string.notification_message_notification),
                value = notificationLevelLabel(state.level),
                onClick = { onIntent(Intent.TapNotification) },
            )
        }
        if (state.soundVisible) {
            DifftSettingsRow(
                label = stringResource(R.string.notification_message_sounds),
                value = state.soundLabel,
                onClick = { onIntent(Intent.TapSound) },
            )
        }
    }
}

@Composable
private fun takeoverText(caption: TakeoverCaption): String? = when (caption) {
    TakeoverCaption.None -> null
    is TakeoverCaption.ByFocus -> stringResource(
        R.string.focus_takeover_caption,
        stringResource(if (caption.muted) R.string.focus_mute_on else R.string.focus_mute_off),
        notificationLevelLabel(caption.level),
    )

    is TakeoverCaption.ByMute -> stringResource(R.string.focus_muted_caption, notificationLevelLabel(caption.level))
}
