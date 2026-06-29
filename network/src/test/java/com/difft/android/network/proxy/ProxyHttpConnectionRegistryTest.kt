package com.difft.android.network.proxy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ProxyHttpConnectionRegistry] must evict the connection pool of every registered
 * client exactly once per [ProxyHttpConnectionRegistry.evictAll], dedupe repeat
 * registrations of the same client, and never let one client's eviction failure
 * mask the others.
 */
@RunWith(RobolectricTestRunner::class)
class ProxyHttpConnectionRegistryTest {

    private fun clientWith(pool: ConnectionPool): OkHttpClient =
        mockk(relaxed = true) { every { connectionPool } returns pool }

    @Test
    fun `evictAll evicts every registered client's pool`() {
        val registry = ProxyHttpConnectionRegistry()
        val poolA = mockk<ConnectionPool>(relaxed = true)
        val poolB = mockk<ConnectionPool>(relaxed = true)
        registry.register(clientWith(poolA))
        registry.register(clientWith(poolB))

        registry.evictAll()

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

        registry.evictAll()

        verify(exactly = 1) { pool.evictAll() }
    }

    @Test
    fun `one client's eviction failure does not stop the others`() {
        val registry = ProxyHttpConnectionRegistry()
        val failingPool = mockk<ConnectionPool> { every { evictAll() } throws RuntimeException("boom") }
        val healthyPool = mockk<ConnectionPool>(relaxed = true)
        registry.register(clientWith(failingPool))
        registry.register(clientWith(healthyPool))

        registry.evictAll() // must not throw

        verify(exactly = 1) { healthyPool.evictAll() }
    }

    @Test
    fun `evictAll with no registered clients is a no-op`() {
        ProxyHttpConnectionRegistry().evictAll()
    }
}
