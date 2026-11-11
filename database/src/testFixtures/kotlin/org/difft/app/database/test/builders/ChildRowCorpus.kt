package org.difft.app.database.test.builders

import org.difft.app.database.hydration.MessageSubData
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel
import org.difft.app.database.models.SpeechToTextModel
import org.difft.app.database.models.TranslateModel
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.SharedContact
import org.difft.app.database.toAttachment
import org.difft.app.database.toMention
import org.difft.app.database.toQuotedAttachment
import org.difft.app.database.toReaction
import org.difft.app.database.toSharedContactName
import org.difft.app.database.toSharedContactPhone
import org.difft.app.database.toSpeechToTextData
import org.difft.app.database.toTranslateData






@Suppress("LongParameterList")
fun buildAttachmentModel(
    databaseId: Int,
    messageId: String? = null,
    forwardModelDatabaseId: Long? = null,
    quoteModelDatabaseId: Long? = null,
    id: String? = "att-$databaseId",
    authorityId: Long? = databaseId.toLong(),
    contentType: String? = "image/jpeg",
    fileName: String? = "file-$databaseId",
    thumbnail: ByteArray? = ByteArray(0),
    flags: Int = 0,
    totalTime: Long? = null,
    amplitudes: String? = null,
): AttachmentModel = AttachmentModel().apply {
    this.databaseId = databaseId
    this.messageId = messageId
    this.forwardModelDatabaseId = forwardModelDatabaseId
    this.quoteModelDatabaseId = quoteModelDatabaseId
    this.id = id
    this.authorityId = authorityId
    this.contentType = contentType
    this.fileName = fileName
    this.thumbnail = thumbnail
    this.flags = flags
    this.totalTime = totalTime
    this.amplitudes = amplitudes
}

fun buildMentionModel(
    databaseId: Int,
    messageId: String? = null,
    forwardModelDatabaseId: Long? = null,
    start: Int = 0,
    length: Int = 1,
    uid: String? = "uid-$databaseId",
    type: Int = 0,
): MentionModel = MentionModel().apply {
    this.databaseId = databaseId
    this.messageId = messageId
    this.forwardModelDatabaseId = forwardModelDatabaseId
    this.start = start
    this.length = length
    this.uid = uid
    this.type = type
}

fun buildReactionModel(
    databaseId: Int,
    messageId: String,
    emoji: String = "emoji-$databaseId",
    uid: String? = "uid-$databaseId",
    timeStamp: Long = databaseId.toLong(),
): ReactionModel = ReactionModel().apply {
    this.databaseId = databaseId
    this.messageId = messageId
    this.emoji = emoji
    this.uid = uid
    this.timeStamp = timeStamp
}

fun buildSharedContactModel(
    databaseId: Int,
    messageId: String,
    givenName: String? = "given-$databaseId",
    familyName: String? = "family-$databaseId",
    displayName: String? = "display-$databaseId",
): SharedContactModel = SharedContactModel().apply {
    this.databaseId = databaseId
    this.messageId = messageId
    this.givenName = givenName
    this.familyName = familyName
    this.displayName = displayName
}

fun buildSharedContactPhoneModel(
    databaseId: Int,
    sharedContactDatabaseId: Long,
    phoneNumber: String? = "phone-$databaseId",
    phoneNumberType: Int = 0,
    phoneNumberLabel: String? = null,
): SharedContactPhoneModel = SharedContactPhoneModel().apply {
    this.databaseId = databaseId
    this.sharedContactDatabaseId = sharedContactDatabaseId
    this.phoneNumber = phoneNumber
    this.phoneNumberType = phoneNumberType
    this.phoneNumberLabel = phoneNumberLabel
}

fun buildTranslateModel(
    databaseId: Int,
    messageId: String,
    translateStatus: Int = 2,
    translatedContentCN: String? = "cn-$databaseId",
    translatedContentEN: String? = "en-$databaseId",
): TranslateModel = TranslateModel().apply {
    this.databaseId = databaseId
    this.messageId = messageId
    this.translateStatus = translateStatus
    this.translatedContentCN = translatedContentCN
    this.translatedContentEN = translatedContentEN
}

