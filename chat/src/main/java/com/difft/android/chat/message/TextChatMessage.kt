package com.difft.android.chat.message

import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.chat.R
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.attachment.ForwardSourceContext
import com.difft.android.chat.attachment.deepCopyWithNewAttachmentIdentities
import com.difft.android.chat.attachment.toForwardCopy
import com.difft.android.chat.widget.AudioMessageManager
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isLongText




data class LongTextFileInfo(
    val filePath: String,
    val messageId: String
)




data class FileInfoForCopy(
    val filePath: String,
    val fileName: String,
    val attachment: Attachment,
    val messageId: String
)

open class TextChatMessage : ChatMessage() {
    var message: CharSequence? = null
    var attachment: Attachment? = null
    var quote: Quote? = null
    var forwardContext: ForwardContext? = null
    var mentions: List<Mention>? = null
    var reactions: List<Reaction>? = null
    var sharedContacts: List<SharedContact>? = null
    var readStatus: Int = 0
    var readContactNumber: Int = 0
    var translateData: TranslateData? = null
    var speechToTextData: SpeechToTextData? = null
    var playStatus: Int = AudioMessageManager.PLAY_STATUS_PLAYED
    var criticalAlertType: Int = CRITICAL_ALERT_TYPE_NONE
    var isScreenShotMessage: Boolean = false
    





    fun contentEqualsIgnoringTranslate(other: TextChatMessage): Boolean {
        if (!super.equals(other)) return false

        if (readStatus != other.readStatus) return false
        if (readContactNumber != other.readContactNumber) return false
        if (playStatus != other.playStatus) return false
        if (message != other.message) return false
        if (attachment != other.attachment) return false
        if (quote != other.quote) return false
        if (forwardContext != other.forwardContext) return false
        if (mentions != other.mentions) return false
        if (reactions != other.reactions) return false
        if (sharedContacts != other.sharedContacts) return false
        if (speechToTextData != other.speechToTextData) return false
        if (criticalAlertType != other.criticalAlertType) return false
        if (isScreenShotMessage != other.isScreenShotMessage) return false

        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextChatMessage) return false
        return contentEqualsIgnoringTranslate(other) && translateData == other.translateData
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + readStatus
        result = 31 * result + readContactNumber
        result = 31 * result + playStatus
        result = 31 * result + criticalAlertType
        result = 31 * result + (message?.hashCode() ?: 0)
        result = 31 * result + (attachment?.hashCode() ?: 0)
        result = 31 * result + (quote?.hashCode() ?: 0)
        result = 31 * result + (forwardContext?.hashCode() ?: 0)
        result = 31 * result + (mentions?.hashCode() ?: 0)
        result = 31 * result + (reactions?.hashCode() ?: 0)
        result = 31 * result + (sharedContacts?.hashCode() ?: 0)
        result = 31 * result + (translateData?.hashCode() ?: 0)
        result = 31 * result + (speechToTextData?.hashCode() ?: 0)
        result = 31 * result + isScreenShotMessage.hashCode()
        return result
    }


}

fun TextChatMessage.isAttachmentMessage(): Boolean {
    return this.attachment != null
}











fun TextChatMessage.singleForwardableAttachment(): Attachment? = when {
    isAttachmentMessage() -> attachment
    forwardContext?.forwards?.size == 1 -> forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()
    else -> null
}

fun TextChatMessage.getAttachmentProgress(): Int? {
    val attachmentId = getAttachmentIdForProgress()
    return FileUtil.getProgress(attachmentId)
}





internal fun TextChatMessage.singleForwardWrappedAttachment(): Attachment? =
    forwardContext?.forwards
        ?.takeIf { it.size == 1 }
        ?.firstOrNull()
        ?.attachments
        ?.firstOrNull()









fun TextChatMessage.getAttachmentIdForProgress(): String =
    getRelevantAttachment()?.localId?.takeIf { it.isNotEmpty() } ?: this.id











internal fun TextChatMessage.getRelevantAttachment(): Attachment? =
    singleForwardWrappedAttachment() ?: attachment




