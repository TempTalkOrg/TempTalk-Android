package com.difft.android.base.call

object LCallConstants {
    const val CALL_VERSION = 10
    const val CALL_NOTIFICATION_TYPE = 22

    const val BUNDLE_KEY_CALLER_ID = "BUNDLE_KEY_CALLER_ID"
    const val BUNDLE_KEY_ROOM_ID = "BUNDLE_KEY_ROOM_ID"
    const val BUNDLE_KEY_CONVERSATION_ID = "BUNDLE_KEY_CONVERSATION_ID"
    const val BUNDLE_KEY_CALL_TYPE = "BUNDLE_KEY_CALL_TYPE"
    const val BUNDLE_KEY_CALL_NAME = "BUNDLE_KEY_CALL_NAME"
    const val BUNDLE_KEY_CALL_ROLE = "BUNDLE_KEY_CALL_ROLE"
    const val BUNDLE_KEY_CRITICAL_CONVERSATION = "BUNDLE_KEY_CRITICAL_CONVERSATION"
    const val BUNDLE_KEY_CRITICAL_TITLE = "BUNDLE_KEY_CRITICAL_TITLE"
    const val BUNDLE_KEY_CRITICAL_MESSAGE = "BUNDLE_KEY_CRITICAL_MESSAGE"
    const val BUNDLE_KEY_CRITICAL_NOTIFICATION_ID = "BUNDLE_KEY_CRITICAL_NOTIFICATION_ID"
    const val BUNDLE_KEY_CRITICAL_IS_NOTIFICATION = "BUNDLE_KEY_CRITICAL_IS_NOTIFICATION"
    const val BUNDLE_KEY_CRITICAL_ROOM_ID = "BUNDLE_KEY_CRITICAL_ROOM_ID"

    const val CALL_ONGOING_TIMEOUT = "CALL_ONGOING_TIMEOUT"
    const val CALL_OPERATION_INVITED_DESTROY = "CALL_OPERATION_INVITED_DESTROY"
    const val KEY_CALLING_NOTIFICATION_ID = "KEY_CALLING_NOTIFICATION_ID"
    const val CALL_NOTIFICATION_OPERATION_ACCEPT_OTHER = "CALLEE_NOTIFICATION_OPERATION_ACCEPT_OTHER"
    const val CALL_NOTIFICATION_OPERATION_REJECT = "CALLEE_NOTIFICATION_OPERATION_REJECT"
    const val CALL_NOTIFICATION_PUSH_STREAM_LIMIT = "CALL_NOTIFICATION_PUSH_STREAM_LIMIT"

    const val CRITICAL_ALERT_ACTION_DISMISS = "CRITICAL_ALERT_ACTION_DISMISS"
    const val CRITICAL_ALERT_ACTION_DISMISS_BY_CONID = "CRITICAL_ALERT_ACTION_DISMISS_BY_CONID"
    const val CRITICAL_ALERT_ACTION_CLICKED = "CRITICAL_ALERT_ACTION_CLICKED"
    const val CRITICAL_ALERT_PARAM_CONVERSATION = "CRITICAL_ALERT_PARAM_CONVERSATION"

}

enum class CallType(val type: String) {
    ONE_ON_ONE("1on1"),
    GROUP("group"),
    INSTANT("instant");

    companion object {
        fun fromString(type: String): CallType? {
            return entries.find { it.type == type }
        }
    }

    fun isOneOnOne() = this == ONE_ON_ONE
    fun isGroup() = this == GROUP
    fun isInstant() = this == INSTANT
}

enum class CallRole(val type: String) {
    CALLER("caller"),
    CALLEE("callee");
    companion object {
        fun fromString(type: String): CallRole? {
            return CallRole.entries.find { it.type == type }
        }
    }
    fun isCaller() = this == CALLER
    fun isCallee() = this == CALLEE
}



enum class CallActionType(val type: String) {
    START("start-call"),
    JOINED("joined"),
    CANCEL("cancel"),
    REJECT("reject"),
    HANGUP("hangup"),
    INVITE("invite-members"),
    CALLEND("call-end"),
    DECLINE("decline");

    companion object {
        fun fromString(type: String): CallActionType? {
            return entries.find { it.type == type }
        }
    }

    fun isStart() = this == START
    fun isJoined() = this == JOINED
    fun isCancel() = this == CANCEL
    fun isReject() = this == REJECT
    fun isHangUp() = this == HANGUP
    fun isInvite() = this == INVITE
    fun isCallEnd() = this == CALLEND
    fun isDecline() = this == DECLINE
}


enum class CallDataSourceType {
    MESSAGE,
    SERVER,
    LOCAL,
    UNKNOWN
}