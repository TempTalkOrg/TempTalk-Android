package com.difft.android.chat.message

import android.content.Context
import com.difft.android.chat.R
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.isAnimatedImage
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isWhitelistedVideoType
import util.TimeFormatter






































object MessageCopyTextFormatter {

    





    fun format(
        messages: List<TextMessage>,
        nameResolver: (uid: String) -> String,
        context: Context,
        language: String,
    ): String {
        return messages.joinToString(separator = "\n\n") { msg ->
            val sender = nameResolver(msg.fromWho.id).sanitizeForHeader()
            val time = TimeFormatter.formatCopyHeaderTime(language, msg.systemShowTimestamp)
            val content = renderContent(msg, context)
            "$sender, [$time]\n$content"
        }
    }

    
    internal fun renderContent(msg: TextMessage, context: Context): String {
        
        if (msg.forwardContext != null) {
            return context.getString(R.string.chat_message_chat_history)
        }

        val sharedContact = msg.sharedContact?.firstOrNull()
        if (sharedContact != null) {
            val placeholder = context.getString(R.string.chat_message_contact_card)
            val contactName = sharedContact.name?.displayName.orEmpty().sanitizeForHeader()
            return if (contactName.isNotEmpty()) "$placeholder $contactName" else placeholder
        }

        val attachment = msg.attachments?.firstOrNull()
        if (attachment != null) {
            return when {
                attachment.isAnimatedImage() -> context.getString(R.string.chat_message_gif)
                attachment.isImage() -> context.getString(R.string.chat_message_image)
                isWhitelistedVideoType(attachment.contentType) -> context.getString(R.string.chat_message_video)
                
                attachment.isAudioMessage() -> context.getString(R.string.chat_message_short_for_generic)
                else -> formatFileAttachment(attachment, context)
            }
        }

        return msg.text?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.chat_message_short_for_generic)
    }

    







    private fun formatFileAttachment(attachment: Attachment, context: Context): String {
        val base = context.getString(R.string.chat_message_attachment)
        val fileName = attachment.fileName?.takeIf { it.isNotBlank() } ?: return base
        return if (base.endsWith("]")) {
            base.dropLast(1) + ": $fileName]"
        } else {
            "$base: $fileName"
        }
    }

    



    private fun String.sanitizeForHeader(): String =
        this.replace(CONTROL_CHARS_REGEX, " ")

    private val CONTROL_CHARS_REGEX = Regex("[\\p{Cntrl}\\r\\n]")
}
