package difft.android.messageserialization.model

import java.io.Serializable

data class Card(
    var cardId: String?,
    var appId: String?,
    var version: Int,
    var creator: String?,
    var timestamp: Long,
    var content: String?,
    var contentType: Int,
    var type: Int,
    var fixedWidth: Boolean,
    var source: String? = "",
    var conversationId: String? = "",
):Serializable{
    val uniqueId =
        if (cardId != null && source != null && conversationId != null) "${cardId}_${source}_$conversationId" else null
}