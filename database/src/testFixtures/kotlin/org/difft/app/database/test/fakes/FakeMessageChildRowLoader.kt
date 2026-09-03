package org.difft.app.database.test.fakes

import org.difft.app.database.hydration.MessageChildRowLoader
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel
import org.difft.app.database.models.SpeechToTextModel
import org.difft.app.database.models.TranslateModel
import org.difft.app.database.test.builders.ChildRowCorpus

/** One recorded loader invocation. [keys] is the exact key list the hydrator passed. */
data class LoaderCall(val method: String, val keys: List<Any>)

/**
 * In-memory [MessageChildRowLoader] over a [ChildRowCorpus].
 *
 * Reproduces the two things the production loader promises the hydrator — the `IN` predicate and
 * `ORDER BY databaseId ASC` — and nothing else. It deliberately does NOT chunk: chunking is
 * `WcdbMessageChildRowLoader`'s internal concern (`chunkKeys`) and is covered by its own case, so
 * a call here is one call and [callLog] measures what the hydrator actually asked for.
 *
 * [failures] maps a method name to a throwable it should raise instead of returning rows, for the
 * "loader exception propagates" case.
 */
class FakeMessageChildRowLoader(
    private val corpus: ChildRowCorpus = ChildRowCorpus(),
    private val failures: Map<String, Throwable> = emptyMap(),
) : MessageChildRowLoader {

    private val recorded = mutableListOf<LoaderCall>()

    /** Every invocation, in order. Empty means the hydrator issued no query at all. */
    val callLog: List<LoaderCall> get() = recorded.toList()

    fun callCount(method: String): Int = recorded.count { it.method == method }

    fun keysPassedTo(method: String): List<List<Any>> =
        recorded.filter { it.method == method }.map { it.keys }

    override fun attachmentsByMessageId(ids: List<String>): List<AttachmentModel> =
        query("attachmentsByMessageId", ids) {
            corpus.attachments.filter { it.messageId in ids }.sortedBy { it.databaseId }
        }

    override fun mentionsByMessageId(ids: List<String>): List<MentionModel> =
        query("mentionsByMessageId", ids) {
            corpus.mentions.filter { it.messageId in ids }.sortedBy { it.databaseId }
        }

    override fun reactionsByMessageId(ids: List<String>): List<ReactionModel> =
        query("reactionsByMessageId", ids) {
            corpus.reactions.filter { it.messageId in ids }.sortedBy { it.databaseId }
        }

    override fun sharedContactsByMessageId(ids: List<String>): List<SharedContactModel> =
        query("sharedContactsByMessageId", ids) {
            corpus.sharedContacts.filter { it.messageId in ids }.sortedBy { it.databaseId }
        }

    override fun translatesByMessageId(ids: List<String>): List<TranslateModel> =
        query("translatesByMessageId", ids) {
            corpus.translates.filter { it.messageId in ids }.sortedBy { it.databaseId }
        }

    override fun speechToTextsByMessageId(ids: List<String>): List<SpeechToTextModel> =
        query("speechToTextsByMessageId", ids) {
            corpus.speechToTexts.filter { it.messageId in ids }.sortedBy { it.databaseId }
        }

    override fun quotesByDatabaseId(quoteIds: List<Long>): List<QuoteModel> =
        query("quotesByDatabaseId", quoteIds) {
            corpus.quotes.filter { it.databaseId.toLong() in quoteIds }.sortedBy { it.databaseId }
        }

    override fun forwardContextsByDatabaseId(fcIds: List<Long>): List<ForwardContextModel> =
        query("forwardContextsByDatabaseId", fcIds) {
            corpus.forwardContexts.filter { it.databaseId.toLong() in fcIds }.sortedBy { it.databaseId }
        }

    override fun attachmentsByQuoteId(quoteIds: List<Long>): List<AttachmentModel> =
        query("attachmentsByQuoteId", quoteIds) {
            corpus.attachments.filter { it.quoteModelDatabaseId in quoteIds }.sortedBy { it.databaseId }
        }

    override fun phonesBySharedContactId(scIds: List<Long>): List<SharedContactPhoneModel> =
        query("phonesBySharedContactId", scIds) {
            corpus.sharedContactPhones
                .filter { it.sharedContactDatabaseId in scIds }
                .sortedBy { it.databaseId }
        }

    override fun topLevelForwardsByContextId(fcIds: List<Long>): List<ForwardModel> =
        query("topLevelForwardsByContextId", fcIds) {
            corpus.forwards
                .filter { it.forwardContextDatabaseId in fcIds && it.parentForwardModelDatabaseId == null }
                .sortedBy { it.databaseId }
        }

    override fun forwardsByParentId(parentIds: List<Long>): List<ForwardModel> =
        query("forwardsByParentId", parentIds) {
            corpus.forwards
                .filter { it.parentForwardModelDatabaseId in parentIds }
                .sortedBy { it.databaseId }
        }

    override fun attachmentsByForwardId(forwardIds: List<Long>): List<AttachmentModel> =
        query("attachmentsByForwardId", forwardIds) {
            corpus.attachments.filter { it.forwardModelDatabaseId in forwardIds }.sortedBy { it.databaseId }
        }

    override fun mentionsByForwardId(forwardIds: List<Long>): List<MentionModel> =
        query("mentionsByForwardId", forwardIds) {
            corpus.mentions.filter { it.forwardModelDatabaseId in forwardIds }.sortedBy { it.databaseId }
        }

    private fun <K : Any, R> query(method: String, keys: List<K>, rows: () -> List<R>): List<R> {
        recorded += LoaderCall(method, keys.toList())
        failures[method]?.let { throw it }
        return rows()
    }
}
