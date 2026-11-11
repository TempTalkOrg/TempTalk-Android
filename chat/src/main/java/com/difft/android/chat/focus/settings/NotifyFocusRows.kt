package com.difft.android.chat.focus.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSwitchRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.focus.settings.NotifyFocusSettingsContract.Intent
import com.difft.android.chat.focus.settings.NotifyFocusSettingsContract.State





@Composable
fun NotifyGroupNotificationRow(state: State, onIntent: (Intent) -> Unit, plainRow: @Composable () -> Unit) {
    if (state.groupNotificationPaused) {
        PausedRow(label = stringResource(R.string.notification_group_chat_notification)) {
            onIntent(Intent.TapGroupNotificationPaused)
        }
    } else {
        plainRow()
    }
}


@Composable
fun groupNotificationNote(state: State): String? =
    if (state.groupNotificationPaused) stringResource(R.string.focus_paused_returns, notificationLevelLabel(state.groupLevel)) else null





@Composable
fun NotifyFocusCard(state: State, onIntent: (Intent) -> Unit) {
    if (!state.featureEnabled) return
    val caption = state.caption
    DifftSettingsGroup(
        footer = {
            DifftSettingsCaption(
                text = stringResource(
                    when (caption) {
                        NotifyFocusCaption.Off -> R.string.focus_me_caption_off
                        is NotifyFocusCaption.Focusing -> R.string.focus_caption_on
                        NotifyFocusCaption.Invalid -> R.string.focus_caption_invalid
                    }
                ),
                color = if (caption == NotifyFocusCaption.Invalid) DifftTheme.colors.textError else DifftTheme.colors.textTertiary,
            )
        },
    ) {
        FocusRowBlock {
            DifftSwitchRow(
                label = stringResource(R.string.focus_group_chat),
                checked = state.focusOn,
                onCheckedChange = { onIntent(Intent.ToggleFocus(it)) },
                modifier = Modifier.fillMaxWidth().background(DifftSettingsDefaults.RowSurfaceColor),
                contentPadding = DifftSettingsDefaults.RowContentPadding,
            )
            if (state.focusOn) {
                InsetDivider()
                if (caption is NotifyFocusCaption.Focusing) {
                    
                    FocusSubRow(
                        label = stringResource(R.string.focus_focusing),
                        value = caption.name,
                        onClick = { onIntent(Intent.TapSelectGroup) },
                    )
                } else {
                    FocusSubRow(
                        label = stringResource(R.string.focus_select_group),
                        value = null,
                        onClick = { onIntent(Intent.TapSelectGroup) },
                    )
                }
                InsetDivider()
                FocusSubRow(
                    label = stringResource(R.string.focus_end_time),
                    value = state.end?.let { focusEndText(it) },
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
