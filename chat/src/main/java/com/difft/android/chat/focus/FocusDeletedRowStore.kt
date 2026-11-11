package com.difft.android.chat.focus

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.di.AppCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton













@Singleton
class FocusDeletedRowStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val repository: GroupFocusRepository,
    scope: CoroutineScope,
    private val selfId: () -> String?,
) {
    @Inject
    constructor(
        @AppStateDataStore dataStore: DataStore<Preferences>,
        repository: GroupFocusRepository,
        @AppCoroutineScope scope: CoroutineScope,
    ) : this(dataStore, repository, scope, { globalServices.myId.takeIf { it.isNotEmpty() } })

    private val mutex = Mutex()
    private var loaded = false
    private var loadFailed = false

    private val _deletedGids = MutableStateFlow<Set<String>>(emptySet())

    
    val deletedGids: StateFlow<Set<String>> = _deletedGids.asStateFlow()

    init {
        scope.launch {
            mutex.withLock { loadLocked() }
            
            repository.ensureLoaded()
            repository.effective.collect { focus -> retain(focus.groupIds) }
        }
    }

    



    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        loadLocked()
        !loadFailed
    }

    
    suspend fun markDeleted(gid: String) {
        mutex.withLock {
            loadLocked()
            if (gid !in repository.effectiveNow().groupIds || gid in _deletedGids.value) return
            L.i { "[GroupFocus] deleted-row recorded" }
            update(_deletedGids.value + gid)
        }
    }

    
    suspend fun clear(gid: String) {
        mutex.withLock {
            loadLocked()
            if (gid !in _deletedGids.value) return
            L.i { "[GroupFocus] deleted-row cleared reason=selected" }
            update(_deletedGids.value - gid)
        }
    }

    private suspend fun retain(focusGids: Set<String>) {
        mutex.withLock {
            loadLocked()
            val kept = _deletedGids.value.filterTo(HashSet()) { it in focusGids }
            if (kept.size == _deletedGids.value.size) return
            L.i { "[GroupFocus] deleted-row dropped reason=focus-changed kept=${kept.size}" }
            update(kept)
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        val raw = try {
            dataStore.data.first()[AppStateKeys.FOCUS_DELETED_ROW_GIDS]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[GroupFocus] deleted-row read failed error=${e.javaClass.simpleName}" }
            loadFailed = true
            null
        }
        val stored = FocusOwnedMark.decode(raw, selfId())?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()
        _deletedGids.value = _deletedGids.value + stored
    }

    private suspend fun update(next: Set<String>) {
        _deletedGids.value = next
        
        val owner = selfId() ?: return
        try {
            dataStore.edit { prefs ->
                if (next.isEmpty()) {
                    prefs.remove(AppStateKeys.FOCUS_DELETED_ROW_GIDS)
                } else {
                    prefs[AppStateKeys.FOCUS_DELETED_ROW_GIDS] = FocusOwnedMark.encode(owner, next.joinToString(SEPARATOR))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[GroupFocus] deleted-row write failed error=${e.javaClass.simpleName}" }
        }
    }

    private companion object {
        const val SEPARATOR = ","
    }
}
