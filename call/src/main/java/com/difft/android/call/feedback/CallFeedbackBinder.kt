package com.difft.android.call.feedback

import com.difft.android.call.data.FeedbackCallInfo
import com.difft.android.call.manager.CallFeedbackManager
import com.difft.android.call.manager.TimerManager
import com.difft.android.call.util.IdUtil








class CallFeedbackBinder(
    private val timerManager: TimerManager,
    private val callFeedbackManager: CallFeedbackManager,
    private val roomIdGetter: () -> String?,
) {
    var userSid: String = ""
    var userIdentity: String? = null
    var roomSid: String = ""
    var currentCallNetworkPoor: Boolean = false

    fun onIdentityResolved(userSid: String, userIdentity: String?, roomSid: String?) {
        this.userSid = userSid
        this.userIdentity = userIdentity
        this.roomSid = roomSid.orEmpty()
    }

    fun maybeTrigger() {
        if (timerManager.getCurrentDuration() < 60) return
        if (!callFeedbackManager.shouldTriggerFeedback(currentCallNetworkPoor)) return
        callFeedbackManager.setCallFeedbackInfo(
            FeedbackCallInfo(
                userIdentity = userIdentity ?: IdUtil.getMyIdentity(),
                userSid = userSid,
                roomId = roomIdGetter().orEmpty(),
                roomSid = roomSid,
            )
        )
    }

    fun reset() {
        userSid = ""
        userIdentity = null
        roomSid = ""
        currentCallNetworkPoor = false
    }
}
