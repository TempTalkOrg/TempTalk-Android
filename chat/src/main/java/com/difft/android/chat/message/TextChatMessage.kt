package com.difft.android.chat.message

import com.difft.android.chat.widget.AudioMessageManager
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.Card
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo

open class TextChatMessage : ChatMessage() {
    var message: CharSequence? = null
    var attachment: Attachment? = null
    var quote: Quote? = null
    var forwardContext: ForwardContext? = null
    var card: Card? = null
    var mentions: List<Mention>? = null
    var reactions: List<Reaction>? = null
    var sharedContacts: List<SharedContact>? = null
    var readStatus: Int = 0
    var readContactNumber: Int = 0
    var translateData: TranslateData? = null
    var speechToTextData: SpeechToTextData? = null
    var playStatus: Int = AudioMessageManager.PLAY_STATUS_PLAYED
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextChatMessage) return false
        if (!super.equals(other)) return false

        if (readStatus != other.readStatus) return false
        if (readContactNumber != other.readContactNumber) return false
        if (playStatus != other.playStatus) return false
        if (message != other.message) return false
        if (attachment != other.attachment) return false
        if (quote != other.quote) return false
        if (forwardContext != other.forwardContext) return false
        if (card != other.card) return false
        if (mentions != other.mentions) return false
        if (reactions != other.reactions) return false
        if (sharedContacts != other.sharedContacts) return false
        if (translateData != other.translateData) return false
        if (speechToTextData != other.speechToTextData) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + readStatus
        result = 31 * result + readContactNumber
        result = 31 * result + playStatus
        result = 31 * result + (message?.hashCode() ?: 0)
        result = 31 * result + (attachment?.hashCode() ?: 0)
        result = 31 * result + (quote?.hashCode() ?: 0)
        result = 31 * result + (forwardContext?.hashCode() ?: 0)
        result = 31 * result + (card?.hashCode() ?: 0)
        result = 31 * result + (mentions?.hashCode() ?: 0)
        result = 31 * result + (reactions?.hashCode() ?: 0)
        result = 31 * result + (sharedContacts?.hashCode() ?: 0)
        result = 31 * result + (translateData?.hashCode() ?: 0)
        result = 31 * result + (speechToTextData?.hashCode() ?: 0)
        return result
    }


}

fun TextChatMessage.isAttachmentMessage(): Boolean {
    return this.attachment != null
}

fun TextChatMessage.shouldDecrypt(): Boolean {
    return this.attachment?.isAudioMessage() != true
}

fun TextChatMessage.canAutoSaveAttachment(): Boolean {
    return !isConfidential() && isAttachmentMessage() && (this.attachment?.isImage() == true || this.attachment?.isVideo() == true)
}