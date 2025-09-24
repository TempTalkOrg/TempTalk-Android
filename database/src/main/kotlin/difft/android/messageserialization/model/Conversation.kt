package difft.android.messageserialization.model

import difft.android.messageserialization.For
import java.io.Serializable

data class Conversation(
    val forWhat: For,
    val latestMessage: Message?,
    val pinnedTime: Long?,
    val muteStatus: Int?,
    val readPosition: Long?,
    val messageExpiry: Long?,
    val lastActiveTime: Long
) : Serializable