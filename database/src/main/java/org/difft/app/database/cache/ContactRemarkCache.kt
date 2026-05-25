package org.difft.app.database.cache

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb

/**
 * Read source of truth for remark name + remark avatar, keyed by uid. Bypasses
 * stale `groupMemberContactor` rows scoped to a specific gid. Preloaded at app
 * startup; cleared on logout.
 */
object ContactRemarkCache {

    private val _state = MutableStateFlow<Map<String, ContactRemarkInfo>>(emptyMap())

    /** Bumped by [clear]; [preload] captures this before DB reads and aborts if it advances. */
    @Volatile
    private var generation = 0

    val state: StateFlow<Map<String, ContactRemarkInfo>> = _state

    fun get(uid: String): ContactRemarkInfo? = _state.value[uid]
    fun getRemark(uid: String): String? = _state.value[uid]?.remark
    fun getRemarkAvatar(uid: String): String? = _state.value[uid]?.remarkAvatar

    /** Replace or remove the whole entry. Memory only; caller writes the DB. */
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

    /**
     * Update only the name field, preserving any existing avatar. The entry-read
     * lives inside [_state.update] so a concurrent [putRemarkAvatar] on the same
     * uid can't lose its update through a stale read.
     */
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

    /** Symmetric counterpart of [putRemark] for the avatar field. */
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

    /** Batch write — single atomic update so subscribers see one notification. */
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

    /** Invoked on logout — also aborts any in-flight [preload]. */
    fun clear() {
        generation++
        _state.value = emptyMap()
        L.i { "[ContactRemarkCache] cleared" }
    }

    /**
     * One-time startup load. Combines contactor (friend) rows with the gid=""
     * groupMemberContactor stubs (non-friend); contactor wins on conflict. Rows
     * with gid="someGroup" are intentionally skipped — they're treated as stale
     * once the cache becomes the read source of truth.
     *
     * Callers MUST invoke this from a `Dispatchers.IO`-bound coroutine
     * (e.g., wrapped in `withContext(Dispatchers.IO)` at the call site).
     */
    @Suppress("BlockingWcdbInSuspend")
    suspend fun preload() {
        val gen = generation
        val start = System.currentTimeMillis()

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
        ).associate {
            it.id to ContactRemarkInfo(
                remark = it.remark.orNullIfEmpty(),
                remarkAvatar = it.remarkAvatar.orNullIfEmpty(),
            )
        }

        val fromDb = fromGMemberStub + fromContactor

        var aborted = false
        // `fromDb + current` keeps any concurrent put/putAll values fresher than DB.
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
