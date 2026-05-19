package com.difft.android.call

import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.BottomCallEndAction
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.DialogActionType
import com.difft.android.call.handler.InviteRequestState
import com.difft.android.call.ui.invite.InviteViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * UI event extensions for [LCallActivity].
 *
 * Centralizes click/gesture handling from the Compose layer so the Activity
 * body stays small. Each extension is a thin wrapper over the Activity's
 * internal members and does not introduce new business rules.
 */

private const val SCREEN_CLICK_THROTTLE_MS = 50L

internal fun LCallActivity.handleScreenClick() {
    val now = android.os.SystemClock.uptimeMillis()
    if (now - lastScreenClickMs < SCREEN_CLICK_THROTTLE_MS) {
        return
    }
    lastScreenClickMs = now

    viewModel.callUiController.toggleOverlays()
}

internal fun LCallActivity.handleInviteUsersClick() {
    L.d { "[Call] LCallActivity handleInviteUsersClick" }
    inviteCallManager?.inviteUsers(context = this)
}

internal fun LCallActivity.handleWindowZoomOutClick() {
    L.d { "[Call] LCallActivity handleWindowZoomOutClick" }
    if (!Settings.canDrawOverlays(this)) {
        showPipPermissionToastOrEnterPipMode("windowZoomOut")
        return
    }
    if (pictureInPictureManager?.isSystemPipEnabledAndAvailable() == true) {
        enterPipModeIfPossible(tag = "windowZoomOut")
    } else {
        AlertDialog.Builder(this)
            .setMessage(R.string.call_pip_not_supported_message)
            .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
            .show()
    }
}

internal fun LCallActivity.handleBottomCallEndAction(action: BottomCallEndAction) {
    when (action) {
        BottomCallEndAction.END_CALL -> {
            L.i { "[call] LCallActivity onClick End" }
            if (viewModel.hasOtherActiveSpeaker()) {
                viewModel.callUiController.setShowBottomCallEndViewEnable(false)
                callDialogManager?.showEndCallForAllDialog { actionType ->
                    when (actionType) {
                        DialogActionType.ON_CONFIRM -> handleExitClick(createCallExitParams(), CallEndType.END)
                        DialogActionType.ON_CANCEL -> callDialogManager?.dismissEndCallForAllDialog()
                    }
                }
            } else {
                handleExitClick(createCallExitParams(), CallEndType.END)
                viewModel.callUiController.setShowBottomCallEndViewEnable(false)
            }
        }

        BottomCallEndAction.LEAVE_CALL -> {
            L.i { "[call] LCallActivity onClick Leave" }
            handleExitClick(createCallExitParams(), CallEndType.LEAVE)
            viewModel.callUiController.setShowBottomCallEndViewEnable(false)
        }

        else -> {
            viewModel.callUiController.setShowBottomCallEndViewEnable(false)
            viewModel.callUiController.setShowBottomToolBarViewEnabled(true)
        }
    }
}

internal fun LCallActivity.handleInviteViewAction(action: InviteViewState) {
    when (action) {
        InviteViewState.INVITE -> {
            viewModel.callUiController.setShowInviteViewEnable(false)
            lifecycleScope.launch(Dispatchers.IO) {
                inviteCallManager?.inviteMembers(
                    callback = { state, invitees ->
                        L.i { "[Call] invite call state: $state invitees:$invitees" }
                        if (state == InviteRequestState.SUCCESS) {
                            if (onGoingCallStateManager.callType() != CallType.GROUP.type) {
                                viewModel.callUiController.setCriticalAlertEnable(true)
                            }
                            viewModel.addAwaitingJoinInvitees(invitees)
                        }
                        val isOneOneCall = onGoingCallStateManager.callType() == CallType.ONE_ON_ONE.type
                        if (state == InviteRequestState.SUCCESS && isOneOneCall) {
                            viewModel.switchToInstantCall()
                            viewModel.stopRingToneAndTimeoutCheck()
                            viewModel.handleConnectedState()
                        }
                        inviteCallManager?.resetState()
                    }
                )
            }
        }

        InviteViewState.DISMISS -> {
            viewModel.callUiController.setShowInviteViewEnable(false)
            inviteCallManager?.resetState()
        }
    }
}

internal fun LCallActivity.enterPipModeIfPossible(tag: String? = null): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        pictureInPictureManager?.enterPipMode(tag) ?: false
    } else {
        false
    }
}

internal fun LCallActivity.showPipPermissionToastOrEnterPipMode(tag: String?) {
    callDialogManager?.showPipPermissionDialog(tag) { enterPipModeIfPossible(it) }
}
