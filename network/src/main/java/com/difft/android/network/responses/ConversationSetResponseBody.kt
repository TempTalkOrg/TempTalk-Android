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
    val remarkAvatar: String? = null,
    val blockStatus: Int = 0,
    val sourceDescribe: String? = null,
    val findyouDescribe: String? = null,
    /**
     * Conversation-level save to photos setting (local only, not synced to server)
     * null: Follow global setting (default)
     * 0: Disabled (never save)
     * 1: Enabled (always save)
     */
    val saveToPhotos: Int? = null,
    /**
     * Conversation-level auto-translate target (local only, not synced to server)
     * 0: Off (default)
     * 1: Chinese
     * 2: English
     */
    val translateSetting: Int = 0
) {
    val isMuted: Boolean
        get() = muteStatus == MuteStatus.MUTED.value

    /** Log-safe summary: free-text fields (remark, describes) are reduced to presence flags. */
    fun toLogString(): String =
        "conv=$conversation mute=$muteStatus block=$blockStatus confidential=$confidentialMode " +
            "expiry=$messageExpiry clearAnchor=$messageClearAnchor version=$version " +
            "hasRemark=${!remark.isNullOrEmpty()} saveToPhotos=$saveToPhotos translate=$translateSetting"
}

enum class MuteStatus(val value: Int) {
    MUTED(1),
    UNMUTED(0);
}
