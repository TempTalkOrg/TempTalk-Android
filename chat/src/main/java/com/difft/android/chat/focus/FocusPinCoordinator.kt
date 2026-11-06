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

/**
 * Keeps the focus group's real pin in step with the effective focus (design §1): the focus group is
 * pinned automatically (`focusPin` = [ROOM_FOCUS_PIN_AUTO]) unless it was pinned already, that pin is
 * revoked when focus ends or moves, and a pin the user changed during focus ([ROOM_FOCUS_PIN_MANUAL])
 * is permanent. [reconcile] is idempotent and runs on every effective-focus change and room-table
 * change (coalesced), with one delayed retry after a failed pass. Every user pin change on a group
 * goes through [setGroupPin]; the deletion of a group conversation runs inside [serialized] so it
 * cannot interleave with a reconcile that is about to create the row.
 */
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

    /** False while the deleted-row records could not be read: no row is created then, only existing rows are written. */
    @Volatile
    private var deletedRowsKnown = false
    private var legacyImported = false
    private val coalescer: FocusFetchCoalescer = FocusFetchCoalescer(scope) { reconcile() }

    init {
        scope.launch {
            // Judge against loaded state only: before the caches load, focus is INACTIVE and no row is deleted.
            repository.ensureLoaded()
            deletedRowsKnown = deletedRows.ensureLoaded()
            launch { roomChanges.collect { coalescer.request() } }
            // A cleared deletion record (the group selected again) may allow the row to be created now.
            launch { deletedRows.deletedGids.collect { coalescer.request() } }
            repository.effective.collect { coalescer.request() }
        }
    }

    /** Runs [block] under the coordinator's lock, serialized with every reconcile and pin write. */
    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * The user's pin change on group [gid] (chat list actions, group info). The focus group's row is
     * made real first when it only had a stand-in, and the write carries [ROOM_FOCUS_PIN_MANUAL] for
     * the focus group or [ROOM_FOCUS_PIN_NONE] for any other group, so a pending reconcile never
     * reverts a pin the user asked for.
     */
    suspend fun setGroupPin(gid: String, pinned: Boolean): Unit = withContext(io) {
        mutex.withLock {
            val mark = if (gid in currentTargets()) ROOM_FOCUS_PIN_MANUAL else ROOM_FOCUS_PIN_NONE
            if (store.pinState(gid) == null) {
                store.ensureVisibleRoom(gid, clock())
                // createRoomIfNotExist swallows an insert failure; a pin on no row would be lost silently.
                checkNotNull(store.pinState(gid)) { "room not created" }
            }
            store.writePin(gid, if (pinned) clock() else null, mark)
            L.i { "[GroupFocus] manual pin pinned=$pinned mark=$mark" }
        }
    }

    /** One idempotent pass (design §1.2); a failure arms one delayed retry. Callers normally go through the coalescer. */
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

    /** Every valid focused group (the list renders each as a focus row); empty when focus is off, expired or invalid. */
    private fun currentTargets(): Set<String> {
        val focus = repository.effectiveNow()
        return focus.groupIds.filterTo(LinkedHashSet()) { focus.isFocusedGroup(it) }
    }

    /**
     * A deleted focus group (or one whose deletion record could not be read) is never created or made
     * visible here: the user's delete runs asynchronously after the record is written
     * (`DBMessageStore.removeRoomAndMessages`), and other screens create hidden rows
     * (`lastActiveTime == 0`) for it. Only a row that is visible on its own, e.g. re-created by a new
     * message, is pinned.
     */
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

    /**
     * One-time import of the pre-`focusPin` opt-out mark (a pin the user changed during a focus that is
     * still running): it becomes [ROOM_FOCUS_PIN_MANUAL] on the focus group, and the key is removed
     * only once that is durable. Without a known account nothing is read or removed.
     */
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
            // createRoomIfNotExist swallows an insert failure: keep the mark and let the pass fail and retry.
            if (materialize) checkNotNull(state) { "focus room not created" }
            if (state != null) {
                // An existing row is stamped even when the deletion records are unknown, before this
                // pass could pin it automatically.
                if (state.focusPin == ROOM_FOCUS_PIN_NONE) store.writeMark(gid, ROOM_FOCUS_PIN_MANUAL)
                if (store.pinState(gid)?.focusPin != ROOM_FOCUS_PIN_MANUAL) return
                L.i { "[GroupFocus] legacy pin opt-out imported" }
            } else if (!deletedRowsKnown) {
                // No row and the deletion records are unknown: nothing is created, keep the mark for later.
                return
            }
            // A deleted group with no row has nothing to protect: its row is not re-created.
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
