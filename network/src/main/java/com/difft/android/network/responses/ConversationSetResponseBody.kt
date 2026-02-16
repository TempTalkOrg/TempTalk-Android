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
    val conversation: String,
    val muteStatus: Int = 0,
    val confidentialMode: Int = 0,
    val messageExpiry: Long = 0L,
    val messageClearAnchor: Long = 0L,
    val version: Int = 0,
    val remark: String? = null,
    val blockStatus: Int = 0,
    val sourceDescribe: String? = null,
    val findyouDescribe: String? = null,
    /**
     * Conversation-level save to photos setting (local only, not synced to server)
     * null: Follow global setting (default)
     * 0: Disabled (never save)
     * 1: Enabled (always save)
     */
    val saveToPhotos: Int? = null
) {
    val isMuted: Boolean
        get() = muteStatus == MuteStatus.MUTED.value
}

enum class MuteStatus(val value: Int) {
    MUTED(1),
    UNMUTED(0);
}
