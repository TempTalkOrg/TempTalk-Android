package difft.android.messageserialization.unreadmessage

data class UnreadMessageInfo(
    val unreadCount: Int?,
    val mentionType: Int? = null,
    val mentionCount: Int? = null,
    val mentionIds: List<Long>? = null
)