fun buildSpeechToTextModel(
    databaseId: Int,
    messageId: String,
    convertStatus: Int = 2,
    speechToTextContent: String? = "stt-$databaseId",
): SpeechToTextModel = SpeechToTextModel().apply {
    this.databaseId = databaseId
    this.messageId = messageId
    this.convertStatus = convertStatus
    this.speechToTextContent = speechToTextContent
}

fun buildQuoteModel(
    databaseId: Int,
    id: Long = databaseId.toLong(),
    author: String = "author-$databaseId",
    text: String = "quoted-$databaseId",
): QuoteModel = QuoteModel().apply {
    this.databaseId = databaseId
    this.id = id
    this.author = author
    this.text = text
}

fun buildForwardContextModel(
    databaseId: Int,
    isFromGroup: Boolean = false,
): ForwardContextModel = ForwardContextModel().apply {
    this.databaseId = databaseId
    this.isFromGroup = isFromGroup
}

@Suppress("LongParameterList")
fun buildForwardModel(
    databaseId: Int,
    forwardContextDatabaseId: Long? = null,
    parentForwardModelDatabaseId: Long? = null,
    id: Long = databaseId.toLong(),
    type: Int = 0,
    isFromGroup: Boolean = false,
    author: String = "author-$databaseId",
    text: String = "forward-$databaseId",
    serverTimestamp: Long = databaseId.toLong(),
): ForwardModel = ForwardModel().apply {
    this.databaseId = databaseId
    this.forwardContextDatabaseId = forwardContextDatabaseId
    this.parentForwardModelDatabaseId = parentForwardModelDatabaseId
    this.id = id
    this.type = type
    this.isFromGroup = isFromGroup
    this.author = author
    this.text = text
    this.serverTimestamp = serverTimestamp
}











