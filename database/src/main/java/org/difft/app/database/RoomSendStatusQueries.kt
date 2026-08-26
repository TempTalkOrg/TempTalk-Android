package org.difft.app.database

import androidx.annotation.WorkerThread
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.winq.Expression
import com.tencent.wcdb.winq.Order
import com.tencent.wcdb.winq.StatementSelect
import difft.android.messageserialization.model.ROOM_SENDING_STATUS_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.MessageModel

/**
 * Room-level outgoing-send-status queries + writes, shared by:
 * - WCDBUpdateService (clear-side recompute)
 * - WcdbJobStorage (cold-start sweep, :chat)
 * - ChatNormalPaginationController (first-screen anchoring, :chat)
 *
 * Kept out of WCDBExtensions.kt on purpose: that file is already ~1450 lines, far past the 500-line
 * ceiling. Top-level `fun WCDB.xxx()` matches the existing idiom there and needs no DI change for
 * the two :chat consumers (both already hold a `WCDB`).
 *
 * All functions are blocking WCDB access — callers MUST be off the main thread.
 */

/**
 * Rows that can legitimately carry an outgoing send status: real text/attachment messages only.
 *
 * The type exclusion is NOT cosmetic. Locally created notify rows can carry `sendType == 0`
 * (`LocalMessageCreator.createEarlierMessagesExpiredMessage` builds the archive tombstone without
 * assigning sendType, so it defaults to 0 == SEND_TYPE_SENDING), and the cold-start sweep flips
 * every `sendType == 0` row to FAILED. Such rows are never resent, so a tag earned by one would
 * only clear on the room's next unrelated message change. Excluding them here — in every query
 * that decides whether a room has a failure — is what keeps an archived-only conversation out of
 * the tag entirely, including for the rows existing installs already flipped.
 *
 * Mirrors the type exclusion used by `updateRoomUnreadState` (WCDBExtensions.kt) and
 * `DBRoomStore.getUnreadMessageInfo`.
 */
private fun realOutgoingMessageScope(): Expression =
    DBMessageModel.type.notIn(MessageModel.TYPE_NOTIFY, MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER)

private fun failedOutgoingCondition(roomId: String): Expression =
    DBMessageModel.roomId.eq(roomId)
        .and(DBMessageModel.sendType.eq(MessageModel.SEND_TYPE_FAILED))
        .and(realOutgoingMessageScope())

// --- Q1: clear-side existence probe ---
/**
 * Whether [roomId] still has at least one failed real outgoing message.
 *
 * Ordered `systemShowTimestamp DESC` on purpose: it lets SQLite walk `idx_room_timestamp`
 * backwards from the newest message and stop at the first hit, so the common case (the failure is
 * recent) costs O(messages newer than the failure) instead of O(room). Only ever called for rooms
 * already flagged non-NONE (see `needsSendStatusRecompute`), which is what bounds the total cost
 * without a `sendType` index.
 */
@WorkerThread
fun WCDB.hasFailedOutgoingMessage(roomId: String): Boolean =
    message.getFirstObject(
        failedOutgoingCondition(roomId),
        DBMessageModel.systemShowTimestamp.order(Order.Desc)
    ) != null

// --- Q2: earliest failed message (first-screen anchor) ---
/**
 * Earliest failed real outgoing message of [roomId], or null.
 *
 * Returns the full model so callers get BOTH keys: `systemShowTimestamp` (window/anchor ordering,
 * matching ChatNormalPaginationController) and `timeStamp` (the key `ScrollAction.ToMessage` and
 * `jumpToMessage` match on).
 *
 * The archive tombstone (`systemShowTimestamp == 1L`, which sorts before every real message)
 * cannot be returned: it is `TYPE_NOTIFY`, excluded by [realOutgoingMessageScope]. No separate
 * sentinel check is needed — callers MUST NOT add one, or the two spellings will drift.
 */
@WorkerThread
fun WCDB.earliestFailedOutgoingMessage(roomId: String): MessageModel? =
    message.getFirstObject(
        failedOutgoingCondition(roomId),
        DBMessageModel.systemShowTimestamp.order(Order.Asc)
    )

