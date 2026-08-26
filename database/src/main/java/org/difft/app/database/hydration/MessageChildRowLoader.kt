package org.difft.app.database.hydration

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

/**
 * Raw child-row `IN` reads for batch hydration. Returns MODELS, not domain objects: the row ->
 * domain mapping is [org.difft.app.database.ChildRowMappers]' job and is shared with the point
 * queries.
 *
 * The production implementation ([WcdbMessageChildRowLoader]) is the ONLY layer here that touches
 * winq, which is what lets [MessageHydrator] — all the grouping, ordering, forward-tree BFS and
 * domain assembly — run on the host JVM under a plain fake.
 *
 * Contract every implementation owes [MessageHydrator]:
 *  - rows come back ordered by `databaseId ASC` (intra-group order participates in
 *    `ChatMessage.equals`);
 *  - an empty key list means zero rows and zero SQL;
 *  - chunking, if any, is BY KEY, never by row, so all rows of one key stay contiguous.
 */
interface MessageChildRowLoader {

    // --- L1: keyed by messageId -------------------------------------------------------------

    fun attachmentsByMessageId(ids: List<String>): List<AttachmentModel>

    fun mentionsByMessageId(ids: List<String>): List<MentionModel>

    fun reactionsByMessageId(ids: List<String>): List<ReactionModel>

    fun sharedContactsByMessageId(ids: List<String>): List<SharedContactModel>

    fun translatesByMessageId(ids: List<String>): List<TranslateModel>

    fun speechToTextsByMessageId(ids: List<String>): List<SpeechToTextModel>

    // --- L1: keyed by child databaseId ------------------------------------------------------

    fun quotesByDatabaseId(quoteIds: List<Long>): List<QuoteModel>

    fun forwardContextsByDatabaseId(fcIds: List<Long>): List<ForwardContextModel>

    // --- L2: second-level fan-out -----------------------------------------------------------

    fun attachmentsByQuoteId(quoteIds: List<Long>): List<AttachmentModel>

    fun phonesBySharedContactId(scIds: List<Long>): List<SharedContactPhoneModel>

    /** Forwards directly under a forward context (`parentForwardModelDatabaseId IS NULL`). */
    fun topLevelForwardsByContextId(fcIds: List<Long>): List<ForwardModel>

    /** One BFS level of nested forwards. */
    fun forwardsByParentId(parentIds: List<Long>): List<ForwardModel>

    fun attachmentsByForwardId(forwardIds: List<Long>): List<AttachmentModel>

    fun mentionsByForwardId(forwardIds: List<Long>): List<MentionModel>
}
