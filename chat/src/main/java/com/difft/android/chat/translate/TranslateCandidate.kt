package com.difft.android.chat.translate

import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.TranslateData
import org.difft.app.database.hydration.MessageSubData
import org.difft.app.database.models.MessageModel
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/** Longest body the engine accepts; anything above is silently skipped in AUTO mode. */
internal const val MAX_TRANSLATABLE_LENGTH = 4096

/**
 * Everything the candidate rules need from a message, and nothing else.
 *
 * Two producers feed it — a hydrated [TextChatMessage] (the assembled window, scenarios B/C) and a
 * raw [MessageModel] plus its [MessageSubData] (a brand-new incoming message, scenario A, which is
 * evaluated before the window is assembled). Both end up in the same pure rule set instead of two
 * drifting copies.
 */
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

/** A message that passed every rule, with the exact text to translate. */
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

/**
 * Scenario-A variant: the window has not been assembled yet, so the fields come straight from the
 * row plus the hydrated child data. Mirrors `generateMessageTwo`'s field assignment exactly.
 */
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
        // generateMessageTwo blanks the body of a forward and reads the forwarded text instead.
        body = if (subData.forwardContext != null) "" else messageText,
        mentions = subData.mentions,
        translateData = subData.translateData,
    )
}

/**
 * Decides whether [input] should be auto-translated into [target], and claims it in [attempted] so
 * no other scenario enqueues the same message twice.
 *
 * The claim is a `Set.add` on a concurrent set: check-and-claim in one atomic step, never
 * "contains then add". Anything this function returns has therefore been reserved by this caller.
 */
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

/** One attempt per message per target, for the lifetime of the conversation page. */
internal fun attemptKey(messageId: String, target: TranslateLang): String = "$messageId#${target.name}"

/**
 * True when the message already carries a translation the user chose — in EITHER language.
 *
 * Auto-translate must never overwrite or shadow a manual choice: a message the user translated into
 * English and then switched off must not reappear in Chinese because the conversation target is zh.
 */
internal fun hasAnyCachedTranslation(data: TranslateData?): Boolean =
    data != null && (!data.translatedContentCN.isNullOrEmpty() || !data.translatedContentEN.isNullOrEmpty())

private val URL_REGEX = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)

/**
 * True when the body still carries a letter after stripping @-mention spans, URLs, digits,
 * punctuation, whitespace and emoji. Emoji are not letters, CJK characters are: "哈哈" passes,
 * "😀😀" / "12345" / a bare link do not.
 */
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
