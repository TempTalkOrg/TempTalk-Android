package org.difft.app.database.hydration

import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.TranslateData











data class MessageSubData(
    val attachment: Attachment? = null,
    val quote: Quote? = null,
    val forwardContext: ForwardContext? = null,
    val mentions: List<Mention> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val sharedContacts: List<SharedContact> = emptyList(),
    val translateData: TranslateData? = null,
    val speechToTextData: SpeechToTextData? = null,
) {
    companion object {
        val EMPTY = MessageSubData()
    }
}






@JvmInline
value class MessageHydration(private val byMessageId: Map<String, MessageSubData>) {
    operator fun get(messageId: String): MessageSubData =
        byMessageId[messageId] ?: MessageSubData.EMPTY

    val size: Int get() = byMessageId.size

    companion object {
        val EMPTY = MessageHydration(emptyMap())
    }
}
