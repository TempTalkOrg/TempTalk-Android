package com.difft.android.chat.focus.settings

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.FocusError
import com.difft.android.chat.focus.FocusGroupDirectory
import com.difft.android.chat.focus.FocusResult
import com.difft.android.chat.focus.FocusTimeFormat
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.focus.GroupNotifySettingsReader
import com.difft.android.chat.focus.settings.GroupFocusSettingsContract.Effect
import com.difft.android.chat.focus.settings.GroupFocusSettingsContract.Intent
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
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue


@OptIn(ExperimentalCoroutinesApi::class)
class GroupFocusSettingsViewModelTest {

    private val gid = "g-self"
    private val now = Instant.parse("2026-10-28T10:00:00Z").toEpochMilli()
    private val effective = MutableStateFlow(EffectiveFocus.INACTIVE)
    private val repository: GroupFocusRepository = mockk()
    private val groups: FocusGroupDirectory = mockk()
    private val reader: GroupNotifySettingsReader = mockk()

    private var defaultZone: TimeZone = TimeZone.getDefault()
    private var defaultLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        every { repository.effective } returns effective
        coEvery { groups.name("g-other") } returns "Other Group"
        coEvery { groups.name(gid) } returns "Self Group"
        coEvery { reader.level(any()) } returns GlobalNotificationType.MENTION
        every { reader.soundSupported() } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    private fun viewModel() = GroupFocusSettingsViewModel(SavedStateHandle(mapOf("groupId" to gid)), repository, groups, reader) { now }

    private fun focus(
        gids: Set<String> = setOf(gid),
        until: Long? = null,
        invalid: Boolean = false,
        feature: Boolean = true,
        me: Boolean = true,
        all: Boolean = false,
        call: Boolean = false,
    ) = EffectiveFocus(
        featureEnabled = feature,
        active = true,
        invalid = invalid,
        groupIds = if (invalid) emptySet() else gids,
        until = until,
        othersAllowMentionMe = me,
        othersAllowMentionAll = all,
        othersAllowGroupCall = call,
        foldOthers = false,
    )

    
    private fun at(until: Long): FocusEnd.At =
        FocusTimeFormat.endLabel(until, now).let { FocusEnd.At(it.time, it.tomorrow) }

    private fun GroupFocusSettingsViewModel.conversation(muted: Boolean, me: Boolean? = null, all: Boolean? = null, call: Boolean? = null) =
        dispatch(Intent.ConversationChanged(muted, me, all, call))

    @Test
    fun `feature off hides the focus card and never pauses rows`() = runTest {
        effective.value = EffectiveFocus(featureEnabled = false)
        val vm = viewModel()
        vm.conversation(muted = true)
        vm.dispatch(Intent.Refresh)

        val state = vm.state.value
        assertFalse(state.featureEnabled)
        assertFalse(state.mutePaused)
        assertFalse(state.notifyPaused)
        assertFalse(state.showMuteExceptions)
        assertEquals(TakeoverCaption.None, state.takeoverCaption)
        assertTrue(state.muted)
    }

    @Test
    fun `idle group shows the hint caption and the level value`() = runTest {
        effective.value = EffectiveFocus(featureEnabled = true)
        val vm = viewModel()
        vm.dispatch(Intent.Refresh)

        val state = vm.state.value
        assertEquals(FocusCaption.Off, state.focusCaption)
        assertFalse(state.focusOn)
        assertFalse(state.notifyPaused)
        assertEquals(GlobalNotificationType.MENTION, state.level)
        assertEquals(TakeoverCaption.None, state.takeoverCaption)
    }

    @Test
    fun `muted group without focus pauses notifications and lists the still-notify summary`() = runTest {
        effective.value = EffectiveFocus(featureEnabled = true)
        val vm = viewModel()
        vm.dispatch(Intent.Refresh)
        vm.conversation(muted = true)

        val state = vm.state.value
        assertTrue(state.showMuteExceptions)
        assertTrue(state.notifyPaused)
        assertFalse(state.mutePaused)
        
        assertEquals(listOf(StillNotifyItem.MENTION_ME), state.muteExceptions)
        assertEquals(TakeoverCaption.ByMute(GlobalNotificationType.MENTION), state.takeoverCaption)

        vm.conversation(muted = true, me = false, all = true, call = true)
        assertEquals(listOf(StillNotifyItem.MENTION_ALL, StillNotifyItem.GROUP_CALL), vm.state.value.muteExceptions)

        vm.conversation(muted = true, me = false, all = false, call = false)
        assertEquals(emptyList(), vm.state.value.muteExceptions)
    }

