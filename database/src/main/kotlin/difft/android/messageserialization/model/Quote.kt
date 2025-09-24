package difft.android.messageserialization.model

import java.io.Serializable

/**
 * TODO Quoted Attachment
 */

data class Quote(val id: Long, val author: String, val text: String, val attachments: List<QuotedAttachment>?, var authorName: String? = null):Serializable

data class QuotedAttachment(val contentType: String, val fileName: String, val thumbnail: Attachment?, val flags: Int):Serializable