package com.difft.android.call

import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.call.data.BottomCallEndAction
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.DialogActionType
import com.difft.android.call.handler.InviteRequestState
import com.difft.android.call.ui.invite.InviteViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch



private const val SCREEN_CLICK_THROTTLE_MS = 50L



private const val SCREENSHOT_LEAVE_HINT_WINDOW_MS = 1_000L

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
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = "",
            message = getString(R.string.call_pip_not_supported_message),
            showCancel = false,
        )
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
                
                
                
                
                
                val wasOneOnOneCall = onGoingCallStateManager.callType() == CallType.ONE_ON_ONE.type
                val (state, invitees) = inviteCallManager?.inviteMembers()
                    ?: (InviteRequestState.FAILED to emptyList())
                L.i { "[Call] invite call state: $state invitees:$invitees" }
                if (state == InviteRequestState.SUCCESS) {
                    if (onGoingCallStateManager.callType() != CallType.GROUP.type) {
                        viewModel.callUiController.setCriticalAlertEnable(true)
                    }
                    viewModel.addAwaitingJoinInvitees(invitees)
                }
                if (state == InviteRequestState.SUCCESS && wasOneOnOneCall) {
                    
                    
                    
                    
                    
                    
                    viewModel.applyInviteUpgrade()
                    
                    
                    viewModel.stopRingToneAndTimeoutCheck()
                    viewModel.handleConnectedState()
                }
                inviteCallManager?.resetState()
            }
        }

        InviteViewState.DISMISS -> {
            viewModel.callUiController.setShowInviteViewEnable(false)
            inviteCallManager?.resetState()
        }
    }
}

internal fun LCallActivity.enterPipModeIfPossible(tag: String? = null): Boolean {
    return pictureInPictureManager?.enterPipMode(tag) ?: false
}

internal fun LCallActivity.showPipPermissionToastOrEnterPipMode(tag: String?) {
    callDialogManager?.showPipPermissionDialog(tag) { enterPipModeIfPossible(it) }
}




internal fun LCallActivity.enterPipOnUserLeaveHint() {
    if (viewModel.isRequestingPermission()) {
        L.i { "[Call] LCallActivity onUserLeaveHint ignored (permission dialog showing)" }
        return
    }

    val sinceScreenshot = screenshotController?.millisSinceLastScreenshot() ?: Long.MAX_VALUE
    if (sinceScreenshot < SCREENSHOT_LEAVE_HINT_WINDOW_MS) {
        L.i { "[Call] LCallActivity onUserLeaveHint ignored (screenshot ${sinceScreenshot}ms ago)" }
        return
    }

    
    
    
    val sinceLabel = if (sinceScreenshot == Long.MAX_VALUE) "none" else "${sinceScreenshot}ms"
    showPipPermissionToastOrEnterPipMode("onUserLeaveHint screenshot=$sinceLabel")
}
