package com.difft.android.call.manager

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.AutoLeave
import com.difft.android.base.user.CallConfig
import com.difft.android.call.LCallToChatController
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallStatus
import com.difft.android.call.state.OnGoingCallStateManager
import kotlinx.coroutines.launch


class CallLifecycleObserver(
    private val viewModel: LCallViewModel,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callToChatController: LCallToChatController,
    private val callDialogManager: CallDialogManager?,
    private val callConfig: CallConfig
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    observeScreenSharingState()
                }
                launch {
                    observeCallStatus()
                }
                launch {
                    observeNoSpeakTimeout()
                }
            }
        }
    }

    private suspend fun observeScreenSharingState() {
        viewModel.callUiController.isShareScreening.collect { isSharing ->
            L.i { "[Call] CallLifecycleObserver, viewModel.isParticipantSharedScreen: $isSharing" }
            onGoingCallStateManager.setIsInScreenSharing(isSharing)
        }
    }

    private suspend fun observeCallStatus() {
        viewModel.callStatus.collect { status ->
            L.i { "[Call] CallLifecycleObserver callStatus:$status" }
            if (status == CallStatus.CONNECTED) {
                onGoingCallStateManager.getCurrentRoomId()?.let { roomId ->
                    callToChatController.cancelNotificationById(roomId.hashCode())
                }
            }
        }
    }

    private suspend fun observeNoSpeakTimeout() {
        viewModel.isNoSpeakSoloTimeout.collect { isTimeout ->
            if (isTimeout) {
                handleNoSpeakTimeout()
            } else {
                dismissNoSpeakTimeout()
            }
        }
    }

    private fun handleNoSpeakTimeout() {
        if (callDialogManager?.isShowingCallingEndReminder() == false) {
            L.i { "[Call] CallLifecycleObserver Show CallingEnd Reminder" }
            val waitingSeconds = callConfig.autoLeave?.runAfterReminderTimeout
                ?: AutoLeave().runAfterReminderTimeout
            val secondsToLeaveMeeting = waitingSeconds / 1000
            callDialogManager.showCallingEndReminder(secondsToLeaveMeeting)
        }
    }

    private fun dismissNoSpeakTimeout() {
        if (callDialogManager?.isShowingCallingEndReminder() == true) {
            L.i { "[Call] CallLifecycleObserver dismiss CallingEnd Reminder" }
            callDialogManager.dismissCallingEndReminder()
        }
    }
}

