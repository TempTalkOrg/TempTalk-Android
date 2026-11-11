package com.difft.android.call.session

import androidx.annotation.StringRes
import com.difft.android.base.call.CallActionType
import com.difft.android.call.R









data class CallFinishRequest(
    val reason: Reason,
    @StringRes val tipRes: Int?,
    val handledWithoutUi: Boolean = false,
) {
    enum class Reason { LOCAL_EXIT, REMOTE_HANGUP, REMOTE_CALLEND, REMOTE_REJECT, TIMEOUT }

    companion object {
        val LOCAL = CallFinishRequest(Reason.LOCAL_EXIT, tipRes = null)

        
        val TIMEOUT = CallFinishRequest(Reason.TIMEOUT, R.string.call_callee_action_noanswer)

        fun remote(type: CallActionType): CallFinishRequest = when (type) {
            CallActionType.REJECT -> CallFinishRequest(Reason.REMOTE_REJECT, R.string.call_callee_action_reject)
            CallActionType.CALLEND -> CallFinishRequest(Reason.REMOTE_CALLEND, R.string.call_callee_action_hangup)
            else -> CallFinishRequest(Reason.REMOTE_HANGUP, R.string.call_callee_action_hangup)
        }
    }
}





internal data class CallExitContext(val conversationId: String?, val callType: String)
