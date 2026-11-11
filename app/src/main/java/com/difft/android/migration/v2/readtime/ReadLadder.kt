package com.difft.android.migration.v2.readtime

import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralReadPosition













data class ReadLadder(
    val rows: List<NeutralReadPosition>,
    val watermark: NeutralReadPosition?,
) {

    





    fun lookup(serverAt: Long): Long? =
        rows.asSequence().filter { it.maxServerTime >= serverAt }.minOfOrNull { it.readAt }

    



    fun effectiveWatermarkReadAt(): Long? =
        watermark?.let { if (it.readAt > 0) it.readAt else it.maxServerTime }

    





    fun derivedStart(direction: MessageDirection, serverAt: Long): Long? = when {
        direction == MessageDirection.OUTGOING -> serverAt
        watermark != null && serverAt <= watermark.maxServerTime ->
            lookup(serverAt) ?: effectiveWatermarkReadAt()
        else -> null
    }

    companion object {

        
        val EMPTY = ReadLadder(emptyList(), null)

        





        fun fromWire(local: NeutralConversationLocal): ReadLadder {
            val watermark = local.readPosition
            var rows = dedupeKeepingMinReadAt(local.readPositions.sortedBy { it.maxServerTime })
            
            
            
            
            rows = rows.map { if (it.readAt > 0) it else it.copy(readAt = it.maxServerTime) }
                .filter { it.readAt > 0 }
            rows = suffixMin(rows)
            if (watermark != null) {
                
                
                rows = dedupeKeepingMinReadAt(
                    rows.map {
                        if (it.maxServerTime > watermark.maxServerTime) it.copy(maxServerTime = watermark.maxServerTime) else it
                    }
                )
            }
            return ReadLadder(rows, watermark)
        }

        
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










object ReadLadderBuilder {

    
    data class IncomingRead(val serverAt: Long, val readTime: Long)

    



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










object AndroidExpiryAnchor {

    
    sealed interface Start {
        
        object NotStarted : Start

        
        object None : Start

        
        data class At(val ms: Long) : Start
    }

    



    fun of(readTime: Long, serverAt: Long, direction: MessageDirection, roomReadPosition: Long): Start = when {
        
        
        readTime < 0L -> Start.NotStarted
        readTime > 0L -> Start.At(maxOf(readTime, serverAt))
        
        
        roomReadPosition > 0L && serverAt <= roomReadPosition -> Start.At(serverAt)
        direction == MessageDirection.OUTGOING -> Start.NotStarted
        else -> Start.None
    }

    




    fun exception(local: Start, derived: Long?): Long? = when (local) {
        is Start.At -> if (derived == local.ms) null else local.ms
        Start.NotStarted -> if (derived != null) 0L else null
        Start.None -> if (derived != null) 0L else null
    }
}
