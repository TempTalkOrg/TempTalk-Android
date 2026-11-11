package com.difft.android.chat.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.difft.android.chat.group.GroupMemberListContract.Intent
import com.difft.android.chat.group.GroupMemberListContract.Member
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
import org.difft.app.database.models.ContactorModel
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class GroupMemberListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val dispatcherRule = TestDispatcherRule(dispatcher)
    private val source = mockk<GroupMemberListSource>()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Test fun `a saved query survives recreation and new queries are saved`() = runTest(dispatcher) {
        every { source.changes("g") } returns changes
        coEvery { source.members("g", any()) } returns GroupMemberListSource.Read(emptyList(), null)
        val handle = SavedStateHandle(mapOf("groupId" to "g", "query" to "zo"))
        val vm = GroupMemberListViewModel(handle, source)
        try {
            runCurrent()
            assertEquals("zo", vm.state.value.query)
            vm.dispatch(Intent.QueryChanged("amy"))
            assertEquals("amy", GroupMemberListViewModel(handle, source).also { it.viewModelScope.cancel() }.state.value.query)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `once the server answered it is not asked again, so a gone group cannot loop`() = runTest(dispatcher) {
        every { source.changes("g") } returns changes
        coEvery { source.members("g", true) } returns GroupMemberListSource.Read(emptyList(), serverAnswered = true)
        coEvery { source.members("g", false) } returns GroupMemberListSource.Read(emptyList(), null)
        val vm = GroupMemberListViewModel(SavedStateHandle(mapOf("groupId" to "g")), source)
        try {
            runCurrent()
            changes.emit(Unit)
            runCurrent()
            vm.dispatch(Intent.QueryChanged("a"))
            runCurrent()
            coVerify(exactly = 1) { source.members("g", true) }
            coVerify(exactly = 1) { source.members("g", false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `a failed fetch is retried by the next read and by a new query`() = runTest(dispatcher) {
        every { source.changes("g") } returns changes
        coEvery { source.members("g", true) } returns GroupMemberListSource.Read(emptyList(), serverAnswered = false)
        val vm = GroupMemberListViewModel(SavedStateHandle(mapOf("groupId" to "g")), source)
        try {
            runCurrent()
            vm.dispatch(Intent.QueryChanged("a"))
            runCurrent()
            changes.emit(Unit)
            runCurrent()
            coVerify(exactly = 3) { source.members("g", true) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `a contact or group change rereads the members`() = runTest(dispatcher) {
        every { source.changes("g") } returns changes
        var name = "Alice"
        coEvery { source.members("g", any()) } answers {
            GroupMemberListSource.Read(listOf(Member("a", name, ContactorModel().apply { id = "a" }, GROUP_ROLE_MEMBER)), null)
        }
        val vm = GroupMemberListViewModel(SavedStateHandle(mapOf("groupId" to "g")), source)
        try {
            runCurrent()
            assertEquals("Alice", vm.state.value.members.single().name)
            name = "Designer"
            changes.emit(Unit)
            runCurrent()
            assertEquals("Designer", vm.state.value.members.single().name)
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}
