package difft.android.messageserialization

import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.TranslateData
import io.reactivex.rxjava3.core.Completable

interface MessageStore {
    /**
     * Only put messages when the message is not exist in the DB store compare to [putMessage]
     */
    fun putWhenNonExist(vararg messages: Message, useTransaction: Boolean = true)

    fun putMessage(vararg messages: Message): Completable

    fun deleteMessage(messageIds: List<String>)

    fun removeRoomAndMessages(roomId: String)

    fun updateMessageReaction(
        conversationId: String,
        reaction: Reaction,
        reactionMessageId: String?,
        envelopeBytes: ByteArray?
    )

    fun updateMessageTranslateData(conversationId: String, messageId: String, translateData: TranslateData): Completable

    fun updateMessageSpeechToTextData(conversationId: String, messageId: String, speechToTextData: SpeechToTextData): Completable

    fun deleteDatabase()

    fun updateMessageReadTime(conversationId: String, readMaxTimestamp: Long): Completable

    fun updateSendStatus(message: Message, status: Int): Completable

    fun savePendingMessage(messageId: String, originalMessageTimeStamp: Long, messageEnvelopBytes: ByteArray)
}