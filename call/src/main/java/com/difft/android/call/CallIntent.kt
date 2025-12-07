package com.difft.android.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.util.ArrayList

class CallIntent(private val intent: Intent) {

    companion object {

        private const val CALL_INTENT_PREFIX = "CallIntent"

        @JvmStatic
        fun getActivityClass(): Class<out Activity> = LCallActivity::class.java

        private fun getActionString(action: Action): String {
            return "$CALL_INTENT_PREFIX.${action.code}"
        }

        private fun getExtraString(extra: Extra): String {
            return "$CALL_INTENT_PREFIX.${extra.code}"
        }
    }

    val action: Action by lazy { Action.fromIntent(intent) }

    enum class Action(val code: String) {
        VIEW(Intent.ACTION_VIEW),
        INCOMING_CALL("INCOMING_CALL_ACTION"),
        ACCEPT_CALL("ACCEPT_CALL_ACTION"),
        JOIN_CALL("JOIN_CALL_ACTION"),
        START_CALL("START_CALL_ACTION"),
        BACK_TO_CALL("BACK_TO_CALL_ACTION");

        companion object {
            fun fromIntent(intent: Intent): Action {
                return entries.firstOrNull { intent.action == it.code || intent.action == getActionString(it) } ?: VIEW
            }
        }
    }

    private enum class Extra(val code: String) {
        CALL_ROOM_ID("CALL_ROOM_ID"),
        CALL_ROOM_NAME("CALL_ROOM_NAME"),
        CALL_TYPE("CALL_TYPE"),
        CALL_SERVER_URLS("CALL_SERVER_URLS"),
        CALL_CALLER_ID("CALL_CALLER_ID"),
        CALL_CONVERSATION_ID("CALL_CONVERSATION_ID"),
        CALL_ROLE("CALL_ROLE"),
        CALL_START_PARAMS("CALL_START_PARAMS"),
        CALL_APP_TOKEN("CALL_APP_TOKEN"),
        CALL_NEED_APP_LOCK("CALL_NEED_APP_LOCK"),
    }

    /**
     * Builds an intent to launch the call screen.
     */
    class Builder(val context: Context, targetActivity: Class<*>) {
        private val intent = Intent(context, targetActivity)

        init {
            withAction(Action.VIEW)
        }

        fun withAddedIntentFlags(flags: Int): Builder {
            intent.addFlags(flags)
            return this
        }

        fun withIntentFlags(flags: Int): Builder {
            intent.flags = flags
            return this
        }

        fun withAction(action: Action?): Builder {
            intent.action = action?.let { getActionString(action) }
            return this
        }
        fun withRoomId(roomID: String?): Builder {
            intent.putExtra(getExtraString(Extra.CALL_ROOM_ID), roomID)
            return this
        }

        fun withRoomName(roomName: String?): Builder {
            intent.putExtra(getExtraString(Extra.CALL_ROOM_NAME), roomName)
            return this
        }

        fun withCallType(type: String?): Builder {
            intent.putExtra(getExtraString(Extra.CALL_TYPE), type)
            return this
        }

        fun withCallRole(callRole: String): Builder {
            intent.putExtra(getExtraString(Extra.CALL_ROLE), callRole)
            return this
        }

        fun withCallerId(callId: String?): Builder {
            intent.putExtra(getExtraString(Extra.CALL_CALLER_ID), callId)
            return this
        }

        fun withConversationId(conversationId: String?): Builder {
            intent.putExtra(getExtraString(Extra.CALL_CONVERSATION_ID), conversationId)
            return this
        }

        fun withCallServerUrls(serviceUrls: List<String>): Builder {
            intent.putStringArrayListExtra(getExtraString(Extra.CALL_SERVER_URLS), ArrayList(serviceUrls))
            return this
        }

        fun withStartCallParams(params: ByteArray): Builder {
            intent.putExtra(getExtraString(Extra.CALL_START_PARAMS), params)
            return this
        }

        fun withAppToken(token: String): Builder {
            intent.putExtra(getExtraString(Extra.CALL_APP_TOKEN), token)
            return this
        }

        fun withNeedAppLock(isNeedAppLock: Boolean): Builder {
            intent.putExtra(getExtraString(Extra.CALL_NEED_APP_LOCK), isNeedAppLock)
            return this
        }

        fun build(): Intent {
            return intent
        }
    }

    val roomId: String by lazy { intent.getStringExtra(getExtraString(Extra.CALL_ROOM_ID)).orEmpty() }

    val roomName: String by lazy { intent.getStringExtra(getExtraString(Extra.CALL_ROOM_NAME)).orEmpty() }

    val callType: String by lazy { intent.getStringExtra(getExtraString(Extra.CALL_TYPE)).orEmpty() }

    val callRole: String by lazy { intent.getStringExtra(getExtraString(Extra.CALL_ROLE)).orEmpty() }

    val callerId: String by lazy { intent.getStringExtra(getExtraString(Extra.CALL_CALLER_ID)).orEmpty() }

    val conversationId: String? by lazy { intent.getStringExtra(getExtraString(Extra.CALL_CONVERSATION_ID)) }

    val callServerUrls: List<String> by lazy { intent.getStringArrayListExtra(getExtraString(Extra.CALL_SERVER_URLS)).orEmpty() }

    val startCallParams: ByteArray? by lazy { intent.getByteArrayExtra(getExtraString(Extra.CALL_START_PARAMS))}

    val appToken: String by lazy { intent.getStringExtra(getExtraString(Extra.CALL_APP_TOKEN)).orEmpty()}

    val needAppLock: Boolean by lazy { intent.getBooleanExtra(getExtraString(Extra.CALL_NEED_APP_LOCK), false) }

    override fun toString(): String {
        return """
      CallIntent
      Action - $action
      CALL_ROOM_ID? $roomId
      CALL_ROOM_NAME? $roomName
      CALL_TYPE? $callType
      CALL_ROLE? $callRole
      CALL_CALLER_ID? $callerId
      CALL_CONVERSATION_ID? $conversationId
      CALL_ServerUrls? $callServerUrls
    """.trimIndent()
    }

}