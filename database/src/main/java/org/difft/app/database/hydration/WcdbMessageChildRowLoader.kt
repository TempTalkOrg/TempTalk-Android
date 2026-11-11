package org.difft.app.database.hydration

import com.tencent.wcdb.winq.Order
import org.difft.app.database.WCDB
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBForwardContextModel
import org.difft.app.database.models.DBForwardModel
import org.difft.app.database.models.DBMentionModel
import org.difft.app.database.models.DBQuoteModel
import org.difft.app.database.models.DBReactionModel
import org.difft.app.database.models.DBSharedContactModel
import org.difft.app.database.models.DBSharedContactPhoneModel
import org.difft.app.database.models.DBSpeechToTextModel
import org.difft.app.database.models.DBTranslateModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel
import org.difft.app.database.models.SpeechToTextModel
import org.difft.app.database.models.TranslateModel
import javax.inject.Inject
import javax.inject.Singleton










@Singleton
class WcdbMessageChildRowLoader @Inject constructor(
    private val wcdb: WCDB,
) : MessageChildRowLoader {

    override fun attachmentsByMessageId(ids: List<String>): List<AttachmentModel> =
        chunkKeys(ids) { chunk ->
            wcdb.attachment.getAllObjects(
                DBAttachmentModel.messageId.`in`(chunk),
                DBAttachmentModel.databaseId.order(Order.Asc),
            )
        }

    override fun mentionsByMessageId(ids: List<String>): List<MentionModel> =
        chunkKeys(ids) { chunk ->
            wcdb.mention.getAllObjects(
                DBMentionModel.messageId.`in`(chunk),
                DBMentionModel.databaseId.order(Order.Asc),
            )
        }

    override fun reactionsByMessageId(ids: List<String>): List<ReactionModel> =
        chunkKeys(ids) { chunk ->
            wcdb.reaction.getAllObjects(
                DBReactionModel.messageId.`in`(chunk),
                DBReactionModel.databaseId.order(Order.Asc),
            )
        }

    override fun sharedContactsByMessageId(ids: List<String>): List<SharedContactModel> =
        chunkKeys(ids) { chunk ->
            wcdb.sharedContact.getAllObjects(
                DBSharedContactModel.messageId.`in`(chunk),
                DBSharedContactModel.databaseId.order(Order.Asc),
            )
        }

    override fun translatesByMessageId(ids: List<String>): List<TranslateModel> =
        chunkKeys(ids) { chunk ->
            wcdb.translate.getAllObjects(
                DBTranslateModel.messageId.`in`(chunk),
                DBTranslateModel.databaseId.order(Order.Asc),
            )
        }

    override fun speechToTextsByMessageId(ids: List<String>): List<SpeechToTextModel> =
        chunkKeys(ids) { chunk ->
            wcdb.speechToText.getAllObjects(
                DBSpeechToTextModel.messageId.`in`(chunk),
                DBSpeechToTextModel.databaseId.order(Order.Asc),
            )
        }

    override fun quotesByDatabaseId(quoteIds: List<Long>): List<QuoteModel> =
        chunkKeys(quoteIds) { chunk ->
            wcdb.quote.getAllObjects(
                DBQuoteModel.databaseId.`in`(chunk),
                DBQuoteModel.databaseId.order(Order.Asc),
            )
        }

    override fun forwardContextsByDatabaseId(fcIds: List<Long>): List<ForwardContextModel> =
        chunkKeys(fcIds) { chunk ->
            wcdb.forwardContext.getAllObjects(
                DBForwardContextModel.databaseId.`in`(chunk),
                DBForwardContextModel.databaseId.order(Order.Asc),
            )
        }

    override fun attachmentsByQuoteId(quoteIds: List<Long>): List<AttachmentModel> =
        chunkKeys(quoteIds) { chunk ->
            wcdb.attachment.getAllObjects(
                DBAttachmentModel.quoteModelDatabaseId.`in`(chunk),
                DBAttachmentModel.databaseId.order(Order.Asc),
            )
        }

    override fun phonesBySharedContactId(scIds: List<Long>): List<SharedContactPhoneModel> =
        chunkKeys(scIds) { chunk ->
            wcdb.sharedContactPhone.getAllObjects(
                DBSharedContactPhoneModel.sharedContactDatabaseId.`in`(chunk),
                DBSharedContactPhoneModel.databaseId.order(Order.Asc),
            )
        }

    override fun topLevelForwardsByContextId(fcIds: List<Long>): List<ForwardModel> =
        chunkKeys(fcIds) { chunk ->
            wcdb.forward.getAllObjects(
                DBForwardModel.forwardContextDatabaseId.`in`(chunk)
                    .and(DBForwardModel.parentForwardModelDatabaseId.isNull),
                DBForwardModel.databaseId.order(Order.Asc),
            )
        }

    override fun forwardsByParentId(parentIds: List<Long>): List<ForwardModel> =
        chunkKeys(parentIds) { chunk ->
            wcdb.forward.getAllObjects(
                DBForwardModel.parentForwardModelDatabaseId.`in`(chunk),
                DBForwardModel.databaseId.order(Order.Asc),
            )
        }

    override fun attachmentsByForwardId(forwardIds: List<Long>): List<AttachmentModel> =
        chunkKeys(forwardIds) { chunk ->
            wcdb.attachment.getAllObjects(
                DBAttachmentModel.forwardModelDatabaseId.`in`(chunk),
                DBAttachmentModel.databaseId.order(Order.Asc),
            )
        }

    override fun mentionsByForwardId(forwardIds: List<Long>): List<MentionModel> =
        chunkKeys(forwardIds) { chunk ->
            wcdb.mention.getAllObjects(
                DBMentionModel.forwardModelDatabaseId.`in`(chunk),
                DBMentionModel.databaseId.order(Order.Asc),
            )
        }
}




internal const val IN_CHUNK_SIZE = 500










internal fun <K, R> chunkKeys(keys: List<K>, query: (List<K>) -> List<R>): List<R> = when {
    keys.isEmpty() -> emptyList()
    keys.size <= IN_CHUNK_SIZE -> query(keys)
    else -> keys.chunked(IN_CHUNK_SIZE).flatMap(query)
}
