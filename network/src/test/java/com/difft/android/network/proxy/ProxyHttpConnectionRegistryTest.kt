package com.difft.android.network.proxy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [ProxyHttpConnectionRegistry] must evict the connection pool of every registered
 * client exactly once per [ProxyHttpConnectionRegistry.evictAll], dedupe repeat
 * registrations of the same client, and never let one client's eviction failure
 * mask the others. Off the main thread it evicts inline; on the main thread it must hand
 * the eviction to a background thread, because closing a pooled TLS socket there throws
 * `NetworkOnMainThreadException` (#1400).
 */
@RunWith(RobolectricTestRunner::class)
class ProxyHttpConnectionRegistryTest {

    /** Robolectric's test thread is the main thread; evict from a plain thread to exercise the inline path. */
    private fun ProxyHttpConnectionRegistry.evictAllOffMain() {
        val worker = Thread({ evictAll() }, "off-main-caller")
        worker.start()
        worker.join(5_000)
    }

    private fun clientWith(pool: ConnectionPool): OkHttpClient =
        mockk(relaxed = true) { every { connectionPool } returns pool }

    @Test
    fun `evictAll evicts every registered client's pool`() {
        val registry = ProxyHttpConnectionRegistry()
        val poolA = mockk<ConnectionPool>(relaxed = true)
        val poolB = mockk<ConnectionPool>(relaxed = true)
        registry.register(clientWith(poolA))
        registry.register(clientWith(poolB))

        registry.evictAllOffMain()

        verify(exactly = 1) { poolA.evictAll() }
        verify(exactly = 1) { poolB.evictAll() }
    }

    @Test
    fun `registering the same client twice evicts its pool only once`() {
        val registry = ProxyHttpConnectionRegistry()
        val pool = mockk<ConnectionPool>(relaxed = true)
        val client = clientWith(pool)
        registry.register(client)
        registry.register(client)

        registry.evictAllOffMain()

        verify(exactly = 1) { pool.evictAll() }
    }

    @Test
    fun `one client's eviction failure does not stop the others`() {
        val registry = ProxyHttpConnectionRegistry()
        val failingPool = mockk<ConnectionPool> { every { evictAll() } throws RuntimeException("boom") }
        val healthyPool = mockk<ConnectionPool>(relaxed = true)
        registry.register(clientWith(failingPool))
        registry.register(clientWith(healthyPool))

        registry.evictAllOffMain() // must not throw

        verify(exactly = 1) { healthyPool.evictAll() }
    }

    @Test
    fun `evictAll with no registered clients is a no-op`() {
        ProxyHttpConnectionRegistry().evictAll()
    }

    /** Pool whose evictAll records the thread it ran on and releases [done]. */
    private fun recordingPool(ranOn: AtomicReference<Thread>, done: CountDownLatch): ConnectionPool =
        mockk {
            every { evictAll() } answers {
                ranOn.set(Thread.currentThread())
                done.countDown()
            }
        }

    @Test
    fun `evictAll on the main thread runs the eviction on a background thread`() {
        val registry = ProxyHttpConnectionRegistry()
        val ranOn = AtomicReference<Thread>()
        val done = CountDownLatch(1)
        registry.register(clientWith(recordingPool(ranOn, done)))
        val mainThread = Thread.currentThread() // Robolectric test thread owns the main looper

        registry.evictAll()

        assertTrue(done.await(5, TimeUnit.SECONDS), "eviction never ran")
        assertNotEquals(mainThread, ranOn.get())
        assertEquals("proxy-http-evict", ranOn.get().name)
    }

    @Test
    fun `main thread evictions stay ordered on the single background thread`() {
        val registry = ProxyHttpConnectionRegistry()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(4)
        val first = mockk<ConnectionPool> { every { evictAll() } answers { order += "first"; done.countDown() } }
        val second = mockk<ConnectionPool> { every { evictAll() } answers { order += "second"; done.countDown() } }
        registry.register(clientWith(first))
        registry.register(clientWith(second))

        registry.evictAll()
        registry.evictAll()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("first", "second", "first", "second"), order.toList())
    }

    @Test
    fun `evictAll off the main thread evicts inline on the calling thread`() {
        val registry = ProxyHttpConnectionRegistry()
        val ranOn = AtomicReference<Thread>()
        val done = CountDownLatch(1)
        registry.register(clientWith(recordingPool(ranOn, done)))
        val caller = Thread({ registry.evictAll() }, "off-main-caller")

        caller.start()
        caller.join(5_000)

        // Inline means the pool was already evicted when evictAll returned.
        assertEquals(0L, done.count)
        assertEquals(caller, ranOn.get())
    }
}
