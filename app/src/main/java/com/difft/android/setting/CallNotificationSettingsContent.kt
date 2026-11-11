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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.chat.R as ChatR

internal const val TAG_ROW_CALL_CATEGORY = "row_call_category"


internal class NotificationSubpageActions(
    val onFix: (NotificationFix) -> Unit,
    val onGuide: () -> Unit,
    val onBackgroundConnection: () -> Unit = {},
)


@Composable
internal fun CallNotificationSettingsContent(
    state: NotificationSettingsUiState?,
    actions: NotificationSubpageActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DifftSettingsDefaults.PageInset),
    ) {
        Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
        DifftSettingsGroup {
            ValueRow(
                label = stringResource(ChatR.string.notification_call_notifications),
                value = state?.let { NotificationReadinessText.categoryLevelLabel(context, it.callLevel) },
                warning = state?.let { NotificationReadinessText.isProblem(it.callLevel) } == true,
                note = if (state?.showXiaomiLockScreenNote == true) stringResource(ChatR.string.notification_lock_screen_check_note) else null,
                onClick = { actions.onFix(NotificationFix.CALL_CATEGORY) },
                modifier = Modifier.testTag(TAG_ROW_CALL_CATEGORY),
            )
            if (state != null) LockScreenRows(state, LockScreenSubject.CALLS, actions.onFix, actions.onGuide)
        }
        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
}
