package difft.android.messageserialization.model

import java.io.Serializable

data class ForwardContext(
    val forwards: List<Forward>?,
    val isFromGroup: Boolean,
    val sharedContactId: String? = null,
    val sharedContactName: String? = null
) : Serializable

data class Forward(
    val id: Long,
    val type: Int,
    val isFromGroup: Boolean,
    val author: String,
    val text: String?,
    var attachments: List<Attachment>?,
    var forwards: List<Forward>?,
    var card: Card?,
    var mentions: List<Mention>?,
    val serverTimestamp: Long = 0L
) : Serializable {
    val serverTimestampForUI: Long
        get() = if (serverTimestamp == 0L) id else serverTimestamp
}