@Suppress("LongParameterList")
class ChildRowCorpus(
    val messages: List<MessageModel> = emptyList(),
    val attachments: List<AttachmentModel> = emptyList(),
    val mentions: List<MentionModel> = emptyList(),
    val reactions: List<ReactionModel> = emptyList(),
    val sharedContacts: List<SharedContactModel> = emptyList(),
    val sharedContactPhones: List<SharedContactPhoneModel> = emptyList(),
    val translates: List<TranslateModel> = emptyList(),
    val speechToTexts: List<SpeechToTextModel> = emptyList(),
    val quotes: List<QuoteModel> = emptyList(),
    val forwardContexts: List<ForwardContextModel> = emptyList(),
    val forwards: List<ForwardModel> = emptyList(),
) {

    fun referenceSubDataFor(message: MessageModel): MessageSubData = MessageSubData(
        attachment = attachments
            .filter { it.messageId == message.id }
            .sortedBy { it.databaseId }
            .firstOrNull()
            ?.toAttachment(),
        quote = message.quoteDatabaseId?.let { quoteId ->
            quotes.firstOrNull { it.databaseId.toLong() == quoteId }?.let { row ->
                Quote(
                    id = row.id,
                    author = row.author,
                    text = row.text,
                    attachments = attachments
                        .filter { it.quoteModelDatabaseId == quoteId }
                        .sortedBy { it.databaseId }
                        .map { it.toQuotedAttachment() }
                        .ifEmpty { null },
                )
            }
        },
        forwardContext = message.forwardContextDatabaseId?.let { fcId ->
            forwardContexts.firstOrNull { it.databaseId.toLong() == fcId }?.let { row ->
                val top = forwards
                    .filter { it.forwardContextDatabaseId == fcId && it.parentForwardModelDatabaseId == null }
                    .sortedBy { it.databaseId }
                ForwardContext(top.map { referenceForward(it) }, row.isFromGroup)
            }
        },
        mentions = mentions
            .filter { it.messageId == message.id }
            .sortedBy { it.databaseId }
            .map { it.toMention() },
        reactions = reactions
            .filter { it.messageId == message.id }
            .sortedBy { it.databaseId }
            .map { it.toReaction() },
        sharedContacts = sharedContacts
            .filter { it.messageId == message.id }
            .sortedBy { it.databaseId }
            .map { contact ->
                SharedContact(
                    contact.toSharedContactName(),
                    sharedContactPhones
                        .filter { it.sharedContactDatabaseId == contact.databaseId.toLong() }
                        .sortedBy { it.databaseId }
                        .map { it.toSharedContactPhone() },
                    null, null, null, null,
                )
            },
        translateData = translates
            .filter { it.messageId == message.id }
            .sortedBy { it.databaseId }
            .firstOrNull()
            ?.toTranslateData(),
        speechToTextData = speechToTexts
            .filter { it.messageId == message.id }
            .sortedBy { it.databaseId }
            .firstOrNull()
            ?.toSpeechToTextData(),
    )

    
    private fun referenceForward(model: ForwardModel): Forward = Forward(
        id = model.id,
        type = model.type,
        isFromGroup = model.isFromGroup,
        author = model.author,
        text = model.text,
        attachments = attachments
            .filter { it.forwardModelDatabaseId == model.databaseId.toLong() }
            .sortedBy { it.databaseId }
            .map { it.toAttachment() },
        forwards = forwards
            .filter { it.parentForwardModelDatabaseId == model.databaseId.toLong() }
            .sortedBy { it.databaseId }
            .map { referenceForward(it) },
        mentions = mentions
            .filter { it.forwardModelDatabaseId == model.databaseId.toLong() }
            .sortedBy { it.databaseId }
            .map { it.toMention() },
        serverTimestamp = model.serverTimestamp,
    )

    companion object {

        





        @Suppress("LongMethod")
        fun rich(): ChildRowCorpus {
            val messages = mutableListOf<MessageModel>()
            val attachments = mutableListOf<AttachmentModel>()
            val mentions = mutableListOf<MentionModel>()
            val reactions = mutableListOf<ReactionModel>()
            val sharedContacts = mutableListOf<SharedContactModel>()
            val phones = mutableListOf<SharedContactPhoneModel>()
            val translates = mutableListOf<TranslateModel>()
            val speechToTexts = mutableListOf<SpeechToTextModel>()
            val quotes = mutableListOf<QuoteModel>()
            val forwardContexts = mutableListOf<ForwardContextModel>()
            val forwards = mutableListOf<ForwardModel>()

            fun message(
                id: String,
                index: Int,
                quoteDatabaseId: Long? = null,
                forwardContextDatabaseId: Long? = null,
            ) = buildMessageModel(id = id, systemShowTimestamp = 1_000L + index * 1_000L).also {
                it.quoteDatabaseId = quoteDatabaseId
                it.forwardContextDatabaseId = forwardContextDatabaseId
                messages += it
            }

            
            message("m-plain", 0)

            
            message("m-attach", 1)
            attachments += buildAttachmentModel(databaseId = 71, messageId = "m-attach")
            attachments += buildAttachmentModel(databaseId = 31, messageId = "m-attach")

            
            message("m-mention", 2)
            mentions += buildMentionModel(databaseId = 93, messageId = "m-mention")
            mentions += buildMentionModel(databaseId = 41, messageId = "m-mention")
            mentions += buildMentionModel(databaseId = 62, messageId = "m-mention")

            
            message("m-reaction", 3)
            reactions += buildReactionModel(databaseId = 12, messageId = "m-reaction")
            reactions += buildReactionModel(databaseId = 11, messageId = "m-reaction")

            
            message("m-derived", 4)
            translates += buildTranslateModel(databaseId = 5, messageId = "m-derived")
            speechToTexts += buildSpeechToTextModel(databaseId = 6, messageId = "m-derived")

            
            quotes += buildQuoteModel(databaseId = 5)
            message("m-quote", 5, quoteDatabaseId = 5L)
            attachments += buildAttachmentModel(
                databaseId = 101, quoteModelDatabaseId = 5L, thumbnail = ByteArray(0),
            )
            attachments += buildAttachmentModel(
                databaseId = 102, quoteModelDatabaseId = 5L, thumbnail = byteArrayOf(1, 2, 3),
            )

            
            quotes += buildQuoteModel(databaseId = 6)
            message("m-quote-bare", 6, quoteDatabaseId = 6L)

            
            message("m-contacts", 7)
            sharedContacts += buildSharedContactModel(databaseId = 21, messageId = "m-contacts")
            sharedContacts += buildSharedContactModel(databaseId = 22, messageId = "m-contacts")
            phones += buildSharedContactPhoneModel(databaseId = 33, sharedContactDatabaseId = 21L)
            phones += buildSharedContactPhoneModel(databaseId = 31, sharedContactDatabaseId = 21L)
            phones += buildSharedContactPhoneModel(databaseId = 32, sharedContactDatabaseId = 21L)
            message("m-contacts-nophone", 8)
            sharedContacts += buildSharedContactModel(databaseId = 23, messageId = "m-contacts-nophone")

            
            forwardContexts += buildForwardContextModel(databaseId = 8, isFromGroup = true)
            message("m-forward-flat", 9, forwardContextDatabaseId = 8L)
            listOf(203, 201, 202).forEach { fwdId ->
                forwards += buildForwardModel(databaseId = fwdId, forwardContextDatabaseId = 8L)
                attachments += buildAttachmentModel(
                    databaseId = 300 + fwdId, forwardModelDatabaseId = fwdId.toLong(),
                )
                mentions += buildMentionModel(
                    databaseId = 400 + fwdId, forwardModelDatabaseId = fwdId.toLong(),
                )
            }

            
            forwardContexts += buildForwardContextModel(databaseId = 9)
            message("m-forward-nested", 10, forwardContextDatabaseId = 9L)
            forwards += buildForwardModel(databaseId = 501, forwardContextDatabaseId = 9L)
            forwards += buildForwardModel(databaseId = 502, parentForwardModelDatabaseId = 501L)
            forwards += buildForwardModel(databaseId = 503, parentForwardModelDatabaseId = 502L)
            listOf(501, 502, 503).forEach { fwdId ->
                attachments += buildAttachmentModel(
                    databaseId = 600 + fwdId - 500, forwardModelDatabaseId = fwdId.toLong(),
                )
            }

            
            quotes += buildQuoteModel(databaseId = 7)
            forwardContexts += buildForwardContextModel(databaseId = 10)
            message("m-everything", 11, quoteDatabaseId = 7L, forwardContextDatabaseId = 10L)
            attachments += buildAttachmentModel(databaseId = 700, messageId = "m-everything")
            attachments += buildAttachmentModel(databaseId = 701, quoteModelDatabaseId = 7L)
            mentions += buildMentionModel(databaseId = 702, messageId = "m-everything")
            reactions += buildReactionModel(databaseId = 703, messageId = "m-everything")
            translates += buildTranslateModel(databaseId = 704, messageId = "m-everything")
            speechToTexts += buildSpeechToTextModel(databaseId = 705, messageId = "m-everything")
            sharedContacts += buildSharedContactModel(databaseId = 706, messageId = "m-everything")
            phones += buildSharedContactPhoneModel(databaseId = 707, sharedContactDatabaseId = 706L)
            forwards += buildForwardModel(databaseId = 708, forwardContextDatabaseId = 10L)
            attachments += buildAttachmentModel(databaseId = 709, forwardModelDatabaseId = 708L)

            return ChildRowCorpus(
                messages = messages,
                attachments = attachments,
                mentions = mentions,
                reactions = reactions,
                sharedContacts = sharedContacts,
                sharedContactPhones = phones,
                translates = translates,
                speechToTexts = speechToTexts,
                quotes = quotes,
                forwardContexts = forwardContexts,
                forwards = forwards,
            )
        }
    }
}
