package com.difft.android.chat.message

import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.chat.R
import com.difft.android.chat.widget.AudioMessageManager
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE
import difft.android.messageserialization.model.Card
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

/**
 * Information about a long text file for copying
 */
data class LongTextFileInfo(
    val filePath: String,
    val messageId: String
)

/**
 * Information about a file attachment for copying to clipboard
 */
data class FileInfoForCopy(
    val filePath: String,
    val fileName: String,
    val attachment: Attachment
)

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
    var criticalAlertType: Int = CRITICAL_ALERT_TYPE_NONE
    var isScreenShotMessage: Boolean = false
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
        if (criticalAlertType != other.criticalAlertType) return false
        if (isScreenShotMessage != other.isScreenShotMessage) return false

        return true
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
        result = 31 * result + (card?.hashCode() ?: 0)
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

fun TextChatMessage.shouldDecrypt(): Boolean {
    return this.attachment?.isAudioMessage() != true
}

fun TextChatMessage.getAttachmentProgress(): Int? {
    // Get the attachment ID to check progress for
    val attachmentId = getAttachmentIdForProgress()
    return FileUtil.getProgress(attachmentId)
}

/**
 * Determines the appropriate attachment ID to use for progress tracking.
 * For single forward messages, uses the forward attachment ID if available,
 * otherwise falls back to the message ID.
 */
fun TextChatMessage.getAttachmentIdForProgress(): String {
    return forwardContext?.forwards
        ?.takeIf { it.size == 1 }
        ?.firstOrNull()
        ?.attachments
        ?.firstOrNull()
        ?.authorityId
        ?.toString()
        ?: this.id
}

fun TextChatMessage.shouldShowFail(): Boolean {
    // Only show fail for non-mine messages or messages from different device
    if (this.isMine && this.id.last().digitToIntOrNull() == DEFAULT_DEVICE_ID) {
        return false
    }

    // Check if progress indicates failure or expired
    val progress = getAttachmentProgress()
    if (progress == -1 || progress == -2) return true

    // If no progress info, check attachment status
    if (progress == null) {
        val attachment = getRelevantAttachment()
        return attachment?.status == AttachmentStatus.FAILED.code || attachment?.status == AttachmentStatus.EXPIRED.code
    }

    return false
}

/**
 * Gets the relevant attachment for this message.
 * For single forward messages, returns the forward attachment, otherwise returns the message attachment.
 */
private fun TextChatMessage.getRelevantAttachment(): Attachment? {
    val forwards = forwardContext?.forwards
    return if (forwards?.size == 1) {
        forwards.firstOrNull()?.attachments?.firstOrNull()
    } else {
        this.attachment
    }
}

// ============ Copy & Forward Extension Functions ============

/**
 * Check if file can be downloaded (excludes long text and audio messages)
 * Used to determine if save/copy file actions should be shown
 */
