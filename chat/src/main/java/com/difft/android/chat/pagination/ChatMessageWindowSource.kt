package com.difft.android.chat.pagination

import kotlinx.coroutines.flow.Flow
import org.difft.app.database.models.MessageModel

/**
 * Data source for a conversation's message window: keyset pagination queries + a debounced
 * change signal.
 *
 * Sole reason to exist: it confines WCDB winq `Expression` construction to one implementation
 * class, so [com.difft.android.chat.ChatNormalPaginationController]'s constructor and every one
 * of its members is free of `com.tencent.wcdb.*` types. Static initialization of any winq type
 * runs `System.loadLibrary("WCDB")`, and that .so only ships for Android ABIs — the host JVM
 * (Robolectric included) cannot load it, which is what made the controller untestable.
 *
 * Every method is BLOCKING and non-suspend, byte-for-byte the same call shape as the
 * `wcdb.message.getXxx(...)` calls it replaces; thread affinity is still decided by the caller's
 * `withContext(Dispatchers.IO)`. This seam changes no scheduling semantics.
 *
 * Ordering key is always `systemShowTimestamp`. `limit` means SQL LIMIT — applied AFTER ordering.
 *
 * The four boundary flavours (`lt` / `le` / `gt` / `ge`) stay four separate methods and must never
 * be folded into an `inclusive: Boolean`: that would hide "which call site uses which boundary"
 * inside a parameter, and the failure-anchored first screen uses `ge` while the read-position
 * back-fill uses `le`.
 */
interface ChatMessageWindowSource {

    /** The room's readPosition + sendStatus, one row two columns. Null when the room has no row. */
    fun roomAnchors(): RoomAnchors?

    /** Earliest failed outgoing message of this conversation. */
    fun earliestFailedOutgoing(): MessageModel?

    /** Earliest message with `systemShowTimestamp > readPosition` and `fromWho != myId`. */
    fun firstUnreadFromOthers(readPosition: Long, myId: String): MessageModel?

    /** COUNT of rows with `systemShowTimestamp` **<** [ts]. */
    fun countOlderThan(ts: Long): Int

    /** COUNT of rows with `systemShowTimestamp` **>** [ts]. */
    fun countNewerThan(ts: Long): Int

    /** `systemShowTimestamp` **>** [ts], **ascending**, LIMIT [limit]. */
    fun newerThan(ts: Long, limit: Long): List<MessageModel>

    /** `systemShowTimestamp` **>=** [ts], **ascending**, LIMIT [limit]. */
    fun atOrNewerThan(ts: Long, limit: Long): List<MessageModel>

    /** `systemShowTimestamp` **<** [ts], **descending**, LIMIT [limit]. */
    fun olderThan(ts: Long, limit: Long): List<MessageModel>

    /** `systemShowTimestamp` **<=** [ts], **descending**, LIMIT [limit]. */
    fun atOrOlderThan(ts: Long, limit: Long): List<MessageModel>

    /** Whole conversation, **descending**, LIMIT [limit]. */
    fun latest(limit: Long): List<MessageModel>

    /** `id` of the newest row by `systemShowTimestamp`; null for an empty conversation. */
    fun latestMessageId(): String?

    /** Exact match on `timeStamp` (**not** systemShowTimestamp). Jump entry point only. */
    fun byTimeStamp(timeStamp: Long): MessageModel?

    /**
     * `systemShowTimestamp >= [fromTs]`, or a closed `between` when [toTs] is non-null.
     * **Ascending**, **no LIMIT**.
     */
    fun ascendingFrom(fromTs: Long, toTs: Long?): List<MessageModel>

    /**
     * Debounced signal of MESSAGE-type changes in this room; each element means "re-query the
     * window".
     *
     * COLD flow: every `collect` re-runs filter + sampling, matching the pre-seam behaviour where
     * each `observerMessagesChanges()` rebuilt the whole chain.
     */
    val messageChanges: Flow<Unit>
}

/** Carrier for [ChatMessageWindowSource.roomAnchors], replacing `Array<com.tencent.wcdb.base.Value>`. */
data class RoomAnchors(val readPosition: Long, val sendStatus: Int)
