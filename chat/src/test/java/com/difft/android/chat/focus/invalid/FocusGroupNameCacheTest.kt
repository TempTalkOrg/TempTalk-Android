package com.difft.android.chat.focus.invalid

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.FocusGroupDirectory
import com.difft.android.chat.focus.GroupFocusRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The focus group's name is kept while it is valid, survives the group's deletion and goes when focus ends. */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusGroupNameCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val effective = MutableStateFlow(EffectiveFocus.INACTIVE)
    private val repository: GroupFocusRepository = mockk {
        every { this@mockk.effective } returns this@FocusGroupNameCacheTest.effective
        coEvery { ensureLoaded() } returns Unit
    }
    private var account: String? = "me"
    private val renames = MutableSharedFlow<GroupNameUpdate>(extraBufferCapacity = 8)
    private val names = mutableMapOf("A" to "Launch Room")
    private val groups: FocusGroupDirectory = mockk {
        coEvery { name(any()) } answers { names[firstArg()] }
    }

    private fun TestScope.newCache(store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) =
        FocusGroupNameCache(store, repository, groups, backgroundScope, { account }, { renames }).also { it.start() }

    private fun valid(gid: String) = EffectiveFocus(featureEnabled = true, active = true, groupIds = setOf(gid), version = 3)

    private fun invalid(gid: String) = valid(gid).copy(invalid = true, invalidReason = 0, version = 4)

    /** DataStore IO is real-threaded: poll the cache until [expected] shows or the budget runs out. */
    private suspend fun FocusGroupNameCache.awaitName(gid: String, expected: String?): String? {
        repeat(200) {
            val now = nameFor(gid)
            if (now == expected) return now
            withContext(Dispatchers.Default) { delay(10) }
        }
        return nameFor(gid)
    }

    @Test
    fun `the name is cached while valid, kept after the group is gone, and cleared when focus ends`() = runTest(UnconfinedTestDispatcher()) {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "names.preferences_pb") }
        val cache = newCache(store)

        effective.value = valid("A")
        assertEquals("Launch Room", cache.awaitName("A", "Launch Room"))
        assertNull(cache.nameFor("B")) // another gid has no cached name

        names.clear() // the dismissed group is deleted locally
        effective.value = invalid("A")
        assertEquals("Launch Room", cache.awaitName("A", "Launch Room"))

        effective.value = EffectiveFocus.INACTIVE
        assertNull(cache.awaitName("A", null))
    }

    @Test
    fun `an unknown name caches nothing and a switch replaces the previous group`() = runTest(UnconfinedTestDispatcher()) {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "names2.preferences_pb") }
        val cache = newCache(store)

        effective.value = valid("Z") // no GroupModel for Z
        assertNull(cache.nameFor("Z"))

        names["B"] = "Design Review"
        effective.value = valid("B")
        assertEquals("Design Review", cache.awaitName("B", "Design Review"))
        assertNull(cache.nameFor("A"))
    }

    @Test
    fun `a rename of the focus group refreshes the cached name, other groups and an invalid focus do not`() = runTest(UnconfinedTestDispatcher()) {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "rename.preferences_pb") }
        val cache = newCache(store)
        effective.value = valid("A")
        assertEquals("Launch Room", cache.awaitName("A", "Launch Room"))

        renames.emit("A" to "Launch Room 2")
        assertEquals("Launch Room 2", cache.awaitName("A", "Launch Room 2"))

        renames.emit("B" to "Not Focused") // another group
        assertNull(cache.nameFor("B"))
        assertEquals("Launch Room 2", cache.nameFor("A"))

        effective.value = invalid("A")
        renames.emit("A" to "Renamed While Gone") // the group is gone: the remembered name stays
        assertEquals("Launch Room 2", cache.nameFor("A"))
    }

    @Test
    fun `a name cached by another account is not used, and this account overwrites it`() = runTest(UnconfinedTestDispatcher()) {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "account.preferences_pb") }
        store.edit {
            it[AppStateKeys.FOCUS_GROUP_NAME_GID] = "someone-else|A"
            it[AppStateKeys.FOCUS_GROUP_NAME] = "Other Account Room"
        }
        names.clear() // no local name to remember: the pre-seeded cache entry is what is under test
        effective.value = valid("A") // a valid focus does not clear the cache
        val cache = newCache(store)
        assertNull(cache.nameFor("A")) // not this account's, even for the same gid

        account = null
        store.edit { it[AppStateKeys.FOCUS_GROUP_NAME_GID] = "me|A" }
        assertNull(cache.nameFor("A")) // no known account: nothing is returned

        account = "me"
        assertEquals("Other Account Room", cache.nameFor("A")) // stamped for this account

        names["A"] = "Launch Room"
        effective.value = valid("A").copy(version = 4) // a new emission: remembered under this account's stamp
        assertEquals("Launch Room", cache.awaitName("A", "Launch Room"))
        assertEquals("me|A", store.data.first()[AppStateKeys.FOCUS_GROUP_NAME_GID])
    }
}
