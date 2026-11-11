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











@Singleton
class PendingRemovalContactRepository @Inject constructor(
    private val wcdb: WCDB,
    private val gson: Gson,
) {

    
    suspend fun getSnapshot(uid: String): ContactorModel? = withContext(Dispatchers.IO) {
        val row = wcdb.pendingRemovalContact
            .getFirstObject(DBPendingRemovalContactModel.uid.eq(uid)) ?: return@withContext null
        row.deserializeSnapshot()
    }

    
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

    
    suspend fun getAllExpireAt(): Map<String, Long> = withContext(Dispatchers.IO) {
        wcdb.pendingRemovalContact.allObjects.associate { it.uid to it.expireAt }
    }

    
    suspend fun isPending(uid: String): Boolean = withContext(Dispatchers.IO) {
        wcdb.pendingRemovalContact.getFirstObject(DBPendingRemovalContactModel.uid.eq(uid)) != null
    }

    




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
        wcdb.pendingRemovalContact.insertOrReplaceObject(row)   
        L.i { "[WeakContact] pendingRepo upsert uid=$uid reason=$reason expireAt=$expireAt" }
    }

    
    suspend fun remove(uid: String) = withContext(Dispatchers.IO) {
        wcdb.pendingRemovalContact.deleteObjects(DBPendingRemovalContactModel.uid.eq(uid))
        L.i { "[WeakContact] pendingRepo remove uid=$uid" }
    }

    
    suspend fun snapshotBeforeOverwrite(): Set<String> = withContext(Dispatchers.IO) {
        wcdb.pendingRemovalContact.allObjects.map { it.uid }.toSet()
    }

    







    suspend fun overwriteAll(rows: List<PendingRemovalContactModel>) = withContext(Dispatchers.IO) {
        wcdb.db.runTransaction {   
            wcdb.pendingRemovalContact.deleteObjects()   
            wcdb.pendingRemovalContact.insertOrReplaceObjects(rows)
            true
        }
        L.i { "[WeakContact] pendingRepo overwriteAll size=${rows.size}" }
    }

    private fun PendingRemovalContactModel.deserializeSnapshot(): ContactorModel? =
        snapshotJson?.let { runCatching { gson.fromJson(it, ContactorModel::class.java) }.getOrNull() }
}
