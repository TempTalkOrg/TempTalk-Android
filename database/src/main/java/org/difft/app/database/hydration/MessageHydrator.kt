package org.difft.app.database.hydration

import com.difft.android.base.log.lumberjack.L
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.SharedContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hard ceiling on nested-forward depth.
 *
 * The point-query path (`ForwardModel.forwards()`) recurses without a bound and without cycle
 * detection: a corrupt `parentForwardModelDatabaseId` loop is a `StackOverflowError` today. This
 * path trades that crash for truncation — a node past the cap reports `forwards = emptyList()`,
 * which is shape-identical to a real leaf, plus one `L.w`. For any acyclic tree at or under this
 * depth (i.e. all real data) output is field-for-field identical to the point-query path.
 */
internal const val MAX_FORWARD_DEPTH = 16

/**
 * Resolves every child-table row for a window of messages in a handful of batched `IN` queries
 * instead of the ~7-9 point queries per message the per-message extensions issue.
 *
 * Contains zero winq: grouping, ordering, the nested-forward BFS and all domain assembly happen
 * here over plain model lists supplied by [MessageChildRowLoader], so the whole thing runs under a
 * fake on the host JVM.
 *
 * Failure semantics are deliberately unchanged from the point-query path: nothing is caught, a
 * loader exception propagates to the caller exactly as a point-query exception does today.
 */
