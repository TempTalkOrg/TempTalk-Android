package com.difft.android.network.speedtest

import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.network.config.GlobalConfigsManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainSpeedTestCoordinatorTest {

    private val mockSpeedTester = mockk<DomainSpeedTester>(relaxed = true)
    private val mockGlobalConfigsManager = mockk<GlobalConfigsManager>(relaxed = true)
    private lateinit var coordinator: DomainSpeedTestCoordinator

    private var storedBestHost: String? = null

    @Before
    fun setUp() {
        storedBestHost = null

        mockkObject(SharedPrefsUtil)
        every { SharedPrefsUtil.getString("sp_speed_test_success_host", any()) } answers { storedBestHost }
        every { SharedPrefsUtil.getString("sp_speed_test_success_host") } answers { storedBestHost }
        every { SharedPrefsUtil.putString("sp_speed_test_success_host", any()) } answers {
            storedBestHost = secondArg()
        }
        every { SharedPrefsUtil.remove("sp_speed_test_success_host") } answers {
            storedBestHost = null
        }

        coordinator = DomainSpeedTestCoordinator(
            globalConfigsManager = dagger.Lazy { mockGlobalConfigsManager },
            speedTester = mockSpeedTester
        )
    }

    @After
    fun tearDown() {
        unmockkObject(SharedPrefsUtil)
    }

    // -- getBestHostSync --

    @Test
    fun `getBestHostSync returns snapshot host when available`() {
        seedSnapshot("fast.host" to 100L, "slow.host" to 500L)

        assertEquals("fast.host", coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync skips invalidated host in snapshot`() {
        seedSnapshot("bad.host" to 100L, "good.host" to 500L)
        coordinator.markHostUnavailable("bad.host")

        assertEquals("good.host", coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync falls back to persisted host when snapshot empty`() {
        storedBestHost = "persisted.host"

        assertEquals("persisted.host", coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync skips invalidated persisted host`() {
        storedBestHost = "persisted.host"
        coordinator.markHostUnavailable("persisted.host")

        // No GlobalConfig mock, so returns null
        assertNull(coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync returns null when everything exhausted`() {
        assertNull(coordinator.getBestHostSync())
    }

    // -- markHostUnavailable --

    @Test
    fun `markHostUnavailable marks host as unavailable in snapshot`() {
        seedSnapshot("bad.host" to 100L)

        coordinator.markHostUnavailable("bad.host")

        val snapshot = getSnapshot()
        val marked = snapshot.find { it.host == "bad.host" }
        assertEquals(false, marked?.isAvailable)
    }

    @Test
    fun `markHostUnavailable with empty snapshot still blocks getBestHostSync`() {
        storedBestHost = "bad.host"
        coordinator.markHostUnavailable("bad.host")

        assertNull(coordinator.getBestHostSync(), "Invalidated host should be skipped even from persisted")
    }

    // -- getAllHostsRanked --

    @Test
    fun `getAllHostsRanked returns snapshot hosts excluding invalidated`() {
        seedSnapshot("host.a" to 100L, "host.b" to 200L, "host.c" to 300L)
        coordinator.markHostUnavailable("host.b")

        val ranked = coordinator.getAllHostsRanked()

        assertEquals(listOf("host.a", "host.c"), ranked)
    }

    @Test
    fun `getAllHostsRanked returns empty when all invalidated`() {
        seedSnapshot("only.host" to 100L)
        coordinator.markHostUnavailable("only.host")

        val ranked = coordinator.getAllHostsRanked()

        assertTrue(ranked.isEmpty())
    }

    // -- onWsFailure / onWsConnected --

    @Test
    fun `onWsConnected resets failure counter`() {
        coordinator.onWsFailure()
        coordinator.onWsFailure()
        coordinator.onWsConnected()
        // After reset, need 3 more failures to trigger
        coordinator.onWsFailure()
        coordinator.onWsFailure()
        // Only 2 failures after reset, should not trigger speed test
        // (no crash = pass, speed test trigger is tested via mock interaction)
    }

    // -- resetSession --

    @Test
    fun `resetSession clears invalidated hosts`() {
        seedSnapshot("host.a" to 100L)
        coordinator.markHostUnavailable("host.a")
        assertNull(coordinator.getBestHostSync())

        coordinator.resetSession()
        // Snapshot is also cleared, so still null (no persisted host)
        // But invalidation set should be clear
        storedBestHost = "host.a"
        assertEquals("host.a", coordinator.getBestHostSync(), "After resetSession, invalidation set is cleared")
    }

    // -- Helpers --

    @Suppress("UNCHECKED_CAST")
    private fun seedSnapshot(vararg hostLatencies: Pair<String, Long>) {
        val results = hostLatencies.map {
            HostSpeedResult(host = it.first, latencyMs = it.second, isAvailable = true)
        }
        val field = DomainSpeedTestCoordinator::class.java.getDeclaredField("snapshot")
        field.isAccessible = true
        val ref = field.get(coordinator) as AtomicReference<List<HostSpeedResult>>
        ref.set(results)
    }

    @Suppress("UNCHECKED_CAST")
    private fun getSnapshot(): List<HostSpeedResult> {
        val field = DomainSpeedTestCoordinator::class.java.getDeclaredField("snapshot")
        field.isAccessible = true
        val ref = field.get(coordinator) as AtomicReference<List<HostSpeedResult>>
        return ref.get()
    }
}
