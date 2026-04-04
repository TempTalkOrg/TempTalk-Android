package com.difft.android.network.requests

/**
 * "conversation":"+77777777777", // required
"remark": "v1|Abcd+/xxxxxxxx", // 备注（密文），｜ 前的是密文
"muteStatus":1,//optional
"blockStatus":0, //optional
"confidentialMode":0 // optional，0:普通消息, 1: 机密消息
"saveToPhotos":null // optional, null:follow global setting, 0:disabled, 1:enabled
 */
data class ConversationSetRequestBody(
    val conversation: String,
    val remark: String? = null,
    val muteStatus: Int? = null,
    val blockStatus: Int? = null,
    val confidentialMode: Int? = null,
    val saveToPhotos: Int? = null
) {
    val isMuted: Boolean
        get() = muteStatus == MuteStatus.MUTED.value
}

enum class MuteStatus(val value: Int) {
    MUTED(1),
    UNMUTED(0);
}
