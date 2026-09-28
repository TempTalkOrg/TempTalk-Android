package com.difft.android.migration.v2.readtime

import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralReadPosition

/**
 * A conversation's own read ladder plus its authoritative watermark — the compact form every
 * platform carries instead of a per-message read time.
 *
 * [rows] are canonical: `maxServerTime` strictly increasing, every `readAt > 0`, `readAt`
 * non-decreasing. [watermark] may sit above the last row; messages in that gap are read and take the
 * watermark's effective `readAt`.
 *
 * Pure Kotlin on purpose: no Android, no WCDB, no logging. Export builds one with
 * [ReadLadderBuilder], import rebuilds one with [fromWire], and both then ask [derivedStart] what a
 * message's expiry anchor would be without an explicit exception.
 */
data class ReadLadder(
    val rows: List<NeutralReadPosition>,
    val watermark: NeutralReadPosition?,
) {

    /**
     * The ladder read time of a message at [serverAt]: the smallest `readAt` among the rows covering
     * it. Canonical rows make that the first covering row; the minimum is taken anyway so a
     * non-canonical input degrades to the same answer every platform's archive query gives. Null when
     * no row covers the message.
     */
    fun lookup(serverAt: Long): Long? =
        rows.asSequence().filter { it.maxServerTime >= serverAt }.minOfOrNull { it.readAt }

    /**
     * The watermark's usable read moment: its `readAt`, or its `maxServerTime` when the source had no
     * read clock to record. Null when the conversation was never read.
     */
    fun effectiveWatermarkReadAt(): Long? =
        watermark?.let { if (it.readAt > 0) it.readAt else it.maxServerTime }

    /**
     * The expiry anchor a message carrying no explicit `expireStartedAt` gets from this ladder.
     * Outgoing anchors at its own server time; an incoming message at or below the watermark takes
     * its covering row's read time, falling back to the watermark's effective one; anything above the
     * watermark is unread and has no anchor.
     */
    fun derivedStart(direction: MessageDirection, serverAt: Long): Long? = when {
        direction == MessageDirection.OUTGOING -> serverAt
        watermark != null && serverAt <= watermark.maxServerTime ->
            lookup(serverAt) ?: effectiveWatermarkReadAt()
        else -> null
    }

    companion object {

        /** The ladder of a conversation that carried none — no rows, no watermark, no derived start. */
        val EMPTY = ReadLadder(emptyList(), null)

        /**
         * Rebuild a canonical ladder from a received [NeutralConversationLocal]. Defensive rather than
         * trusting: a peer may send rows out of order, duplicated, non-monotonic in `readAt`, with no
         * read clock, or reaching past the watermark. None of that aborts an import — it is normalized
         * here so the per-message expansion below can assume canonical rows.
         */
        fun fromWire(local: NeutralConversationLocal): ReadLadder {
            val watermark = local.readPosition
            var rows = dedupeKeepingMinReadAt(local.readPositions.sortedBy { it.maxServerTime })
            // A row with no read clock stands in its own server time, the same substitution the
            // watermark makes; one with neither is unusable and drops out. Substituting BEFORE the
            // normalization matters: a zero left in place would be the suffix minimum and pull every
            // earlier row down to its own server time, discarding read moments the peer did send.
            rows = rows.map { if (it.readAt > 0) it else it.copy(readAt = it.maxServerTime) }
                .filter { it.readAt > 0 }
            rows = suffixMin(rows)
            if (watermark != null) {
                // The watermark decides read / unread, so a row reaching past it must not hand an
                // anchor to a message the target still counts as unread.
                rows = dedupeKeepingMinReadAt(
                    rows.map {
                        if (it.maxServerTime > watermark.maxServerTime) it.copy(maxServerTime = watermark.maxServerTime) else it
                    }
                )
            }
            return ReadLadder(rows, watermark)
        }

        /** Collapse rows sharing a `maxServerTime`, keeping the smallest `readAt`. Input must be sorted. */
        internal fun dedupeKeepingMinReadAt(sorted: List<NeutralReadPosition>): List<NeutralReadPosition> {
            val out = ArrayList<NeutralReadPosition>(sorted.size)
            sorted.forEach { row ->
                val last = out.lastOrNull()
                if (last != null && last.maxServerTime == row.maxServerTime) {
                    if (row.readAt < last.readAt) out[out.size - 1] = row
                } else {
                    out.add(row)
                }
            }
            return out
        }

        /**
         * Make `readAt` non-decreasing by taking the suffix minimum from the last row backwards. The
         * lookup answer is unchanged — it is already the minimum over covering rows — but the rows
         * then satisfy the canonical form, so the first covering row is the answer.
         */
        internal fun suffixMin(sorted: List<NeutralReadPosition>): List<NeutralReadPosition> {
            if (sorted.isEmpty()) return sorted
            val out = ArrayList(sorted)
            for (i in out.size - 2 downTo 0) {
                val next = out[i + 1].readAt
                if (out[i].readAt > next) out[i] = out[i].copy(readAt = next)
            }
            return out
        }
    }
}

