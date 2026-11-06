package com.difft.android.chat.focus

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.difft.android.base.storage.AppStateKeys
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The deleted focus-row records: per group, local, kept while the group stays in the focus set, account-stamped. */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusDeletedRowStoreTest {

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: StateFlow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private val focusStore: GroupFocusStore = mockk()
    private var account: String? = "me"
    private var persisted = focusOn("A", "B", version = 1)

    init {
        coEvery { focusStore.read() } answers { persisted }
        coEvery { focusStore.write(any()) } answers { persisted = firstArg() }
    }

    private fun focusOn(vararg gids: String, version: Int) =
        GroupFocusState(featureEnabled = true, focus = FocusSnapshot(enabled = true, groupIds = gids.toList(), version = version))

    private class Fixture(val repository: GroupFocusRepository, val store: FocusDeletedRowStore, val dataStore: MemoryStore)

    private fun TestScope.setUp(prefs: Preferences = emptyPreferences()): Fixture {
        val repository = GroupFocusRepository({ mockk() }, focusStore, backgroundScope, clock = { 0L })
        val dataStore = MemoryStore(prefs)
        val store = FocusDeletedRowStore(dataStore, repository, backgroundScope) { account }
        runCurrent()
        return Fixture(repository, store, dataStore)
    }

    @Test
    fun `a deletion is recorded only for a group in the focus set, and persisted for this account`() = runTest {
        val f = setUp()
        f.store.markDeleted("A")
        f.store.markDeleted("Z") // not in the focus set
        assertEquals(setOf("A"), f.store.deletedGids.value)
        assertEquals("me|A", f.dataStore.data.value[AppStateKeys.FOCUS_DELETED_ROW_GIDS])
    }

    @Test
    fun `nothing is recorded while focus is off`() = runTest {
        persisted = GroupFocusState(featureEnabled = true, focus = FocusSnapshot(enabled = false, version = 3))
        val f = setUp()
        f.store.markDeleted("A")
        assertEquals(emptySet(), f.store.deletedGids.value)
    }

    @Test
    fun `records survive a restart while the group stays in the focus set`() = runTest {
        val first = setUp()
        first.store.markDeleted("A")
        first.store.markDeleted("B")

        val second = setUp(first.dataStore.data.value)
        assertEquals(setOf("A", "B"), second.store.deletedGids.value)
    }

    @Test
    fun `a focus change keeps only the groups still in the set, and the end of focus drops all`() = runTest {
        val f = setUp()
        f.store.markDeleted("A")
        f.store.markDeleted("B")

        // A stays (the group set changed around it), B is removed, C is new and shows normally.
        f.repository.apply(FocusSnapshot(enabled = true, groupIds = listOf("A", "C"), version = 2), trigger = "USER")
        runCurrent()
        assertEquals(setOf("A"), f.store.deletedGids.value)
        assertEquals("me|A", f.dataStore.data.value[AppStateKeys.FOCUS_DELETED_ROW_GIDS])

        // A remote change that leaves the group in the set does not clear its record.
        f.repository.apply(FocusSnapshot(enabled = true, groupIds = listOf("A"), othersAllowMentionAll = true, version = 3), trigger = "USER")
        runCurrent()
        assertEquals(setOf("A"), f.store.deletedGids.value)

        f.repository.apply(FocusSnapshot(enabled = false, version = 4), trigger = "USER")
        runCurrent()
        assertEquals(emptySet(), f.store.deletedGids.value)
        assertNull(f.dataStore.data.value[AppStateKeys.FOCUS_DELETED_ROW_GIDS])
    }

    @Test
    fun `a stale record loaded for a group that left the focus set is dropped`() = runTest {
        val f = setUp(mutablePreferencesOf(AppStateKeys.FOCUS_DELETED_ROW_GIDS to "me|A,Z"))
        assertEquals(setOf("A"), f.store.deletedGids.value)
        assertEquals("me|A", f.dataStore.data.value[AppStateKeys.FOCUS_DELETED_ROW_GIDS])
    }

    @Test
    fun `a local explicit selection clears the record of that group only`() = runTest {
        val f = setUp()
        f.store.markDeleted("A")
        f.store.markDeleted("B")

        f.store.clear("A")
        assertEquals(setOf("B"), f.store.deletedGids.value)
        assertEquals("me|B", f.dataStore.data.value[AppStateKeys.FOCUS_DELETED_ROW_GIDS])

        f.store.clear("Z") // nothing recorded: no-op
        assertEquals(setOf("B"), f.store.deletedGids.value)
    }

    @Test
    fun `a successful selecting write clears the record, a failed one or a non-selecting one does not`() = runTest {
        val f = setUp()
        f.store.markDeleted("A")
        f.store.markDeleted("B")
        val ok = FocusResult.Success(FocusSnapshot(enabled = true, groupIds = listOf("A"), version = 2))

        assertIs<FocusResult.Failure>(runSelectingWrite("A", f.store) { FocusResult.Failure(FocusError.Conflict) })
        assertEquals(setOf("A", "B"), f.store.deletedGids.value)

        runSelectingWrite(null, f.store) { ok } // end / until / options: no group selected
        assertEquals(setOf("A", "B"), f.store.deletedGids.value)

        assertIs<FocusResult.Success>(runSelectingWrite("A", f.store) { ok })
        assertEquals(setOf("B"), f.store.deletedGids.value)
    }

    @Test
    fun `a record of another account is ignored, and nothing is persisted without an account`() = runTest {
        val other = setUp(mutablePreferencesOf(AppStateKeys.FOCUS_DELETED_ROW_GIDS to "someone-else|A"))
        assertEquals(emptySet(), other.store.deletedGids.value)

        val unstamped = setUp(mutablePreferencesOf(AppStateKeys.FOCUS_DELETED_ROW_GIDS to "A"))
        assertEquals(emptySet(), unstamped.store.deletedGids.value)

        // This account's first deletion replaces the other account's value.
        other.store.markDeleted("B")
        assertEquals("me|B", other.dataStore.data.value[AppStateKeys.FOCUS_DELETED_ROW_GIDS])

        account = null
        val signedOut = setUp(mutablePreferencesOf(AppStateKeys.FOCUS_DELETED_ROW_GIDS to "me|A"))
        assertEquals(emptySet(), signedOut.store.deletedGids.value)
        signedOut.store.markDeleted("A")
        assertEquals("me|A", signedOut.dataStore.data.value[AppStateKeys.FOCUS_DELETED_ROW_GIDS]) // untouched, not ours to write
    }
}
