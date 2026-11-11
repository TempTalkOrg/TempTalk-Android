package com.difft.android.chat.group

import com.difft.android.base.log.lumberjack.L
import org.difft.app.database.models.GroupModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton














@Singleton
class GroupExpiryTracker @Inject constructor() {
    private val lock = Any()
    private val clearedAt = ConcurrentHashMap<String, Long>()

    
    private class Written(val requestStartedAt: Long, val expireTime: Long?)
    private val newestWrite = mutableMapOf<String, Written>()
    private val lastTimestamp = AtomicLong(Long.MIN_VALUE)

    
    private fun nextTimestamp(): Long = lastTimestamp.updateAndGet { maxOf(System.nanoTime(), it + 1) }

    fun requestStartedAt(): Long = nextTimestamp()

    






    fun persistResponse(
        groups: List<GroupModel>,
        requestStartedAt: Long,
        carriesDeadline: (GroupModel) -> Boolean = { true },
        stored: (String) -> Long? = { null },
        clearWritten: (String) -> Unit = {},
        persist: () -> Unit,
    ) {
        synchronized(lock) {
            groups.forEach { group ->
                val gid = group.gid ?: return@forEach
                if (carriesDeadline(group)) settle(group, requestStartedAt) else group.expireTime = stored(gid)
            }
            persist()
            groups.forEach { group ->
                val gid = group.gid ?: return@forEach
                if (group.expireTime != null && settled(gid) == null && clearedSince(gid, requestStartedAt)) {
                    clearWritten(gid)
                    L.i { "[GroupExpiry] clear gid=$gid reason=during_write" }
                }
            }
        }
    }

    
    private fun settle(group: GroupModel, requestStartedAt: Long) {
        val gid = group.gid ?: return
        val newest = newestWrite[gid]
        if (newest != null && newest.requestStartedAt > requestStartedAt) {
            group.expireTime = settled(gid)
            return
        }
        group.expireTime = group.expireTime?.takeIf { it > 0 && !clearedSince(gid, requestStartedAt) }
        newestWrite[gid] = Written(requestStartedAt, group.expireTime)
    }

    
    private fun settled(gid: String): Long? {
        val newest = newestWrite[gid] ?: return null
        return newest.expireTime?.takeIf { !clearedSince(gid, newest.requestStartedAt) }
    }

    private fun clearedSince(gid: String, requestStartedAt: Long): Boolean =
        (clearedAt[gid] ?: Long.MIN_VALUE) > requestStartedAt

    




    fun clearExpiry(
        gid: String,
        reason: String,
        load: () -> GroupModel?,
        persist: (GroupModel) -> Unit,
        emit: (GroupModel) -> Unit,
        hasDeadline: () -> Boolean = { load()?.expireTime != null },
    ) {
        
        val clearedAtStamp = nextTimestamp().also { stamp -> clearedAt.merge(gid, stamp, ::maxOf) }
        if (!hasDeadline()) return
        synchronized(lock) {
            if ((newestWrite[gid]?.requestStartedAt ?: Long.MIN_VALUE) > clearedAtStamp) return
            val group = load() ?: return
            if (group.expireTime == null) return
            group.expireTime = null
            persist(group)
            emit(group)
            L.i { "[GroupExpiry] clear gid=$gid reason=$reason" }
        }
    }
}
