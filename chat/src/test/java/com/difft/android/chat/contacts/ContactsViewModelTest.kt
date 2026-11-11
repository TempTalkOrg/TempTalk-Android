package com.difft.android.chat.contacts

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.difft.android.chat.contacts.compose.DirectoryTab
import com.difft.android.chat.contacts.data.ContactsRepository
import com.difft.android.chat.contacts.data.ContactsSnapshot
import com.difft.android.chat.contacts.data.ContactsTabPreferences
import com.difft.android.chat.contacts.data.PendingContactMetadata
import com.difft.android.chat.contacts.mvi.ContactsContract.Effect
import com.difft.android.chat.contacts.mvi.ContactsContract.Intent
import com.difft.android.chat.contacts.mvi.ContactsViewModel
import com.difft.android.test.TestDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContactsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val dispatcherRule = TestDispatcherRule(dispatcher)
    private val source = mockk<ContactsRepository>()
    private val preferences = mockk<ContactsTabPreferences>()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val dayChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val models = mutableListOf<ContactsViewModel>()

    @Before fun setUp() {
        ContactRemarkCache.clear()
        every { source.changes() } returns changes
        coEvery { source.snapshot() } returns ContactsSnapshot()
        coEvery { preferences.read() } returns null
        coEvery { preferences.save(any()) } returns Unit
    }

    @After fun tearDown() { models.forEach { it.viewModelScope.cancel() } }

    @Test fun `first snapshot defaults to groups without storing it and user selection is stored and survives changes`() = runTest(dispatcher) {
        coEvery { source.snapshot() } returns ContactsSnapshot(groups = listOf(group()))
        val vm = model()
        runCurrent()
        assertEquals(DirectoryTab.Groups, vm.state.value.selectedTab)
        coVerify(exactly = 0) { preferences.save(any()) }
        vm.dispatch(Intent.SelectTab(DirectoryTab.Contacts))
        runCurrent()
        changes.emit(Unit)
        runCurrent()
        assertEquals(DirectoryTab.Contacts, vm.state.value.selectedTab)
        coVerify(exactly = 1) { preferences.save(DirectoryTab.Contacts) }
        coVerify(exactly = 1) { preferences.read() }
    }

    @Test fun `tab choice updates immediately during IO and initial snapshot cannot replace it`() = runTest(dispatcher) {
        val snapshot = CompletableDeferred<ContactsSnapshot>()
        coEvery { source.snapshot() } coAnswers { snapshot.await() }
        val vm = model()
        runCurrent()
        vm.dispatch(Intent.SelectTab(DirectoryTab.Groups))
        assertEquals(DirectoryTab.Groups, vm.state.value.selectedTab)
        snapshot.complete(ContactsSnapshot())
        runCurrent()
        assertEquals(DirectoryTab.Groups, vm.state.value.selectedTab)
        coVerify(exactly = 1) { preferences.save(DirectoryTab.Groups) }
    }

    @Test fun `remembered group tab remains selected for empty directory and refresh keeps dual pane selection`() = runTest(dispatcher) {
        coEvery { preferences.read() } returns DirectoryTab.Groups
        val vm = model()
        runCurrent()
        assertEquals(DirectoryTab.Groups, vm.state.value.selectedTab)
        vm.dispatch(Intent.SelectId("selected"))
        vm.dispatch(Intent.Refresh)
        runCurrent()
        assertEquals("selected", vm.state.value.selectedId)
        coVerify(exactly = 0) { preferences.save(any()) }
    }

    @Test fun `failed initial load ends loading and a lifecycle refresh can retry`() = runTest(dispatcher) {
        coEvery { source.snapshot() } throws IllegalStateException("database unavailable")
        val vm = model()
        runCurrent()
        kotlin.test.assertFalse(vm.state.value.loading)
        kotlin.test.assertFalse(vm.state.value.initialized)
        coEvery { source.snapshot() } returns ContactsSnapshot()
        vm.dispatch(Intent.Refresh)
        runCurrent()
        assertTrue(vm.state.value.initialized)
        kotlin.test.assertFalse(vm.state.value.loading)
    }

    @Test fun `weak contacts no table knows are shown before their lookup and a landed lookup reloads once`() = runTest(dispatcher) {
        val weak = ContactorModel().apply { id = "w"; name = "Weak" }
        val lookup = CompletableDeferred<Boolean>()
        coEvery { source.snapshot() } returns ContactsSnapshot(
            weakContacts = listOf(weak), pending = mapOf("w" to PendingContactMetadata(Long.MAX_VALUE, 0)),
            unresolvedWeakIds = listOf("w"),
        )
        coEvery { source.backfillWeakContacts(listOf("w")) } coAnswers { lookup.await() }
        val vm = model()
        runCurrent()
        assertEquals(listOf("w"), vm.state.value.pendingRemoval.map { it.row.id })
        coVerify(exactly = 1) { source.snapshot() }
        coEvery { source.snapshot() } returns ContactsSnapshot(
            weakContacts = listOf(weak), pending = mapOf("w" to PendingContactMetadata(Long.MAX_VALUE, 0)),
        )
        lookup.complete(true)
        runCurrent()
        coVerify(exactly = 2) { source.snapshot() }
        coVerify(exactly = 1) { source.backfillWeakContacts(any()) }
    }

    @Test fun `a lookup that finds nothing does not reload`() = runTest(dispatcher) {
        coEvery { source.snapshot() } returns ContactsSnapshot(unresolvedWeakIds = listOf("gone"))
        coEvery { source.backfillWeakContacts(listOf("gone")) } returns false
        model()
        runCurrent()
        coVerify(exactly = 1) { source.snapshot() }
    }

    @Test fun `day changes and data changes assemble latest snapshot`() = runTest(dispatcher) {
        val vm = model(dayFlow = true)
        runCurrent()
        assertTrue(vm.state.value.contactSections.isEmpty())
        coEvery { source.snapshot() } returns ContactsSnapshot(friends = listOf(ContactorModel().apply {
            id = "a"; name = "Alice"
        }))
        dayChanges.emit(Unit)
        runCurrent()
        assertEquals(listOf("A"), vm.state.value.indexLetters)
        coEvery { source.snapshot() } returns ContactsSnapshot()
        changes.emit(Unit)
        runCurrent()
        assertTrue(vm.state.value.contactSections.isEmpty())
    }

    @Test fun `row tile and add actions emit card effects and repeated selections are not deduplicated`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.effect.test {
            listOf(Intent.OpenContact("self"), Intent.OpenContact("self"), Intent.OpenGroup("g"), Intent.AddContact)
                .forEach(vm::dispatch)
            runCurrent()
            assertEquals<List<Effect>>(
                listOf(Effect.OpenContact("self"), Effect.OpenContact("self"), Effect.OpenGroup("g"), Effect.AddContact),
                List(4) { awaitItem() },
            )
        }
    }

    @Test fun `pull refresh syncs both lists then reloads once and ignores pulls while it runs`() = runTest(dispatcher) {
        val sync = CompletableDeferred<Unit>()
        coEvery { source.refreshAll() } coAnswers { sync.await() }
        val vm = model()
        runCurrent()
        vm.dispatch(Intent.PullRefresh)
        runCurrent()
        assertTrue(vm.state.value.refreshing)
        vm.dispatch(Intent.PullRefresh)
        runCurrent()
        coVerify(exactly = 1) { source.snapshot() }
        sync.complete(Unit)
        runCurrent()
        assertEquals(false, vm.state.value.refreshing)
        coVerify(exactly = 1) { source.refreshAll() }
        coVerify(exactly = 2) { source.snapshot() }
    }

    @Test fun `failed pull refresh still ends the indicator`() = runTest(dispatcher) {
        coEvery { source.refreshAll() } throws IllegalStateException("offline")
        val vm = model()
        runCurrent()
        vm.dispatch(Intent.PullRefresh)
        runCurrent()
        assertEquals(false, vm.state.value.refreshing)
    }

    private fun model(dayFlow: Boolean = false) = ContactsViewModel(source, preferences,
        mockk<CountdownTicker> {
            every { ticks } returns emptyFlow()
            every { dayChanges } returns if (dayFlow) this@ContactsViewModelTest.dayChanges else emptyFlow()
        },
    ).also(models::add)

    private fun group() = GroupModel().apply { gid = "g"; name = "Group"; status = 0 }
}
