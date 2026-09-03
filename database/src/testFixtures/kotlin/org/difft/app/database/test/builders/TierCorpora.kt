package org.difft.app.database.test.builders

/** Plain tier: [count] text-only messages, every child table empty. */
fun plainCorpus(count: Int): ChildRowCorpus = ChildRowCorpus(messages = buildMessageSequence(count))

/**
 * Uniform rich tier: every message carries 1 attachment, a quote with 1 quoted attachment,
 * 3 reactions, and a 2-level nested forward (context -> top forward -> child forward).
 *
 * Uniformity is the point (unlike [ChildRowCorpus.rich], which is 12 messages of per-message
 * variety): the per-message SQL cost is identical across the window, so per-tier statement
 * counts are exact — the pre-#1143 point-query path issues 24 SELECTs per message, batched
 * hydration 14 for the whole window — instead of a blend over a mixed corpus.
 */
fun uniformRichCorpus(count: Int): ChildRowCorpus {
    val messages = buildMessageSequence(count)
    messages.forEachIndexed { index, message ->
        message.quoteDatabaseId = (index + 1).toLong()
        message.forwardContextDatabaseId = (index + 1).toLong()
    }
    return ChildRowCorpus(
        messages = messages,
        attachments = messages.flatMapIndexed { index, message ->
            listOf(
                buildAttachmentModel(databaseId = index + 1, messageId = message.id),
                buildAttachmentModel(
                    databaseId = count + index + 1,
                    quoteModelDatabaseId = (index + 1).toLong(),
                ),
            )
        },
        reactions = messages.flatMapIndexed { index, message ->
            (0 until 3).map { r ->
                buildReactionModel(databaseId = index * 3 + r + 1, messageId = message.id)
            }
        },
        quotes = List(count) { index -> buildQuoteModel(databaseId = index + 1) },
        forwardContexts = List(count) { index -> buildForwardContextModel(databaseId = index + 1) },
        forwards = (0 until count).flatMap { index ->
            val topId = index * 2 + 1
            listOf(
                buildForwardModel(
                    databaseId = topId,
                    forwardContextDatabaseId = (index + 1).toLong(),
                ),
                buildForwardModel(
                    databaseId = topId + 1,
                    parentForwardModelDatabaseId = topId.toLong(),
                ),
            )
        },
    )
}