// --- Q3: first unread message from someone else (anchor decision + divider guard) ---
/**
 * First message of [roomId] that is BOTH newer than [readPosition] AND not sent by [myId] —
 * i.e. exactly the message the "NEW MESSAGES" divider would land on
 * (`ChatMessageViewModel`: `systemShowTimestamp > readPosition && !message.isMine`).
 *
 * Deliberately NOT the same population as `ChatNormalPaginationController`'s
 * `expectedUnreadMessages`, which has no `fromWho` predicate: a failed message is necessarily mine
 * and typically newer than readPosition, so it would be that set's first element and make the
 * conservative branch fire unconditionally (the anchoring would silently no-op).
 *
 * Case sensitivity: `notEq` is an exact comparison, matching `isMine`'s derivation
 * (`Record2MessageFactory`, `globalServices.myId == record.fromWho`) and the precedent in
 * `DBRoomStore.getUnreadMessageInfo` / `queryUnreadMentions`. Deliberately NOT the
 * case-insensitive `fromWho.upper().notEq(myId.uppercase())` used by `updateRoomUnreadState` —
 * that would be a third definition of "from others".
 *
 * NO message-type filter, again to match the divider (which has none). Residual: the divider runs
 * over the RENDERED list, which drops `mapNotNull`-null rows and empty-showContent notifies, so a
 * dropped row could make the divider land on a LATER in-window message than the one returned here.
 * That degrades the anchor's precision, never its window membership — the divider guard stays sound.
 */
@WorkerThread
fun WCDB.firstUnreadFromOthersMessage(
    roomId: String,
    roomType: Int,
    readPosition: Long,
    myId: String,
): MessageModel? =
    message.getFirstObject(
        DBMessageModel.roomId.eq(roomId)
            .and(DBMessageModel.roomType.eq(roomType))
            .and(DBMessageModel.systemShowTimestamp.gt(readPosition))
            .and(DBMessageModel.fromWho.notEq(myId)),
        DBMessageModel.systemShowTimestamp.order(Order.Asc)
    )

// --- Writes ---
/**
 * Unconditional write. Legal for the SET sources (they write FAILED — see the invariant in
 * RoomSendStatus.kt) and for an escalation of an ALREADY-FLAGGED room resolved by
 * `resolveRoomSendStatus` (RoomSendStatus.kt). Never use it to write NONE: clearing must go through
 * [clearRoomSendStatusIfNoFailure] so a failure that raced in is not wiped.
 */
@WorkerThread
fun WCDB.writeRoomSendStatus(roomId: String, status: Int) {
    room.updateRow(
        arrayOf(Value(status)),
        arrayOf(DBRoomModel.sendStatus),
        DBRoomModel.roomId.eq(roomId)
    )
}

/**
 * Clear [roomId]'s aggregate to NONE, but ONLY if the message table still holds no failed real
 * outgoing row AT THE INSTANT THIS STATEMENT EXECUTES. The existence check is part of the UPDATE
 * on purpose — see below. Callers pre-filter with [hasFailedOutgoingMessage] purely to avoid
 * issuing this statement in the common case; that probe is NOT the guard.
 *
 * WHY NOT a plain `writeRoomSendStatus(roomId, NONE)`:
 * the Kotlin-side probe and the write are two statements, so a source-side escalation
 * (PushTextSendJob.updateMessage) can commit FAILED in between and be overwritten by the stale
 * NONE. That loss does NOT self-heal: the clear gate (`needsSendStatusRecompute`) then reads NONE
 * and skips every later recompute, so the conversation-list tag stays missing until the room's
 * NEXT failure.
 *
 * WHY NOT a snapshot CAS (`WHERE sendStatus = <the probed value>`) — do not "simplify" to it:
 * the source write is value-idempotent (it writes FAILED onto an already-FAILED row), so the
 * snapshot still matches and the stale clear would still apply. The guard has to re-read the
 * MESSAGE table, not the room's own column.
 *
 * CORRECTNESS: SQLite serializes writers and a single UPDATE evaluates its subquery inside its own
 * write transaction. The set sources commit the message row (S1) BEFORE the room row (S2) — that
 * ordering is load-bearing. With B = this statement the only possible orders are
 *   B < S1      -> B clears truthfully, then S2 re-flags FAILED   -> final FAILED
 *   S1 < B < S2 -> B's NOT EXISTS sees the failed row, no-op      -> final FAILED
 *   S2 < B      -> B's NOT EXISTS sees the failed row, no-op      -> final FAILED
 * i.e. no interleaving can lose a FAILED. Wrapping S1+S2 in one transaction is equally safe;
 * emitting the room write BEFORE the message write is what breaks it.
 *
 * `sendStatus != NONE` is in the WHERE for idempotence (no pointless write when a concurrent batch
 * already cleared), not for correctness.
 *
 * The subquery's `roomId` / `sendType` / `type` columns are unqualified, so SQLite resolves them
 * against the innermost FROM (`message`) even though the outer UPDATE targets `room`, which also
 * has a `roomId`. If that resolution ever changes, qualify them with `Field.table("message")`.
 */
