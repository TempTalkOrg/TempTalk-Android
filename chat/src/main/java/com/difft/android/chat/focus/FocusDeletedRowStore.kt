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

/**
 * Focus groups whose conversation the user deleted from this device while they were in the focus set.
 * The chat list does not synthesize a stand-in row for such a group (a room that comes back by itself,
 * e.g. through a new message, still shows normally). Local only (app_state DataStore, stamped with the
 * account, see [FocusOwnedMark]).
 *
 * - [markDeleted] records a gid only while it is in the effective focus set;
 * - on every effective-focus change only gids still in the set keep their record, so a focus change
 *   drops the removed groups and the end of focus drops all;
 * - [clear] is the local explicit selection of that group (picker, reselect, group switch, row action);
 *   a change made on another device never calls it.
 */
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

    /** The recorded group ids. */
    val deletedGids: StateFlow<Set<String>> = _deletedGids.asStateFlow()

    init {
        scope.launch {
            mutex.withLock { loadLocked() }
            // Judge only the loaded focus: before the cache loads, `effective` is INACTIVE and would drop every record.
            repository.ensureLoaded()
            repository.effective.collect { focus -> retain(focus.groupIds) }
        }
    }

    /**
     * Loads the persisted records once; idempotent. Returns false when the read failed, so the records
     * may be incomplete: callers that would create a row for a possibly deleted group must not.
     */
    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        loadLocked()
        !loadFailed
    }

    /** Records that the user deleted the conversation of [gid]; a no-op unless [gid] is in the effective focus set. */
    suspend fun markDeleted(gid: String) {
        mutex.withLock {
            loadLocked()
            if (gid !in repository.effectiveNow().groupIds || gid in _deletedGids.value) return
            L.i { "[GroupFocus] deleted-row recorded" }
            update(_deletedGids.value + gid)
        }
    }

    /** The user explicitly selected [gid] on this device: its row shows again. */
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
        // Without a known account the marks stay in memory only: nothing to stamp, and the stored ones are not ours to touch.
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
