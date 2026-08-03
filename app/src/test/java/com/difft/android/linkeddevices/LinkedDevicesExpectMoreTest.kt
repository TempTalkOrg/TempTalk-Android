package com.difft.android.linkeddevices

import androidx.lifecycle.viewModelScope
import com.difft.android.base.user.LogoutManager
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.test.TestDispatcherRule
import com.difft.android.websocket.api.messages.multidevice.DeviceInfo
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Expect-more-devices poll tests for [LinkedDevicesViewModel]. A [StandardTestDispatcher] drives the
 * 10s poll delay via virtual time; the poll is silent, so assertions pin repository call counts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkedDevicesExpectMoreTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule(StandardTestDispatcher())

    private lateinit var repo: DeviceRepository
    private lateinit var logoutManager: LogoutManager
    private lateinit var countStore: LinkedDevicesCountStore

    private val builtViewModels = mutableListOf<LinkedDevicesViewModel>()

    @Before
    fun setup() {
        repo = mockk()
        logoutManager = mockk(relaxed = true)
        countStore = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        builtViewModels.forEach { it.viewModelScope.cancel() }
        builtViewModels.clear()
        clearMocks(repo, logoutManager, countStore)
    }

    private fun buildViewModel() =
        LinkedDevicesViewModel(repo, logoutManager, countStore).also { builtViewModels += it }

    private fun device(id: Int) = DeviceInfo(id = id, name = "dev$id", created = 1000L, lastSeen = 2000L)

    // Poll arms after link-click + resume refresh and keeps re-fetching every 10s until stopped.
    @Test
    fun `poll re-fetches on interval while expecting`() = runTest(dispatcherRule.testDispatcher) {
        coEvery { repo.getDevices() } returns listOf(device(2)) // count stays 1, never exceeds baseline
        val vm = buildViewModel()

        vm.refresh(); runCurrent()           // seed: 1 device
        vm.onLinkNewDeviceClicked()          // baseline = 1
        vm.refresh(); runCurrent()           // resume refresh: 1 == baseline -> poll armed

        advanceTimeBy(10_000); runCurrent()  // poll tick #1
        advanceTimeBy(10_000); runCurrent()  // poll tick #2
        coVerify(atLeast = 4) { repo.getDevices() } // seed + resume + 2 poll ticks

        vm.stopExpecting()                   // cancel the timer so runTest can settle
    }

    // Poll auto-stops the moment the device count exceeds the baseline.
    @Test
    fun `poll stops when a new device appears`() = runTest(dispatcherRule.testDispatcher) {
        var call = 0
        coEvery { repo.getDevices() } coAnswers {
            call++
            if (call >= 3) listOf(device(2), device(3)) else listOf(device(2))
        }
        val vm = buildViewModel()

        vm.refresh(); runCurrent()           // call 1: [2]
        vm.onLinkNewDeviceClicked()          // baseline = 1
        vm.refresh(); runCurrent()           // call 2: [2] -> poll armed

        advanceTimeBy(10_000); runCurrent()  // call 3: [2,3] -> count 2 > baseline -> satisfied
        assertEquals(2, vm.uiState.value.devices.size)

        advanceTimeBy(30_000); runCurrent()  // no further polling
        coVerify(exactly = 3) { repo.getDevices() }
    }

    // Auth failure during any refresh clears the expectation: the session is gone, so the silent
    // poll must not keep hitting the endpoint (and re-triggering logout) for up to 3 minutes.
    @Test
    fun `auth failure stops the poll`() = runTest(dispatcherRule.testDispatcher) {
        var call = 0
        coEvery { repo.getDevices() } coAnswers {
            call++
            if (call <= 2) listOf(device(2))
            else throw com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException(401, "unauthorized")
        }
        val vm = buildViewModel()

        vm.refresh(); runCurrent()           // seed
        vm.onLinkNewDeviceClicked()          // baseline = 1
        vm.refresh(); runCurrent()           // resume -> poll armed

        advanceTimeBy(10_000); runCurrent()  // tick -> 401 -> logout + poll cleared
        advanceTimeBy(60_000); runCurrent()  // no further ticks
        coVerify(exactly = 3) { repo.getDevices() }
        coVerify(exactly = 1) { logoutManager.doLogoutWithoutRemoveData() }
    }

    // stopExpecting() (host onPause) cancels the timer: no further fetches while paused.
    @Test
    fun `stopExpecting halts the poll`() = runTest(dispatcherRule.testDispatcher) {
        coEvery { repo.getDevices() } returns listOf(device(2))
        val vm = buildViewModel()

        vm.refresh(); runCurrent()           // call 1 (seed)
        vm.onLinkNewDeviceClicked()          // baseline = 1
        vm.refresh(); runCurrent()           // call 2 (resume) -> poll armed, parked at delay

        vm.stopExpecting()                   // cancel timer before any tick
        advanceTimeBy(30_000); runCurrent()  // would have been 3 ticks
        coVerify(exactly = 2) { repo.getDevices() }
    }

    // A failing first post-scan refresh must still arm the poll so linking eventually resolves.
    @Test
    fun `poll arms even when the first post-scan refresh fails`() = runTest(dispatcherRule.testDispatcher) {
        var call = 0
        coEvery { repo.getDevices() } coAnswers {
            call++
            when (call) {
                1 -> listOf(device(2))               // seed
                2 -> throw RuntimeException("boom")   // first post-scan resume refresh fails
                3 -> listOf(device(2))               // poll tick 1: still 1
                else -> listOf(device(2), device(3)) // poll tick 2: new device -> satisfied
            }
        }
        val vm = buildViewModel()

        vm.refresh(); runCurrent()           // seed [2]
        vm.onLinkNewDeviceClicked()          // baseline = 1
        vm.refresh(); runCurrent()           // resume refresh FAILS -> poll must still arm

        advanceTimeBy(10_000); runCurrent()  // poll tick 1: [2]
        advanceTimeBy(10_000); runCurrent()  // poll tick 2: [2,3] -> satisfied
        assertEquals(2, vm.uiState.value.devices.size)
        coVerify(atLeast = 4) { repo.getDevices() }
    }

    // The poll is capped so abandoning the scan flow can't poll forever (seed + resume + 18 ticks).
    @Test
    fun `poll gives up after the tick limit`() = runTest(dispatcherRule.testDispatcher) {
        coEvery { repo.getDevices() } returns listOf(device(2)) // count stays 1, never satisfies
        val vm = buildViewModel()

        vm.refresh(); runCurrent()           // seed
        vm.onLinkNewDeviceClicked()          // baseline = 1, tick count reset
        vm.refresh(); runCurrent()           // resume -> poll armed

        advanceTimeBy(10_000L * 30); runCurrent() // well past the 18-tick cap
        coVerify(exactly = 20) { repo.getDevices() } // seed + resume + 18 poll ticks
    }

    // A device unlinked mid-session re-bases the baseline so a later link still satisfies the poll.
    @Test
    fun `baseline rebases after an unlink so a later link still satisfies`() =
        runTest(dispatcherRule.testDispatcher) {
            var call = 0
            coEvery { repo.getDevices() } coAnswers {
                call++
                when (call) {
                    1 -> listOf(device(2), device(3))    // seed: 2 devices
                    2 -> listOf(device(2), device(3))    // resume: still 2 -> poll armed, baseline 2
                    3 -> listOf(device(2))               // tick: one unlinked -> count 1 -> rebase to 1
                    else -> listOf(device(2), device(4)) // tick: count 2 > rebased 1 -> satisfied
                }
            }
            val vm = buildViewModel()

            vm.refresh(); runCurrent()           // seed [2,3]
            vm.onLinkNewDeviceClicked()          // baseline = 2
            vm.refresh(); runCurrent()           // resume [2,3] -> poll armed

            advanceTimeBy(10_000); runCurrent()  // tick: [2] -> rebase baseline to 1
            advanceTimeBy(10_000); runCurrent()  // tick: [2,4] -> satisfied
            assertEquals(2, vm.uiState.value.devices.size)

            advanceTimeBy(30_000); runCurrent()  // no further polling
            coVerify(exactly = 4) { repo.getDevices() }
        }
}
