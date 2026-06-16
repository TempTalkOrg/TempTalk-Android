package com.difft.android.messageserialization.db.store

import com.difft.android.base.log.lumberjack.L
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBPendingRemovalContactModel
import org.difft.app.database.models.PendingRemovalContactModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of reads/writes to the weak (pending-removal) placeholder table.
 *
 * This repo only manages the table; it does NOT touch cross-module caches — cache invalidation is
 * handled in the :chat orchestration layer (WeakContactReconciler.invalidateAndEmit).
 *
 * All methods switch to [Dispatchers.IO] internally, so callers may invoke them from any context.
 * The snapshot reuses [ContactorModel]: reads gson-deserialize [PendingRemovalContactModel.snapshotJson];
 * writes serialize a whole row after the caller maps in the server name/avatar.
 */
@Singleton
class PendingRemovalContactRepository @Inject constructor(
    private val wcdb: WCDB,
    private val gson: Gson,
) {

    /** Single-uid weak snapshot → ContactorModel (value-chain fallback). null = not weak or deserialize failed. */
    suspend fun getSnapshot(uid: String): ContactorModel? = withContext(Dispatchers.IO) {
        val row = wcdb.pendingRemovalContact
            .getFirstObject(DBPendingRemovalContactModel.uid.eq(uid)) ?: return@withContext null
        row.deserializeSnapshot()
    }

    /** Batch snapshots. Returns hit-and-deserializable uid → ContactorModel. */
    suspend fun getSnapshots(uids: List<String>): Map<String, ContactorModel> {
        if (uids.isEmpty()) return emptyMap()
        val distinct = uids.toSet()
        return withContext(Dispatchers.IO) {
            wcdb.pendingRemovalContact
                .getAllObjects(DBPendingRemovalContactModel.uid.`in`(*distinct.toTypedArray()))
                .mapNotNull { row -> row.deserializeSnapshot()?.let { row.uid to it } }
                .toMap()
        }
    }

    /** Expiry metadata (for the list subtitle). Returns uid → expireAt. */
    suspend fun getAllExpireAt(): Map<String, Long> = withContext(Dispatchers.IO) {
        wcdb.pendingRemovalContact.allObjects.associate { it.uid to it.expireAt }
    }

    /** Whether this uid is in the weak state. */
    suspend fun isPending(uid: String): Boolean = withContext(Dispatchers.IO) {
        wcdb.pendingRemovalContact.getFirstObject(DBPendingRemovalContactModel.uid.eq(uid)) != null
    }

    /**
     * Upsert via insertOrReplaceObject (WCDB REPLACE atomic semantics on the uid primary key),
     * replacing a deleteObjects + insertObject pair to avoid a concurrent-write window. Does not
     * invalidate caches here (this repo never touches cross-module caches).
     */
    suspend fun upsert(
        uid: String,
        expireAt: Long,
        reason: Int,
        deleteTime: Long,
        snapshot: ContactorModel,
    ) = withContext(Dispatchers.IO) {
        val row = PendingRemovalContactModel().also {
            it.uid = uid
            it.expireAt = expireAt
            it.reason = reason
            it.deleteTime = deleteTime
            it.snapshotJson = gson.toJson(snapshot)
        }
        wcdb.pendingRemovalContact.insertOrReplaceObject(row)   // atomic REPLACE upsert
        L.i { "[WeakContact] pendingRepo upsert uid=$uid reason=$reason expireAt=$expireAt" }
    }

    /** Remove a single weak placeholder. Idempotent. */
    suspend fun remove(uid: String) = withContext(Dispatchers.IO) {
        wcdb.pendingRemovalContact.deleteObjects(DBPendingRemovalContactModel.uid.eq(uid))
        L.i { "[WeakContact] pendingRepo remove uid=$uid" }
    }

    /** "Before" snapshot for reconcile: all current weak-table uids, used for the diff. */
    suspend fun snapshotBeforeOverwrite(): Set<String> = withContext(Dispatchers.IO) {
        wcdb.pendingRemovalContact.allObjects.map { it.uid }.toSet()
    }

    /**
     * Full overwrite for reconcile: wipe + bulk-write in a single WCDB transaction, so no concurrent
     * read can observe the "cleared but not yet rewritten" intermediate state. The caller (reconcile)
     * already holds the Mutex; the transaction boundary plus the Mutex together guarantee consistency.
     * Rows are pre-built [PendingRemovalContactModel] instances.
     *
     * Note: WCDB Table has no `deleteAllObjects()`; the no-arg `deleteObjects()` deletes all rows.
     */
    suspend fun overwriteAll(rows: List<PendingRemovalContactModel>) = withContext(Dispatchers.IO) {
        wcdb.db.runTransaction {   // WCDB transaction API is runTransaction; returning true commits
            wcdb.pendingRemovalContact.deleteObjects()   // no-arg = delete all rows
            wcdb.pendingRemovalContact.insertOrReplaceObjects(rows)
            true
        }
        L.i { "[WeakContact] pendingRepo overwriteAll size=${rows.size}" }
    }

    private fun PendingRemovalContactModel.deserializeSnapshot(): ContactorModel? =
        snapshotJson?.let { runCatching { gson.fromJson(it, ContactorModel::class.java) }.getOrNull() }
}
