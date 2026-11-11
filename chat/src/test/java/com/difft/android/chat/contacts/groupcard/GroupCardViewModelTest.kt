package com.difft.android.chat.contacts.groupcard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.difft.android.chat.contacts.CardPreloads
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Action
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Effect
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Intent
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardDataSource
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardDataSource.Snapshot
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardViewModel
import com.difft.android.chat.group.GROUP_ROLE_MEMBER
import com.difft.android.test.TestDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.difft.app.database.models.GroupModel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse


@OptIn(ExperimentalCoroutinesApi::class)
class GroupCardViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val dispatcherRule = TestDispatcherRule(dispatcher)
    private val source = mockk<GroupCardDataSource>()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val models = mutableListOf<GroupCardViewModel>()
    private val preloads = CardPreloads()

    @Before fun setUp() {
        every { source.changes(GID) } returns changes
    }

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
    }

    @Test
    fun `a snapshot read before the sheet showed is the first state`() = runTest(dispatcher) {
        coEvery { source.snapshot(GID, any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val key = preloads.put(Snapshot(group(), memberCount = 7))
        val vm = model(mapOf(GroupCardViewModel.ARG_GROUP_ID to GID, GroupCardViewModel.ARG_PRELOAD_KEY to key))
        
        assertEquals("Group", vm.state.value.name)
        assertEquals(7, vm.state.value.memberCount)
        assertFalse(vm.state.value.loading)
        assertEquals(null, preloads.take<Snapshot>(key))
    }

    @Test
    fun `invalid status rejects every action without state change or effects`() = runTest(dispatcher) {
        coEvery { source.snapshot(GID, any()) } returns Snapshot(group().apply { status = 1 })
        val vm = model()
        runCurrent()
        val before = vm.state.value
        vm.effect.test {
            Action.entries.forEach { vm.dispatch(Intent.Open(it)) }
            runCurrent()
            assertEquals(before, vm.state.value)
            expectNoEvents()
        }
    }

    @Test
    fun `forbidden invite explains the missing permission instead of navigating`() = runTest(dispatcher) {
        coEvery { source.snapshot(GID, any()) } returns Snapshot(group().apply { invitationRule = 1 },
            selfRole = GROUP_ROLE_MEMBER)
        val vm = model()
        runCurrent()
        val before = vm.state.value
        vm.effect.test {
            vm.dispatch(Intent.Open(Action.INVITE))
            runCurrent()
            assertEquals(before, vm.state.value)
            assertEquals(Effect.CannotInvite, awaitItem())
        }
    }

    @Test
    fun `call uses the same speak guard and a repeated tap produces a new effect`() = runTest(dispatcher) {
        coEvery { source.snapshot(GID, any()) } returns Snapshot(group(), canSpeak = false)
        val vm = model()
        runCurrent()
        vm.effect.test {
            repeat(2) {
                vm.dispatch(Intent.Open(Action.CALL))
                runCurrent()
                assertEquals(Effect.CannotSpeak, awaitItem())
            }
            coEvery { source.snapshot(GID, any()) } returns Snapshot(group(), canSpeak = true)
            vm.dispatch(Intent.Open(Action.CALL))
            runCurrent()
            assertEquals(Effect.Call(GID, "Group"), awaitItem())
        }
    }

    @Test
    fun `update event invalidates the card and refresh explicitly fetches details`() = runTest(dispatcher) {
        coEvery { source.snapshot(GID, any()) } returns Snapshot(group(), canSpeak = true)
        val vm = model()
        runCurrent()
        coEvery { source.snapshot(GID, any()) } returns Snapshot(null)
        changes.emit(Unit)
        runCurrent()
        assertFalse(vm.state.value.actionsEnabled)
        vm.dispatch(Intent.Refresh)
        runCurrent()
        coVerify(exactly = 1) { source.snapshot(GID, true) }
    }

    private fun model(args: Map<String, Any?> = mapOf(GroupCardViewModel.ARG_GROUP_ID to GID)) = GroupCardViewModel(
        SavedStateHandle(args), source,
        mockk { every { dayChanges } returns emptyFlow() },
        preloads,
    ).also(models::add)

    private fun group() = GroupModel().apply {
        gid = GID
        name = "Group"
        status = 0
        invitationRule = 2
    }

    private companion object { const val GID = "group" }
}
