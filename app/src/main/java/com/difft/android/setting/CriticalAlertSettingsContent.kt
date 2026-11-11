package com.difft.android.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.chat.R as ChatR

internal const val TAG_ROW_DND_ACCESS = "row_dnd_access"
internal const val TAG_ROW_CRITICAL_CATEGORY = "row_critical_category"
internal const val TAG_ROW_CRITICAL_BACKGROUND = "row_critical_background_connection"





@Composable
internal fun CriticalAlertSettingsContent(
    state: NotificationSettingsUiState?,
    actions: NotificationSubpageActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DifftSettingsDefaults.PageInset),
    ) {
        Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
        DifftSettingsGroup {
            
            ValueRow(
                label = stringResource(ChatR.string.notification_dnd_access),
                value = state?.let {
                    stringResource(if (it.dndAccessGranted) ChatR.string.notification_dnd_access_granted else ChatR.string.notification_dnd_access_denied)
                },
                note = stringResource(ChatR.string.critical_alert_dnd_caption),
                onClick = { state?.let { actions.onFix(it.dndFix) } },
                modifier = Modifier.testTag(TAG_ROW_DND_ACCESS),
            )
            StatusValueRow(
                label = stringResource(ChatR.string.notification_critical_alert_category),
                status = state?.criticalCategoryStatus,
                tag = TAG_ROW_CRITICAL_CATEGORY,
                onClick = { actions.onFix(NotificationFix.CRITICAL_ALERT_CATEGORY) },
                note = stringResource(
                    if (state?.showXiaomiLockScreenNote == true) ChatR.string.critical_alert_category_note_xiaomi else ChatR.string.critical_alert_category_note,
                ),
            )
            if (state != null) LockScreenRows(state, LockScreenSubject.CRITICAL_ALERTS, actions.onFix, actions.onGuide)
            ValueRow(
                label = stringResource(ChatR.string.background_connection),
                value = state?.let {
                    stringResource(if (it.backgroundConnectionOn) ChatR.string.notification_enable else ChatR.string.background_connection_status_off)
                },
                warning = state?.backgroundConnectionWarning == true,
                note = stringResource(ChatR.string.critical_alert_connection_caption),
                onClick = actions.onBackgroundConnection,
                modifier = Modifier.testTag(TAG_ROW_CRITICAL_BACKGROUND),
            )
        }
        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
}
