package com.difft.android.network.requests







data class ConversationSetRequestBody(
    val conversation: String,
    val remark: String? = null,
    val remarkAvatar: String? = null,
    val muteStatus: Int? = null,
    val blockStatus: Int? = null,
    val confidentialMode: Int? = null,
    val saveToPhotos: Int? = null,
    
    val allowMentionMe: Boolean? = null,
    val allowMentionAll: Boolean? = null,
    val allowGroupCall: Boolean? = null
) {
    val isMuted: Boolean
        get() = muteStatus == MuteStatus.MUTED.value
}

enum class MuteStatus(val value: Int) {
    MUTED(1),
    UNMUTED(0);

    companion object {
        fun of(muted: Boolean): MuteStatus = if (muted) MUTED else UNMUTED
    }
}