/**
 * Builds the export-side ladder from Android's per-message read times.
 *
 * Android stores the ladder expanded: every incoming row carries the `readAt` of the batch that
 * marked it. Consecutive rows sharing a `readAt` are therefore one rung, and the rung's
 * `maxServerTime` is the last message it covers. Decreasing runs are left alone — a late-arriving
 * message and a second device's write-once both produce them and are indistinguishable — because the
 * export self-check carries the affected messages as explicit exceptions instead.
 */
object ReadLadderBuilder {

    /** One incoming message's ladder input: where it sits on the server axis, and when it was read. */
    data class IncomingRead(val serverAt: Long, val readTime: Long)

    /**
     * Build the canonical ladder for one room. [reads] are its incoming messages only (own-sent rows
     * carry a send anchor, not a read time); [roomReadPosition] is `RoomModel.readPosition`.
     */
    fun build(reads: List<IncomingRead>, roomReadPosition: Long): ReadLadder {
        var rows = runLengthRows(reads.filter { it.readTime > 0 }.sortedBy { it.serverAt })
        rows = ReadLadder.dedupeKeepingMinReadAt(rows)
        rows = ReadLadder.suffixMin(rows)
        rows = mergeAdjacentEqualReadAt(rows)

        val lastRow = rows.lastOrNull()
        val maxServerTime = maxOf(roomReadPosition, lastRow?.maxServerTime ?: 0L)
        val watermark = if (maxServerTime <= 0L) {
            null
        } else {
            // The watermark's effective read moment must never undercut the last rung, or the gap
            // between the last rung and the watermark would expire earlier than the rung itself.
            val readAt = lastRow?.readAt ?: 0L
            val effective = if (readAt > 0) readAt else maxServerTime
            NeutralReadPosition(
                readAt = if (lastRow != null && effective < lastRow.readAt) lastRow.readAt else readAt,
                maxServerTime = maxServerTime,
                maxNotifySequenceId = 0L,
                maxSequenceId = 0L,
            )
        }
        return ReadLadder(rows, watermark)
    }

    /** One rung per run of equal `readTime`, ending at the run's last server time. Input must be sorted. */
    private fun runLengthRows(sorted: List<IncomingRead>): List<NeutralReadPosition> {
        val out = ArrayList<NeutralReadPosition>()
        sorted.forEach { read ->
            val last = out.lastOrNull()
            if (last != null && last.readAt == read.readTime) {
                out[out.size - 1] = last.copy(maxServerTime = read.serverAt)
            } else {
                out.add(NeutralReadPosition(readAt = read.readTime, maxServerTime = read.serverAt, maxNotifySequenceId = 0L, maxSequenceId = 0L))
            }
        }
        return out
    }

    /** Collapse rungs the suffix-min pass equalized, keeping the one that reaches furthest. */
    private fun mergeAdjacentEqualReadAt(rows: List<NeutralReadPosition>): List<NeutralReadPosition> {
        val out = ArrayList<NeutralReadPosition>(rows.size)
        rows.forEach { row ->
            val last = out.lastOrNull()
            if (last != null && last.readAt == row.readAt) {
                out[out.size - 1] = last.copy(maxServerTime = maxOf(last.maxServerTime, row.maxServerTime))
            } else {
                out.add(row)
            }
        }
        return out
    }
}

/**
 * The expiry start Android's archive sweep actually uses for a row, and the exception anchor that
 * has to travel when it differs from what the ladder derives.
 *
 * Mirrors `MessageArchiveManager.buildMessageClearCondition`: the normal branch requires both
 * `readTime` and `systemShowTimestamp` to have aged, so the effective start is their maximum (the
 * arrival floor); the legacy branch covers `readTime = 0` rows inside the watermark and ages them by
 * arrival alone; a row matching neither branch never expires by message time.
 */
object AndroidExpiryAnchor {

    /** What the source's sweep would use as this message's countdown start. */
    sealed interface Start {
        /** The source deliberately has no start yet, and the target must not invent one. */
        object NotStarted : Start

        /** No start because the message is simply unread; reading it on the target starts the clock. */
        object None : Start

        /** The countdown starts at [ms]. */
        data class At(val ms: Long) : Start
    }

    /**
     * The effective start of one row. [serverAt] is the neutral record's server time, which is the
     * arrival value the sweep floors against; [roomReadPosition] is the room's own watermark.
     */
    fun of(readTime: Long, serverAt: Long, direction: MessageDirection, roomReadPosition: Long): Start = when {
        // The migration "not started" sentinel: neither archive branch matches it, and it must never
        // be run through the arrival floor.
        readTime < 0L -> Start.NotStarted
        readTime > 0L -> Start.At(maxOf(readTime, serverAt))
        // Legacy pre-#1020 row: read (it is inside the watermark) but with no recorded read moment,
        // so the sweep ages it by arrival.
        roomReadPosition > 0L && serverAt <= roomReadPosition -> Start.At(serverAt)
        direction == MessageDirection.OUTGOING -> Start.NotStarted
        else -> Start.None
    }

    /**
     * The `expireStartedAt` to put on the wire: null when the ladder already derives the source's
     * [local] start, the start itself when it does not, and an explicit 0 when the source has no start
     * but the ladder would invent one.
     */
    fun exception(local: Start, derived: Long?): Long? = when (local) {
        is Start.At -> if (derived == local.ms) null else local.ms
        Start.NotStarted -> if (derived != null) 0L else null
        Start.None -> if (derived != null) 0L else null
    }
}