@Singleton
class MessageHydrator @Inject constructor(
    private val loader: MessageChildRowLoader,
) {

    /**
     * `withContext(IO)` even though the current caller is already on IO: same-dispatcher
     * `withContext` is a context comparison, not a thread hop, and it keeps the guarantee if a
     * future caller invokes this from elsewhere.
     */
    suspend fun hydrate(messages: List<MessageModel>): MessageHydration =
        withContext(Dispatchers.IO) { hydrateBlocking(messages) }

    private fun hydrateBlocking(messages: List<MessageModel>): MessageHydration {
        if (messages.isEmpty()) return MessageHydration.EMPTY

        val startedNs = System.nanoTime()
        val stats = Stats()

        val ids = messages.map { it.id }.distinct()
        val quoteIds = messages.mapNotNull { it.quoteDatabaseId }.distinct()
        val fcIds = messages.mapNotNull { it.forwardContextDatabaseId }.distinct()

        val attachmentsByMessage = stats.load("att", ids) { loader.attachmentsByMessageId(it) }
            .groupByNotNull { it.messageId }
        val mentionsByMessage = stats.load("mention", ids) { loader.mentionsByMessageId(it) }
            .groupByNotNull { it.messageId }
        val reactionsByMessage = stats.load("reaction", ids) { loader.reactionsByMessageId(it) }
            .groupByNotNull { it.messageId }
        val translateByMessage = stats.load("translate", ids) { loader.translatesByMessageId(it) }
            .groupByNotNull { it.messageId }
        val speechToTextByMessage = stats.load("stt", ids) { loader.speechToTextsByMessageId(it) }
            .groupByNotNull { it.messageId }

        val sharedContactRows = stats.load("contact", ids) { loader.sharedContactsByMessageId(it) }
        val sharedContactsByMessage = sharedContactRows.groupByNotNull { it.messageId }
        val phonesByContact = stats
            .load("phone", sharedContactRows.map { it.databaseId.toLong() }) { loader.phonesBySharedContactId(it) }
            .groupByNotNull { it.sharedContactDatabaseId }

        val quoteRowsById = stats.load("quote", quoteIds) { loader.quotesByDatabaseId(it) }
            .associateBy { it.databaseId.toLong() }
        val quoteAttachmentsByQuote = stats.load("quoteAtt", quoteIds) { loader.attachmentsByQuoteId(it) }
            .groupByNotNull { it.quoteModelDatabaseId }

        val forwardContextRowsById = stats.load("fwdCtx", fcIds) { loader.forwardContextsByDatabaseId(it) }
            .associateBy { it.databaseId.toLong() }
        val forwardTree = loadForwardTree(fcIds, stats)

        val byMessageId = messages.associate { message ->
            val subData = MessageSubData(
                attachment = attachmentsByMessage[message.id]?.firstOrNull()?.toAttachment(),
                quote = message.quoteDatabaseId?.let { quoteId ->
                    quoteRowsById[quoteId]?.let { row ->
                        Quote(
                            id = row.id,
                            author = row.author,
                            text = row.text,
                            attachments = quoteAttachmentsByQuote[quoteId]
                                .orEmpty()
                                .map { it.toQuotedAttachment() }
                                .ifEmpty { null },
                        )
                    }
                },
                forwardContext = message.forwardContextDatabaseId?.let { fcId ->
                    forwardContextRowsById[fcId]?.let { row ->
                        ForwardContext(
                            buildForwards(forwardTree.topByContext[fcId].orEmpty(), forwardTree, depth = 1),
                            row.isFromGroup,
                        )
                    }
                },
                mentions = mentionsByMessage[message.id].orEmpty().map { it.toMention() },
                reactions = reactionsByMessage[message.id].orEmpty().map { it.toReaction() },
                sharedContacts = sharedContactsByMessage[message.id].orEmpty().map { contact ->
                    SharedContact(
                        contact.toSharedContactName(),
                        phonesByContact[contact.databaseId.toLong()].orEmpty()
                            .map { it.toSharedContactPhone() },
                        null, null, null, null,
                    )
                },
                translateData = translateByMessage[message.id]?.firstOrNull()?.toTranslateData(),
                speechToTextData = speechToTextByMessage[message.id]?.firstOrNull()?.toSpeechToTextData(),
            )
            message.id to subData
        }

        L.i {
            "[MessageHydrator] msgs=${messages.size} queries=${stats.queries} " +
                "fwdDepth=${stats.forwardDepth} cost=${(System.nanoTime() - startedNs) / NANOS_PER_MILLI}ms " +
                "detail=${stats.breakdown()}"
        }
        return MessageHydration(byMessageId)
    }

    /**
     * Level-by-level fetch of the nested-forward forest, then ONE attachment query and ONE mention
     * query covering every level at once.
     */
    private fun loadForwardTree(fcIds: List<Long>, stats: Stats): ForwardTree {
        val top = stats.load("fwdTop", fcIds) { loader.topLevelForwardsByContextId(it) }
        if (top.isEmpty()) return ForwardTree.EMPTY

        val childrenByParent = LinkedHashMap<Long, List<ForwardModel>>()
        val visited = LinkedHashSet<Long>()
        top.forEach { visited.add(it.databaseId.toLong()) }

        var frontier = top.map { it.databaseId.toLong() }
        var depth = 0
        while (frontier.isNotEmpty() && depth < MAX_FORWARD_DEPTH) {
            // The visited filter is the cycle guard: a node already materialised never re-enters
            // the frontier, so a corrupt parent loop terminates instead of spinning.
            val next = stats.load("fwdChild", frontier) { loader.forwardsByParentId(it) }
                .filter { visited.add(it.databaseId.toLong()) }
            if (next.isEmpty()) {
                frontier = emptyList()
                break
            }
            childrenByParent.putAll(next.groupByNotNull { it.parentForwardModelDatabaseId })
            frontier = next.map { it.databaseId.toLong() }
            depth++
        }
        if (frontier.isNotEmpty()) {
            L.w {
                "[MessageHydrator] forward nesting hit MAX_FORWARD_DEPTH=$MAX_FORWARD_DEPTH " +
                    "contexts=${fcIds.size} truncatedNodes=${frontier.size}"
            }
        }
        stats.forwardDepth = depth

        val allForwardIds = visited.toList()
        return ForwardTree(
            topByContext = top.groupByNotNull { it.forwardContextDatabaseId },
            childrenByParent = childrenByParent,
            attachmentsByForward = stats.load("fwdAtt", allForwardIds) { loader.attachmentsByForwardId(it) }
                .groupByNotNull { it.forwardModelDatabaseId }
                .mapValues { (_, rows) -> rows.map { it.toAttachment() } },
            mentionsByForward = stats.load("fwdMention", allForwardIds) { loader.mentionsByForwardId(it) }
                .groupByNotNull { it.forwardModelDatabaseId }
                .mapValues { (_, rows) -> rows.map { it.toMention() } },
        )
    }

    /**
     * Depth-bounded rebuild. Both guards are needed: `visited` stops the BFS from re-fetching a
     * cycle, [MAX_FORWARD_DEPTH] stops THIS walk from looping over a cycle that the BFS already
     * materialised into `childrenByParent`.
     */
    private fun buildForwards(nodes: List<ForwardModel>, tree: ForwardTree, depth: Int): List<Forward> {
        if (depth > MAX_FORWARD_DEPTH) return emptyList()
        return nodes.map { node ->
            val key = node.databaseId.toLong()
            Forward(
                id = node.id,
                type = node.type,
                isFromGroup = node.isFromGroup,
                author = node.author,
                text = node.text,
                attachments = tree.attachmentsByForward[key].orEmpty(),
                forwards = buildForwards(tree.childrenByParent[key].orEmpty(), tree, depth + 1),
                mentions = tree.mentionsByForward[key].orEmpty(),
                serverTimestamp = node.serverTimestamp,
            )
        }
    }

    private class ForwardTree(
        val topByContext: Map<Long, List<ForwardModel>>,
        val childrenByParent: Map<Long, List<ForwardModel>>,
        val attachmentsByForward: Map<Long, List<Attachment>>,
        val mentionsByForward: Map<Long, List<Mention>>,
    ) {
        companion object {
            val EMPTY = ForwardTree(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
    }

    /**
     * Counters for the single exit log line. `queries` counts loader calls, not SQL statements.
     *
     * Per-loader keys and elapsed time are accumulated so the one log line can say WHERE a slow
     * hydration went, which is the split between fixed and per-row cost that a single total hides.
     */
    private class Stats(var queries: Int = 0, var forwardDepth: Int = 0) {

        // Insertion-ordered so the breakdown reads in call order; a loader that never ran is absent.
        private val loaderCosts = LinkedHashMap<String, LoaderCost>()

        fun record(loader: String, keys: Int, elapsedNs: Long) {
            val cost = loaderCosts.getOrPut(loader) { LoaderCost() }
            cost.keys += keys
            cost.elapsedNs += elapsedNs
        }

        /** `att:82/3ms,quote:4/1ms` — key counts and timings only, never row content. */
        fun breakdown(): String =
            loaderCosts.entries.joinToString(",") { (loader, cost) ->
                "$loader:${cost.keys}/${cost.elapsedNs / NANOS_PER_MILLI}ms"
            }

        private class LoaderCost(var keys: Int = 0, var elapsedNs: Long = 0)
    }

    /** Runs [query] only when there is at least one key — an empty key set issues no query. */
    private inline fun <K, R> Stats.load(
        loader: String,
        keys: List<K>,
        query: (List<K>) -> List<R>,
    ): List<R> {
        if (keys.isEmpty()) return emptyList()
        queries++
        val startedNs = System.nanoTime()
        val rows = query(keys)
        record(loader, keys.size, System.nanoTime() - startedNs)
        return rows
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000
    }
}

/**
 * Groups preserving encounter order inside each group (the loader hands rows back in
 * `databaseId ASC`, and that intra-group order participates in `ChatMessage.equals`). Rows whose
 * key is null are dropped — the `IN` predicate that produced them cannot match a NULL key.
 */
private inline fun <T, K : Any> List<T>.groupByNotNull(key: (T) -> K?): Map<K, List<T>> {
    val grouped = LinkedHashMap<K, MutableList<T>>()
    for (row in this) {
        val k = key(row) ?: continue
        grouped.getOrPut(k) { mutableListOf() }.add(row)
    }
    return grouped
}
