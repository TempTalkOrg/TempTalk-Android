package difft.android.messageserialization

import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.TranslateData

interface MessageStore {
    /**
     * Only put messages when the message is not exist in the DB store
     */
    fun putWhenNonExist(vararg messages: Message)

    fun deleteMessage(messageIds: List<String>)

    fun removeRoomAndMessages(roomId: String)

    fun updateMessageReaction(
        conversationId: String,
        reaction: Reaction,
        reactionMessageId: String?,
        envelopeBytes: ByteArray?
    )

    suspend fun updateMessageTranslateData(conversationId: String, messageId: String, translateData: TranslateData)

    suspend fun updateMessageSpeechToTextData(conversationId: String, messageId: String, speechToTextData: SpeechToTextData)

    fun deleteDatabase()

    suspend fun updateMessageReadTime(conversationId: String, readMaxTimestamp: Long)

    fun savePendingMessage(messageId: String, originalMessageTimeStamp: Long, messageEnvelopBytes: ByteArray)
}