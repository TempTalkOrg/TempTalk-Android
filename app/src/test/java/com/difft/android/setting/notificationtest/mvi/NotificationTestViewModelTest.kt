package com.difft.android.setting.notificationtest.mvi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.difft.android.base.notification.NotificationTestCallUpdate
import com.difft.android.base.notification.NotificationTestClock
import com.difft.android.base.notification.NotificationTestRuns
import com.difft.android.base.notification.NotificationTestRun
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationReadiness
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestOutcome
import com.difft.android.base.notification.NotificationTestRepository
import com.difft.android.setting.notificationtest.NotificationTestLifetime
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





@OptIn(ExperimentalCoroutinesApi::class)
class NotificationTestViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule(StandardTestDispatcher())

    private val shown = MutableStateFlow<String?>(null)
    private val events = mutableListOf<String>()
    private var wakeLockReleases = 0
    private lateinit var repository: NotificationTestRepository
    private lateinit var poster: NotificationTestPoster
    private lateinit var device: NotificationTestDevice
    private val leases = NotificationTestRuns()
    private lateinit var vm: NotificationTestViewModel

    @Before
    fun setup() {
        repository = mockk {
            every { callShownRun } returns shown
            coEvery { readReadiness() } returns NotificationReadiness(items = emptyList())
            coEvery { fingerprint(any()) } returns "fp"
            coEvery { prepareCall(any(), any(), any()) } just Runs
            coEvery { cancelCall(any()) } just Runs
            coEvery { record(any(), any(), any(), any()) } just Runs
            
            coEvery { recordCallTimeout(any(), any()) } returns NotificationTestCallUpdate.RECORDED
        }
        poster = mockk {
            coEvery { post(any()) } answers {
                events += "posted"
                true
            }
            coEvery { cancel(any(), any()) } answers { events += "cancel" }
        }
        device = mockk {
            every { isDndOn() } returns false
            every { isKeyguardLocked() } returns true
            every { isInteractive() } returns false
            every { isDeviceSecure() } returns true
            every { opensVendorPopupDirectly() } returns false
            every { screenOffEvents() } returns flowOf(Unit)
            every { holdWakeLock(any()) } returns { wakeLockReleases++ }
        }
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    private fun TestScope.buildViewModel(savedState: SavedStateHandle = SavedStateHandle()): NotificationTestViewModel {
        val clock = mockk<NotificationTestClock> { every { nowMs() } answers { 100_000L + testScheduler.currentTime } }
        val lifetime = NotificationTestLifetime(repository, poster, leases, backgroundScope)
        vm = NotificationTestViewModel(repository, poster, device, leases, lifetime, clock, savedState, StandardTestDispatcher(testScheduler))
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
        coVerify(exactly = 0) { poster.post(any()) }
        advance(1)
        coVerify(exactly = 1) { poster.post(match { it.kind == NotificationTestKind.BANNER }) }
        assertEquals(Phase.POSTED, vm.state.value.phase)
        assertNull(vm.state.value.dialog)

        advance(3_000)
        assertEquals(Dialog.Question(NotificationTestKind.BANNER, vm.state.value.runId!!), vm.state.value.dialog)
    }

    @Test
    fun `an answer is recorded under the fingerprint taken when the notification was posted`() = runTest {
        coEvery { repository.fingerprint(NotificationTestKind.BANNER) } returnsMany listOf("fp-posted", "fp-later")
        buildViewModel()
        start(NotificationTestKind.BANNER)
        advance(8_000)

        vm.dispatch(Intent.Answer(seen = false, runId = vm.state.value.runId!!))
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

        coVerify(exactly = 0) { poster.post(any()) }
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

        coVerify(exactly = 0) { poster.post(any()) }
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
        coVerify(exactly = 0) { poster.post(any()) }
        advance(1)

        coVerify(exactly = 1) { poster.post(match { it.kind == NotificationTestKind.CALL }) }
        assertEquals(Phase.WAIT_CALL, vm.state.value.phase)
    }

    @Test
    fun `a delayed keyguard is waited for and the run posts as soon as it locks`() = runTest {
        every { device.isKeyguardLocked() } answers { testScheduler.currentTime >= 3_000 }
        buildViewModel()
        start(NotificationTestKind.LOCKSCREEN)

        advance(2_999)
        coVerify(exactly = 0) { poster.post(any()) }
        advance(1)

        coVerify(exactly = 1) { poster.post(match { it.kind == NotificationTestKind.LOCKSCREEN }) }
    }

    @Test
    fun `a call run that times out without the call screen records a failure`() = runTest {
        buildViewModel()
        start(NotificationTestKind.CALL)
        advance(2_000)
        assertEquals(Phase.WAIT_CALL, vm.state.value.phase)
        val runId = vm.state.value.runId!!
        coVerify { repository.prepareCall(runId, "fp", any()) }

        advance(15_000)

        coVerify(exactly = 1) { repository.recordCallTimeout(runId, any()) }
        assertEquals(Dialog.Failed(NotificationTestKind.CALL, null), vm.state.value.dialog)
        assertNull(vm.state.value.running)
        coVerify { poster.cancel(runId, NotificationTestKind.CALL) }
        
        verify(exactly = 1) { device.holdWakeLock(any()) }
        assertEquals(1, wakeLockReleases)
    }

    @Test
    fun `a call timeout offers the vendor pop-up page when the device opens it directly`() = runTest {
        every { device.opensVendorPopupDirectly() } returns true
        buildViewModel()
        start(NotificationTestKind.CALL)
        advance(2_000)

        advance(15_000)

        assertEquals(Dialog.Failed(NotificationTestKind.CALL, NotificationFix.OEM_POPUP), vm.state.value.dialog)
    }

    @Test
    fun `a call screen opening during the vendor page probe records no failure`() = runTest {
        every { device.opensVendorPopupDirectly() } answers {
            shown.value = vm.state.value.runId
            true
        }
        buildViewModel()
        start(NotificationTestKind.CALL)

        advance(2_000 + 15_000)

        coVerify(exactly = 0) { repository.recordCallTimeout(any(), any()) }
        assertNull(vm.state.value.dialog)
        assertNull(vm.state.value.running)
    }

    @Test
    fun `a call screen opening in time ends the run without a failure`() = runTest {
        buildViewModel()
        start(NotificationTestKind.CALL)
        advance(2_000)

        shown.value = vm.state.value.runId
        runCurrent()
        assertNull(vm.state.value.running)
        advance(15_000)

        coVerify(exactly = 0) { repository.recordCallTimeout(any(), any()) }
        assertNull(vm.state.value.dialog)
    }

    @Test
    fun `an atomic timeout rejected by an already shown ticket records no failure`() = runTest {
        coEvery { repository.recordCallTimeout(any(), any()) } returns NotificationTestCallUpdate.ALREADY_SHOWN
        buildViewModel()
        start(NotificationTestKind.CALL)
        advance(2_000 + 15_000)
        coVerify(exactly = 1) { repository.recordCallTimeout(any(), any()) }
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
        coVerify(exactly = 0) { poster.post(any()) }
        coVerify { poster.cancel(any(), NotificationTestKind.BANNER) }

        vm.dispatch(Intent.DismissDialog)
        start(NotificationTestKind.BANNER)
        advance(8_000)
        coVerify(exactly = 1) { poster.post(match { it.kind == NotificationTestKind.BANNER }) }
        assertEquals(Dialog.Question(NotificationTestKind.BANNER, vm.state.value.runId!!), vm.state.value.dialog)
    }

    @Test
    fun `a cancel while the post is in flight takes the notification back after it lands`() = runTest {
        coEvery { poster.post(match { it.kind == NotificationTestKind.BANNER }) } answers {
            vm.dispatch(Intent.Cancel)
            events += "posted"
            true
        }
        buildViewModel()
        start(NotificationTestKind.BANNER)

        advance(5_000)

        assertEquals(listOf("posted", "cancel"), events)
        advance(3_000)
        assertNull(vm.state.value.running)
        assertNull(vm.state.value.dialog)
    }
    @Test
    fun `two actual VMs cannot claim concurrently and old cleanup cannot free the successor`() = runTest {
        buildViewModel()
        val first = vm
        start(NotificationTestKind.BANNER)
        val firstRun = vm.state.value.runId!!
        buildViewModel()
        val second = vm
        start(NotificationTestKind.CALL)
        assertEquals(Dialog.Info(Notice.BUSY), second.state.value.dialog)
        assertNull(second.state.value.running)
        first.dispatch(Intent.Cancel)
        runCurrent()
        start(NotificationTestKind.CALL)
        val secondRun = second.state.value.runId!!
        first.dispatch(Intent.Cancel)
        first.dispatch(Intent.Answer(true, firstRun))
        runCurrent()
        assertEquals(secondRun, second.state.value.runId)
        assertEquals(NotificationTestKind.CALL, second.state.value.running)
        first.viewModelScope.cancel()
    }

    @Test
    fun `Finishing holds the lease through a suspended failed result write and then allows retry`() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { repository.record(any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            finish.await()
            throw java.io.IOException("disk")
        }
        buildViewModel()
        start(NotificationTestKind.BANNER)
        advance(8_000)
        val runId = vm.state.value.runId!!
        vm.dispatch(Intent.Answer(true, runId))
        runCurrent()
        assertEquals(Phase.FINISHING, vm.state.value.phase)
        vm.dispatch(Intent.Start(NotificationTestKind.CALL))
        vm.dispatch(Intent.ConfirmStart)
        assertEquals(runId, vm.state.value.runId)
        coVerify(exactly = 0) { poster.cancel(any(), any()) }
        finish.complete(Unit)
        runCurrent()
        assertEquals(Dialog.Info(Notice.PERSISTENCE_FAILED), vm.state.value.dialog)
        assertNull(vm.state.value.running)
        assertEquals(com.difft.android.base.notification.NotificationTestResults.NONE, vm.state.value.results)
        start(NotificationTestKind.CALL)
        assertEquals(NotificationTestKind.CALL, vm.state.value.running)
    }

    @Test
    fun `an old answer cannot write a newer question`() = runTest {
        buildViewModel()
        start(NotificationTestKind.BANNER)
        advance(8_000)
        val first = vm.state.value.runId!!
        vm.dispatch(Intent.Cancel)
        runCurrent()
        start(NotificationTestKind.BANNER)
        advance(8_000)
        val next = vm.state.value.runId!!
        vm.dispatch(Intent.Answer(true, first))
        runCurrent()
        coVerify(exactly = 0) { repository.record(any(), any(), any(), any()) }
        assertEquals(Dialog.Question(NotificationTestKind.BANNER, next), vm.state.value.dialog)
    }

    @Test
    fun `CALL ticket failure prevents notify and a failed conditional write is visible`() = runTest {
        coEvery { repository.prepareCall(any(), any(), any()) } throws java.io.IOException("disk")
        buildViewModel()
        start(NotificationTestKind.CALL)
        advance(2_000)
        coVerify(exactly = 0) { poster.post(any()) }
        assertEquals(Dialog.Info(Notice.PERSISTENCE_FAILED), vm.state.value.dialog)
        assertNull(vm.state.value.running)
        coEvery { repository.prepareCall(any(), any(), any()) } just Runs
        coEvery { repository.recordCallTimeout(any(), any()) } returns NotificationTestCallUpdate.PERSISTENCE_FAILED
        start(NotificationTestKind.CALL)
        advance(17_000)
        assertEquals(Dialog.Info(Notice.PERSISTENCE_FAILED), vm.state.value.dialog)
        assertNull(vm.state.value.running)
    }

    @Test
    fun `cleanup keeps a claim busy until its own token notification has been canceled`() = runTest {
        val cleanup = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { poster.cancel(any(), any()) } coAnswers { cleanup.await() }
        buildViewModel()
        val first = vm
        start(NotificationTestKind.BANNER)
        first.dispatch(Intent.Cancel)
        runCurrent()
        assertEquals(Phase.FINISHING, first.state.value.phase)
        buildViewModel()
        start(NotificationTestKind.CALL)
        assertEquals(Dialog.Info(Notice.BUSY), vm.state.value.dialog)
        cleanup.complete(Unit)
        runCurrent()
        assertNull(first.state.value.running)
        start(NotificationTestKind.CALL)
        assertEquals(NotificationTestKind.CALL, vm.state.value.running)
        first.viewModelScope.cancel()
    }

    @Test
    fun `clearing the actual ViewModel store cleans the run and restoring the owner never restarts timers`() = runTest {
        val saved = SavedStateHandle()
        buildViewModel(saved)
        val first = vm
        val owner = first.owner
        val store = androidx.lifecycle.ViewModelStore()
        val provider = androidx.lifecycle.ViewModelProvider(store, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = first as T
        })
        provider[NotificationTestViewModel::class.java]
        start(NotificationTestKind.BANNER)
        store.clear()
        runCurrent()
        assertNull(first.state.value.running)
        coVerify(exactly = 1) { poster.cancel(any(), NotificationTestKind.BANNER) }
        buildViewModel(saved)
        assertEquals(owner, vm.owner)
        assertNull(vm.state.value.running)
        advance(8_000)
        coVerify(exactly = 0) { poster.post(any()) }
    }

}
