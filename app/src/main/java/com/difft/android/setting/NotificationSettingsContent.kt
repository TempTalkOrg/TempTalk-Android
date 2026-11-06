package com.difft.android.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.compose.DifftButtonSize
import com.difft.android.base.ui.compose.DifftButtonVariant
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R as ChatR
import com.difft.android.chat.focus.settings.NotifyFocusCard
import com.difft.android.chat.focus.settings.NotifyGroupNotificationRow
import com.difft.android.chat.focus.settings.groupNotificationNote
import com.difft.android.chat.focus.settings.NotifyFocusSettingsContract

internal const val TAG_BANNER = "notification_banner"
internal const val TAG_GROUP_APP_NOTIFICATIONS = "group_app_notifications"
internal const val TAG_ROW_APP_NOTIFICATIONS = "row_app_notifications"
internal const val TAG_ROW_MESSAGE = "row_message"
internal const val TAG_ROW_CALLS = "row_calls"
internal const val TAG_ROW_CRITICAL = "row_critical"
internal const val TAG_ROW_BACKGROUND = "row_background_connection"
internal const val TAG_ACTION_NOTIFICATION_OVERVIEW = "notification_overview_action"

/** Labels that come from user settings rather than from the readiness checks. */
internal data class NotificationSettingsLabels(
    val groupNotification: String,
    val displayContent: String,
)

internal class NotificationSettingsActions(
    val onFix: (NotificationFix) -> Unit,
    /** Opens the Calls page. */
    val onCalls: () -> Unit,
    /** Opens the Critical Alerts page. */
    val onCriticalAlerts: () -> Unit,
    val onGroupNotification: () -> Unit,
    val onDisplayContent: () -> Unit,
    val onBackgroundConnection: () -> Unit,
    val onGuide: () -> Unit,
    /** Opens the notification test page (#1350). */
    val onNotificationTest: () -> Unit,
)

/**
 * The notification settings page as one grouped list. Calls and Critical Alerts summarise their own
 * pages: "on" when readable, otherwise the first definite problem, otherwise nothing.
 */
@Composable
internal fun NotificationSettingsContent(
    state: NotificationSettingsUiState?,
    labels: NotificationSettingsLabels,
    actions: NotificationSettingsActions,
    modifier: Modifier = Modifier,
    focus: NotifyFocusSettingsContract.State = NotifyFocusSettingsContract.State(),
    onFocusIntent: (NotifyFocusSettingsContract.Intent) -> Unit = {},
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DifftSettingsDefaults.PageInset),
    ) {
        Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
        if (state?.showBanner == true) {
            NotificationOffBanner(onEnable = { actions.onFix(NotificationFix.APP_NOTIFICATIONS) })
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }

        if (state?.showAppNotificationsGroup == true) {
            AppNotificationsGroup(state, actions)
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }

        MessageGroup(state, labels, actions, focus, onFocusIntent)

        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        DifftSettingsGroup {
            StatusValueRow(
                label = stringResource(ChatR.string.notification_calls),
                status = state?.callSummary,
                tag = TAG_ROW_CALLS,
                onClick = actions.onCalls,
            )
            StatusValueRow(
                label = stringResource(ChatR.string.critical_alerts),
                status = state?.criticalSummary,
                tag = TAG_ROW_CRITICAL,
                onClick = actions.onCriticalAlerts,
            )
        }

        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        DifftSettingsGroup {
            ValueRow(
                label = stringResource(ChatR.string.background_connection),
                value = state?.let {
                    stringResource(if (it.backgroundConnectionOn) ChatR.string.notification_enable else ChatR.string.background_connection_status_off)
                },
                onClick = actions.onBackgroundConnection,
                modifier = Modifier.testTag(TAG_ROW_BACKGROUND),
            )
        }

        SetupGuideFooter(onClick = actions.onGuide, onTest = actions.onNotificationTest)
    }
}

/**
 * The app-level switch every category depends on. Xiaomi, vivo and OPPO keep more app-level switches
 * on that page, so the caption names them and links there.
 */
