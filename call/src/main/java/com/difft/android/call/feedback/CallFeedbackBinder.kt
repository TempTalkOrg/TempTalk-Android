package com.difft.android.call.feedback

import com.difft.android.call.data.FeedbackCallInfo
import com.difft.android.call.manager.CallFeedbackManager
import com.difft.android.call.manager.TimerManager
import com.difft.android.call.util.IdUtil

/**
 * Holds feedback-related session state (sid/identity/network poor flag) and
 * decides whether the feedback view should be triggered on call teardown.
 *
 * Centralizing this removes three mutable fields + a helper pair from
 * `LCallViewModel`.
 */
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
