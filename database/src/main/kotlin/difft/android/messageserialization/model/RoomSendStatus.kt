package difft.android.messageserialization.model

/**
 * Room-level aggregate outgoing-send signal for `RoomModel.sendStatus`.
 *
 * Semantics are AGGREGATE ("this conversation contains a message in that state"), not
 * "state of the last message" — a later successful message must not mask an earlier failure.
 *
 * Numeric value doubles as priority (higher wins when aggregating).
 *
 * NONE MUST stay 0: `RoomModel.sendStatus` ships with `@WCDBDefault(intValue = 0)`, so every
 * pre-existing row reads back 0 after the upgrade. Reusing `SendType`'s domain (where 0 ==
 * Sending) would make every historical conversation render as "sending".
 *
 * INVARIANT: every code path that writes `message.sendType = SEND_TYPE_FAILED` MUST also write
 * `ROOM_SEND_STATUS_FAILED` for that room in the same path. The recompute in WCDBUpdateService
 * only ever CLEARS (see [needsSendStatusRecompute]) — it never discovers a new failure. Current
 * writers: PushTextSendJob.updateMessage, WcdbJobStorage sweep. Pre-existing failed messages are
 * not backfilled (see [roomSendStatusBackfillPlan] for the reusable building block if that changes).
 */
const val ROOM_SEND_STATUS_NONE = 0

/** Reserved: an outgoing message is in flight. Not produced yet — see [aggregateRoomSendStatus]. */
const val ROOM_SEND_STATUS_SENDING = 1

/** At least one real outgoing message in this room failed to send. */
const val ROOM_SEND_STATUS_FAILED = 2

/**
 * Aggregate the room-level signal. Equivalent to max-of-priorities, spelled as a `when` for
 * readability.
 *
 * [hasSending] is wired but never true this release: no caller queries for in-flight messages
 * (that would need a second EXISTS on the write path, which the gated-clear design deliberately
 * avoids). Enabling a "sending" tag later needs a SENDING set-source honouring the invariant —
 * the schema and this function need no change.
 */
fun aggregateRoomSendStatus(hasFailed: Boolean, hasSending: Boolean = false): Int = when {
    hasFailed -> ROOM_SEND_STATUS_FAILED
    hasSending -> ROOM_SEND_STATUS_SENDING
    else -> ROOM_SEND_STATUS_NONE
}

/**
 * Gate for the clear-side recompute: only rooms already flagged can need clearing (the set side is
 * written at the source, so set-side discovery is unnecessary). This is what keeps the write path
 * at ZERO extra queries for the overwhelming majority of rooms and is why no `sendType` index is
 * required.
 *
 * This gate is a COST filter, not a correctness gate: a stale read in either direction is safe,
 * because the clear itself re-checks the message table inside its own UPDATE statement (see
 * `WCDB.clearRoomSendStatusIfNoFailure`).
 */
fun needsSendStatusRecompute(storedStatus: Int): Boolean = storedStatus != ROOM_SEND_STATUS_NONE

/**
 * Target value for a flagged room after a message change, or `null` when no write is needed.
 *
 * Never escalates NONE -> FAILED — discovering a new failure is the set-source's job. The
 * early return, not the caller's [needsSendStatusRecompute] gate, is what enforces that: the gate
 * is only a cost filter (a stale read in either direction is safe), so if the invariant lived
 * solely at the call site, loosening the gate would silently turn the recompute into a set source.
 *
 * Escalating an already-flagged SENDING room to FAILED is NOT the forbidden transition — the room
 * is already flagged, so no new tag is being discovered.
 */
fun resolveRoomSendStatus(storedStatus: Int, hasFailed: Boolean): Int? {
    if (storedStatus == ROOM_SEND_STATUS_NONE) return null
    val target = aggregateRoomSendStatus(hasFailed)
    return target.takeIf { it != storedStatus }
}

/**
 * One-shot backfill plan: which rooms to flag and which to clear. Pure set arithmetic so the
 * upgrade path is unit-testable without WCDB.
 *
 * @return (toFlagFailed, toClear)
 */
fun roomSendStatusBackfillPlan(
    roomsWithFailedMessage: Set<String>,
    roomsCurrentlyFlagged: Set<String>,
): Pair<List<String>, List<String>> =
    (roomsWithFailedMessage - roomsCurrentlyFlagged).toList() to
        (roomsCurrentlyFlagged - roomsWithFailedMessage).toList()
