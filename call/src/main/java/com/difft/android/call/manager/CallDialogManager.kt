package com.difft.android.call.manager

import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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
import io.livekit.android.room.participant.RemoteParticipant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private var callEndReminderDialog: ComposeDialog? = null
    private var callEndReminderMessageView: TextView? = null
    private var isShowCallingEndReminder: Boolean = false
    private var countdownJob: Job? = null

    private var pipPermissionDialog: ComposeDialog? = null

    private var endCallForAllDialog: ComposeDialog? = null

    @SuppressLint("AutoDispose")
    fun showCallingEndReminder(secondsToLeaveMeeting: Long = 180L) {
        
        if (callEndReminderDialog != null) {
            val remainingSeconds = secondsToLeaveMeeting - 1
            callEndReminderMessageView?.text = activity.getString(R.string.call_ending_seconds_left, remainingSeconds)
            if (remainingSeconds <= 0) {
                onEndCall()
            }
            return
        }

        isShowCallingEndReminder = true
        
        
        val hasRemote = viewModel.participants.value.any { it is RemoteParticipant }
        val title = if (!hasRemote) {
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
                    viewModel.participants.value.firstOrNull { it is RemoteParticipant }?.let { participant ->
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
                messageView.text = activity.getString(R.string.call_ending_seconds_left, secondsToLeaveMeeting)
                callEndReminderMessageView = messageView
            }
        )

        if (viewModel.callUiController.isInPipMode.value) {
            val intent = CallIntent.Builder(activity, LCallActivity::class.java)
                .withAction(CallIntent.Action.BACK_TO_CALL)
                .withIntentFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .build()
            activity.startActivity(intent)
        }

        startCountdown(secondsToLeaveMeeting)
    }

    fun dismissCallingEndReminder() {
        callEndReminderDialog?.dismiss()
        isShowCallingEndReminder = false
        callEndReminderDialog = null
        callEndReminderMessageView = null
        countdownJob?.cancel()
        countdownJob = null
    }

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
                            "package:${activity.packageName}".toUri()
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        ToastUtil.show(activity.getString(R.string.call_pip_permission_setting_failed))
                    }
                },
                onCancel = {
                    
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

    fun dismissAll() {
        dismissCallingEndReminder()
        pipPermissionDialog?.dismiss()
        pipPermissionDialog = null
        endCallForAllDialog?.dismiss()
        endCallForAllDialog = null
    }

    private fun startCountdown(secondsToLeaveMeeting: Long) {
        countdownJob?.cancel()

        countdownJob = lifecycleScope.launch {
            try {
                for (remainingSeconds in secondsToLeaveMeeting downTo 0) {
                    if (!isActive || activity.isFinishing || activity.isDestroyed) {
                        return@launch
                    }
                    callEndReminderMessageView?.text = activity.getString(R.string.call_ending_seconds_left, remainingSeconds)
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

    fun isShowingCallingEndReminder(): Boolean = isShowCallingEndReminder

    

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

    fun dismissEndCallForAllDialog() {
        endCallForAllDialog?.dismiss()
        endCallForAllDialog = null
    }

}

