package com.difft.android.setting.notificationtest

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.widget.MessageDialog
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Dialog
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Intent
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Notice
import com.difft.android.chat.R as ChatR

/** The page's dialogs, driven by state so they survive the page going to the background mid-test. */
@Composable
internal fun NotificationTestDialogs(dialog: Dialog?, onIntent: (Intent) -> Unit) {
    val dismiss = { onIntent(Intent.DismissDialog) }
    when (dialog) {
        null -> Unit
        is Dialog.Intro -> MessageDialog(
            isVisible = true,
            title = stringResource(kindLabel(dialog.kind)),
            message = stringResource(introText(dialog.kind)),
            confirmText = stringResource(ChatR.string.notification_test_start),
            onConfirm = { onIntent(Intent.ConfirmStart) },
            onCancel = dismiss,
            onDismiss = dismiss,
        )
        is Dialog.Blocked -> {
            val fix = dialog.fix
            MessageDialog(
                isVisible = true,
                title = stringResource(blockText(dialog.block)),
                confirmText = if (fix != null) stringResource(ChatR.string.notification_test_open_settings) else "",
                showCancel = fix != null,
                onConfirm = { if (fix != null) onIntent(Intent.OpenFix(fix)) else dismiss() },
                onCancel = dismiss,
                onDismiss = dismiss,
            )
        }
        is Dialog.Question -> MessageDialog(
            isVisible = true,
            title = stringResource(
                if (dialog.kind == NotificationTestKind.BANNER) {
                    ChatR.string.notification_test_banner_question
                } else {
                    ChatR.string.notification_test_lockscreen_question
                }
            ),
            confirmText = stringResource(ChatR.string.notification_test_yes),
            cancelText = stringResource(ChatR.string.notification_test_no),
            cancelable = false,
            onConfirm = { onIntent(Intent.Answer(seen = true)) },
            onCancel = { onIntent(Intent.Answer(seen = false)) },
        )
        is Dialog.Failed -> FailedDialog(dialog, onIntent)
        is Dialog.Info -> MessageDialog(
            isVisible = true,
            title = stringResource(noticeText(dialog.notice)),
            showCancel = false,
            onConfirm = dismiss,
            onDismiss = dismiss,
        )
    }
}

/** Offers the setup guide, plus the settings page that fixes the failure where one exists. */
@Composable
private fun FailedDialog(dialog: Dialog.Failed, onIntent: (Intent) -> Unit) {
    val fix = dialog.settingsFix
    val guide = stringResource(ChatR.string.background_connection_guide_link)
    val settings = when (fix) {
        null -> null
        NotificationFix.OEM_POPUP -> stringResource(ChatR.string.notification_test_open_settings)
        else -> stringResource(ChatR.string.notification_test_open_system_settings)
    }
    MessageDialog(
        isVisible = true,
        title = stringResource(
            if (dialog.kind == NotificationTestKind.CALL) ChatR.string.notification_test_call_failed else ChatR.string.notification_test_not_seen
        ),
        message = stringResource(ChatR.string.notification_test_failed_message),
        confirmText = settings ?: guide,
        cancelText = if (settings != null) guide else "",
        onConfirm = { onIntent(if (fix != null) Intent.OpenFix(fix) else Intent.OpenGuide) },
        onCancel = { onIntent(if (fix != null) Intent.OpenGuide else Intent.DismissDialog) },
        onDismiss = { onIntent(Intent.DismissDialog) },
    )
}

private fun introText(kind: NotificationTestKind): Int = when (kind) {
    NotificationTestKind.BANNER -> ChatR.string.notification_test_banner_intro
    NotificationTestKind.LOCKSCREEN -> ChatR.string.notification_test_lockscreen_intro
    NotificationTestKind.CALL -> ChatR.string.notification_test_call_intro
}

private fun blockText(block: PrecheckBlock): Int = when (block) {
    PrecheckBlock.MESSAGE_OFF -> ChatR.string.notification_test_message_off
    PrecheckBlock.LOCK_HIDDEN -> ChatR.string.notification_test_lock_hidden
    PrecheckBlock.CALL_OFF -> ChatR.string.notification_test_call_off
    PrecheckBlock.FULL_SCREEN_OFF -> ChatR.string.notification_test_full_screen_off
    PrecheckBlock.DND_ON -> ChatR.string.notification_test_dnd_on
}

private fun noticeText(notice: Notice): Int = when (notice) {
    Notice.NO_LOCK -> ChatR.string.notification_test_no_lock
    Notice.POST_FAILED -> ChatR.string.notification_test_post_failed
    Notice.VOID_UNLOCKED -> ChatR.string.notification_test_void_unlocked
    Notice.VOID_NOT_LOCKED -> ChatR.string.notification_test_void_not_locked
    Notice.VOID_DND -> ChatR.string.notification_test_void_dnd
}
