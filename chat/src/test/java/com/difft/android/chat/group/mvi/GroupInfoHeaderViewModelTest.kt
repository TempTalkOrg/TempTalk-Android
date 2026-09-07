package com.difft.android.chat.group.mvi

import android.content.Context
import app.cash.turbine.test
import com.difft.android.chat.R
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.group.GroupAvatarUploader
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.group.mvi.GroupInfoHeaderContract.Effect
import com.difft.android.chat.group.mvi.GroupInfoHeaderContract.ExitSource
import com.difft.android.chat.group.mvi.GroupInfoHeaderContract.Intent
import com.difft.android.network.BaseResponse
import com.difft.android.network.group.ChangeGroupSettingsReq
import com.difft.android.network.group.GetGroupInfoResp
import com.difft.android.network.group.GroupRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.difft.app.database.models.GroupModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group header edit state machine: editability gating from crypto mode + key presence, name
 * submit validation / commit / discard, and avatar click routing by mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupInfoHeaderViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val appContext: Context = mockk(relaxed = true)
    private val groupRepo: GroupRepo = mockk()
    private val groupUtil: GroupUtil = mockk(relaxed = true)
    private val groupCryptoRepo: GroupCryptoRepo = mockk()
    private val groupAvatarUploader: GroupAvatarUploader = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { groupCryptoRepo.getRGroupBytes(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = GroupInfoHeaderViewModel(appContext, groupRepo, groupUtil, groupCryptoRepo, groupAvatarUploader)

    /** Load runs its key lookup on Dispatchers.IO, so wait for the state to reflect the new group. */
    private suspend fun GroupInfoHeaderViewModel.load(
        group: GroupModel,
        until: (GroupInfoHeaderContract.State) -> Boolean = { it.groupId == GID && it.name == group.name },
    ) {
        dispatch(Intent.Load(group))
        state.first(until)
    }

    private fun group(cryptoMode: Int = 0, name: String = "Team") = GroupModel().apply {
        gid = GID
        this.name = name
        groupCryptoMode = cryptoMode
    }

    private fun ok() = BaseResponse<GetGroupInfoResp>(ver = 1, status = 0, reason = null, data = null)
    private fun rejected(reason: String) = BaseResponse<GetGroupInfoResp>(ver = 1, status = 1, reason = reason, data = null)

    @Test
    fun `plain group - name editable, avatar not`() = runTest(dispatcher) {
        val vm = vm()
        vm.load(group(cryptoMode = 0))

        val s = vm.state.value
        assertTrue(s.nameEditable)
        assertFalse(s.avatarEditable)
        assertFalse(s.isEncrypted)
        assertEquals("Team", s.name)
    }

    @Test
    fun `encrypted group without key - nothing editable`() = runTest(dispatcher) {
        val vm = vm()
        vm.load(group(cryptoMode = 1))

        assertFalse(vm.state.value.nameEditable)
        assertFalse(vm.state.value.avatarEditable)
    }

    @Test
    fun `encrypted group with key - both editable`() = runTest(dispatcher) {
        every { groupCryptoRepo.getRGroupBytes(GID) } returns ByteArray(32)
        val vm = vm()
        vm.load(group(cryptoMode = 1))

        assertTrue(vm.state.value.nameEditable)
        assertTrue(vm.state.value.avatarEditable)
        assertTrue(vm.state.value.isEncrypted)
    }

    @Test
    fun `enter edit seeds editing name and discard restores it`() = runTest(dispatcher) {
        val vm = vm()
        vm.load(group())

        vm.dispatch(Intent.EnterEdit)
        assertTrue(vm.state.value.isEditing)
        assertEquals("Team", vm.state.value.editingName)

        vm.dispatch(Intent.ChangeName("Draft"))
        vm.dispatch(Intent.DiscardEdit)
        assertFalse(vm.state.value.isEditing)
        assertEquals("Team", vm.state.value.editingName)
        assertEquals("Team", vm.state.value.name)
        coVerify(exactly = 0) { groupRepo.changeGroupSettings(any(), any()) }
    }

    @Test
    fun `load while editing keeps the draft`() = runTest(dispatcher) {
        val vm = vm()
        vm.load(group())
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("Draft"))

        vm.load(group(name = "Team (renamed elsewhere)"))

        assertTrue(vm.state.value.isEditing)
        assertEquals("Draft", vm.state.value.editingName)
        assertEquals("Team (renamed elsewhere)", vm.state.value.name)
        assertEquals("Team", vm.state.value.editBaseline) // baseline frozen for the whole edit
    }

    @Test
    fun `request exit via back - clean finishes, dirty asks, discard finishes`() = runTest(dispatcher) {
        val vm = vm()
        vm.load(group())
        vm.dispatch(Intent.EnterEdit)

        vm.effect.test {
            vm.dispatch(Intent.RequestExit(ExitSource.Back))
            assertEquals(Effect.HideKeyboard, awaitItem())
            assertEquals(Effect.Finish, awaitItem())
            assertFalse(vm.state.value.isEditing)

            vm.dispatch(Intent.EnterEdit)
            vm.dispatch(Intent.ChangeName("Draft"))
            vm.dispatch(Intent.RequestExit(ExitSource.Back))
            assertEquals(Effect.ShowUnsavedDialog(ExitSource.Back), awaitItem())
            assertTrue(vm.state.value.isEditing)

            vm.dispatch(Intent.DiscardExit(ExitSource.Back))
            assertEquals(Effect.HideKeyboard, awaitItem())
            assertEquals(Effect.Finish, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { groupRepo.changeGroupSettings(any(), any()) }
    }

    @Test
    fun `request exit via outside tap - dirty asks, save exit submits and stays on the page`() = runTest(dispatcher) {
        coEvery { groupRepo.changeGroupSettings(GID, any()) } returns ok()
        val vm = vm()
        vm.load(group())
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("New name"))

        vm.effect.test {
            vm.dispatch(Intent.RequestExit(ExitSource.OutsideTap))
            assertEquals(Effect.ShowUnsavedDialog(ExitSource.OutsideTap), awaitItem())

            vm.dispatch(Intent.SaveExit(ExitSource.OutsideTap))
            assertEquals(Effect.ShowWait, awaitItem())
            assertEquals(Effect.DismissWait, awaitItem())
            assertEquals(Effect.HideKeyboard, awaitItem())
            expectNoEvents() // no Finish: an outside tap keeps the user on the page
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("New name", vm.state.value.name)
        assertFalse(vm.state.value.isEditing)
    }

    @Test
    fun `submit unchanged name exits without a request`() = runTest(dispatcher) {
        val vm = vm()
        vm.load(group())
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("  Team  "))

        vm.dispatch(Intent.SubmitName)

        assertFalse(vm.state.value.isEditing)
        coVerify(exactly = 0) { groupRepo.changeGroupSettings(any(), any()) }
    }

    @Test
    fun `submit empty or too long name toasts and stays in edit`() = runTest(dispatcher) {
        val vm = vm()
        vm.load(group())
        vm.dispatch(Intent.EnterEdit)

        vm.effect.test {
            vm.dispatch(Intent.ChangeName("   "))
            vm.dispatch(Intent.SubmitName)
            assertEquals(Effect.ShowToastRes(R.string.group_edit_name_empty), awaitItem())

            vm.dispatch(Intent.ChangeName("x".repeat(GroupInfoHeaderViewModel.MAX_GROUP_NAME_LENGTH + 1)))
            vm.dispatch(Intent.SubmitName)
            assertEquals(Effect.ShowToastRes(R.string.chat_group_name_too_long), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(vm.state.value.isEditing)
        coVerify(exactly = 0) { groupRepo.changeGroupSettings(any(), any()) }
    }

    @Test
    fun `submit new name on plain group sends name and commits`() = runTest(dispatcher) {
        val request = slot<ChangeGroupSettingsReq>()
        coEvery { groupRepo.changeGroupSettings(GID, capture(request)) } returns ok()
        val vm = vm()
        vm.load(group())
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("New name"))

        vm.dispatch(Intent.SubmitName)
        vm.state.first { !it.isEditing && !it.isSubmitting }

        assertEquals("New name", request.captured.name)
        assertNull(request.captured.encryptedName)
        assertEquals("New name", vm.state.value.name)
        assertFalse(vm.state.value.isEditing)
        assertFalse(vm.state.value.isSubmitting)
        coVerify(exactly = 1) { groupUtil.fetchAndSaveSingleGroupInfo(GID, true) }
    }

    @Test
    fun `submit rejected by server toasts reason and stays in edit`() = runTest(dispatcher) {
        coEvery { groupRepo.changeGroupSettings(GID, any()) } returns rejected("nope")
        val vm = vm()
        vm.load(group())
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("New name"))

        vm.effect.test {
            vm.dispatch(Intent.SubmitName)
            assertEquals(Effect.ShowWait, awaitItem())
            assertEquals(Effect.DismissWait, awaitItem())
            assertEquals(Effect.ShowToast("nope"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(vm.state.value.isEditing)
        assertEquals("Team", vm.state.value.name)
    }

    @Test
    fun `avatar click - browse previews only when there is an avatar`() = runTest(dispatcher) {
        val vm = vm()
        vm.load(group())

        vm.effect.test {
            vm.dispatch(Intent.AvatarClick)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `avatar click - edit mode requests picker only when avatar is editable`() = runTest(dispatcher) {
        val vm = vm()
        vm.load(group(cryptoMode = 0))
        vm.dispatch(Intent.EnterEdit)

        vm.effect.test {
            vm.dispatch(Intent.AvatarClick)
            expectNoEvents()

            every { groupCryptoRepo.getRGroupBytes(GID) } returns ByteArray(32)
            vm.load(group(cryptoMode = 1)) { it.avatarEditable }
            vm.dispatch(Intent.AvatarClick)
            assertIs<Effect.RequestPickAvatar>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `load arriving during a successful save is dropped, later loads apply`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { groupRepo.changeGroupSettings(GID, any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            ok()
        }
        val vm = vm()
        vm.load(group())
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("New name"))
        vm.dispatch(Intent.SubmitName)
        started.await()

        // A pre-save snapshot lands while the request is in flight.
        vm.dispatch(Intent.Load(group(name = "Team")))
        gate.complete(Unit)
        vm.state.first { !it.isEditing && !it.isSubmitting }

        assertEquals("New name", vm.state.value.name)

        // The save's own refresh (or any later load) still applies.
        vm.load(group(name = "Fresh from server"))
        assertEquals("Fresh from server", vm.state.value.name)
    }

    @Test
    fun `load arriving during a failed save is replayed afterwards`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { groupRepo.changeGroupSettings(GID, any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            rejected("nope")
        }
        val vm = vm()
        vm.load(group())
        vm.dispatch(Intent.EnterEdit)
        vm.dispatch(Intent.ChangeName("New name"))
        vm.dispatch(Intent.SubmitName)
        started.await()

        vm.dispatch(Intent.Load(group(name = "Renamed elsewhere")))
        gate.complete(Unit)

        vm.state.first { it.name == "Renamed elsewhere" }
        assertTrue(vm.state.value.isEditing)
    }

    private companion object {
        const val GID = "gid-1"
    }
}
