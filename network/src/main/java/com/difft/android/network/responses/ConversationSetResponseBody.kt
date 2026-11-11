package com.difft.android.network.responses









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
    





    val saveToPhotos: Int? = null,
    





    val translateSetting: Int = 0,
    
    val allowMentionMe: Boolean? = null,
    val allowMentionAll: Boolean? = null,
    val allowGroupCall: Boolean? = null
) {
    val isMuted: Boolean
        get() = muteStatus == MuteStatus.MUTED.value

    
    fun toLogString(): String =
        "conv=$conversation mute=$muteStatus block=$blockStatus confidential=$confidentialMode " +
            "expiry=$messageExpiry clearAnchor=$messageClearAnchor version=$version " +
            "hasRemark=${!remark.isNullOrEmpty()} saveToPhotos=$saveToPhotos translate=$translateSetting " +
            "allowMe=$allowMentionMe allowAll=$allowMentionAll allowCall=$allowGroupCall"
}

enum class MuteStatus(val value: Int) {
    MUTED(1),
    UNMUTED(0);
}