fun TextChatMessage.canDownloadFile(): Boolean {
    // Check current message attachment
    if (isAttachmentMessage()
        && (attachment?.isAudioMessage() != true)
        && (attachment?.isLongText() != true)
        && (attachment?.status == AttachmentStatus.SUCCESS.code || getAttachmentProgress() == 100)
    ) {
        return true
    }

    // Check forwarded message attachment
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

/**
 * Check if message is a long text attachment
 * Used to determine if copy should read from file
 */
fun TextChatMessage.isLongTextAttachment(): Boolean {
    // Check current message attachment
    if (isAttachmentMessage() && attachment?.isLongText() == true) {
        return true
    }

    // Check forwarded message attachment
    val forwards = forwardContext?.forwards
    if (forwards?.size == 1) {
        val attachment = forwards.firstOrNull()?.attachments?.firstOrNull()
        if (attachment?.isLongText() == true) {
            return true
        }
    }
    return false
}

/**
 * Check if message has text content (not attachment message)
 * Used to determine if copy/translate actions should be shown
 */
fun TextChatMessage.hasTextContent(): Boolean {
    if (isAttachmentMessage()) return false

    forwardContext?.forwards?.let { forwards ->
        if (forwards.size == 1) {
            val forward = forwards.firstOrNull()
            if (!forward?.card?.content.isNullOrEmpty() || !forward?.text.isNullOrEmpty()) {
                return true
            }
        }
    } ?: run {
        if (!card?.content.isNullOrEmpty() || !message.isNullOrEmpty()) {
            return true
        }
    }

    return false
}

/**
 * Get copyable text content from message
 * Returns text from forward context (single forward) or message/card content
 */
fun TextChatMessage.getCopyableTextContent(): String? {
    return forwardContext?.forwards?.let { forwards ->
        if (forwards.size == 1) {
            forwards.firstOrNull()?.let { forward ->
                forward.card?.content.takeUnless { it.isNullOrEmpty() }
                    ?: forward.text.takeUnless { it.isNullOrEmpty() }
            }
        } else null
    } ?: card?.content.takeUnless { it.isNullOrEmpty() }
    ?: message?.toString().takeUnless { it.isNullOrEmpty() }
}

/**
 * Get long text file info for copying
 * Returns the file path and message ID for long text attachment
 */
fun TextChatMessage.getLongTextFileInfo(): LongTextFileInfo? {
    val (attachment, messageId) = when {
        isAttachmentMessage() -> {
            this.attachment to this.id
        }

        forwardContext?.forwards?.size == 1 -> {
            val forward = forwardContext?.forwards?.firstOrNull()
            val forwardMessage = forward?.let { generateMessageFromForward(it) }
            forward?.attachments?.firstOrNull() to (forwardMessage?.id ?: "")
        }

        else -> null to ""
    }

    if (attachment == null || messageId.isEmpty()) return null

    val filePath = FileUtil.getMessageAttachmentFilePath(messageId) + attachment.fileName
    return LongTextFileInfo(filePath, messageId)
}

/**
 * Get file info for copying file to clipboard
 * Returns file path, file name, and attachment info
 */
fun TextChatMessage.getFileInfoForCopy(): FileInfoForCopy? {
    val attachment = when {
        isAttachmentMessage() -> this.attachment
        forwardContext?.forwards?.size == 1 -> {
            forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()
        }

        else -> null
    } ?: return null

    val messageId = if (isAttachmentMessage()) id else attachment.authorityId.toString()
    val filePath = FileUtil.getMessageAttachmentFilePath(messageId) + attachment.fileName

    return FileInfoForCopy(filePath, attachment.fileName ?: "file", attachment)
}

/**
 * Build forward data (content description and ForwardContext)
 * Used when forwarding message to other chats
 */
fun TextChatMessage.buildForwardData(): Pair<String, ForwardContext>? {
    val content: String
    val forwardCtx: ForwardContext

    if (forwardContext != null) {
        // Already a forward message, re-forward it
        content = if (forwardContext?.forwards?.size == 1) {
            val forward = forwardContext?.forwards?.firstOrNull()
            if (forward?.attachments?.isNotEmpty() == true) {
                ResUtils.getString(R.string.chat_message_attachment)
            } else {
                forward?.text ?: forward?.card?.content ?: ResUtils.getString(R.string.chat_history)
            }
        } else {
            ResUtils.getString(R.string.chat_history)
        }
        forwardCtx = forwardContext ?: return null
    } else {
        // Create a new forward context from this message
        content = if (isAttachmentMessage()) {
            ResUtils.getString(R.string.chat_message_attachment)
        } else {
            message?.toString() ?: card?.content ?: ""
        }

        forwardCtx = ForwardContext(
            mutableListOf<Forward>().apply {
                add(
                    Forward(
                        timeStamp,
                        0,
                        false, // forward message is not from group context
                        authorId,
                        message?.toString(),
                        attachment?.let { attach ->
                            listOf(attach.copy(status = AttachmentStatus.LOADING.code))
                        },
                        null,
                        card,
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
