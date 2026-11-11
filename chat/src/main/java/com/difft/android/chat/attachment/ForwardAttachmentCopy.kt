package com.difft.android.chat.attachment

import com.difft.android.chat.media.EncryptedAttachmentAccess
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.ForwardSourceFallback
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.VideoProbeState
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Mode
import java.util.UUID




















data class ForwardSourceContext(
    val ownerMessageId: String?,
    val isConfidential: Boolean
)


fun Message.forwardSourceContext(): ForwardSourceContext =
    ForwardSourceContext(id, mode == Mode.CONFIDENTIAL_VALUE)









fun Attachment.toForwardCopy(source: ForwardSourceContext? = null): Attachment {
    val sourceFilePath = source?.let { forwardSourceFilePath(this, it) } ?: forwardSourceFilePath
    
    
    
    val fallback = when {
        sourceFilePath != null -> null
        source == null -> forwardSourceFallback
        source.isConfidential -> null
        else -> ForwardSourceFallback(this, source.ownerMessageId)
    }
    return copy(
        localId = UUID.randomUUID().toString(),
        status = AttachmentStatus.LOADING.code,
        isForwardCopy = true,
        
        videoProbeState = VideoProbeState.NONE
    ).also {
        it.forwardSourceFilePath = sourceFilePath
        it.forwardSourceFallback = fallback
    }
}









fun ForwardContext.deepCopyWithNewAttachmentIdentities(source: ForwardSourceContext? = null): ForwardContext =
    copy(forwards = forwards?.map { it.deepCopyWithNewAttachmentIdentities(source) })

fun Forward.deepCopyWithNewAttachmentIdentities(source: ForwardSourceContext? = null): Forward =
    copy(
        attachments = attachments?.map { it.toForwardCopy(source) },
        forwards = forwards?.map { it.deepCopyWithNewAttachmentIdentities(source) },
        mentions = mentions?.toList()
    )






private fun forwardSourceFilePath(attachment: Attachment, source: ForwardSourceContext): String? {
    if (source.isConfidential) return null
    if (attachment.fileName.isNullOrEmpty()) return null
    
    
    
    
    val path = AttachmentPathResolver.fileFor(attachment)
    return path.takeIf { EncryptedAttachmentAccess.isReadable(it) }
}