@WorkerThread
fun WCDB.clearRoomSendStatusIfNoFailure(roomId: String) {
    val stillFailing = StatementSelect()
        .select(DBMessageModel.id)
        .from("message")
        .where(failedOutgoingCondition(roomId))
        .limit(1)
    room.updateRow(
        arrayOf(Value(ROOM_SEND_STATUS_NONE)),
        arrayOf(DBRoomModel.sendStatus),
        DBRoomModel.roomId.eq(roomId)
            .and(DBRoomModel.sendStatus.notEq(ROOM_SEND_STATUS_NONE))
            .and(Expression.notExists(stillFailing))
    )
}

/** Batch form of [writeRoomSendStatus] — same restriction: SET sources only (currently the sweep). */
@WorkerThread
fun WCDB.writeRoomSendStatusFor(roomIds: List<String>, status: Int) {
    if (roomIds.isEmpty()) return
    room.updateRow(
        arrayOf(Value(status)),
        arrayOf(DBRoomModel.sendStatus),
        DBRoomModel.roomId.`in`(*roomIds.toTypedArray())
    )
}

// --- Cold-start sweep support (tag side only — read-only) ---
/**
 * Distinct roomIds holding at least one `sendType == SENDING` real outgoing message (empty ids
 * dropped).
 *
 * Serves ONLY the tag side of the cold-start sweep in `WcdbJobStorage`: which rooms deserve the
 * conversation-list send-status tag. The message-row flip stays with that caller and keeps its
 * legacy predicate. This helper writes nothing.
 *
 * The type exclusion keeps locally created notify rows (the archive tombstone leaves `sendType` at
 * its 0 default) from earning a room a tag — see [realOutgoingMessageScope]. MUST be called BEFORE
 * the flip; afterwards no row matches the predicate any more.
 */
@WorkerThread
fun WCDB.roomIdsWithStaleSendingOutgoing(): List<String> =
    message.getOneColumnString(
        DBMessageModel.roomId,
        DBMessageModel.sendType.eq(MessageModel.SEND_TYPE_SENDING).and(realOutgoingMessageScope())
    ).filter { !it.isNullOrEmpty() }.distinct()

/**
 * All roomIds that currently hold at least one failed real outgoing message.
 * No production caller yet — reserved for a future backfill (see `roomSendStatusBackfillPlan`).
 */
@WorkerThread
fun WCDB.roomIdsWithFailedOutgoingMessage(): Set<String> =
    message.getOneColumnString(
        DBMessageModel.roomId,
        DBMessageModel.sendType.eq(MessageModel.SEND_TYPE_FAILED).and(realOutgoingMessageScope())
    ).filter { !it.isNullOrEmpty() }.toSet()

/**
 * All roomIds whose stored aggregate is not NONE.
 * No production caller yet — reserved for a future backfill (see `roomSendStatusBackfillPlan`).
 */
