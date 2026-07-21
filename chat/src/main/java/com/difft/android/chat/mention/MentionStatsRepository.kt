package com.difft.android.chat.mention

import com.difft.android.base.log.lumberjack.L
import com.tencent.wcdb.winq.StatementSelect
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBMentionModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.MessageModel
import javax.inject.Inject

// File-level so the pure aggregate below can share it without companion visibility juggling.
private const val WINDOW_24H_MS = 24L * 60 * 60 * 1000
private const val WINDOW_14D_MS = 14L * 24 * 60 * 60 * 1000 // 1_209_600_000

/**
 * Reads the local WCDB to derive @-mention sort stats for one chat, over rolling windows:
 * (1) the current user's own mentions in the last 14 days, and
 * (2) each member's last-speak time in the last 14 days.
 * A single [loadSnapshot] backs one panel-open; keyword filtering reuses the snapshot in memory.
 */
internal class MentionStatsRepository @Inject constructor(
    private val wcdb: WCDB,
) {

    /** Produces the snapshot in one load. Switches to IO internally; caller need not. */
    suspend fun loadSnapshot(roomId: String, myId: String, now: Long): MentionStatsSnapshot =
        withContext(Dispatchers.IO) {
            val cutoff14d = now - WINDOW_14D_MS
            val mentionStats = queryMyMentionStats(roomId, myId, now, cutoff14d)
            val lastSpeak = queryLastSpeakTime(roomId, cutoff14d)
            L.i { "[Mention] stats loaded room=$roomId mentioned=${mentionStats.size} spoke=${lastSpeak.size}" }
            MentionStatsSnapshot(roomId, now, mentionStats, lastSpeak)
        }

    // ---- Query 1: current user's mentions in the last 14 days, aggregated per uid. ----
    private fun queryMyMentionStats(
        roomId: String,
        myId: String,
        now: Long,
        cutoff14d: Long,
    ): Map<String, MentionStats> {
        // Correlated subquery: my messages in the window (not materialized). Filters on
        // systemShowTimestamp so it reuses the existing (roomId, systemShowTimestamp) index
        // idx_room_timestamp — no new b-tree on the hottest write table. systemShowTimestamp is
        // the server-corrected authoritative axis (resistant to sender clock skew); for my own
        // sent messages it equals timeStamp at insert time, so mention-history semantics hold.
        val myMsgIds = StatementSelect()
            .select(DBMessageModel.id)
            .from("message")
            .where(
                DBMessageModel.roomId.eq(roomId)
                    .and(DBMessageModel.fromWho.eq(myId))
                    .and(DBMessageModel.systemShowTimestamp.gt(cutoff14d)) // rolling lower bound (> strict)
            )
        // Mention rows on those messages, excluding @all and empty uids.
        val mentionRows = wcdb.mention.getAllObjects(
            DBMentionModel.messageId.`in`(myMsgIds)
                .and(DBMentionModel.uid.notEq(MENTIONS_ALL_ID))
        ).filter { !it.uid.isNullOrEmpty() && !it.messageId.isNullOrEmpty() }
        if (mentionRows.isEmpty()) return emptyMap()

        // messageId -> systemShowTimestamp (only the involved messages; small volume).
        // Column-projected, no full MessageModel load. Same authoritative axis as the window filter.
        val msgIds = mentionRows.mapNotNull { it.messageId }.distinct()
        val tsRows = wcdb.db.getAllRowsFromStatement(
            StatementSelect()
                .select(DBMessageModel.id, DBMessageModel.systemShowTimestamp)
                .from("message")
                .where(DBMessageModel.id.`in`(*msgIds.toTypedArray()))
        )
        val tsByMsg: Map<String, Long> = tsRows.mapNotNull { row ->
            val id = row[0].text ?: return@mapNotNull null
            id to row[1].long
        }.toMap()

        return aggregateMentionStats(mentionRows, tsByMsg, now)
    }

    // ---- Query 2: each member's last-speak time in the current chat within the last 14 days. ----
    private fun queryLastSpeakTime(roomId: String, cutoff14d: Long): Map<String, Long> {
        // SELECT fromWho, MAX(systemShowTimestamp) FROM message
        //   WHERE roomId=? AND type IN (TEXT, ATTACHMENT) AND systemShowTimestamp > cutoff14d
        //   GROUP BY fromWho
        // Uses systemShowTimestamp (server-corrected axis) so a peer with a fast clock can't
        // dominate "last-speak"; also reuses idx_room_timestamp (roomId, systemShowTimestamp).
        val stmt = StatementSelect()
            .select(DBMessageModel.fromWho, DBMessageModel.systemShowTimestamp.max())
            .from("message")
            .where(
                DBMessageModel.roomId.eq(roomId)
                    .and(DBMessageModel.type.`in`(MessageModel.TYPE_TEXT, MessageModel.TYPE_ATTACHMENT))
                    .and(DBMessageModel.systemShowTimestamp.gt(cutoff14d))
            )
            .groupBy(DBMessageModel.fromWho)
        return wcdb.db.getAllRowsFromStatement(stmt).mapNotNull { row ->
            val uid = row[0].text ?: return@mapNotNull null
            uid to row[1].long
        }.toMap()
    }
}

/** Pure aggregation, extracted for unit testing (no WCDB dependency). */
internal fun aggregateMentionStats(
    mentionRows: List<MentionModel>,
    tsByMsg: Map<String, Long>,
    now: Long,
): Map<String, MentionStats> {
    val cutoff24h = now - WINDOW_24H_MS
    // De-dup by (messageId, uid): the same uid mentioned multiple times in one message counts once.
    val distinctPairs = mentionRows.distinctBy { "${it.messageId}|${it.uid}" }
    return distinctPairs
        .groupBy { it.uid!! }
        .mapNotNull { (uid, rows) ->
            val timed = rows.mapNotNull { r -> tsByMsg[r.messageId]?.let { r to it } }
            if (timed.isEmpty()) return@mapNotNull null // message deleted/dangling → skip
            val last = timed.maxByOrNull { it.second }!!
            uid to MentionStats(
                lastMentionTime = last.second,
                lastMentionMessageId = last.first.messageId!!,
                count24h = timed.count { it.second > cutoff24h },
                count14d = timed.size,
            )
        }.toMap()
}
