package org.difft.app.database.cache

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.PublicAccountType
import org.difft.app.database.wcdb

/**
 * In-memory source of truth for "is this id an OFFICIAL account". Preloaded at app
 * startup from `contactor.publicAccountType == OFFICIAL`; updated on every contactor
 * write path; cleared on logout. Reads are synchronous so RecyclerView binds / Fragment
 * init can call [contains] without a DB hit.
 */
object OfficialAccountCache {

    private val _state = MutableStateFlow<Set<String>>(emptySet())

    /** Bumped by [clear]; [preload] captures this before DB reads and aborts if it advances. */
    @Volatile
    private var generation = 0

    val state: StateFlow<Set<String>> = _state

    fun contains(id: String): Boolean = _state.value.contains(id)

    /** Add/remove one id. Memory only; caller owns the DB write. */
    fun put(id: String, isOfficial: Boolean) {
        _state.update { current ->
            when {
                isOfficial && id !in current -> current + id
                !isOfficial && id in current -> current - id
                else -> current
            }
        }
    }

    /**
     * Replace the entire set — used by full sync, which WIPES + re-inserts the contactor
     * table, so the cache must reflect exactly the newly-written official ids. Bumps the
     * generation so an in-flight [preload] (older generation) aborts instead of clobbering
     * this authoritative full set.
     */
    fun replaceAll(officialIds: Set<String>) {
        generation++
        _state.update { officialIds }
        L.i { "[OfficialAccountCache] replaceAll size=${officialIds.size}" }
    }

    fun clear() {
        generation++
        _state.value = emptySet()
        L.i { "[OfficialAccountCache] cleared" }
    }

    /** Read the current generation to capture before a DB read; [applyPreload] compares against it. */
    internal fun snapshotGeneration(): Int = generation

    /**
     * One-time startup load. Callers MUST invoke from a Dispatchers.IO-bound coroutine.
     * Soft-fails to an empty set on a corrupt/unreadable DB (recovery driven by MainActivity).
     */
    @Suppress("BlockingWcdbInSuspend")
    suspend fun preload() {
        if (wcdb.dbCorrupted) {
            L.w { "[OfficialAccountCache] preload skipped: db marked corrupt" }
            return
        }
        val gen = snapshotGeneration()
        val start = System.currentTimeMillis()
        val fromDb = runCatching {
            wcdb.contactor
                .getAllObjects(DBContactorModel.publicAccountType.eq(PublicAccountType.OFFICIAL))
                .map { it.id }
                .toSet()
        }.getOrElse { e ->
            L.e { "[OfficialAccountCache] preload DB read failed (cache left empty): ${e.stackTraceToString()}" }
            return
        }
        val costMs = System.currentTimeMillis() - start
        if (applyPreload(gen, fromDb)) {
            L.i { "[OfficialAccountCache] preload done dbSize=${fromDb.size} costMs=$costMs" }
        } else {
            L.i { "[OfficialAccountCache] preload aborted (clear/replaceAll fired mid-flight) costMs=$costMs" }
        }
    }

    /**
     * Apply a preload result under the generation guard. Returns true if applied, false if aborted
     * (a [clear] or [replaceAll] advanced the generation since [capturedGeneration] was snapshotted).
     * On apply the DB set is UNIONed with the current set so a concurrent [put](id, true) delta
     * survives; a concurrent [put](id, false) that raced the DB read may be resurrected until the
     * next sync — accepted residual. Pure memory — unit-testable without a WCDB read.
     */
    internal fun applyPreload(capturedGeneration: Int, fromDb: Set<String>): Boolean {
        var applied = false
        _state.update { current ->
            applied = generation == capturedGeneration
            if (applied) fromDb + current else current
        }
        return applied
    }
}
