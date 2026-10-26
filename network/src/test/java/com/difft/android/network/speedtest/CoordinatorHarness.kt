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

/**
 * A [DomainSpeedTestCoordinator] over a mocked config, with the clock, the network state and the
 * probe launcher faked ([nowMs], [online], [probeLaunches]) so the throttles, the offline gate and
 * the "probe requested" decisions are deterministic (#1396).
 *
 * Uses a local in-memory UserManager rather than `FakeUserManager` from `:base` testFixtures
 * because the testFixtures Kotlin source set is not compiled for downstream consumers (see
 * base/build.gradle.kts workaround comment).
 */
internal class CoordinatorHarness {

    private class InMemoryUserManager(private var data: UserData? = UserData()) : UserManager {
        override fun setUserData(userData: UserData, commit: Boolean) {
            this.data = userData
        }

        override fun getUserData(): UserData? = data
    }

    val speedTester = mockk<DomainSpeedTester>(relaxed = true)
    private val globalConfigs = mockk<GlobalConfigsManager>(relaxed = true)
    private val proxy = mockk<ProxyConfigProvider>(relaxed = true)
    private val userManager: UserManager = InMemoryUserManager()

    var online = true
    var nowMs = START_MS
    var probeLaunches = 0

    val coordinator: DomainSpeedTestCoordinator

    init {
        // Default: proxy disabled — exercises the normal speed-test path.
        every { proxy.isEnabled } returns false
        every { globalConfigs.getProxyTunnelChatDomains() } returns emptyList()
        setPool(A, B, C)
        coordinator = DomainSpeedTestCoordinator(
            globalConfigsManager = dagger.Lazy { globalConfigs },
            speedTester = speedTester,
            userManager = userManager,
            proxyConfigProvider = dagger.Lazy { proxy },
            networkAvailable = { online },
            clock = { nowMs },
            launchProbe = { probeLaunches++ },
        )
    }

    val persistedBestHost: String? get() = userManager.getUserData()?.bestHost

    fun setPersistedBestHost(host: String?) {
        userManager.update { bestHost = host }
    }

    fun setPool(vararg hosts: String) {
        val labels = hosts.indices.map { "chat${it + 1}" }
        every { globalConfigs.getNewGlobalConfigs() } returns NewGlobalConfig(
            data = Data(
                domains = hosts.mapIndexed { i, host -> Domain(label = labels[i], domain = host, certType = "self") },
                services = listOf(Service(name = "chat", path = "/chat", domains = labels)),
            )
        )
    }

    fun setPoolUnknown() {
        every { globalConfigs.getNewGlobalConfigs() } returns null
    }

    fun setProxy(vararg tunnelHosts: String) {
        every { proxy.isEnabled } returns true
        every { globalConfigs.getProxyTunnelChatDomains() } returns tunnelHosts.toList()
    }

    /** Publishes a probe that started before anything was marked: the ranking as of "now". */
    fun probe(vararg results: Pair<String, Boolean>) {
        coordinator.publishProbeResults(
            results.mapIndexed { i, (host, isUp) -> if (isUp) up(host, (i + 1) * 100L) else down(host) },
            startGeneration = 0L,
        )
    }

    fun up(host: String, latencyMs: Long = 100L) = HostSpeedResult(host = host, latencyMs = latencyMs, isAvailable = true)

    fun down(host: String) = HostSpeedResult(host = host, latencyMs = Long.MAX_VALUE, isAvailable = false)

    companion object {
        const val A = "a.test"
        const val B = "b.test"
        const val C = "c.test"
        const val P1 = "p1.test"
        const val P2 = "p2.test"
        const val START_MS = 1_000_000L
        const val MINUTE_MS = 60_000L
    }
}
