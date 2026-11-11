package com.difft.android.chat.focus

import android.app.Activity
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.R


object FocusDialogs {

    
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

    
    fun mutePausedByFocus(activity: Activity, onGoSettings: () -> Unit) = paused(
        activity,
        title = R.string.focus_dialog_mute_paused_title,
        message = R.string.focus_dialog_takeover_message,
        confirm = R.string.focus_dialog_go_settings,
        onConfirm = onGoSettings,
    )

    
    fun notifyPausedByFocus(activity: Activity, onGoSettings: () -> Unit) = paused(
        activity,
        title = R.string.focus_dialog_notify_paused_title,
        message = R.string.focus_dialog_takeover_message,
        confirm = R.string.focus_dialog_go_settings,
        onConfirm = onGoSettings,
    )

    
    fun groupNotifyPausedByFocus(activity: Activity, onEndFocus: () -> Unit) = paused(
        activity,
        title = R.string.focus_dialog_group_notify_paused_title,
        message = R.string.focus_dialog_takeover_message,
        confirm = R.string.focus_dialog_end_focus,
        onConfirm = onEndFocus,
    )

    
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
