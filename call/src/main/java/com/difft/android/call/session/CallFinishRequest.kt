package com.difft.android.call.session

import androidx.annotation.StringRes
import com.difft.android.base.call.CallActionType
import com.difft.android.call.R

/**
 * The session's terminal request to the call page: show [tipRes] (if any), then run the page's
 * end sequence. Set once per session and kept (sticky), so a page that starts collecting late, or
 * a recreated page, still finishes.
 *
 * [handledWithoutUi] is set when no page was attached at the time: the session already showed the
 * tip and ended itself, so a page that attaches afterwards only finishes, without a second tip.
 */
data class CallFinishRequest(
    val reason: Reason,
    @StringRes val tipRes: Int?,
    val handledWithoutUi: Boolean = false,
) {
    enum class Reason { LOCAL_EXIT, REMOTE_HANGUP, REMOTE_CALLEND, REMOTE_REJECT, TIMEOUT }

    companion object {
        val LOCAL = CallFinishRequest(Reason.LOCAL_EXIT, tipRes = null)

        /** The 1v1 no-answer / remote-left timeout: tip, then end; no tone and no signaling. */
        val TIMEOUT = CallFinishRequest(Reason.TIMEOUT, R.string.call_callee_action_noanswer)

        fun remote(type: CallActionType): CallFinishRequest = when (type) {
            CallActionType.REJECT -> CallFinishRequest(Reason.REMOTE_REJECT, R.string.call_callee_action_reject)
            CallActionType.CALLEND -> CallFinishRequest(Reason.REMOTE_CALLEND, R.string.call_callee_action_hangup)
            else -> CallFinishRequest(Reason.REMOTE_HANGUP, R.string.call_callee_action_hangup)
        }
    }
}

/**
 * What the exit decision reads: the conversation id and the call type from the call's start intent.
 * The call type deliberately does not follow later changes: an invite upgrade keeps the intent's value.
 */
internal data class CallExitContext(val conversationId: String?, val callType: String)
