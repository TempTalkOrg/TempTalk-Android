package com.difft.android.chat.translate

import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.TranslateData
import org.difft.app.database.hydration.MessageSubData
import org.difft.app.database.models.MessageModel
import org.whispersystems.signalservice.internal.push.SignalServiceProtos


internal const val MAX_TRANSLATABLE_LENGTH = 4096







internal data class TranslateCandidateInput(
    val messageId: String,
    val isMine: Boolean,
    val mode: Int,
    val hasAttachment: Boolean,
    val forwardCount: Int,
    val singleForwardText: String?,
    val body: String?,
    val mentions: List<Mention>?,
    val translateData: TranslateData?,
)


internal data class TranslateJobInput(
    val messageId: String,
    val text: String,
)

internal fun TextChatMessage.toTranslateCandidateInput(): TranslateCandidateInput {
    val forwards = forwardContext?.forwards
    return TranslateCandidateInput(
        messageId = id,
        isMine = isMine,
        mode = mode,
        hasAttachment = attachment != null,
        forwardCount = forwards?.size ?: 0,
        singleForwardText = forwards?.firstOrNull()?.text,
        body = message?.toString(),
        mentions = mentions,
        translateData = translateData,
    )
}





internal fun MessageModel.toTranslateCandidateInput(
    subData: MessageSubData,
    myId: String,
): TranslateCandidateInput? {
    if (type != MessageModel.TYPE_TEXT && type != MessageModel.TYPE_ATTACHMENT) return null
    val forwards = subData.forwardContext?.forwards
    return TranslateCandidateInput(
        messageId = id,
        isMine = fromWho == myId,
        mode = mode,
        hasAttachment = subData.attachment != null,
        forwardCount = forwards?.size ?: 0,
        singleForwardText = forwards?.firstOrNull()?.text,
        
        body = if (subData.forwardContext != null) "" else messageText,
        mentions = subData.mentions,
        translateData = subData.translateData,
    )
}








internal fun toCandidate(
    input: TranslateCandidateInput,
    target: TranslateLang,
    attempted: MutableSet<String>,
    userDisabled: Set<String>,
): TranslateJobInput? {
    if (input.isMine) return null
    if (input.mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) return null
    if (input.hasAttachment) return null
    if (input.forwardCount > 1) return null
    val text = if (input.forwardCount == 1) input.singleForwardText else input.body
    if (text.isNullOrBlank()) return null
    if (text.length > MAX_TRANSLATABLE_LENGTH) return null
    if (!isTranslatableText(text, input.mentions)) return null
    if (hasAnyCachedTranslation(input.translateData)) return null
    if (input.messageId in userDisabled) return null
    if (!attempted.add(attemptKey(input.messageId, target))) return null
    return TranslateJobInput(input.messageId, text)
}


internal fun attemptKey(messageId: String, target: TranslateLang): String = "$messageId#${target.name}"







internal fun hasAnyCachedTranslation(data: TranslateData?): Boolean =
    data != null && (!data.translatedContentCN.isNullOrEmpty() || !data.translatedContentEN.isNullOrEmpty())

private val URL_REGEX = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)






internal fun isTranslatableText(text: String, mentions: List<Mention>?): Boolean {
    val withoutMentions = blankOutMentions(text, mentions)
    val withoutUrls = URL_REGEX.replace(withoutMentions, " ")
    return withoutUrls.any { it.isLetter() }
}

private fun blankOutMentions(text: String, mentions: List<Mention>?): String {
    if (mentions.isNullOrEmpty()) return text
    val chars = text.toCharArray()
    mentions.forEach { mention ->
        val start = mention.start.coerceAtLeast(0)
        val end = (mention.start + mention.length).coerceAtMost(chars.size)
        for (i in start until end) chars[i] = ' '
    }
    return String(chars)
}
