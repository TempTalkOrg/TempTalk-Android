package org.difft.app.database

import androidx.annotation.WorkerThread
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBPendingMessageModelNew
import org.difft.app.database.models.PendingMessageModelNew

/**
 * The `pending_message_new` operations the pending-message replay needs (#1382).
 *
 * A row is addressed by its opaque `messageId` (the table's primary key; an `ios:` id from an
 * imported container is as valid as a live Android one): several rows can wait on the same target
 * timestamp, and each is consumed on its own.
 */
interface PendingMessageStore {
    fun loadAll(): List<PendingMessageModelNew>

    /** The subset of [timestamps] that a stored message currently carries. */
    fun existingTargetTimestamps(timestamps: Collection<Long>): Set<Long>

    /**
     * Deletes each row whose messageId AND target timestamp still match one of [keys]; other rows on
     * the same target stay. Matching the target too keeps a row that processing re-saved under the
     * same messageId for a different target (one envelope covering several targets).
     */
    fun deleteConsumed(keys: Collection<PendingRowKey>)

    /** Deletes every row waiting on one of [timestamps]; only for the expiry sweep, where they all expire together. */
    fun deleteByTargetTimestamps(timestamps: Collection<Long>)

    fun count(): Int
}

/** A pending row as it was when loaded: its messageId and the target it was waiting on. */
data class PendingRowKey(val messageId: String, val originalMessageTimeStamp: Long)

/** Blocking WCDB calls; callers run them on Dispatchers.IO. */
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
        // One statement per target timestamp: `originalMessageTimeStamp = ? AND messageId IN (...)`.
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
        // Under SQLite's default SQLITE_MAX_VARIABLE_NUMBER (999), as in GroupCryptoRepo.
        const val IN_CHUNK_SIZE = 500
    }
}
