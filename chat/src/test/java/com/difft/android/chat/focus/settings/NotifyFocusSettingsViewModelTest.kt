package com.difft.android.chat.focus.settings

import app.cash.turbine.test
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.FocusGroupDirectory
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.focus.GroupNotifySettingsReader
import com.difft.android.chat.focus.settings.NotifyFocusSettingsContract.Effect
import com.difft.android.chat.focus.settings.NotifyFocusSettingsContract.Intent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import kotlin.test.assertTrue

/** State mapping of the Me > Notifications focus block and the paused global group notification row. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotifyFocusSettingsViewModelTest {

    private val effective = MutableStateFlow(EffectiveFocus.INACTIVE)
    private val repository: GroupFocusRepository = mockk()
    private val groups: FocusGroupDirectory = mockk()
    private val reader: GroupNotifySettingsReader = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { repository.effective } returns effective
        coEvery { groups.name("A") } returns "Alpha"
        every { reader.globalLevel() } returns GlobalNotificationType.MENTION
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = NotifyFocusSettingsViewModel(repository, groups, reader) { 1_000L }

    private fun active(invalid: Boolean = false, until: Long? = null) = EffectiveFocus(
        featureEnabled = true,
        active = true,
        invalid = invalid,
        groupIds = if (invalid) emptySet() else setOf("A"),
        until = until,
    )

    @Test
    fun `feature off shows no focus block and keeps the plain group row`() = runTest {
        effective.value = EffectiveFocus(featureEnabled = false)
        val vm = viewModel()
        vm.dispatch(Intent.Refresh)

        val state = vm.state.value
        assertFalse(state.featureEnabled)
        assertFalse(state.focusOn)
        assertFalse(state.groupNotificationPaused)
    }

    @Test
    fun `idle shows the off caption and the global level`() = runTest {
        effective.value = EffectiveFocus(featureEnabled = true)
        val vm = viewModel()
        vm.dispatch(Intent.Refresh)

        val state = vm.state.value
        assertTrue(state.featureEnabled)
        assertFalse(state.focusOn)
        assertEquals(NotifyFocusCaption.Off, state.caption)
        assertEquals(GlobalNotificationType.MENTION, state.groupLevel)
        assertFalse(state.groupNotificationPaused)
    }

    @Test
    fun `focusing names the group and pauses the global group notification`() = runTest {
        effective.value = active(until = null)
        val vm = viewModel()
        vm.dispatch(Intent.Refresh)

        val state = vm.state.value
        assertTrue(state.focusOn)
        assertEquals(NotifyFocusCaption.Focusing("Alpha"), state.caption)
        assertEquals(FocusEnd.Manual, state.end)
        assertTrue(state.groupNotificationPaused)
        assertEquals(GlobalNotificationType.MENTION, state.groupLevel)
        assertFalse(state.invalid)
    }

    @Test
    fun `invalid state keeps focus on with the error caption and the reselect entry`() = runTest {
        effective.value = active(invalid = true)
        val vm = viewModel()

        val state = vm.state.value
        assertTrue(state.focusOn)
        assertTrue(state.invalid)
        assertEquals(NotifyFocusCaption.Invalid, state.caption)
        assertTrue(state.groupNotificationPaused)
    }

    @Test
    fun `switch and rows route to effects`() = runTest {
        effective.value = active()
        val vm = viewModel()
        vm.effect.test {
            vm.dispatch(Intent.ToggleFocus(true))
            assertEquals(Effect.OpenGroupPicker, awaitItem())
            vm.dispatch(Intent.ToggleFocus(false))
            assertEquals(Effect.EndFocus, awaitItem())
            vm.dispatch(Intent.TapEndTime)
            assertEquals(Effect.ChangeEndTime, awaitItem())
            vm.dispatch(Intent.TapOthersStillNotify)
            assertEquals(Effect.OpenOthersStillNotify, awaitItem())
            vm.dispatch(Intent.TapSelectGroup)
            assertEquals(Effect.OpenGroupPicker, awaitItem())
            vm.dispatch(Intent.TapGroupNotificationPaused)
            assertEquals(Effect.ShowPausedDialog, awaitItem())
        }
    }
}
