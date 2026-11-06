package com.difft.android.chat.group

import androidx.lifecycle.SavedStateHandle
import com.difft.android.base.storage.user.CredentialChangeSignal
import com.difft.android.chat.group.mvi.GroupMutationRepository
import com.difft.android.chat.group.mvi.GroupPageContract.Intent
import com.difft.android.chat.group.mvi.GroupPageContract.MemberAction
import com.difft.android.chat.group.mvi.GroupPageContract.Page
import com.difft.android.chat.group.mvi.GroupPageStore
import com.difft.android.chat.group.mvi.GroupPageViewModel
import com.difft.android.chat.group.mvi.toGroupMemberRow
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.difft.app.database.models.ContactorModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Member search matches the displayed name, the raw contact names and the uid (PR #1461 review). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupPageMemberSearchTest {
    @get:Rule(order = 0) val dispatcher = TestDispatcherRule()
    @get:Rule(order = 1) val globals = GlobalStaticMockRule("owner")

    private fun viewModel(vararg members: GroupMemberModel): GroupPageViewModel {
        val store = mockk<GroupPageStore> {
            every { snapshot("group") } returns GroupPageStore.Snapshot("group", 0, 2, 2, false, false, false, members.toList())
        }
        val util = mockk<GroupUtil> {
            every { singleGroupsUpdate } returns MutableSharedFlow()
            coEvery { fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } returns null
        }
        val mutations = mockk<GroupMutationRepository> { every { pendingFor(any(), any()) } returns null }
        val credentials = mockk<CredentialChangeSignal> { every { credentialHash } returns MutableStateFlow("login-a") }
        val arguments = SavedStateHandle(mapOf(
            GroupPageViewModel.ARG_GID to "group", GroupPageViewModel.ARG_PAGE to Page.MEMBERS.name,
            GroupPageViewModel.ARG_TYPE to MemberAction.REMOVE.name,
        ))
        return GroupPageViewModel(arguments, util, mutations, store, credentials)
    }

    @Test fun `a remarked contact is found by its public name, its remark and its uid`() = runBlocking {
        val owner = GroupMemberModel("Owner", "owner", null, null, "O", GROUP_ROLE_OWNER)
        val colleague = ContactorModel().apply { id = "member-2"; name = "alice.w"; publicName = "Alice"; remark = "同事" }.toGroupMemberRow()
        assertEquals("同事", colleague.name)
        val vm = viewModel(owner, colleague)
        vm.state.first { it.loaded }
        assertEquals(setOf("owner", "member-2"), vm.state.value.rows.map { it.uid }.toSet())

        vm.dispatch(Intent.Search("Alice"))
        assertEquals(listOf("member-2"), vm.state.value.rows.map { it.uid })
        vm.dispatch(Intent.Search("alice.w"))
        assertEquals(listOf("member-2"), vm.state.value.rows.map { it.uid })
        vm.dispatch(Intent.Search("同事"))
        assertEquals(listOf("member-2"), vm.state.value.rows.map { it.uid })
        vm.dispatch(Intent.Search("MEMBER-2"))
        assertEquals(listOf("member-2"), vm.state.value.rows.map { it.uid })
        vm.dispatch(Intent.Search("nobody"))
        assertEquals(emptyList(), vm.state.value.rows.map { it.uid })
        vm.dispatch(Intent.Search(""))
        assertEquals(2, vm.state.value.rows.size)
    }
}
