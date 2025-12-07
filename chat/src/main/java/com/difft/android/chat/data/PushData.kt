package com.difft.android.chat.data

import android.os.Parcelable
import com.difft.android.base.log.lumberjack.L
import com.google.gson.Gson
import kotlinx.parcelize.Parcelize


data class PushCustomContent(
    val gid: String?,
    val locKey: String?,
    val msg: String?,//加密消息内容
    val mutableContent: Int,
    val notifyType: Int,
    val passthrough: String?,
    val uid: String?,
    val critical: Int,
    val timestamp: Long = 0,
    ) {
    val passthroughData: Passthrough?
        get() = passthrough?.let {
            try {
                Gson().fromJson(passthrough, Passthrough::class.java)
            } catch (e: Exception) {
                L.i { "[fcm] parse passthroughData error:" + e.stackTraceToString() }
                null
            }
        }
}

@Parcelize
data class CallInfo(
    val caller: String?,
    val meetingName: String?,
    val meetingId: String?,
    val startAt: Long,
    val channelName: String?,
    val mode: String?,
    val groupId: String?
) : Parcelable

data class Passthrough(
    val conversationId: String,
    val callInfo: CallInfo?,
)

const val PERSONAL_CALL = "PERSONAL_CALL"
const val GROUP_CALL = "GROUP_CALL"

// Push notification types
const val NOTIFY_TYPE_CALL_HANGUP = 23 // call挂断消息
