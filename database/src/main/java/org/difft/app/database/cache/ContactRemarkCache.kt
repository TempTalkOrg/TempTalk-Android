package org.difft.app.database.cache

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb






object ContactRemarkCache {

    private val _state = MutableStateFlow<Map<String, ContactRemarkInfo>>(emptyMap())

    
    @Volatile
    private var generation = 0

    val state: StateFlow<Map<String, ContactRemarkInfo>> = _state

    fun get(uid: String): ContactRemarkInfo? = _state.value[uid]
    fun getRemark(uid: String): String? = _state.value[uid]?.remark
    fun getRemarkAvatar(uid: String): String? = _state.value[uid]?.remarkAvatar

    
    fun put(uid: String, info: ContactRemarkInfo?) {
        if (info == null || info.isEmpty) {
            _state.update { current ->
                if (uid in current) current - uid else current
            }
            return
        }
        _state.update { current ->
            if (current[uid] == info) current else current + (uid to info)
        }
    }

    




    fun putRemark(uid: String, remark: String?) {
        val normalized = remark?.takeIf { it.isNotEmpty() }
        _state.update { current ->
            val entry = current[uid]
            val next = entry?.copy(remark = normalized) ?: ContactRemarkInfo(remark = normalized)
            if (next.isEmpty) {
                if (uid in current) current - uid else current
            } else {
                if (entry == next) current else current + (uid to next)
            }
        }
    }

    
    fun putRemarkAvatar(uid: String, avatar: String?) {
        val normalized = avatar?.takeIf { it.isNotEmpty() }
        _state.update { current ->
            val entry = current[uid]
            val next = entry?.copy(remarkAvatar = normalized) ?: ContactRemarkInfo(remarkAvatar = normalized)
            if (next.isEmpty) {
                if (uid in current) current - uid else current
            } else {
                if (entry == next) current else current + (uid to next)
            }
        }
    }

    
    fun putAll(updates: Map<String, ContactRemarkInfo?>) {
        if (updates.isEmpty()) return
        _state.update { current ->
            var next = current
            updates.forEach { (uid, info) ->
                if (info == null || info.isEmpty) {
                    if (uid in next) next = next - uid
                } else if (next[uid] != info) {
                    next = next + (uid to info)
                }
            }
            next
        }
    }

    
    fun clear() {
        generation++
        _state.value = emptyMap()
        L.i { "[ContactRemarkCache] cleared" }
    }

    








    @Suppress("BlockingWcdbInSuspend")
    suspend fun preload() {
        
        
        if (wcdb.dbCorrupted) {
            L.w { "[ContactRemarkCache] preload skipped: db marked corrupt" }
            return
        }

        val gen = generation
        val start = System.currentTimeMillis()

        
        
        
        
        val fromDb = runCatching {
            val fromContactor = wcdb.contactor.getAllObjects(
                DBContactorModel.remark.notNull().and(DBContactorModel.remark.notEq(""))
                    .or(DBContactorModel.remarkAvatar.notNull().and(DBContactorModel.remarkAvatar.notEq("")))
            ).associate {
                it.id to ContactRemarkInfo(
                    remark = it.remark.orNullIfEmpty(),
                    remarkAvatar = it.remarkAvatar.orNullIfEmpty(),
                )
            }

            val fromGMemberStub = wcdb.groupMemberContactor.getAllObjects(
                DBGroupMemberContactorModel.gid.eq("")
                    .and(
                        DBGroupMemberContactorModel.remark.notNull().and(DBGroupMemberContactorModel.remark.notEq(""))
                            .or(DBGroupMemberContactorModel.remarkAvatar.notNull().and(DBGroupMemberContactorModel.remarkAvatar.notEq("")))
                    )
            ).mapNotNull { member ->
                val memberId = member.id ?: return@mapNotNull null
                memberId to ContactRemarkInfo(
                    remark = member.remark.orNullIfEmpty(),
                    remarkAvatar = member.remarkAvatar.orNullIfEmpty(),
                )
            }.toMap()

            fromGMemberStub + fromContactor
        }.getOrElse { e ->
            L.e { "[ContactRemarkCache] preload DB read failed (cache left empty): ${e.stackTraceToString()}" }
            return
        }

        var aborted = false
        
        _state.update { current ->
            if (generation != gen) {
                aborted = true
                current
            } else {
                fromDb + current
            }
        }

        val cost = System.currentTimeMillis() - start
        if (aborted) {
            L.i { "[ContactRemarkCache] preload aborted (clear fired mid-flight) costMs=$cost" }
        } else {
            L.i { "[ContactRemarkCache] preload done dbSize=${fromDb.size} costMs=$cost" }
        }
    }

    private fun String?.orNullIfEmpty(): String? = this?.takeIf { it.isNotEmpty() }
}
