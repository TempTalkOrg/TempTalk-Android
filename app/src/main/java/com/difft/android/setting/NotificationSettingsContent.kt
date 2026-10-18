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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.ReadinessItem
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.compose.DifftButtonSize
import com.difft.android.base.ui.compose.DifftButtonVariant
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.PackageUtil
import com.difft.android.call.R as CallR
import com.difft.android.chat.R as ChatR

internal const val TAG_BANNER = "notification_banner"
internal const val TAG_ROW_MESSAGE = "row_message"
internal const val TAG_ROW_CALL = "row_call"
internal const val TAG_ROW_FULL_SCREEN = "row_full_screen"
internal const val TAG_ROW_OEM_POPUP = "row_oem_popup"
internal const val TAG_ROW_CRITICAL = "row_critical"
internal const val TAG_GROUP_LOCK_SCREEN = "group_lock_screen"

/** Labels that come from user settings rather than from the readiness checks. */
internal data class NotificationSettingsLabels(
    val groupNotification: String,
    val displayContent: String,
    /** Current MESSAGE category sound; null until read (or below API 26). */
    val messageSound: String? = null,
)

internal class NotificationSettingsActions(
    val onFix: (NotificationFix) -> Unit,
    val onCriticalAlerts: () -> Unit,
    val onGroupNotification: () -> Unit,
    val onMessageSound: () -> Unit,
    val onDisplayContent: () -> Unit,
    val onBackgroundConnection: () -> Unit,
    val onGuide: () -> Unit,
)

/**
 * The notification settings page as one grouped list. Status rows show a status only when it can
 * be determined and open exactly one settings page. Captions appear only where the system keeps
 * switches that cannot be read.
 */
@Composable
internal fun NotificationSettingsContent(
    state: NotificationSettingsUiState?,
    labels: NotificationSettingsLabels,
    actions: NotificationSettingsActions,
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
        if (state?.showBanner == true) {
            NotificationOffBanner(onEnable = { actions.onFix(NotificationFix.APP_NOTIFICATIONS) })
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }

        DifftSettingsGroup {
            ValueRow(
                label = stringResource(ChatR.string.notification_message_notifications),
                value = state?.let { NotificationReadinessText.categoryLevelLabel(context, it.messageLevel) },
                onClick = { actions.onFix(NotificationFix.MESSAGE_CATEGORY) },
                modifier = Modifier.testTag(TAG_ROW_MESSAGE),
            )
            ValueRow(
                label = stringResource(ChatR.string.notification_group_chat_notification),
                value = labels.groupNotification,
                onClick = actions.onGroupNotification,
            )
            ValueRow(
                label = stringResource(ChatR.string.notification_message_sounds),
                value = labels.messageSound,
                onClick = actions.onMessageSound,
            )
            ValueRow(
                label = stringResource(ChatR.string.notification_display),
                value = labels.displayContent,
                onClick = actions.onDisplayContent,
            )
        }

        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        DifftSettingsGroup {
            ValueRow(
                label = stringResource(ChatR.string.notification_call_notifications),
                value = state?.let { NotificationReadinessText.categoryLevelLabel(context, it.callLevel) },
                onClick = { actions.onFix(NotificationFix.CALL_CATEGORY) },
                modifier = Modifier.testTag(TAG_ROW_CALL),
            )
        }

        if (state?.showLockScreenGroup == true) {
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
            DifftSettingsGroup(
                modifier = Modifier.testTag(TAG_GROUP_LOCK_SCREEN),
                footer = {
                    DifftSettingsCaption(stringResource(ChatR.string.notification_group_lock_screen_caption))
                    if (state.oemPopupItem != null && !state.oemPopupOpensGuide) {
                        DifftSettingsCaption(stringResource(CallR.string.notification_hint_oem_xiaomi))
                    }
                },
            ) {
                state.fullScreenItem?.let {
                    StatusRow(ChatR.string.notification_full_screen_display, it, TAG_ROW_FULL_SCREEN) {
                        actions.onFix(NotificationFix.FULL_SCREEN_INTENT)
                    }
                }
                state.oemPopupItem?.let {
                    val label = if (state.oemPopupOpensGuide) ChatR.string.notification_vendor_settings else ChatR.string.notification_oem_popup
                    StatusRow(label, it, TAG_ROW_OEM_POPUP) {
                        if (state.oemPopupOpensGuide) actions.onGuide() else actions.onFix(NotificationFix.OEM_POPUP)
                    }
                }
            }
        }

        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        DifftSettingsGroup(
            // The access explanation shows only while Do Not Disturb access is missing; the delivery
            // note shows when push is unavailable and the background connection is off.
            footer = if (state?.showDndAccessCaption == true || state?.showCriticalDeliveryCaption == true) {
                {
                    if (state.showDndAccessCaption) {
                        DifftSettingsCaption(stringResource(ChatR.string.critical_alerts_content, PackageUtil.getAppName().orEmpty()))
                    }
                    if (state.showCriticalDeliveryCaption) {
                        DifftSettingsCaption(stringResource(ChatR.string.critical_alert_delivery_caption))
                    }
                }
            } else null,
        ) {
            ValueRow(
                label = stringResource(ChatR.string.critical_alerts),
                value = state?.let { criticalLabel(context, it.criticalStatus) },
                onClick = actions.onCriticalAlerts,
                modifier = Modifier.testTag(TAG_ROW_CRITICAL),
            )
        }

        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        DifftSettingsGroup {
            ValueRow(
                label = stringResource(ChatR.string.background_connection),
                onClick = actions.onBackgroundConnection,
            )
        }

        SetupGuideFooter(onClick = actions.onGuide)
    }
}

private fun criticalLabel(context: android.content.Context, status: CriticalAlertStatus): String = context.getString(
    when (status) {
        CriticalAlertStatus.ON -> ChatR.string.notification_enable
        CriticalAlertStatus.OFF -> ChatR.string.notification_disable
        CriticalAlertStatus.NEEDS_ATTENTION -> ChatR.string.notification_status_problem
        CriticalAlertStatus.CHECK -> ChatR.string.notification_status_check
    }
)

@Composable
private fun StatusRow(label: Int, item: ReadinessItem?, tag: String, onClick: () -> Unit) {
    val context = LocalContext.current
    ValueRow(
        label = stringResource(label),
        value = item?.let { NotificationReadinessText.itemStatusLabel(context, it.status) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
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
 * Settings row whose title always shows in full: the value is single-line, right-aligned and
 * ellipsised into whatever width the title leaves. (The shared [DifftSettingsRow] label/value form
 * truncates the label first, which hides titles beside long values.)
 */
@Composable
private fun ValueRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    DifftSettingsRow(modifier = modifier, onClick = onClick) {
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
                color = DifftTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}
