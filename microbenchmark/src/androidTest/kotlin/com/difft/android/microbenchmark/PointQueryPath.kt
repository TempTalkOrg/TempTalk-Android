package com.difft.android.microbenchmark

import com.tencent.wcdb.winq.Order
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.SharedContact
import org.difft.app.database.WCDB
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
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.toAttachment
import org.difft.app.database.toMention
import org.difft.app.database.toQuotedAttachment
import org.difft.app.database.toReaction
import org.difft.app.database.toSharedContactName
import org.difft.app.database.toSharedContactPhone
import org.difft.app.database.toSpeechToTextData
import org.difft.app.database.toTranslateData

/**
 * The pre-#1143 per-row point-query path, re-implemented against an injected [WCDB].
 *
 * This measures the historical query SHAPE, not the production Kotlin wrappers: the original
 * functions in `WCDBExtensions.kt` resolve their database through a Hilt/application global that
 * an instrumented benchmark cannot satisfy without a full Hilt host. Every winq expression below
 * is copied verbatim from `WCDBExtensions.kt` (`attachment()`/`mentions()`/`reactions()`/
 * `sharedContacts()`/`translateData()`/`speechToTextData()`/`quote()`/`forwardContext()`), and the
 * per-message call list mirrors the historical `Record2MessageFactory` call shape — including
 * `forwardContext()` being invoked twice (branch test + assignment). Those production functions
 * are off the hot path since PR #1143; if they are ever edited, this copy must follow.
 *
 * Exact statement counts with the benchmark corpora: plain tier 6 SELECTs per message
 * (quote/forwardContext short-circuit on a null FK; sharedContacts issues no phone query when
 * empty), uniform rich tier 24 per message.
 */
class PointQueryPath(private val wcdb: WCDB) {

    fun subDataFor(message: MessageModel) {
        wcdb.attachment.getAllObjects(
            DBAttachmentModel.messageId.eq(message.id),
            DBAttachmentModel.databaseId.order(Order.Asc),
        ).firstOrNull()?.toAttachment()

        wcdb.mention.getAllObjects(
            DBMentionModel.messageId.eq(message.id),
            DBMentionModel.databaseId.order(Order.Asc),
        ).map { it.toMention() }

        wcdb.reaction.getAllObjects(
            DBReactionModel.messageId.eq(message.id),
            DBReactionModel.databaseId.order(Order.Asc),
        ).map { it.toReaction() }

        wcdb.sharedContact.getAllObjects(
            DBSharedContactModel.messageId.eq(message.id),
            DBSharedContactModel.databaseId.order(Order.Asc),
        ).map { model ->
            val phones = wcdb.sharedContactPhone.getAllObjects(
                DBSharedContactPhoneModel.sharedContactDatabaseId.eq(model.databaseId),
                DBSharedContactPhoneModel.databaseId.order(Order.Asc),
            ).map { it.toSharedContactPhone() }
            SharedContact(model.toSharedContactName(), phones, null, null, null, null)
        }

        wcdb.translate.getFirstObject(
            DBTranslateModel.messageId.eq(message.id),
            DBTranslateModel.databaseId.order(Order.Asc),
        )?.toTranslateData()

        wcdb.speechToText.getFirstObject(
            DBSpeechToTextModel.messageId.eq(message.id),
            DBSpeechToTextModel.databaseId.order(Order.Asc),
        )?.toSpeechToTextData()

        quote(message)

        // The historical factory queried forwardContext() twice: once as the branch test,
        // once for the assignment.
        forwardContext(message)
        forwardContext(message)
    }

    private fun quote(message: MessageModel): Quote? = message.quoteDatabaseId?.let { qId ->
        wcdb.quote.getFirstObject(DBQuoteModel.databaseId.eq(qId))?.let { qm ->
            val attachments = wcdb.attachment
                .getAllObjects(
                    DBAttachmentModel.quoteModelDatabaseId.eq(qId),
                    DBAttachmentModel.databaseId.order(Order.Asc),
                )
                .map { it.toQuotedAttachment() }
            Quote(
                id = qm.id,
                author = qm.author,
                text = qm.text,
                attachments = attachments.ifEmpty { null },
            )
        }
    }

    private fun forwardContext(message: MessageModel): ForwardContext? =
        message.forwardContextDatabaseId?.let { fcId ->
            wcdb.forwardContext.getFirstObject(DBForwardContextModel.databaseId.eq(fcId))?.let { fc ->
                val forwards = wcdb.forward.getAllObjects(
                    DBForwardModel.forwardContextDatabaseId.eq(fcId)
                        .and(DBForwardModel.parentForwardModelDatabaseId.isNull),
                    DBForwardModel.databaseId.order(Order.Asc),
                ).map { fm -> toForward(fm) }
                ForwardContext(forwards, fc.isFromGroup)
            }
        }

    private fun toForward(fm: ForwardModel): Forward = Forward(
        id = fm.id,
        type = fm.type,
        isFromGroup = fm.isFromGroup,
        author = fm.author,
        text = fm.text,
        attachments = wcdb.attachment.getAllObjects(
            DBAttachmentModel.forwardModelDatabaseId.eq(fm.databaseId),
            DBAttachmentModel.databaseId.order(Order.Asc),
        ).map { it.toAttachment() },
        forwards = wcdb.forward.getAllObjects(
            DBForwardModel.parentForwardModelDatabaseId.eq(fm.databaseId),
            DBForwardModel.databaseId.order(Order.Asc),
        ).map { child -> toForward(child) },
        mentions = wcdb.mention.getAllObjects(
            DBMentionModel.forwardModelDatabaseId.eq(fm.databaseId),
            DBMentionModel.databaseId.order(Order.Asc),
        ).map { it.toMention() },
        serverTimestamp = fm.serverTimestamp,
    )
}
