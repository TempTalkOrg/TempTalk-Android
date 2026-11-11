package org.difft.app.database

import androidx.annotation.WorkerThread
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBPendingMessageModelNew
import org.difft.app.database.models.PendingMessageModelNew








interface PendingMessageStore {
    fun loadAll(): List<PendingMessageModelNew>

    
    fun existingTargetTimestamps(timestamps: Collection<Long>): Set<Long>

    




    fun deleteConsumed(keys: Collection<PendingRowKey>)

    
    fun deleteByTargetTimestamps(timestamps: Collection<Long>)

    fun count(): Int
}


data class PendingRowKey(val messageId: String, val originalMessageTimeStamp: Long)


class WcdbPendingMessageStore(private val wcdb: WCDB) : PendingMessageStore {

    @WorkerThread
    override fun loadAll(): List<PendingMessageModelNew> = wcdb.pendingMessageNew.allObjects

    @WorkerThread
    override fun existingTargetTimestamps(timestamps: Collection<Long>): Set<Long> =
        timestamps.distinct().chunked(IN_CHUNK_SIZE).flatMapTo(HashSet()) { chunk ->
            wcdb.message.getAllObjects(DBMessageModel.timeStamp.`in`(chunk)).map { it.timeStamp }
        }

    @WorkerThread
    override fun deleteConsumed(keys: Collection<PendingRowKey>) {
        
        keys.distinct().groupBy({ it.originalMessageTimeStamp }, { it.messageId }).forEach { (timestamp, ids) ->
            ids.chunked(IN_CHUNK_SIZE).forEach { chunk ->
                wcdb.pendingMessageNew.deleteObjects(
                    DBPendingMessageModelNew.originalMessageTimeStamp.eq(timestamp)
                        .and(DBPendingMessageModelNew.messageId.`in`(chunk))
                )
            }
        }
    }

    @WorkerThread
    override fun deleteByTargetTimestamps(timestamps: Collection<Long>) {
        timestamps.distinct().chunked(IN_CHUNK_SIZE).forEach { chunk ->
            wcdb.pendingMessageNew.deleteObjects(DBPendingMessageModelNew.originalMessageTimeStamp.`in`(chunk))
        }
    }

    @WorkerThread
    override fun count(): Int = wcdb.pendingMessageNew.allObjects.size

    private companion object {
        
        const val IN_CHUNK_SIZE = 500
    }
}
