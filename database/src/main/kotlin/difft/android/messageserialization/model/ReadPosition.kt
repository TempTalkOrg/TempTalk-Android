package difft.android.messageserialization.model

data class ReadPosition(
    val groupId: String?,
    val readAt: Long,
    val maxServerTime: Long,
    val maxNotifySequenceId: Long,
    val maxSequenceId: Long
)

data class ReadInfoOfDb(
    val readTimestamp: Long
)