@WorkerThread
fun WCDB.roomIdsWithNonNoneSendStatus(): Set<String> =
    room.getOneColumnString(
        DBRoomModel.roomId,
        DBRoomModel.sendStatus.notEq(ROOM_SEND_STATUS_NONE)
    ).filter { !it.isNullOrEmpty() }.toSet()

// ─── `RoomModel.sendingStatus` — the independent "sending" aggregate ─────────────────────────
// Same machinery as the FAILED aggregate above, cloned per column ON PURPOSE: the failed-writer
// and sending-writer run concurrently, and separate columns (separate statements) make the
// set-vs-clear race structurally impossible. A generic column-parameterized helper was rejected:
// it would blur the per-column single-statement pattern the correctness argument depends on.

private fun sendingOutgoingCondition(roomId: String): Expression =
    DBMessageModel.roomId.eq(roomId)
        .and(DBMessageModel.sendType.eq(MessageModel.SEND_TYPE_SENDING))
        .and(realOutgoingMessageScope())

/**
 * Whether [roomId] still has at least one real outgoing message in Sending state.
 * DESC order for the same early-exit reason as [hasFailedOutgoingMessage] (in-flight messages
 * are almost always the newest rows). Cost filter for the clear — NOT the correctness guard.
 */
@WorkerThread
fun WCDB.hasSendingOutgoingMessage(roomId: String): Boolean =
    message.getFirstObject(
        sendingOutgoingCondition(roomId),
        DBMessageModel.systemShowTimestamp.order(Order.Desc)
    ) != null

/**
 * Set source only (PushTextSendJob.updateMessage writes ACTIVE, message row first — the same
 * load-bearing ordering as the FAILED write). Never use it to write NONE: clearing must go
 * through [clearRoomSendingStatusIfNoSending] so a send that raced in is not wiped.
 */
@WorkerThread
fun WCDB.writeRoomSendingStatus(roomId: String, status: Int) {
    room.updateRow(
        arrayOf(Value(status)),
        arrayOf(DBRoomModel.sendingStatus),
        DBRoomModel.roomId.eq(roomId)
    )
}

/**
 * Clear [roomId]'s sending aggregate, but ONLY if the message table holds no sending real
 * outgoing row AT THE INSTANT THIS STATEMENT EXECUTES. Verbatim transplant of
 * [clearRoomSendStatusIfNoFailure]'s guarded-UPDATE construction — read its KDoc for the full
 * interleaving proof (why not a plain write, why not a snapshot CAS, why message-before-room
 * ordering at the set source closes every window). The proof transfers because the set source
 * commits the message row (sendType=SENDING) strictly before the room row, exactly like the
 * FAILED path.
 */
@WorkerThread
fun WCDB.clearRoomSendingStatusIfNoSending(roomId: String) {
    val stillSending = StatementSelect()
        .select(DBMessageModel.id)
        .from("message")
        .where(sendingOutgoingCondition(roomId))
        .limit(1)
    room.updateRow(
        arrayOf(Value(ROOM_SENDING_STATUS_NONE)),
        arrayOf(DBRoomModel.sendingStatus),
        DBRoomModel.roomId.eq(roomId)
            .and(DBRoomModel.sendingStatus.notEq(ROOM_SENDING_STATUS_NONE))
            .and(Expression.notExists(stillSending))
    )
}

/**
 * All roomIds currently flagged sending. Serves the cold-start sweep's post-flip cleanup:
 * GLOBAL scope on purpose (not just the rooms the sweep flipped) so flags stale for any other
 * reason heal on the same pass. Each returned room is then cleared via the per-room guarded
 * [clearRoomSendingStatusIfNoSending], which keeps every statement individually race-safe
 * against a send racing the sweep.
 */
@WorkerThread
fun WCDB.roomIdsWithSendingStatusFlagged(): List<String> =
    room.getOneColumnString(
        DBRoomModel.roomId,
        DBRoomModel.sendingStatus.notEq(ROOM_SENDING_STATUS_NONE)
    ).filter { !it.isNullOrEmpty() }
