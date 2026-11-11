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


private const val WINDOW_24H_MS = 24L * 60 * 60 * 1000
private const val WINDOW_14D_MS = 14L * 24 * 60 * 60 * 1000 







internal class MentionStatsRepository @Inject constructor(
    private val wcdb: WCDB,
) {

    
    suspend fun loadSnapshot(roomId: String, myId: String, now: Long): MentionStatsSnapshot =
        withContext(Dispatchers.IO) {
            val cutoff14d = now - WINDOW_14D_MS
            val mentionStats = queryMyMentionStats(roomId, myId, now, cutoff14d)
            val lastSpeak = queryLastSpeakTime(roomId, cutoff14d)
            L.i { "[Mention] stats loaded room=$roomId mentioned=${mentionStats.size} spoke=${lastSpeak.size}" }
            MentionStatsSnapshot(roomId, now, mentionStats, lastSpeak)
        }

    
    private fun queryMyMentionStats(
        roomId: String,
        myId: String,
        now: Long,
        cutoff14d: Long,
    ): Map<String, MentionStats> {
        
        
        
        
        
        val myMsgIds = StatementSelect()
            .select(DBMessageModel.id)
            .from("message")
            .where(
                DBMessageModel.roomId.eq(roomId)
                    .and(DBMessageModel.fromWho.eq(myId))
                    .and(DBMessageModel.systemShowTimestamp.gt(cutoff14d)) 
            )
        
        val mentionRows = wcdb.mention.getAllObjects(
            DBMentionModel.messageId.`in`(myMsgIds)
                .and(DBMentionModel.uid.notEq(MENTIONS_ALL_ID))
        ).filter { !it.uid.isNullOrEmpty() && !it.messageId.isNullOrEmpty() }
        if (mentionRows.isEmpty()) return emptyMap()

        
        
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

    
    private fun queryLastSpeakTime(roomId: String, cutoff14d: Long): Map<String, Long> {
        
        
        
        
        
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


internal fun aggregateMentionStats(
    mentionRows: List<MentionModel>,
    tsByMsg: Map<String, Long>,
    now: Long,
): Map<String, MentionStats> {
    val cutoff24h = now - WINDOW_24H_MS
    
    val distinctPairs = mentionRows.distinctBy { "${it.messageId}|${it.uid}" }
    return distinctPairs
        .groupBy { it.uid!! }
        .mapNotNull { (uid, rows) ->
            val timed = rows.mapNotNull { r -> tsByMsg[r.messageId]?.let { r to it } }
            if (timed.isEmpty()) return@mapNotNull null 
            val last = timed.maxByOrNull { it.second }!!
            uid to MentionStats(
                lastMentionTime = last.second,
                lastMentionMessageId = last.first.messageId!!,
                count24h = timed.count { it.second > cutoff24h },
                count14d = timed.size,
            )
        }.toMap()
}
