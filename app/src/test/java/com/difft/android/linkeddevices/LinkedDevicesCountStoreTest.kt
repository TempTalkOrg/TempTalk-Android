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

    
    private fun devices(n: Int): List<DeviceInfo> = List(n) { DeviceInfo(id = 100 + it) }

    
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

    
    @Test
    fun `C6t refresh always fetches`() = runTest {
        coEvery { repo.getDevices() } returns devices(2)

        store.refresh()
        store.refresh()

        assertEquals(2, store.count.value)
        coVerify(exactly = 2) { repo.getDevices() }
    }

    
    @Test
    fun `C8t refresh swallows auth failure and keeps last-known`() = runTest {
        coEvery { repo.getDevices() } returns devices(2)
        store.refresh()
        assertEquals(2, store.count.value)

        coEvery { repo.getDevices() } throws AuthorizationFailedException(401, "auth failed")
        store.refresh() 

        assertEquals(2, store.count.value)
    }

    
    @Test
    fun `C9t refresh rethrows CancellationException`() = runTest {
        coEvery { repo.getDevices() } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { store.refresh() }
    }

    
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
        runCurrent() 
        assertEquals(1, maxInFlight)

        gate.complete(Unit)
        advanceUntilIdle()
        job1.join(); job2.join()

        assertEquals(2, store.count.value)
        coVerify(exactly = 1) { repo.getDevices() } 
    }

    
    
    @Test
    fun `C12t push during in-flight fetch discards the stale fetch result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { repo.getDevices() } coAnswers { gate.await(); devices(5) }

        val job = launch { store.refresh() }
        runCurrent() 

        store.update(2) 
        assertEquals(2, store.count.value)

        gate.complete(Unit) 
        advanceUntilIdle()
        job.join()

        assertEquals(2, store.count.value)
        coVerify(exactly = 1) { repo.getDevices() }
    }
}
