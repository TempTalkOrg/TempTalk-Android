package com.difft.android.chat.focus

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.difft.android.base.storage.AppStateKeys
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.difft.app.database.models.ROOM_FOCUS_PIN_AUTO
import org.difft.app.database.models.ROOM_FOCUS_PIN_MANUAL
import org.difft.app.database.models.ROOM_FOCUS_PIN_NONE
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue





@OptIn(ExperimentalCoroutinesApi::class)
class FocusPinCoordinatorTest {

    private class MemoryPrefs(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: StateFlow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private class Row(var pinnedTime: Long?, var focusPin: Int, var lastActiveTime: Long)

    
    private class MemoryStore : FocusPinStore {
        val rows = LinkedHashMap<String, Row>()
        var failCreate = false
        var writes = 0

        fun room(gid: String, pinned: Boolean = false, focusPin: Int = ROOM_FOCUS_PIN_NONE) {
            rows[gid] = Row(if (pinned) 1L else null, focusPin, 10L)
        }

        override fun ownedRooms(): Map<String, Int> = rows.filterValues { it.focusPin != ROOM_FOCUS_PIN_NONE }.mapValues { it.value.focusPin }
        override fun pinState(gid: String): FocusPinState? =
            rows[gid]?.let { FocusPinState((it.pinnedTime ?: 0L) > 0L, it.focusPin, visible = it.lastActiveTime != 0L) }
        override fun ensureVisibleRoom(gid: String, now: Long) {
            if (failCreate) return
            val row = rows.getOrPut(gid) { Row(null, ROOM_FOCUS_PIN_NONE, 0L) }
            if (row.lastActiveTime == 0L) row.lastActiveTime = now
        }

        override fun writePin(gid: String, pinnedTime: Long?, focusPin: Int) {
            writes++
            rows[gid]?.let { it.pinnedTime = pinnedTime; it.focusPin = focusPin }
        }

        override fun writeMark(gid: String, focusPin: Int) {
            writes++
            rows[gid]?.focusPin = focusPin
        }
    }

    private val focusStore: GroupFocusStore = mockk()
    private var persisted = GroupFocusState(featureEnabled = true, focus = snapshot("A", version = 1), ownerUid = "me")
    private var account: String? = "me"

    init {
        coEvery { focusStore.read() } answers { persisted }
        coEvery { focusStore.write(any()) } answers { persisted = firstArg() }
    }

    private fun snapshot(vararg gids: String, version: Int, enabled: Boolean = true) =
        FocusSnapshot(enabled = enabled, groupIds = gids.toList(), version = version)

    private class Fixture(
        val repository: GroupFocusRepository,
        val deleted: FocusDeletedRowStore,
        val store: MemoryStore,
        val prefs: MemoryPrefs,
        val coordinator: FocusPinCoordinator,
        val roomChanges: MutableSharedFlow<Unit>,
    )

    private fun TestScope.setUp(
        prefs: Preferences = emptyPreferences(),
        rooms: MemoryStore.() -> Unit = {},
        deletedStoreFails: Boolean = false,
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = GroupFocusRepository({ mockk() }, focusStore, backgroundScope, clock = { 0L }, selfId = { account })
        val deletedPrefs: DataStore<Preferences> = if (deletedStoreFails) mockk { coEvery { data } throws IllegalStateException("io") } else MemoryPrefs()
        val deleted = FocusDeletedRowStore(deletedPrefs, repository, backgroundScope) { account }
        val store = MemoryStore().apply(rooms)
        val memoryPrefs = MemoryPrefs(prefs)
        val roomChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val coordinator = FocusPinCoordinator(
            store, repository, deleted, memoryPrefs, backgroundScope, roomChanges,
            selfId = { account }, clock = { 1_000L }, io = dispatcher, retryDelayMs = 100L,
        )
        runCurrent()
        return Fixture(repository, deleted, store, memoryPrefs, coordinator, roomChanges)
    }

    private fun MemoryStore.row(gid: String) = rows.getValue(gid)

    @Test
    fun `focusing an unpinned group pins it automatically, an already pinned one is left untouched`() = runTest {
        val f = setUp(rooms = { room("A"); room("P", pinned = true) })
        assertEquals(1_000L, f.store.row("A").pinnedTime)
        assertEquals(ROOM_FOCUS_PIN_AUTO, f.store.row("A").focusPin)

        
        f.repository.apply(snapshot("P", version = 2), "test")
        runCurrent()
        assertEquals(ROOM_FOCUS_PIN_NONE, f.store.row("P").focusPin)
        assertEquals(1L, f.store.row("P").pinnedTime)
        
        assertNull(f.store.row("A").pinnedTime)
        assertEquals(ROOM_FOCUS_PIN_NONE, f.store.row("A").focusPin)
    }

    @Test
    fun `ending focus revokes the automatic pin and a second pass writes nothing`() = runTest {
        val f = setUp(rooms = { room("A") })
        f.repository.apply(snapshot(version = 2, enabled = false), "test")
        runCurrent()
        assertNull(f.store.row("A").pinnedTime)
        assertEquals(ROOM_FOCUS_PIN_NONE, f.store.row("A").focusPin)

        val before = f.store.writes
        f.coordinator.reconcile()
        runCurrent()
        assertEquals(before, f.store.writes)
    }

    @Test
    fun `a manual change during focus is permanent, in both directions`() = runTest {
        val f = setUp(rooms = { room("A") })
        f.coordinator.setGroupPin("A", pinned = false)
        runCurrent()
        assertEquals(ROOM_FOCUS_PIN_MANUAL, f.store.row("A").focusPin)
        assertNull(f.store.row("A").pinnedTime)
        f.coordinator.reconcile()
        runCurrent()
        assertNull(f.store.row("A").pinnedTime) 

        f.coordinator.setGroupPin("A", pinned = true)
        runCurrent()
        f.repository.apply(snapshot(version = 2, enabled = false), "test")
        runCurrent()
        assertEquals(1_000L, f.store.row("A").pinnedTime) 
        assertEquals(ROOM_FOCUS_PIN_NONE, f.store.row("A").focusPin)
    }

    @Test
    fun `a manual pin on a group that is not the target relinquishes a stale automatic mark`() = runTest {
        val f = setUp(rooms = { room("A"); room("B", focusPin = ROOM_FOCUS_PIN_AUTO, pinned = true) })
        
        f.coordinator.setGroupPin("B", pinned = true)
        runCurrent()
        assertEquals(ROOM_FOCUS_PIN_NONE, f.store.row("B").focusPin)
        f.coordinator.reconcile()
        runCurrent()
        assertEquals(1_000L, f.store.row("B").pinnedTime)
    }

    @Test
    fun `a focus group without a room gets a visible, pinned row, unless the user deleted it`() = runTest {
        val f = setUp()
        assertEquals(1_000L, f.store.row("A").lastActiveTime)
        assertEquals(ROOM_FOCUS_PIN_AUTO, f.store.row("A").focusPin)

        f.coordinator.serialized {
            f.deleted.markDeleted("A")
            f.store.rows.remove("A")
        }
        f.coordinator.reconcile()
        runCurrent()
        assertFalse("A" in f.store.rows)

        
        f.store.room("A")
        f.roomChanges.tryEmit(Unit)
        runCurrent()
        assertEquals(ROOM_FOCUS_PIN_AUTO, f.store.row("A").focusPin)
    }

    @Test
    fun `a deleted focus group is never re-created or revealed, even while its delete is still running`() = runTest {
        val f = setUp(rooms = { room("A") })
        
        f.coordinator.serialized { f.deleted.markDeleted("A") }
        f.coordinator.reconcile()
        runCurrent()
        f.store.rows.remove("A")
        f.coordinator.reconcile()
        runCurrent()
        assertFalse("A" in f.store.rows)

        
        f.store.rows["A"] = Row(null, ROOM_FOCUS_PIN_NONE, 0L)
        f.roomChanges.tryEmit(Unit)
        runCurrent()
        assertEquals(0L, f.store.row("A").lastActiveTime)
        assertNull(f.store.row("A").pinnedTime)
    }

    @Test
    fun `every group in the focus set is pinned, and none of them is revoked`() = runTest {
        persisted = persisted.copy(focus = snapshot("A", "B", version = 1))
        val f = setUp(rooms = { room("A"); room("B") })
        assertEquals(ROOM_FOCUS_PIN_AUTO, f.store.row("A").focusPin)
        assertEquals(ROOM_FOCUS_PIN_AUTO, f.store.row("B").focusPin)

        f.repository.apply(snapshot("B", version = 2), "test")
        runCurrent()
        assertNull(f.store.row("A").pinnedTime)
        assertEquals(1_000L, f.store.row("B").pinnedTime)
    }

    @Test
    fun `selecting a deleted focus group again creates and pins its row`() = runTest {
        val f = setUp()
        f.coordinator.serialized {
            f.deleted.markDeleted("A")
            f.store.rows.remove("A")
        }
        f.coordinator.reconcile()
        runCurrent()
        assertFalse("A" in f.store.rows)

        
        f.deleted.clear("A")
        runCurrent()
        assertEquals(ROOM_FOCUS_PIN_AUTO, f.store.row("A").focusPin)
    }

    @Test
    fun `no row is created while the deleted-row records could not be read`() = runTest {
        val f = setUp(rooms = { room("B", focusPin = ROOM_FOCUS_PIN_AUTO, pinned = true) }, deletedStoreFails = true)
        assertFalse("A" in f.store.rows)
        
        assertNull(f.store.row("B").pinnedTime)
    }

    @Test
    fun `a failed pass is retried after the delay`() = runTest {
        val f = setUp(rooms = { failCreate = true })
        assertFalse("A" in f.store.rows)
        f.store.failCreate = false
        advanceTimeBy(101L)
        runCurrent()
        assertEquals(ROOM_FOCUS_PIN_AUTO, f.store.row("A").focusPin)
    }

    @Test
    fun `a manual pin whose row cannot be created fails instead of being lost`() = runTest {
        val f = setUp(rooms = { failCreate = true })
        assertFailsWith<IllegalStateException> { f.coordinator.setGroupPin("A", pinned = false) }
    }

    @Test
    fun `the legacy opt-out mark becomes a manual mark before the first automatic pin, then is removed`() = runTest {
        val f = setUp(prefs = mutablePreferencesOf(AppStateKeys.FOCUS_PIN_OPT_OUT_GID to "me|A"), rooms = { room("A") })
        assertEquals(ROOM_FOCUS_PIN_MANUAL, f.store.row("A").focusPin)
        assertNull(f.store.row("A").pinnedTime)
        assertNull(f.prefs.data.value[AppStateKeys.FOCUS_PIN_OPT_OUT_GID])
    }

    @Test
    fun `a legacy mark is imported onto an existing row even when the deletion records could not be read`() = runTest {
        val f = setUp(prefs = mutablePreferencesOf(AppStateKeys.FOCUS_PIN_OPT_OUT_GID to "me|A"), rooms = { room("A") }, deletedStoreFails = true)
        assertEquals(ROOM_FOCUS_PIN_MANUAL, f.store.row("A").focusPin)
        assertNull(f.store.row("A").pinnedTime)
        assertNull(f.prefs.data.value[AppStateKeys.FOCUS_PIN_OPT_OUT_GID])
    }

    @Test
    fun `a legacy mark survives a failed room creation and is imported on the retry`() = runTest {
        val f = setUp(prefs = mutablePreferencesOf(AppStateKeys.FOCUS_PIN_OPT_OUT_GID to "me|A"), rooms = { failCreate = true })
        assertTrue(f.prefs.data.value[AppStateKeys.FOCUS_PIN_OPT_OUT_GID] != null)
        f.store.failCreate = false
        advanceTimeBy(101L)
        runCurrent()
        assertEquals(ROOM_FOCUS_PIN_MANUAL, f.store.row("A").focusPin)
        assertNull(f.store.row("A").pinnedTime)
        assertNull(f.prefs.data.value[AppStateKeys.FOCUS_PIN_OPT_OUT_GID])
    }

    @Test
    fun `a legacy mark for another group or account is dropped, one without a known account is kept`() = runTest {
        val other = setUp(prefs = mutablePreferencesOf(AppStateKeys.FOCUS_PIN_OPT_OUT_GID to "me|Z"), rooms = { room("A") })
        assertEquals(ROOM_FOCUS_PIN_AUTO, other.store.row("A").focusPin)
        assertNull(other.prefs.data.value[AppStateKeys.FOCUS_PIN_OPT_OUT_GID])

        account = null
        val signedOut = setUp(prefs = mutablePreferencesOf(AppStateKeys.FOCUS_PIN_OPT_OUT_GID to "me|A"), rooms = { room("A") })
        assertTrue(signedOut.prefs.data.value[AppStateKeys.FOCUS_PIN_OPT_OUT_GID] != null)
    }
}
