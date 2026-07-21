package com.difft.android.linkeddevices

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.difft.android.base.user.LogoutManager
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.test.TestDispatcherRule
import com.difft.android.websocket.api.messages.multidevice.DeviceInfo
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [LinkedDevicesViewModel]. The Unconfined [TestDispatcherRule] runs viewModelScope
 * coroutines eagerly; gated tests hold the fetch mid-flight with a [CompletableDeferred].
 */
class LinkedDevicesViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

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

    private fun device(id: Int, name: String? = "dev$id", created: Long = 1000L, lastSeen: Long = 2000L) =
        DeviceInfo(id = id, name = name, created = created, lastSeen = lastSeen)

    // U1
    @Test
    fun `refresh success populates devices`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2), device(3))
        val vm = buildViewModel()
        vm.refresh()
        val s = vm.uiState.value
        assertEquals(2, s.devices.size)
        assertFalse(s.isLoading)
    }

    // U2
    @Test
    fun `refresh empty is not an error`() = runTest {
        coEvery { repo.getDevices() } returns emptyList()
        val vm = buildViewModel()
        vm.refresh()
        val s = vm.uiState.value
        assertTrue(s.devices.isEmpty())
    }

    // U3
    @Test
    fun `first refresh on empty shows loading`() = runTest {
        val gate = CompletableDeferred<List<DeviceInfo>>()
        coEvery { repo.getDevices() } coAnswers { gate.await() }
        val vm = buildViewModel()
        vm.refresh()
        assertTrue(vm.uiState.value.isLoading)
        gate.complete(listOf(device(2)))
        assertFalse(vm.uiState.value.isLoading)
    }

    // U4
    @Test
    fun `refresh auth failure logs out and clears loading flags`() = runTest {
        coEvery { repo.getDevices() } throws AuthorizationFailedException(401, "unauthorized")
        val vm = buildViewModel()
        vm.pullRefresh()
        coVerify(exactly = 1) { logoutManager.doLogoutWithoutRemoveData() }
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    // U5 — a generic failure emits a one-shot FetchFailed (toast) and keeps any list already shown;
    // there is no full-screen error state (the Link New Device entry never disappears).
    @Test
    fun `refresh generic failure emits FetchFailed and keeps existing list`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2))
        val vm = buildViewModel()
        vm.refresh() // seed one device
        coEvery { repo.getDevices() } throws RuntimeException("boom")
        vm.events.test {
            vm.refresh(force = true)
            assertEquals(LinkedDevicesViewModel.UiEvent.FetchFailed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        val s = vm.uiState.value
        assertEquals(1, s.devices.size) // list preserved on failure
        assertFalse(s.isLoading)
    }

    // U6
    @Test
    fun `refresh cancellation is not swallowed`() = runTest {
        coEvery { repo.getDevices() } throws CancellationException("cancelled")
        val vm = buildViewModel()
        vm.refresh()
        coVerify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
        verify(exactly = 0) { countStore.update(any()) }
    }

    // U7
    @Test
    fun `concurrent refresh is deduped`() = runTest {
        val gate = CompletableDeferred<List<DeviceInfo>>()
        coEvery { repo.getDevices() } coAnswers { gate.await() }
        val vm = buildViewModel()
        vm.refresh()
        vm.refresh()
        gate.complete(listOf(device(2)))
        coVerify(exactly = 1) { repo.getDevices() }
    }

    // U8
    @Test
    fun `refresh with existing list is silent`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2))
        val vm = buildViewModel()
        vm.refresh() // seed
        val gate = CompletableDeferred<List<DeviceInfo>>()
        coEvery { repo.getDevices() } coAnswers { gate.await() }
        vm.refresh()
        assertFalse(vm.uiState.value.isLoading) // silent — list already shown
        gate.complete(listOf(device(2)))
    }

    // U9
    @Test
    fun `lastSeen before created clamps to created`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2, created = 1000L, lastSeen = 500L))
        val vm = buildViewModel()
        vm.refresh()
        assertEquals(1000L, vm.uiState.value.devices.first().lastActive)
    }

    // U10
    @Test
    fun `lastSeen after created is preserved`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2, created = 1000L, lastSeen = 2000L))
        val vm = buildViewModel()
        vm.refresh()
        assertEquals(2000L, vm.uiState.value.devices.first().lastActive)
    }

    // U11
    @Test
    fun `null and blank names resolve to no display name`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2, name = null), device(3, name = ""))
        val vm = buildViewModel()
        vm.refresh()
        val devices = vm.uiState.value.devices
        assertEquals(null, devices[0].displayName)
        assertEquals(null, devices[0].rawName)
        assertEquals(null, devices[1].displayName)
        assertEquals("", devices[1].rawName)
    }

    // Full-screen loading fires only on the first load: a later refresh over an already-loaded
    // (even empty) list must not re-spin the whole screen or it would swallow the pull gesture.
    @Test
    fun `refresh after first empty load does not show full-screen loading`() = runTest {
        coEvery { repo.getDevices() } returns emptyList()
        val vm = buildViewModel()
        vm.refresh()
        assertTrue(vm.uiState.value.hasLoadedOnce)

        val gate = CompletableDeferred<List<DeviceInfo>>()
        coEvery { repo.getDevices() } coAnswers { gate.await() }
        vm.refresh()
        assertFalse(vm.uiState.value.isLoading) // not full-screen despite an empty list
        gate.complete(emptyList())
    }

    // Unlink succeeds but the authoritative re-fetch fails: the row must not stay stuck "unlinking",
    // so it can be unlinked again.
    @Test
    fun `unlink success with failed refetch leaves row retryable`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2))
        coEvery { repo.removeDevice(2) } returns Unit
        val vm = buildViewModel()
        vm.refresh() // seed [2]

        coEvery { repo.getDevices() } throws RuntimeException("boom") // re-fetch fails
        vm.unlink(2)
        assertFalse(vm.uiState.value.devices.first { it.id == 2 }.isUnlinking)

        vm.unlink(2) // retry actually issues a second DELETE
        coVerify(exactly = 2) { repo.removeDevice(2) }
    }

    // A failed first load still counts as loaded: retries must not respin the full-screen spinner
    // (it would hide the Link New Device entry, contradicting the toast-only failure design).
    @Test
    fun `failed first load does not respin full-screen spinner on retry`() = runTest {
        coEvery { repo.getDevices() } throws RuntimeException("boom")
        val vm = buildViewModel()
        vm.refresh()
        assertTrue(vm.uiState.value.hasLoadedOnce)

        val gate = CompletableDeferred<List<DeviceInfo>>()
        coEvery { repo.getDevices() } coAnswers { gate.await() }
        vm.refresh()
        assertFalse(vm.uiState.value.isLoading) // list body (with entry) stays visible
        gate.complete(emptyList())
    }

    // isUnlinking stays set until the post-DELETE re-fetch settles, so a repeat tap in that
    // window cannot fire a duplicate DELETE (which the server would 404 as a false failure).
    @Test
    fun `duplicate unlink is ignored while the refetch is in flight`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2))
        coEvery { repo.removeDevice(2) } returns Unit
        val vm = buildViewModel()
        vm.refresh() // seed [2]

        val gate = CompletableDeferred<List<DeviceInfo>>()
        coEvery { repo.getDevices() } coAnswers { gate.await() } // re-fetch parked
        vm.unlink(2)
        vm.unlink(2) // guard active: no second DELETE
        coVerify(exactly = 1) { repo.removeDevice(2) }

        gate.complete(emptyList())
        assertTrue(vm.uiState.value.devices.isEmpty()) // re-fetch replaced the list
    }

    // A concurrent force refresh (poll tick) cancels-and-replaces the job unlink is waiting on.
    // The guard must hold until the SUCCESSOR settles, not release on the cancelled attempt.
    @Test
    fun `unlink guard survives a superseding force refresh`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2))
        coEvery { repo.removeDevice(2) } returns Unit
        val vm = buildViewModel()
        vm.refresh() // seed [2]

        val gate1 = CompletableDeferred<List<DeviceInfo>>()
        val gate2 = CompletableDeferred<List<DeviceInfo>>()
        var call = 0
        coEvery { repo.getDevices() } coAnswers { if (++call == 1) gate1.await() else gate2.await() }

        vm.unlink(2)            // DELETE ok; its re-fetch parks on gate1; join loop waits
        vm.refresh(force = true) // supersedes: cancels gate1's job, new job parks on gate2
        // Guard must still be held: the cancelled attempt never fetched.
        assertTrue(vm.uiState.value.devices.first { it.id == 2 }.isUnlinking)
        vm.unlink(2)             // repeat tap in the window: must not fire a second DELETE
        coVerify(exactly = 1) { repo.removeDevice(2) }

        gate2.complete(emptyList()) // successor settles -> guard released, list authoritative
        assertTrue(vm.uiState.value.devices.isEmpty())
    }

    // U12
    @Test
    fun `unlink success removes then refetches`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2))
        coEvery { repo.removeDevice(any()) } returns Unit
        val vm = buildViewModel()
        vm.refresh()
        vm.unlink(2)
        coVerify { repo.removeDevice(2) }
        coVerify(atLeast = 2) { repo.getDevices() } // seed + post-unlink re-fetch
    }

    // U13
    @Test
    fun `unlink failure emits event and leaves list unchanged`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2))
        coEvery { repo.removeDevice(any()) } throws RuntimeException("boom")
        val vm = buildViewModel()
        vm.refresh()
        vm.events.test {
            vm.unlink(2)
            assertEquals(LinkedDevicesViewModel.UiEvent.UnlinkFailed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        val d = vm.uiState.value.devices.first { it.id == 2 }
        assertFalse(d.isUnlinking)
        assertEquals(1, vm.uiState.value.devices.size)
    }

    // U14
    @Test
    fun `unlink auth failure logs out and clears unlinking flag`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2))
        coEvery { repo.removeDevice(any()) } throws AuthorizationFailedException(403, "forbidden")
        val vm = buildViewModel()
        vm.refresh()
        vm.unlink(2)
        coVerify(exactly = 1) { logoutManager.doLogoutWithoutRemoveData() }
        assertFalse(vm.uiState.value.devices.first { it.id == 2 }.isUnlinking)
    }

    // U15
    @Test
    fun `unlink cancellation is not swallowed`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2))
        coEvery { repo.removeDevice(any()) } throws CancellationException("cancelled")
        val vm = buildViewModel()
        vm.refresh()
        vm.events.test {
            vm.unlink(2)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    // U16
    @Test
    fun `unlink is guarded against concurrent calls`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2))
        val gate = CompletableDeferred<Unit>()
        coEvery { repo.removeDevice(any()) } coAnswers { gate.await() }
        val vm = buildViewModel()
        vm.refresh()
        vm.unlink(2)
        vm.unlink(2) // second call no-ops while first in flight
        gate.complete(Unit)
        coVerify(exactly = 1) { repo.removeDevice(2) }
    }

    // U17
    @Test
    fun `unlink sets in-progress flag during flight`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2))
        val gate = CompletableDeferred<Unit>()
        coEvery { repo.removeDevice(any()) } coAnswers { gate.await() }
        val vm = buildViewModel()
        vm.refresh()
        vm.unlink(2)
        assertTrue(vm.uiState.value.devices.first { it.id == 2 }.isUnlinking)
        gate.complete(Unit)
    }

    // U18
    @Test
    fun `refresh pushes count on success`() = runTest {
        coEvery { repo.getDevices() } returns listOf(device(2), device(3))
        val vm = buildViewModel()
        vm.refresh()
        verify(exactly = 1) { countStore.update(2) }
    }

    // U19
    @Test
    fun `refresh does not push count on failure`() = runTest {
        coEvery { repo.getDevices() } throws RuntimeException("boom")
        val vm = buildViewModel()
        vm.refresh()
        verify(exactly = 0) { countStore.update(any()) }
    }

    // Unlink's forced re-fetch cancels an in-flight stale resume-GET so it can't revive a removed row.
    @Test
    fun `unlink forces refresh cancelling in-flight stale fetch`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var call = 0
        coEvery { repo.getDevices() } coAnswers {
            call++
            if (call == 1) {
                gate.await() // stale resume-GET, held mid-flight
                listOf(device(2), device(3))
            } else {
                listOf(device(3)) // forced post-unlink re-fetch
            }
        }
        coEvery { repo.removeDevice(any()) } returns Unit
        val vm = buildViewModel()

        vm.refresh() // resume GET #1 suspends on the gate (stale [2,3])
        vm.unlink(2) // success → refresh(force=true) cancels the suspended GET #1, GET #2 returns [3]

        val devices = vm.uiState.value.devices
        assertEquals(listOf(3), devices.map { it.id }) // stale [2,3] never became terminal
        verify(exactly = 1) { countStore.update(1) }
        verify(exactly = 0) { countStore.update(2) }

        gate.complete(Unit) // releasing the cancelled GET is a no-op
    }
}
