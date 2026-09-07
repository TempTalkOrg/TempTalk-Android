package com.difft.android.chat.message

import com.difft.android.base.utils.DEFAULT_DEVICE_ID
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

/**
 * ACTION-coupled selector: the single attachment eligible for per-item actions (save / favorite) —
 * a direct attachment message, or the sole attachment of a single-item forward (a combined-forward
 * wrapper with exactly one forward). Returns null otherwise. Keeps save/favorite detection in
 * lockstep across the chat list and the forward detail view — a single forward wraps its gif in
 * forwardContext, so `attachment` is null and a naive check misses it.
 *
 * OWN-attachment-first, deliberately unlike the render-coupled [getRelevantAttachment]: an action
 * offered on a message acts on the attachment that message itself carries whenever it has one.
 */
fun TextChatMessage.singleForwardableAttachment(): Attachment? = when {
    isAttachmentMessage() -> attachment
    forwardContext?.forwards?.size == 1 -> forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()
    else -> null
}

fun TextChatMessage.getAttachmentProgress(): Int? {
    // Get the attachment ID to check progress for
    val attachmentId = getAttachmentIdForProgress()
    return FileUtil.getProgress(attachmentId)
}

/**
 * The attachment carried by a single-item forward wrapper — the one such a bubble renders and
 * addresses. Null when this message is not a single-item forward, or that forward has no attachment.
 */
internal fun TextChatMessage.singleForwardWrappedAttachment(): Attachment? =
    forwardContext?.forwards
        ?.takeIf { it.size == 1 }
        ?.firstOrNull()
        ?.attachments
        ?.firstOrNull()

/**
 * The single authority for the progress-map key of this message: emit side and collect side must
 * both go through it, or progress UI silently stops matching.
 *
 * The key is the relevant attachment's own [Attachment.localId], so every forwarded copy tracks its
 * own transfer instead of sharing one with the message it came from. A message with no attachment
 * (or a row whose localId has not been backfilled yet) falls back to the message id.
 */
fun TextChatMessage.getAttachmentIdForProgress(): String =
    getRelevantAttachment()?.localId?.takeIf { it.isNotEmpty() } ?: this.id

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
 * RENDER-coupled selector: the attachment whose transfer state (progress key, status, fail display)
 * this bubble shows — which must be the attachment the bubble actually draws.
 *
 * FORWARD-leaf-first, deliberately unlike the action-coupled [singleForwardableAttachment]:
 * `ChatMessageViewHolder` binds the forward leaf for every `forwards.size == 1` message, so for a
 * crafted message carrying both an own attachment and a single-item forward wrapper (no client
 * produces this, but the wire format allows it) the own attachment is never on screen and keying
 * state to it would track an attachment nobody sees.
 */
internal fun TextChatMessage.getRelevantAttachment(): Attachment? =
    singleForwardWrappedAttachment() ?: attachment

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

/**
 * Get copyable text content from message
 * Returns text from forward context (single forward) or message content
 */
fun TextChatMessage.getCopyableTextContent(): String? {
    return forwardContext?.forwards?.let { forwards ->
        if (forwards.size == 1) {
            forwards.firstOrNull()?.text.takeUnless { it.isNullOrEmpty() }
        } else null
    } ?: message?.toString().takeUnless { it.isNullOrEmpty() }
}

/**
 * Get long text file info for copying
 * Returns the file path and message ID for long text attachment
 *
 * Blocking IO — callers must be off the main thread, which is what licenses the MIGRATING read
 * below. Copy is not a download gate: a miss enqueues nothing, so a long-text file still at its
 * pre-per-copy owner-message address would silently put the 2KB body preview on the clipboard and
 * report success.
 */
fun TextChatMessage.getLongTextFileInfo(): LongTextFileInfo? {
    // Disambiguation only: which attachment. The resolver owns where its file is.
    val attachment = (if (isAttachmentMessage()) this.attachment else singleForwardWrappedAttachment())
        ?: return null
    // Directory key, which callers also take as the message-scoped handle of the file (it is the
    // segment of the exported content uri).
    val messageId = AttachmentPathResolver.directoryKeyFor(attachment)
    if (messageId.isEmpty()) return null

    val filePath = AttachmentPathResolver.materializedFileFor(attachment, id)
    return LongTextFileInfo(filePath, messageId)
}

/**
 * Get file info for copying file to clipboard
 * Returns file path, file name, and attachment info
 *
 * Blocking IO — callers must be off the main thread, same contract and same reason as
 * [getLongTextFileInfo]: a Copy that misses is a total no-op (no clipboard write, no toast, no
 * download), so this read has to be the migrating one.
 */
fun TextChatMessage.getFileInfoForCopy(): FileInfoForCopy? {
    // Disambiguation only: which attachment. The resolver owns where its file is.
    val attachment = (if (isAttachmentMessage()) this.attachment else singleForwardWrappedAttachment()) ?: return null

    // Doubles as the message segment of the exported content uri, so it stays the directory key.
    val messageId = AttachmentPathResolver.directoryKeyFor(attachment)
    val filePath = AttachmentPathResolver.materializedFileFor(attachment, id)

    return FileInfoForCopy(filePath, attachment.fileName ?: "file", attachment, messageId)
}

/**
 * Build forward data (content description and ForwardContext)
 * Used when forwarding message to other chats
 */
fun TextChatMessage.buildForwardData(): Pair<String, ForwardContext>? {
    val content: String
    val forwardCtx: ForwardContext
    // Owner message id travels only as the migration's legacy-address hint; addressing never reads it.
    val sourceContext = ForwardSourceContext(id, isConfidential())

    if (forwardContext != null) {
        // Already a forward message, re-forward it
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
        // Deep copy, never the original tree: reusing it would hand the new message the SAME
        // attachment identities as the message being forwarded, re-coupling their files and state.
        forwardCtx = (forwardContext ?: return null)
            .deepCopyWithNewAttachmentIdentities(sourceContext)
    } else {
        // Create a new forward context from this message
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
                        false, // forward message is not from group context
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