fun TextChatMessage.canDownloadFile(): Boolean {
    if (isAttachmentMessage()
        && (attachment?.isAudioMessage() != true)
        && (attachment?.isLongText() != true)
        && (attachment?.status == AttachmentStatus.SUCCESS.code || getAttachmentProgress() == 100)
    ) {
        return true
    }

    val forwards = forwardContext?.forwards
    if (forwards?.size == 1) {
        val forward = forwards.firstOrNull()
        val forwardAttachment = forward?.attachments?.firstOrNull()
        if (forwardAttachment != null
            && !forwardAttachment.isAudioMessage()
            && !forwardAttachment.isLongText()
            && (forwardAttachment.status == AttachmentStatus.SUCCESS.code || getAttachmentProgress() == 100)
        ) {
            return true
        }
    }
    return false
}


fun TextChatMessage.isLongTextAttachment(): Boolean {
    if (isAttachmentMessage() && attachment?.isLongText() == true) {
        return true
    }

    val forwards = forwardContext?.forwards
    if (forwards?.size == 1) {
        val attachment = forwards.firstOrNull()?.attachments?.firstOrNull()
        if (attachment?.isLongText() == true) {
            return true
        }
    }
    return false
}


fun TextChatMessage.hasTextContent(): Boolean {
    if (isAttachmentMessage()) return false

    forwardContext?.forwards?.let { forwards ->
        if (forwards.size == 1) {
            val forward = forwards.firstOrNull()
            if (!forward?.text.isNullOrEmpty()) {
                return true
            }
        }
    } ?: run {
        if (!message.isNullOrEmpty()) {
            return true
        }
    }

    return false
}


fun TextChatMessage.getCopyableTextContent(): String? {
    return forwardContext?.forwards?.let { forwards ->
        if (forwards.size == 1) {
            forwards.firstOrNull()?.text.takeUnless { it.isNullOrEmpty() }
        } else null
    } ?: message?.toString().takeUnless { it.isNullOrEmpty() }
}





fun TextChatMessage.getLongTextFileInfo(): LongTextFileInfo? {
    
    val attachment = (if (isAttachmentMessage()) this.attachment else singleForwardWrappedAttachment())
        ?: return null
    
    
    val messageId = AttachmentPathResolver.directoryKeyFor(attachment)
    if (messageId.isEmpty()) return null

    val filePath = AttachmentPathResolver.materializedFileFor(attachment, id)
    return LongTextFileInfo(filePath, messageId)
}





fun TextChatMessage.getFileInfoForCopy(): FileInfoForCopy? {
    
    val attachment = (if (isAttachmentMessage()) this.attachment else singleForwardWrappedAttachment()) ?: return null

    
    val messageId = AttachmentPathResolver.directoryKeyFor(attachment)
    val filePath = AttachmentPathResolver.materializedFileFor(attachment, id)

    return FileInfoForCopy(filePath, attachment.fileName ?: "file", attachment, messageId)
}


fun TextChatMessage.buildForwardData(): Pair<String, ForwardContext>? {
    val content: String
    val forwardCtx: ForwardContext
    
    val sourceContext = ForwardSourceContext(id, isConfidential())

    if (forwardContext != null) {
        content = if (forwardContext?.forwards?.size == 1) {
            val forward = forwardContext?.forwards?.firstOrNull()
            if (forward?.attachments?.isNotEmpty() == true) {
                ResUtils.getString(R.string.chat_message_attachment)
            } else {
                forward?.text ?: ResUtils.getString(R.string.chat_history)
            }
        } else {
            ResUtils.getString(R.string.chat_history)
        }
        
        
        forwardCtx = (forwardContext ?: return null)
            .deepCopyWithNewAttachmentIdentities(sourceContext)
    } else {
        content = if (isAttachmentMessage()) {
            ResUtils.getString(R.string.chat_message_attachment)
        } else {
            message?.toString() ?: ""
        }

        forwardCtx = ForwardContext(
            mutableListOf<Forward>().apply {
                add(
                    Forward(
                        timeStamp,
                        0,
                        false, 
                        authorId,
                        message?.toString(),
                        attachment?.let { attach ->
                            listOf(attach.toForwardCopy(sourceContext))
                        },
                        null,
                        mentions,
                        systemShowTimestamp
                    )
                )
            },
            false
        )
    }

    return content to forwardCtx
}
