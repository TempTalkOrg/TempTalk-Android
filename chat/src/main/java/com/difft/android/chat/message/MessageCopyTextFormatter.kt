package com.difft.android.chat.message

import android.content.Context
import com.difft.android.chat.R
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.isAnimatedImage
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import util.TimeFormatter

/**
 * Renders multi-select copy output per PRD §3.
 *
 * Format (one block per message, blocks separated by a single blank line):
 *
 *   {Sender}, [{date+time header}]
 *   {content}
 *
 *   {Sender}, [{date+time header}]
 *   {content}
 *
 * Per-message content rules (PRD §3.4):
 *
 * | Type              | Output                                |
 * |-------------------|---------------------------------------|
 * | Combined forward  | `[Chat History]` / `[聊天记录]`       |
 * | Contact card      | `[Contact Card] {name}`               |
 * | Image attachment  | `[Image]` / `[图片]`                  |
 * | Video attachment  | `[Video]` / `[视频]`                  |
 * | Voice (defensive) | `[Message]` (voice not selectable)    |
 * | File attachment   | `[Attachment: {fileName}]`            |
 * | Plain text        | original text (newlines + emoji kept) |
 * | Quote reply       | recurse on reply body (quote ignored) |
 * | Fallback          | `[Message]` / `[消息]`                |
 *
 * Sender name rules (PRD §3.3):
 *  - Always uses the author's own name (NOT local remark name) via [nameResolver]
 *  - Control chars / line breaks in the name are replaced with a space so the
 *    "{Sender}, [...]" header stays on a single line
 *
 * Ordering: [messages] must be pre-sorted by conversation display order (PRD §3.6);
 * this formatter does not re-sort.
 *
 * NOTE: separate from [com.difft.android.chat.message.TextChatMessage.getDescription] —
 * intentionally not reused. `getDescription` has per-type branches for many notify
 * subtypes that should all collapse to `[Message]` here (PRD §3.4 fallback).
 */
object MessageCopyTextFormatter {

    /**
     * @param messages         pre-sorted messages to format
     * @param nameResolver     uid → author name (no remark); use a pre-warmed cache to keep this synchronous
     * @param context          for string resources
     * @param language         locale language code (`zh` / `en` / ...) for time header formatting
     */
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

    /**
     * Renders a single message's content line per §3.4. Quote field is intentionally
     * ignored — PRD §3.4 says copy of a quote-reply does NOT expand the quoted source,
     * which falls out naturally by only looking at the message's own body fields.
     */
    internal fun renderContent(msg: TextMessage, context: Context): String {
        // Order: specialized containers → contact card → attachment kind → plain text → fallback
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
                attachment.isVideo() -> context.getString(R.string.chat_message_video)
                // Voice messages are excluded from multi-select (PRD §2.1), so this is a
                // defensive branch — if a voice somehow makes it in, render as the
                // generic placeholder rather than leaking voice-specific UX text.
                attachment.isAudioMessage() -> context.getString(R.string.chat_message_short_for_generic)
                else -> formatFileAttachment(attachment, context)
            }
        }

        return msg.text?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.chat_message_short_for_generic)
    }

    /**
     * "[Attachment]" → "[Attachment: design.fig]" (en)
     * "[附件]"        → "[附件: design.fig]"      (zh)
     *
     * When fileName is missing, falls back to the bare placeholder. The exact ":"
     * placement is computed from the placeholder string (which ends with "]") to
     * stay locale-correct without hardcoded prefixes.
     */
    private fun formatFileAttachment(attachment: Attachment, context: Context): String {
        val base = context.getString(R.string.chat_message_attachment)
        val fileName = attachment.fileName?.takeIf { it.isNotBlank() } ?: return base
        return if (base.endsWith("]")) {
            base.dropLast(1) + ": $fileName]"
        } else {
            "$base: $fileName"
        }
    }

    /**
     * PRD §3.3: replace control chars / line breaks in names with a single space so
     * the "{Sender}, [date]" header stays on one line. Applied to sender names AND
     * contact-card names.
     */
    private fun String.sanitizeForHeader(): String =
        this.replace(CONTROL_CHARS_REGEX, " ")

    private val CONTROL_CHARS_REGEX = Regex("[\\p{Cntrl}\\r\\n]")
}
