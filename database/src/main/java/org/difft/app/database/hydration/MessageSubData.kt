package org.difft.app.database.hydration

import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.TranslateData

/**
 * All child-table data for ONE message, resolved up-front by [MessageHydrator].
 *
 * Field-for-field mirror of what the per-message point queries in `WCDBExtensions.kt`
 * (`attachment()`, `quote()`, `forwardContext()`, `mentions()`, `reactions()`, `sharedContacts()`,
 * `translateData()`, `speechToTextData()`) return for the same message.
 *
 * NOT included: `screenShot()` — it parses the in-memory `MessageModel.screenShotJson` column, it
 * is not a DB query, so it stays a direct call at the point of use.
 */
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

/**
 * `messageId -> `[MessageSubData]. A missing key yields [MessageSubData.EMPTY] — a message with no
 * child rows and a message that was never hydrated are indistinguishable BY DESIGN: both mean "no
 * child data", which is exactly what the point queries return for a message without child rows.
 */
@JvmInline
value class MessageHydration(private val byMessageId: Map<String, MessageSubData>) {
    operator fun get(messageId: String): MessageSubData =
        byMessageId[messageId] ?: MessageSubData.EMPTY

    val size: Int get() = byMessageId.size

    companion object {
        val EMPTY = MessageHydration(emptyMap())
    }
}
