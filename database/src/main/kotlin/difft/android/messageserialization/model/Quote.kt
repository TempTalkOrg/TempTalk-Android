package difft.android.messageserialization.model

import java.io.Serializable

data class Quote(val id: Long, val author: String, val text: String, val attachments: List<QuotedAttachment>?) : Serializable

data class QuotedAttachment(val contentType: String, val fileName: String, val thumbnail: Attachment?, val flags: Int) : Serializable