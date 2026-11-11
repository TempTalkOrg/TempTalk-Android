package org.difft.app.database.cache

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.PublicAccountType
import org.difft.app.database.wcdb







object OfficialAccountCache {

    private val _state = MutableStateFlow<Set<String>>(emptySet())

    
    @Volatile
    private var generation = 0

    val state: StateFlow<Set<String>> = _state

    fun contains(id: String): Boolean = _state.value.contains(id)

    
    fun put(id: String, isOfficial: Boolean) {
        _state.update { current ->
            when {
                isOfficial && id !in current -> current + id
                !isOfficial && id in current -> current - id
                else -> current
            }
        }
    }

    





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

    
    internal fun snapshotGeneration(): Int = generation

    



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

    






    internal fun applyPreload(capturedGeneration: Int, fromDb: Set<String>): Boolean {
        var applied = false
        _state.update { current ->
            applied = generation == capturedGeneration
            if (applied) fromDb + current else current
        }
        return applied
    }
}
