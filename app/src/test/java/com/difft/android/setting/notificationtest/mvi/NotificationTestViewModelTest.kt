package com.difft.android.setting.notificationtest.mvi

import androidx.lifecycle.viewModelScope
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationReadiness
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestOutcome
import com.difft.android.base.notification.NotificationTestRepository
import com.difft.android.setting.notificationtest.NotificationTestDevice
import com.difft.android.setting.notificationtest.NotificationTestPoster
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Dialog
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Intent
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Notice
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Phase
import com.difft.android.test.TestDispatcherRule
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * #1350: the notification test runs (banner, lock screen, call) against a mocked repository, poster
 * and device. Time is virtual: the main and IO dispatchers share one test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationTestViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule(StandardTestDispatcher())

    private val shown = MutableStateFlow(0L)
    private val events = mutableListOf<String>()
    private var wakeLockReleases = 0
    private lateinit var repository: NotificationTestRepository
    private lateinit var poster: NotificationTestPoster
    private lateinit var device: NotificationTestDevice
    private lateinit var vm: NotificationTestViewModel

    @Before
    fun setup() {
        repository = mockk {
            every { callShown } returns shown
            coEvery { readReadiness() } returns NotificationReadiness(items = emptyList())
            coEvery { fingerprint(any()) } returns "fp"
            every { notePostedCall(any()) } just Runs
            coEvery { record(any(), any(), any(), any()) } just Runs
            // Mirrors the repository contract: no FAIL once the call screen has opened since the post.
            coEvery { recordCallTimeout(any(), any(), any()) } answers { shown.value < secondArg<Long>() }
        }
        poster = mockk {
            every { post(any()) } answers {
                events += "posted"
                true
            }
            every { cancel(any()) } answers { events += "cancel" }
        }
        device = mockk {
            every { isDndOn() } returns false
            every { isKeyguardLocked() } returns true
            every { isInteractive() } returns false
            every { isDeviceSecure() } returns true
            every { screenOffEvents() } returns flowOf(Unit)
            every { holdWakeLock(any()) } returns { wakeLockReleases++ }
        }
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    private fun TestScope.buildViewModel(): NotificationTestViewModel {
        vm = NotificationTestViewModel(repository, poster, device, StandardTestDispatcher(testScheduler))
        runCurrent()
        return vm
    }

    private fun TestScope.start(kind: NotificationTestKind) {
        vm.dispatch(Intent.Start(kind))
        runCurrent()
        assertEquals(Dialog.Intro(kind), vm.state.value.dialog)
        vm.dispatch(Intent.ConfirmStart)
        runCurrent()
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `a banner run posts after the countdown and then asks`() = runTest {
        buildViewModel()
        start(NotificationTestKind.BANNER)
        assertEquals(Phase.COUNTDOWN, vm.state.value.phase)

        advance(4_999)
        verify(exactly = 0) { poster.post(any()) }
        advance(1)
        verify(exactly = 1) { poster.post(NotificationTestKind.BANNER) }
        assertEquals(Phase.POSTED, vm.state.value.phase)
        assertNull(vm.state.value.dialog)

        advance(3_000)
        assertEquals(Dialog.Question(NotificationTestKind.BANNER), vm.state.value.dialog)
    }

    @Test
    fun `an answer is recorded under the fingerprint taken when the notification was posted`() = runTest {
        coEvery { repository.fingerprint(NotificationTestKind.BANNER) } returnsMany listOf("fp-posted", "fp-later")
        buildViewModel()
        start(NotificationTestKind.BANNER)
        advance(8_000)

        vm.dispatch(Intent.Answer(seen = false))
        runCurrent()

        coVerify(exactly = 1) { repository.record(NotificationTestKind.BANNER, NotificationTestOutcome.FAIL, "fp-posted", any()) }
        coVerify(exactly = 1) { repository.fingerprint(NotificationTestKind.BANNER) }
        assertEquals(Dialog.Failed(NotificationTestKind.BANNER, NotificationFix.MESSAGE_CATEGORY), vm.state.value.dialog)
        assertNull(vm.state.value.running)
    }

    @Test
    fun `a lock-screen run is voided when the device is unlocked and in use again`() = runTest {
        every { device.isKeyguardLocked() } returns false
        every { device.isInteractive() } returns true
        buildViewModel()
        start(NotificationTestKind.LOCKSCREEN)

        advance(2_000)

        verify(exactly = 0) { poster.post(any()) }
        assertEquals(Dialog.Info(Notice.VOID_UNLOCKED), vm.state.value.dialog)
        assertNull(vm.state.value.running)
    }

    @Test
    fun `without a keyguard a lock-screen run is voided after the settle time`() = runTest {
        every { device.isKeyguardLocked() } returns false
        every { device.isDeviceSecure() } returns false
        buildViewModel()
        start(NotificationTestKind.LOCKSCREEN)

        advance(5_000)

        verify(exactly = 0) { poster.post(any()) }
        assertEquals(Dialog.Info(Notice.VOID_NOT_LOCKED), vm.state.value.dialog)
        assertNull(vm.state.value.running)
    }

    @Test
    fun `without a keyguard a call run posts once the screen has stayed off for the settle time`() = runTest {
        every { device.isKeyguardLocked() } returns false
        every { device.isDeviceSecure() } returns false
        buildViewModel()
        start(NotificationTestKind.CALL)

        advance(4_999)
        verify(exactly = 0) { poster.post(any()) }
        advance(1)

        verify(exactly = 1) { poster.post(NotificationTestKind.CALL) }
        assertEquals(Phase.WAIT_CALL, vm.state.value.phase)
    }

    @Test
    fun `a delayed keyguard is waited for and the run posts as soon as it locks`() = runTest {
        every { device.isKeyguardLocked() } answers { testScheduler.currentTime >= 3_000 }
        buildViewModel()
        start(NotificationTestKind.LOCKSCREEN)

        advance(2_999)
        verify(exactly = 0) { poster.post(any()) }
        advance(1)

        verify(exactly = 1) { poster.post(NotificationTestKind.LOCKSCREEN) }
    }

    @Test
    fun `a call run that times out without the call screen records a failure`() = runTest {
        buildViewModel()
        start(NotificationTestKind.CALL)
        advance(2_000)
        assertEquals(Phase.WAIT_CALL, vm.state.value.phase)
        val postedAt = vm.state.value.postedAt
        verify { repository.notePostedCall("fp") }

        advance(15_000)

        coVerify(exactly = 1) { repository.recordCallTimeout("fp", postedAt, any()) }
        assertEquals(NotificationTestKind.CALL, assertIs<Dialog.Failed>(vm.state.value.dialog).kind)
        assertNull(vm.state.value.running)
        verify { poster.cancel(NotificationTestKind.CALL) }
        // The screen-off phase held a wake lock and let it go when the run ended.
        verify(exactly = 1) { device.holdWakeLock(any()) }
        assertEquals(1, wakeLockReleases)
    }

    @Test
    fun `a call screen opening in time ends the run without a failure`() = runTest {
        buildViewModel()
        start(NotificationTestKind.CALL)
        advance(2_000)

        shown.value = System.currentTimeMillis()
        runCurrent()
        assertNull(vm.state.value.running)
        advance(15_000)

        coVerify(exactly = 0) { repository.recordCallTimeout(any(), any(), any()) }
        assertNull(vm.state.value.dialog)
    }

    @Test
    fun `a call screen opening right at the timeout records no failure`() = runTest {
        // The timeout path cancels the notification first; the screen opens in that window.
        every { poster.cancel(NotificationTestKind.CALL) } answers { shown.value = System.currentTimeMillis() }
        buildViewModel()
        start(NotificationTestKind.CALL)

        advance(2_000 + 15_000)

        coVerify(exactly = 0) { repository.recordCallTimeout(any(), any(), any()) }
        coVerify(exactly = 0) { repository.record(any(), any(), any(), any()) }
        assertNull(vm.state.value.dialog)
        assertNull(vm.state.value.running)
    }

    @Test
    fun `an exception ends the run with a notice and a later start still runs`() = runTest {
        var reads = 0
        coEvery { repository.fingerprint(NotificationTestKind.BANNER) } answers {
            if (reads++ == 0) throw IllegalStateException("boom") else "fp"
        }
        buildViewModel()
        start(NotificationTestKind.BANNER)

        advance(5_000)
        assertEquals(Dialog.Info(Notice.POST_FAILED), vm.state.value.dialog)
        assertNull(vm.state.value.running)
        verify(exactly = 0) { poster.post(any()) }
        verify { poster.cancel(NotificationTestKind.BANNER) }

        vm.dispatch(Intent.DismissDialog)
        start(NotificationTestKind.BANNER)
        advance(8_000)
        verify(exactly = 1) { poster.post(NotificationTestKind.BANNER) }
        assertEquals(Dialog.Question(NotificationTestKind.BANNER), vm.state.value.dialog)
    }

    @Test
    fun `a cancel while the post is in flight takes the notification back after it lands`() = runTest {
        every { poster.post(NotificationTestKind.BANNER) } answers {
            vm.dispatch(Intent.Cancel)
            events += "posted"
            true
        }
        buildViewModel()
        start(NotificationTestKind.BANNER)

        advance(5_000)

        assertEquals(listOf("cancel", "posted", "cancel"), events)
        advance(3_000)
        assertNull(vm.state.value.running)
        assertNull(vm.state.value.dialog)
    }
}
