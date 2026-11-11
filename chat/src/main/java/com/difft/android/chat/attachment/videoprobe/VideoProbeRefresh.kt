package com.difft.android.chat.attachment.videoprobe

import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext





fun ChatMessage.rendersAttachment(attachmentKey: String): Boolean {
    if (this !is TextChatMessage) return false
    if (attachment?.localId == attachmentKey) return true
    return forwardContext?.forwards?.singleOrNull()?.attachments?.any { it.localId == attachmentKey } == true
}


fun ForwardContext.containsAttachment(attachmentKey: String): Boolean =
    forwards.orEmpty().any { it.containsAttachment(attachmentKey) }

private fun Forward.containsAttachment(attachmentKey: String): Boolean =
    attachments.orEmpty().any { it.localId == attachmentKey } ||
        forwards.orEmpty().any { it.containsAttachment(attachmentKey) }






fun ForwardContext.withProbedAttachment(attachmentKey: String, row: VideoProbeRow): ForwardContext =
    copy(forwards = forwards?.map { it.withProbedAttachment(attachmentKey, row) })

private fun Forward.withProbedAttachment(attachmentKey: String, row: VideoProbeRow): Forward {
    if (!containsAttachment(attachmentKey)) return this
    return copy(
        attachments = attachments?.map { if (it.localId == attachmentKey) it.withProbedRow(row) else it },
        forwards = forwards?.map { it.withProbedAttachment(attachmentKey, row) },
    )
}

private fun Attachment.withProbedRow(row: VideoProbeRow): Attachment =
    copy(width = row.width, height = row.height, videoProbeState = row.videoProbeState)
