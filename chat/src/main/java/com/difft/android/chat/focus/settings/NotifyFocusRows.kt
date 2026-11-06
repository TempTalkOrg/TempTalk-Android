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

/**
 * The "Group Notifications" row of Me > Notifications: [plainRow] normally, a "Paused" row while focus
 * is on. Emitted into the caller's group; the restore value goes below that group ([groupNotificationNote]).
 */
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

/** The note below the group holding [NotifyGroupNotificationRow]: what the level returns to; null when not paused. */
@Composable
fun groupNotificationNote(state: State): String? =
    if (state.groupNotificationPaused) stringResource(R.string.focus_paused_returns, notificationLevelLabel(state.groupLevel)) else null

/**
 * The "Group Focus" card of Me > Notifications (UI spec 10c8d1c9): the switch, then while focus is on
 * "- Focusing on  {group} >" (or "- Select Focus Group >" when the group is gone), the end time, the
 * still-notify items and folding; the state sentence sits below the card. Nothing when the server
 * feature is off.
 */
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
                    // Switching the focus group: the picker opens with the current group selected.
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
