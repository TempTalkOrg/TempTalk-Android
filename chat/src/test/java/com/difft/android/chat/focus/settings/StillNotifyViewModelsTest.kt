package com.difft.android.chat.focus.settings

import app.cash.turbine.test
import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.FocusError
import com.difft.android.chat.focus.FocusGroupDirectory
import com.difft.android.chat.focus.FocusGroupItem
import com.difft.android.chat.focus.FocusResult
import com.difft.android.chat.focus.FocusSnapshot
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.focus.GroupNotifyPolicy.MuteOptions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue


@OptIn(ExperimentalCoroutinesApi::class)
class StillNotifyViewModelsTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    

    private val effective = MutableStateFlow(EffectiveFocus(featureEnabled = true, othersAllowMentionMe = true))
    private val repository: GroupFocusRepository = mockk()

    private fun othersVm(): FocusOthersNotifyViewModel {
        every { repository.effective } returns effective
        return FocusOthersNotifyViewModel(repository)
    }

    @Test
    fun `others page mirrors the focus options`() = runTest {
        val vm = othersVm()
        assertEquals(StillNotifyState(mentionMe = true, mentionAll = false, groupCall = false), vm.state.value.items)

        effective.value = effective.value.copy(othersAllowMentionAll = true, othersAllowGroupCall = true)
        assertEquals(StillNotifyState(true, true, true), vm.state.value.items)
    }

    @Test
    fun `others toggle shows the requested value at once and keeps it on success`() = runTest {
        val gate = CompletableDeferred<FocusResult>()
        coEvery { repository.updateOptions(othersAllowMentionAll = true) } coAnswers { gate.await() }
        val vm = othersVm()

        vm.dispatch(FocusOthersNotifyContract.Intent.Toggle(StillNotifyItem.MENTION_ALL, true))
        assertTrue(vm.state.value.items.mentionAll)

        
        effective.value = effective.value.copy(othersAllowMentionAll = true)
        gate.complete(FocusResult.Success(FocusSnapshot()))
        assertTrue(vm.state.value.items.mentionAll)
        coVerify(exactly = 1) { repository.updateOptions(othersAllowMentionAll = true) }
    }

    @Test
    fun `others toggle rolls back and reports when the write fails`() = runTest {
        coEvery { repository.updateOptions(othersAllowGroupCall = true) } returns FocusResult.Failure(FocusError.Network("x"))
        val vm = othersVm()

        vm.effect.test {
            vm.dispatch(FocusOthersNotifyContract.Intent.Toggle(StillNotifyItem.GROUP_CALL, true))
            assertEquals(FocusOthersNotifyContract.Effect.ShowError, awaitItem())
        }
        assertFalse(vm.state.value.items.groupCall)
    }

    

    @Test
    fun `mute exceptions default and follow the stored options`() = runTest {
        val vm = MuteExceptionsViewModel()
        assertEquals(StillNotifyState(true, false, false), vm.state.value.items)

        vm.dispatch(MuteExceptionsContract.Intent.ConversationChanged(MuteOptions(false, true, null)))
        assertEquals(StillNotifyState(false, true, false), vm.state.value.items)
    }

    @Test
    fun `mute exception toggle is optimistic and falls back when the stored value did not change`() = runTest {
        val vm = MuteExceptionsViewModel()
        vm.dispatch(MuteExceptionsContract.Intent.ConversationChanged(MuteOptions(true, false, false)))

        vm.effect.test {
            vm.dispatch(MuteExceptionsContract.Intent.Toggle(StillNotifyItem.MENTION_ALL, true))
            assertEquals(MuteExceptionsContract.Effect.Write(StillNotifyItem.MENTION_ALL, true), awaitItem())
            assertTrue(vm.state.value.items.mentionAll)

            
            vm.dispatch(MuteExceptionsContract.Intent.Toggle(StillNotifyItem.MENTION_ALL, false))
            expectNoEvents()

            
            vm.dispatch(MuteExceptionsContract.Intent.WriteFinished(StillNotifyItem.MENTION_ALL))
            assertFalse(vm.state.value.items.mentionAll)

            
            vm.dispatch(MuteExceptionsContract.Intent.Toggle(StillNotifyItem.GROUP_CALL, true))
            assertEquals(MuteExceptionsContract.Effect.Write(StillNotifyItem.GROUP_CALL, true), awaitItem())
            vm.dispatch(MuteExceptionsContract.Intent.ConversationChanged(MuteOptions(true, false, true)))
            vm.dispatch(MuteExceptionsContract.Intent.WriteFinished(StillNotifyItem.GROUP_CALL))
            assertTrue(vm.state.value.items.groupCall)
        }
    }

    

    private val directory: FocusGroupDirectory = mockk()

    private fun item(gid: String, name: String) = FocusGroupItem(gid, name, memberCount = 3, avatar = null)

    @Test
    fun `picker lists groups, filters by name and enables Done only after a selection`() = runTest {
        coEvery { directory.activeGroups() } returns listOf(item("1", "Design Review"), item("2", "Tasks Platform"))
        every { repository.effectiveNow() } returns EffectiveFocus.INACTIVE
        every { repository.effective } returns MutableStateFlow(EffectiveFocus.INACTIVE)
        val vm = FocusGroupPickerViewModel(directory, repository)

        vm.dispatch(FocusGroupPickerContract.Intent.Load)
        assertFalse(vm.state.value.loading)
        assertEquals(listOf("1", "2"), vm.state.value.groups.map { it.gid })
        assertFalse(vm.state.value.canConfirm)

        vm.dispatch(FocusGroupPickerContract.Intent.QueryChanged("task"))
        assertEquals(listOf("2"), vm.state.value.groups.map { it.gid })

        vm.effect.test {
            
            vm.dispatch(FocusGroupPickerContract.Intent.Done)
            expectNoEvents()

            vm.dispatch(FocusGroupPickerContract.Intent.Select("2"))
            assertTrue(vm.state.value.canConfirm)
            assertEquals("2", vm.state.value.selectedGid)
            vm.dispatch(FocusGroupPickerContract.Intent.Done)
            assertEquals(FocusGroupPickerContract.Effect.Finish("2"), awaitItem())

            vm.dispatch(FocusGroupPickerContract.Intent.Cancel)
            assertEquals(FocusGroupPickerContract.Effect.Finish(null), awaitItem())
        }
        assertNull(vm.state.value.groups.firstOrNull { it.gid == "1" })
    }

    @Test
    fun `while focusing the current group starts selected and only another group enables Done`() = runTest {
        coEvery { directory.activeGroups() } returns listOf(item("1", "Design Review"), item("2", "Tasks Platform"))
        val focusing = EffectiveFocus(featureEnabled = true, active = true, groupIds = setOf("1"))
        every { repository.effectiveNow() } returns focusing
        every { repository.effective } returns MutableStateFlow(focusing)
        val vm = FocusGroupPickerViewModel(directory, repository)

        vm.dispatch(FocusGroupPickerContract.Intent.Load)
        assertEquals("1", vm.state.value.selectedGid)
        assertFalse(vm.state.value.canConfirm)

        vm.effect.test {
            vm.dispatch(FocusGroupPickerContract.Intent.Done)
            expectNoEvents()
            vm.dispatch(FocusGroupPickerContract.Intent.Select("2"))
            assertTrue(vm.state.value.canConfirm)
            vm.dispatch(FocusGroupPickerContract.Intent.Select("1"))
            assertFalse(vm.state.value.canConfirm)
            vm.dispatch(FocusGroupPickerContract.Intent.Select("2"))
            vm.dispatch(FocusGroupPickerContract.Intent.Done)
            assertEquals(FocusGroupPickerContract.Effect.Finish("2"), awaitItem())
        }
    }

    @Test
    fun `focus ending while the picker is open makes the preselected group choosable`() = runTest {
        coEvery { directory.activeGroups() } returns listOf(item("1", "Design Review"))
        val focusing = EffectiveFocus(featureEnabled = true, active = true, groupIds = setOf("1"))
        val effective = MutableStateFlow(focusing)
        every { repository.effectiveNow() } returns focusing
        every { repository.effective } returns effective
        val vm = FocusGroupPickerViewModel(directory, repository)
        vm.dispatch(FocusGroupPickerContract.Intent.Load)
        assertFalse(vm.state.value.canConfirm)

        effective.value = EffectiveFocus(featureEnabled = true)
        assertEquals("1", vm.state.value.selectedGid)
        assertTrue(vm.state.value.canConfirm)
    }

    @Test
    fun `an invalid focus preselects nothing`() = runTest {
        coEvery { directory.activeGroups() } returns listOf(item("1", "Design Review"))
        val invalid = EffectiveFocus(featureEnabled = true, active = true, invalid = true, groupIds = setOf("1"))
        every { repository.effectiveNow() } returns invalid
        every { repository.effective } returns MutableStateFlow(invalid)
        val vm = FocusGroupPickerViewModel(directory, repository)
        vm.dispatch(FocusGroupPickerContract.Intent.Load)
        assertNull(vm.state.value.selectedGid)
    }
}
