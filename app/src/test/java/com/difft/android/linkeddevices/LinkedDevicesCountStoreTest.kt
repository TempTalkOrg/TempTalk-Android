package com.difft.android.linkeddevices

import app.cash.turbine.test
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.test.TestDispatcherRule
import com.difft.android.websocket.api.messages.multidevice.DeviceInfo
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [LinkedDevicesCountStore]. Pure JVM; the gated concurrency tests drive the
 * [StandardTestDispatcher] scheduler explicitly with [runCurrent]/[advanceUntilIdle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkedDevicesCountStoreTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var repo: DeviceRepository
    private lateinit var store: LinkedDevicesCountStore

    @Before
    fun setup() {
        repo = mockk()
        store = LinkedDevicesCountStore(repo)
    }

    @After
    fun tearDown() {
        clearMocks(repo)
    }

    /** getDevices() returns an already-primary-filtered list; the store only reads its size. */
    private fun devices(n: Int): List<DeviceInfo> = List(n) { DeviceInfo(id = 100 + it) }

    // C5t: seed — count==null, repo returns 2 → count emits null→2; getDevices called once.
    @Test
    fun `C5t refresh seeds count from null`() = runTest {
        coEvery { repo.getDevices() } returns devices(2)

        store.count.test {
            assertEquals(null, awaitItem())
            store.refresh()
            assertEquals(2, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { repo.getDevices() }
    }

    // C6t: no throttling — every call issues a real fetch → getDevices called twice.
    @Test
    fun `C6t refresh always fetches`() = runTest {
        coEvery { repo.getDevices() } returns devices(2)

        store.refresh()
        store.refresh()

        assertEquals(2, store.count.value)
        coVerify(exactly = 2) { repo.getDevices() }
    }

    // C8t: an auth failure is swallowed (best-effort); last-known count kept, no logout.
    @Test
    fun `C8t refresh swallows auth failure and keeps last-known`() = runTest {
        coEvery { repo.getDevices() } returns devices(2)
        store.refresh()
        assertEquals(2, store.count.value)

        coEvery { repo.getDevices() } throws AuthorizationFailedException(401, "auth failed")
        store.refresh() // must not throw

        assertEquals(2, store.count.value)
    }

    // C9t: CancellationException is rethrown, not swallowed.
    @Test
    fun `C9t refresh rethrows CancellationException`() = runTest {
        coEvery { repo.getDevices() } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { store.refresh() }
    }

    // C10t: update() pushes immediately; with no throttle a later refresh still fetches and wins.
    @Test
    fun `C10t update pushes value and later refresh still fetches`() = runTest {
        coEvery { repo.getDevices() } returns devices(5)

        store.count.test {
            assertEquals(null, awaitItem())
            store.update(3)
            assertEquals(3, awaitItem())
            store.refresh()
            assertEquals(5, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { repo.getDevices() }
    }

    // C11t: a refresh made while another is in flight coalesces (tryLock) — only one GET fires.
    @Test
    fun `C11t concurrent refresh coalesces to a single fetch`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var maxInFlight = 0
        coEvery { repo.getDevices() } coAnswers {
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            gate.await()
            inFlight--
            devices(2)
        }

        val job1 = launch { store.refresh() }
        val job2 = launch { store.refresh() }
        runCurrent() // job1 holds the lock and parks at the gate; job2 tryLock fails and returns
        assertEquals(1, maxInFlight)

        gate.complete(Unit)
        advanceUntilIdle()
        job1.join(); job2.join()

        assertEquals(2, store.count.value)
        coVerify(exactly = 1) { repo.getDevices() } // coalesced
    }

    // C12t: a push landing mid-fetch bumps the generation, so the in-flight fetch's stale result is
    // discarded on commit and the pushed value wins.
    @Test
    fun `C12t push during in-flight fetch discards the stale fetch result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { repo.getDevices() } coAnswers { gate.await(); devices(5) }

        val job = launch { store.refresh() }
        runCurrent() // fetch is in flight (genAtStart captured), parked at the gate

        store.update(2) // authoritative push — bumps generation, count=2
        assertEquals(2, store.count.value)

        gate.complete(Unit) // fetch returns stale 5, but generation changed → discarded
        advanceUntilIdle()
        job.join()

        assertEquals(2, store.count.value)
        coVerify(exactly = 1) { repo.getDevices() }
    }
}
