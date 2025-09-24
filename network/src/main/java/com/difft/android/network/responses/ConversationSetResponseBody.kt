package com.difft.android.network.responses

/**
 *
"version": 1,
"remark": "v1|Abcd+/xxxxxxxx",
"muteStatus": 1,
"blockStatus": 0,
"confidentialMode": 0
 */
data class ConversationSetResponseBody(
    val version: Int,
    val remark: String?,
    val muteStatus: Int,
    val blockStatus: Int,
    val confidentialMode: Int,
    val conversation: String,
    val sourceDescribe: String?, //相遇文案（有时间）
    val findyouDescribe: String?, //相遇文案（无时间）
    val messageExpiry: Long, //消息过期时间
    val messageClearAnchor: Long //基于会话归档消息锚点
) {
    val isMuted: Boolean
        get() = muteStatus == MuteStatus.MUTED.value
}

enum class MuteStatus(val value: Int) {
    MUTED(1),
    UNMUTED(0);
}
