package difft.android.messageserialization

import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.TranslateData

interface MessageStore {
    /**
     * Only put messages when the message is not exist in the DB store.
     *
     * @return how many messages were newly inserted; existing ones and ones skipped because the
     *   same id is being processed concurrently are not counted.
     */
    fun putWhenNonExist(vararg messages: Message): Int

    fun deleteMessage(messageIds: List<String>)

    fun removeRoomAndMessages(roomId: String)

    fun updateMessageReaction(
        conversationId: String,
        reaction: Reaction,
        reactionMessageId: String?,
        envelopeBytes: ByteArray?
    )

    suspend fun updateMessageTranslateData(conversationId: String, messageId: String, translateData: TranslateData)

    /**
     * Batch variant of [updateMessageTranslateData]: one round of queries for the whole batch
     * instead of two per entry.
     *
     * Entries whose status is not persistable (Translating / SameLanguage / Failed /
     * FailedTooLong / FailedModel) are dropped with a warning, and so are entries whose message
     * no longer exists in [conversationId].
     */
    suspend fun updateMessageTranslateDataBatch(
        conversationId: String,
        dataByMessageId: Map<String, TranslateData>
    )

    suspend fun updateMessageSpeechToTextData(conversationId: String, messageId: String, speechToTextData: SpeechToTextData)

    fun deleteDatabase()

    /**
     * @param readMaxTimestamp row-selection bound (server axis).
     * @param readAt value written to readTime — the actual read moment (#1020 Phase 2), kept separate
     *   from the selection bound.
     */
    suspend fun updateMessageReadTime(conversationId: String, readMaxTimestamp: Long, readAt: Long)

    fun savePendingMessage(messageId: String, originalMessageTimeStamp: Long, messageEnvelopBytes: ByteArray)
}