    @Test
    fun `focus group shows the end time rows and pauses mute and notifications`() = runTest {
        effective.value = focus(until = now + 3_600_000, me = true, all = true)
        val vm = viewModel()
        vm.dispatch(Intent.Refresh)
        vm.conversation(muted = true)

        val state = vm.state.value
        assertTrue(state.focusOn)
        assertEquals(FocusCaption.OthersMuted, state.focusCaption)
        assertEquals(at(now + 3_600_000), state.focusEnd)
        assertEquals(listOf(StillNotifyItem.MENTION_ME, StillNotifyItem.MENTION_ALL), state.othersStillNotify)
        assertFalse(state.foldOthers)
        assertTrue(state.mutePaused)
        assertTrue(state.notifyPaused)
        
        assertFalse(state.showMuteExceptions)
        assertEquals(TakeoverCaption.ByFocus(muted = true, level = GlobalNotificationType.MENTION), state.takeoverCaption)
    }

    @Test
    fun `end time on another day is flagged as tomorrow and manual end has none`() = runTest {
        effective.value = focus(until = now + 24 * 3_600_000L)
        val vm = viewModel()
        assertEquals(at(now + 24 * 3_600_000L), vm.state.value.focusEnd)

        effective.value = focus(until = null)
        assertEquals(FocusEnd.Manual, vm.state.value.focusEnd)
    }

    @Test
    fun `another focused group is named in the caption with its end time`() = runTest {
        effective.value = focus(gids = setOf("g-other"), until = now + 3_600_000)
        val vm = viewModel()
        vm.conversation(muted = false)

        val state = vm.state.value
        assertFalse(state.focusOn)
        assertNull(state.focusEnd)
        assertEquals(FocusCaption.OtherGroup("Other Group", at(now + 3_600_000)), state.focusCaption)
        assertTrue(state.mutePaused)
        assertTrue(state.notifyPaused)
        assertEquals(TakeoverCaption.ByFocus(muted = false, level = GlobalNotificationType.ALL), state.takeoverCaption)

        effective.value = focus(gids = setOf("g-other"), until = null)
        assertEquals(FocusCaption.OtherGroup("Other Group", FocusEnd.Manual), vm.state.value.focusCaption)
    }

    @Test
    fun `invalid focus keeps every group paused and shows the error caption`() = runTest {
        effective.value = focus(invalid = true, until = now + 3_600_000)
        val vm = viewModel()

        val state = vm.state.value
        assertEquals(FocusCaption.Invalid, state.focusCaption)
        assertFalse(state.focusOn)
        assertTrue(state.mutePaused)
        assertTrue(state.notifyPaused)
    }

    @Test
    fun `sound row follows platform support and the read label`() = runTest {
        effective.value = EffectiveFocus(featureEnabled = true)
        val vm = viewModel()
        vm.dispatch(Intent.Refresh)
        assertFalse(vm.state.value.soundVisible)

        every { reader.soundSupported() } returns true
        coEvery { reader.soundLabel(gid) } returns "Spaceline"
        vm.dispatch(Intent.Refresh)
        assertTrue(vm.state.value.soundVisible)
        assertEquals("Spaceline", vm.state.value.soundLabel)
    }

    @Test
    fun `focus switch routes to the launcher effects`() = runTest {
        effective.value = EffectiveFocus(featureEnabled = true)
        val vm = viewModel()
        vm.dispatch(Intent.Refresh)
        vm.effect.test {
            vm.dispatch(Intent.ToggleFocus(true))
            assertEquals(Effect.RequestFocus(gid, "Self Group"), awaitItem())

            
            vm.dispatch(Intent.ToggleFocus(false))
            expectNoEvents()

            effective.value = focus()
            vm.dispatch(Intent.ToggleFocus(false))
            assertEquals(Effect.EndFocus, awaitItem())
        }
    }

    @Test
    fun `tapping a paused row explains why`() = runTest {
        effective.value = focus(gids = setOf("g-other"))
        val vm = viewModel()
        vm.effect.test {
            vm.dispatch(Intent.TapMutePaused)
            assertEquals(Effect.ShowPausedDialog(PausedDialog.MUTE_BY_FOCUS), awaitItem())
            vm.dispatch(Intent.TapNotification)
            assertEquals(Effect.ShowPausedDialog(PausedDialog.NOTIFY_BY_FOCUS), awaitItem())

            effective.value = EffectiveFocus(featureEnabled = true)
            vm.conversation(muted = true)
            vm.dispatch(Intent.TapNotification)
            assertEquals(Effect.ShowPausedDialog(PausedDialog.NOTIFY_BY_MUTE), awaitItem())

            vm.conversation(muted = false)
            vm.dispatch(Intent.TapNotification)
            assertEquals(Effect.OpenNotificationLevel, awaitItem())
        }
    }

    @Test
    fun `a failed fold update surfaces an error and a mute toggle goes to the write path`() = runTest {
        effective.value = focus()
        coEvery { repository.updateOptions(foldOthers = true) } returns FocusResult.Failure(FocusError.Network("x"))
        val vm = viewModel()
        vm.effect.test {
            vm.dispatch(Intent.SetFoldOthers(true))
            assertEquals(Effect.ShowError, awaitItem())
            vm.dispatch(Intent.ToggleMute(true))
            assertEquals(Effect.WriteMute(true), awaitItem())
        }
    }
}