@Composable
private fun AppNotificationsGroup(state: NotificationSettingsUiState, actions: NotificationSettingsActions) {
    DifftSettingsGroup(
        modifier = Modifier.testTag(TAG_GROUP_APP_NOTIFICATIONS),
        footer = if (state.showOverviewHint) {
            {
                // The link ends the sentence instead of taking a line of its own.
                CaptionWithLink(
                    caption = stringResource(overviewCaption(state.oemBrand)),
                    link = stringResource(ChatR.string.notification_overview_action),
                    tag = TAG_ACTION_NOTIFICATION_OVERVIEW,
                    onLink = { actions.onFix(NotificationFix.APP_NOTIFICATIONS) },
                )
            }
        } else null,
    ) {
        ValueRow(
            label = stringResource(ChatR.string.notification_app_notifications),
            value = if (state.appNotificationsOn) stringResource(ChatR.string.notification_enable) else null,
            onClick = { actions.onFix(NotificationFix.APP_NOTIFICATIONS) },
            modifier = Modifier.testTag(TAG_ROW_APP_NOTIFICATIONS),
        )
    }
}

@Composable
private fun NotificationOffBanner(onEnable: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DifftSettingsDefaults.GroupShape)
            .background(DifftSettingsDefaults.RowSurfaceColor)
            .padding(DifftSettingsDefaults.RowContentPadding)
            .testTag(TAG_BANNER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DifftTheme.spacing.inlineMedium),
    ) {
        Text(
            text = stringResource(ChatR.string.notification_banner_off),
            style = DifftSettingsDefaults.LabelTextStyle,
            color = DifftTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        DifftButton(
            text = stringResource(ChatR.string.notification_banner_enable),
            onClick = onEnable,
            size = DifftButtonSize.S,
            variant = DifftButtonVariant.Primary,
        )
    }
}

/**
 * The message category and the message preferences. With the group focus feature on, "Group Focus"
 * is its own card after them, each state or restore sentence below its card.
 */
@Composable
private fun MessageGroup(
    state: NotificationSettingsUiState?,
    labels: NotificationSettingsLabels,
    actions: NotificationSettingsActions,
    focus: NotifyFocusSettingsContract.State,
    onFocusIntent: (NotifyFocusSettingsContract.Intent) -> Unit,
) {
    val context = LocalContext.current
    val messageRow: @Composable () -> Unit = {
        ValueRow(
            label = stringResource(ChatR.string.notification_message_notifications),
            value = state?.let { NotificationReadinessText.categoryLevelLabel(context, it.messageLevel) },
            warning = state?.let { NotificationReadinessText.isProblem(it.messageLevel) } == true,
            onClick = { actions.onFix(NotificationFix.MESSAGE_CATEGORY) },
            modifier = Modifier.testTag(TAG_ROW_MESSAGE),
        )
    }
    val groupRow: @Composable () -> Unit = {
        NotifyGroupNotificationRow(state = focus, onIntent = onFocusIntent) {
            ValueRow(
                label = stringResource(ChatR.string.notification_group_chat_notification),
                value = labels.groupNotification,
                onClick = actions.onGroupNotification,
            )
        }
    }
    val displayRow: @Composable () -> Unit = {
        ValueRow(
            label = stringResource(ChatR.string.notification_display),
            value = labels.displayContent,
            onClick = actions.onDisplayContent,
        )
    }
    if (!focus.featureEnabled) {
        DifftSettingsGroup {
            messageRow()
            groupRow()
            displayRow()
        }
        return
    }
    // Display is a message preference, so it stays with the message rows; Group Focus follows as its own card.
    val groupNote = groupNotificationNote(focus)
    DifftSettingsGroup(footer = groupNote?.let { note -> { DifftSettingsCaption(note) } }) {
        messageRow()
        groupRow()
        displayRow()
    }
    Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    NotifyFocusCard(state = focus, onIntent = onFocusIntent)
}

/** The overview caption names the verified section and switches on Xiaomi and vivo; other skins get the generic one. */
private fun overviewCaption(brand: OemBrand): Int = when (brand) {
    OemBrand.XIAOMI -> ChatR.string.notification_overview_caption_xiaomi
    OemBrand.VIVO -> ChatR.string.notification_overview_caption_vivo
    else -> ChatR.string.notification_overview_caption
}
