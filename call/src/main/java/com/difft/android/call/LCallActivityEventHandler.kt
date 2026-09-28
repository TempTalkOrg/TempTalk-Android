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

/**
 * UI event extensions for [LCallActivity].
 *
 * Centralizes click/gesture handling from the Compose layer so the Activity
 * body stays small. Most extensions are thin wrappers over the Activity's
 * internal members; a few carry a policy of their own — which framework hints
 * to honour, how to order work against a server-side state flip — and each
 * spells that policy out where it lives.
 */

private const val SCREEN_CLICK_THROTTLE_MS = 50L

/**
 * How fresh a screen capture has to be for [enterPipOnUserLeaveHint] to treat an incoming
 * user-leave hint as a side effect of that capture rather than an intent to leave. Observed
 * gap between the two on the affected shell is ~30ms; the rest is headroom.
 *
 * Both ends are measured from when the capture was *detected*. On the API 34+ callback that is
 * effectively the shutter, but on the legacy MediaStore path detection trails it, which shifts
 * this window later by the same amount rather than widening it — trail far enough and the hint
 * arrives before the window even opens, suppressing nothing rather than merely cutting it fine.
 */
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
                // Snapshot the type BEFORE the request: the server flips room.metadata.callType to
                // instant as soon as it accepts the invite, and that RoomUpdate can land before this
                // response does. Reading afterwards would see instant and skip the ringtone/connected
                // handling below, leaving the outgoing ringtone playing and the 1v1 no-answer timeout
                // armed to end the call ~60s later.
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
                    // Land instant now instead of waiting for the server to report it on
                    // room.metadata. Until the type flips, CallExitHandler routes a hangup down the
                    // 1v1 END path, which would terminate the meeting for the person just invited
                    // rather than leaving it. The server flips callType when it accepts the invite,
                    // so this only closes the gap until that RoomUpdate lands, and instant is a
                    // one-way latch — the two cannot end up disagreeing.
                    viewModel.applyInviteUpgrade()
                    // Then the 1v1-specific waiting state, since we are no longer waiting on a
                    // single callee to answer.
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

/**
 * PiP entry for `Activity.onUserLeaveHint`.
 *
 * `onUserLeaveHint` is a hint, not a fact: the framework leaves it to system_server to decide what
 * counts as the user choosing to leave, so what reaches us varies by shell. Two cases are known to
 * arrive while the user is still in the call and must not minimize it:
 *
 *  - a permission dialog taking focus, and
 *  - a screen capture, which one shell reports as a user-leave ~30ms after the capture callback.
 *
 * Both would otherwise drop the call into PiP behind the user's back.
 *
 * The screenshot window cannot tell a shell's spurious hint apart from a real Home press made
 * within the same second, so that press is filtered too and the call is backgrounded without a
 * PiP window (it keeps running, reachable from the ongoing-call notification). That is the
 * deliberate trade: a missing PiP window costs far less than the dropped call this guards
 * against, and the window is already the smallest that covers the observed gap. Minimizing via
 * the back press or the zoom-out button does not come through here at all — those call
 * [showPipPermissionToastOrEnterPipMode] directly and are never filtered.
 */
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

    // Carry the reading into the tag so a field log shows WHY a hint was honoured: "none" means no
    // capture was ever observed (instant call, or the detector never ran), a figure means one was
    // but fell outside the window. Without it an unsuppressed hint looks the same in both cases.
    val sinceLabel = if (sinceScreenshot == Long.MAX_VALUE) "none" else "${sinceScreenshot}ms"
    showPipPermissionToastOrEnterPipMode("onUserLeaveHint screenshot=$sinceLabel")
}
