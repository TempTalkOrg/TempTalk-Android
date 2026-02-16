package com.difft.android.call.manager

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.R
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallActivity
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.LCallViewModel
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.DialogActionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 管理通话相关的对话框
 * 负责显示和关闭各种对话框，简化状态管理
 */
class CallDialogManager(
    private val activity: android.app.Activity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val viewModel: LCallViewModel,
    private val callIntent: CallIntent,
    private val callRole: CallRole,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val userManager: UserManager,
    private val onExitCall: (CallExitParams) -> Unit,
    private val onEndCall: () -> Unit
) {
    // 通话结束提醒对话框
    private var callEndReminderDialog: ComposeDialog? = null
    private var callEndReminderMessageView: TextView? = null
    private var isShowCallingEndReminder: Boolean = false
    private var countdownJob: Job? = null

    // PIP 权限对话框
    private var pipPermissionDialog: ComposeDialog? = null

    // End Call for all 提醒对话框
    private var endCallForAllDialog: ComposeDialog? = null

    /**
     * 显示通话结束提醒对话框
     * @param secondsToLeaveMeeting 剩余秒数，默认 180 秒
     */
    @SuppressLint("AutoDispose")
    fun showCallingEndReminder(secondsToLeaveMeeting: Long = 180L) {
        // 如果对话框已存在，只更新倒计时
        if (callEndReminderDialog != null) {
            val remainingSeconds = secondsToLeaveMeeting - 1
            callEndReminderMessageView?.text = "${remainingSeconds}s left"
            if (remainingSeconds <= 0) {
                onEndCall()
            }
            return
        }

        // 创建新对话框
        isShowCallingEndReminder = true
        val title = if (viewModel.room.remoteParticipants.isEmpty()) {
            activity.getString(R.string.call_single_person_timeout_reminder)
        } else {
            activity.getString(R.string.call_all_mute_timeout_reminder)
        }

        callEndReminderDialog = ComposeDialogManager.showMessageDialog(
            context = activity,
            title = title,
            layoutId = R.layout.dialog_calling_end_reminder,
            cancelable = false,
            confirmText = activity.getString(R.string.call_reminder_button_continue),
            cancelText = activity.getString(R.string.call_reminder_button_exit),
            confirmButtonColor = androidx.compose.ui.graphics.Color(
                ContextCompat.getColor(activity, com.difft.android.base.R.color.t_info)
            ),
            cancelButtonColor = androidx.compose.ui.graphics.Color(
                ContextCompat.getColor(activity, com.difft.android.base.R.color.t_error_night)
            ),
            onConfirm = {
                viewModel.resetNoBodySpeakCheck()
                if (viewModel.callType.value == CallType.ONE_ON_ONE.type) {
                    viewModel.room.remoteParticipants.values.firstOrNull()?.let { participant ->
                        viewModel.rtm.sendContinueCallRtmMessage(participant)
                    }
                }
                dismissCallingEndReminder()
            },
            onCancel = {
                onGoingCallStateManager.getCurrentRoomId()?.let { roomId ->
                    val callExitParams = CallExitParams(
                        roomId,
                        callIntent.callerId,
                        callRole,
                        viewModel.callType.value,
                        onGoingCallStateManager.getConversationId()
                    )
                    onExitCall(callExitParams)
                }
            },
            onDismiss = {
                countdownJob?.cancel()
                countdownJob = null
            },
            onViewCreated = { view ->
                val messageView = view.findViewById<TextView>(R.id.tv_message)
                messageView.text = "${secondsToLeaveMeeting}s left"
                callEndReminderMessageView = messageView
            }
        )

        // 如果在 PIP 模式，返回通话界面
        if (viewModel.callUiController.isInPipMode.value) {
            val intent = CallIntent.Builder(activity, LCallActivity::class.java)
                .withAction(CallIntent.Action.BACK_TO_CALL)
                .withIntentFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .build()
            activity.startActivity(intent)
        }

        // 启动倒计时
        startCountdown(secondsToLeaveMeeting)
    }

    /**
     * 关闭通话结束提醒对话框
     */
    fun dismissCallingEndReminder() {
        callEndReminderDialog?.dismiss()
        isShowCallingEndReminder = false
        callEndReminderDialog = null
        callEndReminderMessageView = null
        countdownJob?.cancel()
        countdownJob = null
    }

    /**
     * 显示 PIP 权限对话框
     * @param tag 调用标签，用于日志记录
     * @param enterPipModeIfPossible 如果可以进入 PIP 模式的回调
     */
    fun showPipPermissionDialog(
        tag: String?,
        enterPipModeIfPossible: (String?) -> Unit
    ) {
        val currentVersion = userManager.getUserData()?.floatingWindowPermissionTipsShowedVersion
        if (currentVersion == PackageUtil.getAppVersionName() || Settings.canDrawOverlays(activity)) {
            enterPipModeIfPossible(tag)
            return
        }

        if (pipPermissionDialog == null) {
            pipPermissionDialog = ComposeDialogManager.showMessageDialog(
                context = activity,
                cancelable = false,
                title = activity.getString(R.string.call_pip_no_permission_tip_title),
                message = activity.getString(R.string.call_pip_permission_tip_content),
                confirmText = activity.getString(R.string.call_permission_button_setting_go),
                cancelText = activity.getString(R.string.call_permission_button_not_now),
                onConfirm = {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${activity.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        ToastUtil.show(activity.getString(R.string.call_pip_permission_setting_failed))
                    }
                },
                onCancel = {
                    // 用户选择"暂不开启" → 记录当前版本，避免重复弹窗
                    lifecycleScope.launch(Dispatchers.IO) {
                        userManager.update {
                            floatingWindowPermissionTipsShowedVersion = PackageUtil.getAppVersionName()
                        }
                    }
                    enterPipModeIfPossible(tag)
                },
                onDismiss = {
                    pipPermissionDialog = null
                }
            )
        } else {
            pipPermissionDialog?.dismiss()
            pipPermissionDialog = null
        }
    }

    /**
     * 关闭所有对话框
     */
    fun dismissAll() {
        dismissCallingEndReminder()
        pipPermissionDialog?.dismiss()
        pipPermissionDialog = null
        endCallForAllDialog?.dismiss()
        endCallForAllDialog = null
    }

    /**
     * 启动倒计时
     */
    private fun startCountdown(secondsToLeaveMeeting: Long) {
        countdownJob?.cancel()

        countdownJob = lifecycleScope.launch {
            try {
                for (remainingSeconds in secondsToLeaveMeeting downTo 0) {
                    // 检查 Activity 是否还存在
                    if (!isActive || activity.isFinishing || activity.isDestroyed) {
                        return@launch
                    }
                    // 更新倒计时显示
                    callEndReminderMessageView?.text = "${remainingSeconds}s left"
                    if (remainingSeconds <= 0) {
                        onEndCall()
                        return@launch
                    }
                    delay(1000)
                }
            } catch (e: CancellationException) {
                L.d { "[Call] CallDialogManager countdown cancelled" }
            } catch (e: Exception) {
                L.e { "[Call] CallDialogManager showCallingEndReminder countdown error: ${e.message}" }
            } finally {
                countdownJob = null
            }
        }
    }

    /**
     * 检查是否正在显示通话结束提醒
     */
    fun isShowingCallingEndReminder(): Boolean = isShowCallingEndReminder

    /**
     * 显示“结束所有人通话”的确认对话框，并处理用户操作。
     * 如果对话框已存在，则先关闭现有对话框再重新创建（避免重复显示）。
     *
     * @param onAction 回调函数，当用户点击确认或取消按钮时触发，返回操作类型 [DialogActionType]。
     * - [DialogActionType.ON_CONFIRM] 用户点击确认按钮
     * - [DialogActionType.ON_CANCEL] 用户点击取消按钮
     */
    fun showEndCallForAllDialog(onAction: (DialogActionType) -> Unit) {
        if (endCallForAllDialog == null) {
            endCallForAllDialog = ComposeDialogManager.showMessageDialog(
                context = activity,
                cancelable = false,
                title = activity.getString(R.string.call_end_forall_toast_title),
                message = activity.getString(R.string.call_end_forall_toast_message),
                confirmText = activity.getString(R.string.call_button_group_alert_end_text),
                confirmButtonColor =  Color(ContextCompat.getColor(activity, com.difft.android.base.R.color.t_error)),
                cancelText = activity.getString(R.string.call_button_group_alert_cancel_text),
                onConfirm = {
                    L.d { "[Call] CallDialogManager showEndCallForAllDialog onConfirm" }
                    onAction(DialogActionType.ON_CONFIRM)
                },
                onCancel = {
                    endCallForAllDialog?.dismiss()
                    L.d { "[Call] CallDialogManager showEndCallForAllDialog onCancel" }
                    onAction(DialogActionType.ON_CANCEL)
                }
            )
        } else {
            endCallForAllDialog?.dismiss()
            endCallForAllDialog = null
        }
    }

    /**
     * 主动关闭并清理“结束所有人通话”对话框资源。
     */
    fun dismissEndCallForAllDialog() {
        endCallForAllDialog?.dismiss()
        endCallForAllDialog = null
    }

}

