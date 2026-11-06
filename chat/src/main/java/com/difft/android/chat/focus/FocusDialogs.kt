package com.difft.android.chat.focus

import android.app.Activity
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.R

/** The confirm and "paused" explanation dialogs of the focus feature (PRD 8.1: D2, D6, D7, D8). */
object FocusDialogs {

    /** D2: focusing another group while one is already focused. */
    fun confirmSwitch(activity: Activity, newGroup: String, oldGroup: String, onConfirm: () -> Unit) {
        ComposeDialogManager.showMessageDialog(
            context = activity,
            title = activity.getString(R.string.focus_switch_title),
            message = activity.getString(R.string.focus_switch_message, newGroup, oldGroup),
            confirmText = activity.getString(R.string.focus_switch_confirm),
            cancelText = activity.getString(R.string.focus_cancel),
            onConfirm = onConfirm,
        )
    }

    /** D6: tap on the paused Mute row while focus is on. The primary button goes to the focus settings. */
    fun mutePausedByFocus(activity: Activity, onGoSettings: () -> Unit) = paused(
        activity,
        title = R.string.focus_dialog_mute_paused_title,
        message = R.string.focus_dialog_takeover_message,
        confirm = R.string.focus_dialog_go_settings,
        onConfirm = onGoSettings,
    )

    /** D7 in group settings: tap on the paused Notifications row while focus is on. */
    fun notifyPausedByFocus(activity: Activity, onGoSettings: () -> Unit) = paused(
        activity,
        title = R.string.focus_dialog_notify_paused_title,
        message = R.string.focus_dialog_takeover_message,
        confirm = R.string.focus_dialog_go_settings,
        onConfirm = onGoSettings,
    )

    /** D7 in Me > Notifications: tap on the paused group notification row; the primary button ends focus. */
    fun groupNotifyPausedByFocus(activity: Activity, onEndFocus: () -> Unit) = paused(
        activity,
        title = R.string.focus_dialog_group_notify_paused_title,
        message = R.string.focus_dialog_takeover_message,
        confirm = R.string.focus_dialog_end_focus,
        onConfirm = onEndFocus,
    )

    /** D8: tap on the paused Notifications row while the group is muted. The primary button turns Mute off. */
    fun notifyPausedByMute(activity: Activity, onTurnOffMute: () -> Unit) = paused(
        activity,
        title = R.string.focus_dialog_notify_paused_title,
        message = R.string.focus_dialog_muted_message,
        confirm = R.string.focus_dialog_turn_off_mute,
        onConfirm = onTurnOffMute,
    )

    private fun paused(activity: Activity, title: Int, message: Int, confirm: Int, onConfirm: () -> Unit) {
        ComposeDialogManager.showMessageDialog(
            context = activity,
            title = activity.getString(title),
            message = activity.getString(message),
            confirmText = activity.getString(confirm),
            cancelText = activity.getString(R.string.focus_cancel),
            onConfirm = onConfirm,
        )
    }
}
