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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDBUpdateService
import org.difft.app.database.models.ROOM_FOCUS_PIN_AUTO
import org.difft.app.database.models.ROOM_FOCUS_PIN_MANUAL
import org.difft.app.database.models.ROOM_FOCUS_PIN_NONE
import javax.inject.Inject
import javax.inject.Singleton










@Singleton
class FocusPinCoordinator internal constructor(
    private val store: FocusPinStore,
    private val repository: GroupFocusRepository,
    private val deletedRows: FocusDeletedRowStore,
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    roomChanges: Flow<Unit>,
    private val selfId: () -> String?,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    @Inject
    constructor(
        store: DbFocusPinStore,
        repository: GroupFocusRepository,
        deletedRows: FocusDeletedRowStore,
        @AppStateDataStore dataStore: DataStore<Preferences>,
        @AppCoroutineScope scope: CoroutineScope,
    ) : this(
        store, repository, deletedRows, dataStore, scope,
        roomChanges = WCDBUpdateService.roomTableUpdated,
        selfId = { globalServices.myId.takeIf { it.isNotEmpty() } },
        clock = { System.currentTimeMillis() },
    )

    private val mutex = Mutex()
    private var retryJob: Job? = null

    
    @Volatile
    private var deletedRowsKnown = false
    private var legacyImported = false
    private val coalescer: FocusFetchCoalescer = FocusFetchCoalescer(scope) { reconcile() }

    init {
        scope.launch {
            
            repository.ensureLoaded()
            deletedRowsKnown = deletedRows.ensureLoaded()
            launch { roomChanges.collect { coalescer.request() } }
            
            launch { deletedRows.deletedGids.collect { coalescer.request() } }
            repository.effective.collect { coalescer.request() }
        }
    }

    
    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }

    





    suspend fun setGroupPin(gid: String, pinned: Boolean): Unit = withContext(io) {
        mutex.withLock {
            val mark = if (gid in currentTargets()) ROOM_FOCUS_PIN_MANUAL else ROOM_FOCUS_PIN_NONE
            if (store.pinState(gid) == null) {
                store.ensureVisibleRoom(gid, clock())
                
                checkNotNull(store.pinState(gid)) { "room not created" }
            }
            store.writePin(gid, if (pinned) clock() else null, mark)
            L.i { "[GroupFocus] manual pin pinned=$pinned mark=$mark" }
        }
    }

    
    suspend fun reconcile(): Unit = withContext(io) {
        mutex.withLock {
            retryJob?.cancel()
            retryJob = null
            val ok = try {
                importLegacyMark()
                runPass()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[GroupFocus] pin reconcile failed error=${e.javaClass.simpleName}" }
                false
            }
            if (!ok) {
                retryJob = scope.launch {
                    delay(retryDelayMs)
                    coalescer.request()
                }
            }
        }
    }

    
    private fun currentTargets(): Set<String> {
        val focus = repository.effectiveNow()
        return focus.groupIds.filterTo(LinkedHashSet()) { focus.isFocusedGroup(it) }
    }

    






    private fun mayMaterialize(gid: String): Boolean = deletedRowsKnown && gid !in deletedRows.deletedGids.value

    private fun runPass() {
        val targets = currentTargets()
        store.ownedRooms().forEach { (gid, mark) ->
            if (gid in targets) return@forEach
            if (mark == ROOM_FOCUS_PIN_AUTO) store.writePin(gid, null, ROOM_FOCUS_PIN_NONE) else store.writeMark(gid, ROOM_FOCUS_PIN_NONE)
            L.i { "[GroupFocus] pin revoked mark=$mark" }
        }
        targets.forEach { target ->
            val state = if (mayMaterialize(target)) {
                store.ensureVisibleRoom(target, clock())
                store.pinState(target) ?: error("focus room not created")
            } else {
                store.pinState(target)?.takeIf { it.visible } ?: return@forEach
            }
            if (state.focusPin == ROOM_FOCUS_PIN_NONE && !state.pinned) {
                store.writePin(target, clock(), ROOM_FOCUS_PIN_AUTO)
                L.i { "[GroupFocus] pin applied" }
            }
        }
    }

    




    private suspend fun importLegacyMark() {
        if (legacyImported) return
        val owner = selfId() ?: return
        val raw = dataStore.data.first()[AppStateKeys.FOCUS_PIN_OPT_OUT_GID]
        if (raw == null) {
            legacyImported = true
            return
        }
        val gid = FocusOwnedMark.decode(raw, owner)
        if (gid != null && gid in currentTargets()) {
            val materialize = mayMaterialize(gid)
            if (materialize) store.ensureVisibleRoom(gid, clock())
            val state = store.pinState(gid)
            
            if (materialize) checkNotNull(state) { "focus room not created" }
            if (state != null) {
                
                
                if (state.focusPin == ROOM_FOCUS_PIN_NONE) store.writeMark(gid, ROOM_FOCUS_PIN_MANUAL)
                if (store.pinState(gid)?.focusPin != ROOM_FOCUS_PIN_MANUAL) return
                L.i { "[GroupFocus] legacy pin opt-out imported" }
            } else if (!deletedRowsKnown) {
                
                return
            }
            
        }
        removeLegacyKey()
    }

    private suspend fun removeLegacyKey() {
        dataStore.edit { it.remove(AppStateKeys.FOCUS_PIN_OPT_OUT_GID) }
        legacyImported = true
    }

    private companion object {
        const val RETRY_DELAY_MS = 15_000L
    }
}
