package com.difft.android.setting.notificationtest

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestOutcome
import com.difft.android.base.notification.NotificationTestResult
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.compose.DifftButtonSize
import com.difft.android.base.ui.compose.DifftButtonVariant
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Intent
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.State
import java.text.DateFormat
import java.util.Date
import com.difft.android.chat.R as ChatR

internal fun testRowTag(kind: NotificationTestKind) = "notification_test_row_${kind.logName}"
internal fun testButtonTag(kind: NotificationTestKind) = "notification_test_button_${kind.logName}"
internal fun testStatusTag(kind: NotificationTestKind) = "notification_test_status_${kind.logName}"

/** The notification test page (#1350): one row per test with its last valid result and a start button. */
@Composable
internal fun NotificationTestContent(
    state: State,
    onIntent: (Intent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DifftSettingsDefaults.PageInset),
    ) {
        Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
        DifftSettingsGroup(
            footer = { DifftSettingsCaption(stringResource(ChatR.string.notification_test_caption)) },
        ) {
            NotificationTestKind.entries.forEach { kind ->
                TestRow(
                    kind = kind,
                    state = state,
                    onIntent = onIntent,
                )
            }
        }
        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
    NotificationTestDialogs(state.dialog, onIntent)
}

@Composable
private fun TestRow(kind: NotificationTestKind, state: State, onIntent: (Intent) -> Unit) {
    val context = LocalContext.current
    val running = state.running == kind
    DifftSettingsRow(
        modifier = Modifier.testTag(testRowTag(kind)),
        trailing = {
            DifftButton(
                text = stringResource(if (running) android.R.string.cancel else ChatR.string.notification_test_start),
                onClick = { onIntent(if (running) Intent.Cancel else Intent.Start(kind)) },
                enabled = running || state.running == null,
                size = DifftButtonSize.S,
                variant = if (running) DifftButtonVariant.Text else DifftButtonVariant.Secondary,
                modifier = Modifier.testTag(testButtonTag(kind)),
            )
        },
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(kindLabel(kind)),
                style = DifftSettingsDefaults.LabelTextStyle,
                color = DifftTheme.colors.textPrimary,
            )
            Text(
                text = if (running) {
                    stringResource(ChatR.string.notification_test_running)
                } else {
                    resultLabel(context, kind, state.results[kind])
                },
                style = DifftTheme.typography.p3,
                color = DifftTheme.colors.textTertiary,
                modifier = Modifier.testTag(testStatusTag(kind)),
            )
        }
    }
}

internal fun kindLabel(kind: NotificationTestKind): Int = when (kind) {
    NotificationTestKind.BANNER -> ChatR.string.notification_test_banner
    NotificationTestKind.LOCKSCREEN -> ChatR.string.notification_test_lockscreen
    NotificationTestKind.CALL -> ChatR.string.notification_call_popup
}

/** "Not tested", or the outcome with its date: confirmed / not seen for messages, passed / did not pop up for calls. */
internal fun resultLabel(context: Context, kind: NotificationTestKind, result: NotificationTestResult?): String {
    if (result == null) return context.getString(ChatR.string.notification_test_untested)
    val ok = result.outcome == NotificationTestOutcome.OK
    val label = when (kind) {
        NotificationTestKind.CALL -> if (ok) ChatR.string.notification_test_passed else ChatR.string.notification_test_not_shown
        else -> if (ok) ChatR.string.notification_level_confirmed else ChatR.string.notification_test_not_seen
    }
    val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(result.epochMs))
    return "${context.getString(label)} · $date"
}
