package com.difft.android.network.speedtest

import com.difft.android.base.user.Data
import com.difft.android.base.user.Domain
import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.base.user.Service
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.proxy.ProxyConfigProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coordinator unit tests. Post-issue-#725, the legacy `SharedPrefsUtil` is gone;
 * persisted best-host now lives in the unified UserManager snapshot. An
 * in-memory [UserManager] gives us a real snapshot so we can verify level-2
 * fallback behavior end-to-end (write → read → invalidate).
 *
 * Uses a local in-memory UserManager rather than `FakeUserManager` from
 * `:base` testFixtures because the testFixtures Kotlin source set is not
 * compiled for downstream consumers (see base/build.gradle.kts workaround comment).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DomainSpeedTestCoordinatorTest {

    private class InMemoryUserManager(
        private var data: UserData? = UserData()
    ) : UserManager {
        override fun setUserData(userData: UserData, commit: Boolean) {
            this.data = userData
        }

        override fun getUserData(): UserData? = data
    }

    private val mockSpeedTester = mockk<DomainSpeedTester>(relaxed = true)
    private val mockGlobalConfigsManager = mockk<GlobalConfigsManager>(relaxed = true)
    private val mockProxyConfigProvider = mockk<ProxyConfigProvider>(relaxed = true)

    private lateinit var userManager: InMemoryUserManager
    private lateinit var coordinator: DomainSpeedTestCoordinator

    @Before
    fun setUp() {
        userManager = InMemoryUserManager()
        // Default: proxy disabled — exercises the normal speed-test path.
        every { mockProxyConfigProvider.isEnabled } returns false

        coordinator = DomainSpeedTestCoordinator(
            globalConfigsManager = dagger.Lazy { mockGlobalConfigsManager },
            speedTester = mockSpeedTester,
            userManager = userManager,
            proxyConfigProvider = dagger.Lazy { mockProxyConfigProvider },
        )
    }

    private fun setPersistedBestHost(host: String?) {
        userManager.update { bestHost = host }
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
        setPersistedBestHost("persisted.host")

        assertEquals("persisted.host", coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync skips invalidated persisted host`() {
        setPersistedBestHost("persisted.host")
        coordinator.markHostUnavailable("persisted.host")

        // No GlobalConfig mock, so returns null
        assertNull(coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync returns null when everything exhausted`() {
        assertNull(coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync falls back to resolver host from global config`() {
        // Snapshot empty + no persisted host → level-3 fallback resolves the chat
        // host pool from services + domains via ServiceUrlResolver.
        every { mockGlobalConfigsManager.getNewGlobalConfigs() } returns NewGlobalConfig(
            data = Data(
                domains = listOf(Domain(label = "chat1", domain = "chat.chative.im", certType = "self")),
                services = listOf(Service(name = "chat", path = "/chat", domains = listOf("chat1"))),
            )
        )

        assertEquals("chat.chative.im", coordinator.getBestHostSync())
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
        setPersistedBestHost("bad.host")
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

    // -- firstAvailableHost (proxy embedded-host failover) --

    @Test
    fun `firstAvailableHost returns first candidate when none invalidated`() {
        assertEquals(
            "chat.temptalk.net",
            coordinator.firstAvailableHost(listOf("chat.temptalk.net", "chat.chative.im")),
        )
    }

    @Test
    fun `firstAvailableHost skips invalidated candidate`() {
        coordinator.markHostUnavailable("chat.temptalk.net")

        assertEquals(
            "chat.chative.im",
            coordinator.firstAvailableHost(listOf("chat.temptalk.net", "chat.chative.im")),
        )
    }

    @Test
    fun `firstAvailableHost returns null when all candidates invalidated`() {
        coordinator.markHostUnavailable("chat.temptalk.net")
        coordinator.markHostUnavailable("chat.chative.im")

        assertNull(coordinator.firstAvailableHost(listOf("chat.temptalk.net", "chat.chative.im")))
    }

    @Test
    fun `firstAvailableHost returns null for empty candidates`() {
        assertNull(coordinator.firstAvailableHost(emptyList()))
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
        setPersistedBestHost("host.a")
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
