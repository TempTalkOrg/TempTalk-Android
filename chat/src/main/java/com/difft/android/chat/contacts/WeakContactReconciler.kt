package com.difft.android.chat.contacts

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.network.HttpService
import com.difft.android.network.responses.DeletedRecordDto
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.PendingRemovalContactRepository
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.cache.OfficialAccountCache
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.PendingRemovalContactModel
import javax.inject.Inject
import javax.inject.Singleton















@Singleton
class WeakContactReconciler @Inject constructor(
    private val pendingRepo: PendingRemovalContactRepository,
    private val dbMessageStore: DBMessageStore,
    private val httpService: HttpService,                       
    private val contactorCacheManager: ContactorCacheManager,   
    private val wcdb: WCDB,
    private val gson: Gson,
) {
    private val mutex = Mutex()   

    
    suspend fun enterWeak(uid: String, expireAt: Long, reason: Int, deleteTime: Long, snapshot: ContactorModel) =
        mutex.withLock { enterWeakLocked(uid, expireAt, reason, deleteTime, snapshot) }

    
    suspend fun removeWeak(uid: String) = mutex.withLock { removeWeakLocked(uid) }

    
    suspend fun clearWeakOnFriendRestored(uid: String) = mutex.withLock { dropPlaceholderLocked(uid) }

    



    suspend fun removeNow(uid: String): Result<Unit> = runCatching {
        httpService.deleteDeletedRecord(uid)   
        removeWeak(uid)                                  
        Unit
    }.onFailure { L.w { "[WeakContact] removeNow uid=$uid failed (offline?): ${it.stackTraceToString()}" } }

    








    suspend fun reconcile(trigger: String): Boolean = mutex.withLock {
        val resp = runCatching { httpService.fetchDeletedRecords() }
            .onFailure { L.w { "[WeakContact] reconcile($trigger) fetch error: ${it.stackTraceToString()}" } }
            .getOrNull()
        
        
        val records = resp?.data
        if (resp == null || !resp.isSuccess() || records == null) {
            L.w { "[WeakContact] reconcile($trigger) fetch incomplete (resp=${resp != null} success=${resp?.isSuccess()} dataNull=${resp?.data == null}), skip" }
            return@withLock false
        }
        val serverNow = resp.serverTimestamp ?: 0L
        
        
        
        
        val validRecords = records.filter { !it.uid.isNullOrBlank() }
        val skipped = records.size - validRecords.size
        if (skipped > 0) {
            L.w { "[WeakContact] reconcile($trigger) skip $skipped records with null/blank uid" }
        }
        val latest = validRecords.associateBy { it.uid!! }               
        val before = pendingRepo.snapshotBeforeOverwrite()                
        
        
        
        
        val existingSnapshots = pendingRepo.getSnapshots(before.toList())

        
        val snapshots = HashMap<String, ContactorModel>()
        latest.forEach { (uid, dto) ->
            val snap = dto.toContactorSnapshot(uid)
            
            if (snap.remark.isNullOrEmpty()) {
                existingSnapshots[uid]?.remark?.let { snap.remark = it }
            }
            snapshots[uid] = snap
            if (uid !in before) {
                
                enterWeakLocked(uid, dto.expireTime, dto.reason, dto.deleteTime, snap)
            }
            
            
            
        }
        (before - latest.keys).forEach { uid ->
            
            
            
            dropPlaceholderLocked(uid)
        }
        
        val rows = latest.map { (uid, dto) ->
            PendingRemovalContactModel().also {
                it.uid = uid
                it.expireAt = dto.expireTime
                it.reason = dto.reason
                it.deleteTime = dto.deleteTime
                it.snapshotJson = gson.toJson(snapshots.getValue(uid))
            }
        }
        pendingRepo.overwriteAll(rows)                  
        L.i { "[WeakContact] reconcile($trigger) done latest=${latest.size} before=${before.size} serverNow=$serverNow" }
        true   
    }

    

    



    private suspend fun enterWeakLocked(uid: String, expireAt: Long, reason: Int, deleteTime: Long, snapshot: ContactorModel) = withContext(Dispatchers.IO) {
        L.i { "[WeakContact] enterWeak uid=$uid reason=$reason expireAt=$expireAt" }
        
        wcdb.contactor.getFirstObject(DBContactorModel.id.eq(uid))?.remark
            ?.let { snapshot.remark = it }
        pendingRepo.upsert(uid, expireAt, reason, deleteTime, snapshot)  
        wcdb.contactor.deleteObjects(DBContactorModel.id.eq(uid))        
        OfficialAccountCache.put(uid, false)                            
        dbMessageStore.removeRoomAndMessages(uid)                        
        invalidateAndEmit(uid)
    }

    
    private suspend fun removeWeakLocked(uid: String) {
        L.i { "[WeakContact] removeWeak (real removal) uid=$uid" }
        pendingRepo.remove(uid)                     
        dbMessageStore.removeRoomAndMessages(uid)   
        invalidateAndEmit(uid)
    }

    
    private suspend fun dropPlaceholderLocked(uid: String) {
        L.i { "[WeakContact] clearWeak (friend restored) uid=$uid" }
        pendingRepo.remove(uid)   
        invalidateAndEmit(uid)
    }

    
    private fun DeletedRecordDto.toContactorSnapshot(uid: String): ContactorModel = ContactorModel().also {
        it.id = uid
        it.name = name
        it.avatar = avatar
        wcdb.contactor.getFirstObject(DBContactorModel.id.eq(uid))?.remark?.let { rmk -> it.remark = rmk }
    }

    private fun invalidateAndEmit(uid: String) {
        contactorCacheManager.invalidateUser(uid)
        ContactorUtil.emitContactsUpdate(listOf(uid))
    }
}
