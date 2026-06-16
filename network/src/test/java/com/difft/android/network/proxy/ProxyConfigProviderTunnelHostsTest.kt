package com.difft.android.network.proxy

import com.difft.android.base.user.Data
import com.difft.android.base.user.Domain
import com.difft.android.base.user.Host
import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ICallServiceUrlsProvider
import com.difft.android.base.utils.IConnectionRefresher
import com.difft.android.base.utils.IGlobalConfigsManager
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [ProxyConfigProvider.shouldTunnel] /
 * [ProxyConfigProvider.recomputeTunnelHosts] / [ProxyConfigProvider.extractGlobalSelfCertHosts]
 * driven through the provider's public push hooks. Mocks the two manager
 * collaborators (`IGlobalConfigsManager`, `ICallServiceUrlsProvider`).
 *
 * Robolectric is retained for parity with the other proxy tests in this package,
 * even though the redesigned `ProxyConfigProvider` no longer touches Android
 * `SharedPreferences` (persistence flows through [UserManager] now).
 *
 * Coverage map (design §10):
 *  - T5: extractGlobalSelfCertHosts filter across both list shapes
 *  - T8-T12: shouldTunnel match semantics
 *  - T13-T14: recompute push paths preserve the other source's entries
 *  - T15: cold-start fallback (both sources empty → baseline only)
 *  - T17: concurrent recompute (thread safety)
 *  - T18: clear() preserves the snapshot
 *
 * Note: T1-T7 (pure derivation) live in `ComputeTunnelHostsTest`.
 * T16 (CallServiceUrlManager assets fallback) lives in `:call`'s test source set
 * because `:network`'s test classpath does NOT include `:call`.
 */
@RunWith(RobolectricTestRunner::class)
class ProxyConfigProviderTunnelHostsTest {

    /** Mirrors `ProxyConfigProvider.HARDCODED_BASELINE`. */
    private val baseline: Set<String> = setOf(
        "chative.im",
        "temptalk.net",
        "ablivekit.org",
        "chative.online",
        "chative.ninja",
    )

    /**
     * Minimal in-memory [UserManager] that satisfies the interface contract.
     * These tests focus on tunnel-host bookkeeping; the persistence path is
     * incidental, so the stub stays minimal (no proxy fields exercised here).
     */
    private class FakeUserManager : UserManager {
        private var current: UserData? = UserData()
        override fun setUserData(userData: UserData, commit: Boolean) {
            current = userData
        }

        override fun getUserData(): UserData? = current
    }

    private fun lazyOf(impl: IGlobalConfigsManager): Lazy<IGlobalConfigsManager> =
        Lazy { impl }

    private fun lazyOfCall(impl: ICallServiceUrlsProvider): Lazy<ICallServiceUrlsProvider> =
        Lazy { impl }

    /** No-op refresher — tunnel-host bookkeeping tests don't exercise the reconnect path. */
    private object NoOpRefresher : IConnectionRefresher {
        override fun reconnectAfterProxyChange() = Unit
    }

    private val noOpRefresherLazy: Lazy<IConnectionRefresher> = Lazy { NoOpRefresher }

    /** Builds a `NewGlobalConfig` with the supplied self-cert host names + domain names. */
    private fun configWithSelfCertEntries(
        hostNames: List<String> = emptyList(),
        domainNames: List<String> = emptyList(),
        authorityHostNames: List<String> = emptyList(),
        authorityDomainNames: List<String> = emptyList(),
    ): NewGlobalConfig {
        val hosts = hostNames.map { Host(certType = "self", name = it) } +
            authorityHostNames.map { Host(certType = "authority", name = it) }
        val domains = domainNames.map { Domain(domain = it, certType = "self") } +
            authorityDomainNames.map { Domain(domain = it, certType = "authority") }
        return NewGlobalConfig(code = 0, data = Data(hosts = hosts, domains = domains))
    }

    private fun makeProvider(
        gc: NewGlobalConfig?,
        callDomains: List<String>,
    ): ProxyConfigProvider {
        val gcm = mockk<IGlobalConfigsManager>(relaxed = true)
        every { gcm.getNewGlobalConfigs() } returns gc
        val csp = mockk<ICallServiceUrlsProvider>(relaxed = true)
        every { csp.getCachedServiceUrlsDomains() } returns callDomains
        return ProxyConfigProvider(
            userManager = FakeUserManager(),
            globalConfigsManagerLazy = lazyOf(gcm),
            callServiceUrlsProviderLazy = lazyOfCall(csp),
            connectionRefresherLazy = noOpRefresherLazy,
        )
    }

    // -----------------------------------------------------------------------
    // T5
    // -----------------------------------------------------------------------

    @Test
    fun `T5 extractGlobalSelfCertHosts - filters self entries across hosts and domains`() {
        // Arrange
        val provider = makeProvider(gc = null, callDomains = emptyList())
        val config = configWithSelfCertEntries(
            hostNames = listOf("a.com"),
            domainNames = listOf("d.com"),
            authorityHostNames = listOf("cdn.x.com"),
            authorityDomainNames = listOf("pub.com"),
        )

        // Act
        val extracted = provider.extractGlobalSelfCertHosts(config)

        // Assert: authority entries filtered out, both list shapes contribute.
        assertEquals(listOf("a.com", "d.com"), extracted)
    }

    @Test
    fun `T5b extractGlobalSelfCertHosts - null config returns empty`() {
        // Arrange
        val provider = makeProvider(gc = null, callDomains = emptyList())

        // Act
        val extracted = provider.extractGlobalSelfCertHosts(null)

        // Assert
        assertTrue(extracted.isEmpty())
    }

    // -----------------------------------------------------------------------
    // T8-T12 (shouldTunnel semantics)
    // -----------------------------------------------------------------------

    @Test
    fun `T8 shouldTunnel - exact match against a baseline entry`() {
        // Arrange — no global / call contributions; only baseline.
        val provider = makeProvider(gc = null, callDomains = emptyList())
        // Drive a synchronous recompute so we know the set is published before the assertion.
        provider.onGlobalConfigChanged()

        // Act + Assert
        assertTrue(provider.shouldTunnel("chative.im"))
    }

    @Test
    fun `T9 shouldTunnel - subdomain match against a baseline entry`() {
        // Arrange
        val provider = makeProvider(gc = null, callDomains = emptyList())
        provider.onGlobalConfigChanged()

        // Act + Assert
        assertTrue(provider.shouldTunnel("api.chative.im"))
    }

    @Test
    fun `T10 shouldTunnel - sibling FQDN does not match unrelated parent`() {
        // Arrange — add "srv.example.org" to the set via the global config push.
        val gc = configWithSelfCertEntries(hostNames = listOf("srv.example.org"))
        val provider = makeProvider(gc = gc, callDomains = emptyList())
        provider.onGlobalConfigChanged()

        // Assert (a): sibling FQDN — "api.example.org" is NOT a child of
        // "srv.example.org" and matches no baseline entry.
        assertFalse(provider.shouldTunnel("api.example.org"))
        // Assert (b): exact match against the FQDN entry.
        assertTrue(provider.shouldTunnel("srv.example.org"))
        // Assert (c): subdomain of the FQDN entry.
        assertTrue(provider.shouldTunnel("child.srv.example.org"))
    }

    @Test
    fun `T11 shouldTunnel - query side normalization upper case and trailing dot`() {
        // Arrange
        val provider = makeProvider(gc = null, callDomains = emptyList())
        provider.onGlobalConfigChanged()

        // Act + Assert: query is normalized before match.
        assertTrue(provider.shouldTunnel("API.CHATIVE.IM."))
    }

    @Test
    fun `T12 shouldTunnel - non-match returns false`() {
        // Arrange — only baseline present.
        val provider = makeProvider(gc = null, callDomains = emptyList())
        provider.onGlobalConfigChanged()

        // Act + Assert
        assertFalse(provider.shouldTunnel("example.org"))
    }

    // -----------------------------------------------------------------------
    // T13-T14 (recompute push paths preserve the other source's entries)
    // -----------------------------------------------------------------------

    @Test
    fun `T13 recompute via onGlobalConfigChanged retains existing call entries`() {
        // Arrange — prime with empty global + a call entry.
        val gcm = mockk<IGlobalConfigsManager>(relaxed = true)
        every { gcm.getNewGlobalConfigs() } returns null
        val csp = mockk<ICallServiceUrlsProvider>(relaxed = true)
        every { csp.getCachedServiceUrlsDomains() } returns listOf("c.ablivekit.org")
        val provider = ProxyConfigProvider(
            userManager = FakeUserManager(),
            globalConfigsManagerLazy = lazyOf(gcm),
            callServiceUrlsProviderLazy = lazyOfCall(csp),
            connectionRefresherLazy = noOpRefresherLazy,
        )
        provider.onGlobalConfigChanged()
        assertTrue(provider.shouldTunnel("c.ablivekit.org")) // baseline pre-assertion

        // Act — update global mock and fire the global push hook.
        val updatedGc = configWithSelfCertEntries(hostNames = listOf("g.example.com"))
        every { gcm.getNewGlobalConfigs() } returns updatedGc
        provider.onGlobalConfigChanged()

        // Assert: BOTH new global-derived entry AND existing call-derived entry are present.
        assertTrue(provider.shouldTunnel("g.example.com"))
        assertTrue(provider.shouldTunnel("c.ablivekit.org"))
    }

    @Test
    fun `T14 recompute via onCallServiceUrlsChanged retains existing global entries`() {
        // Arrange — prime with a global entry + empty call.
        val initialGc = configWithSelfCertEntries(hostNames = listOf("g.example.com"))
        val gcm = mockk<IGlobalConfigsManager>(relaxed = true)
        every { gcm.getNewGlobalConfigs() } returns initialGc
        val csp = mockk<ICallServiceUrlsProvider>(relaxed = true)
        every { csp.getCachedServiceUrlsDomains() } returns emptyList()
        val provider = ProxyConfigProvider(
            userManager = FakeUserManager(),
            globalConfigsManagerLazy = lazyOf(gcm),
            callServiceUrlsProviderLazy = lazyOfCall(csp),
            connectionRefresherLazy = noOpRefresherLazy,
        )
        provider.onCallServiceUrlsChanged()
        assertTrue(provider.shouldTunnel("g.example.com")) // baseline pre-assertion

        // Act — update call mock and fire the call push hook.
        every { csp.getCachedServiceUrlsDomains() } returns listOf("c.ablivekit.org")
        provider.onCallServiceUrlsChanged()

        // Assert: BOTH global-derived and new call-derived entries present.
        assertTrue(provider.shouldTunnel("g.example.com"))
        assertTrue(provider.shouldTunnel("c.ablivekit.org"))
    }

    // -----------------------------------------------------------------------
    // T15 — cold-start fallback
    // -----------------------------------------------------------------------

    @Test
    fun `T15 cold start - both sources empty leaves baseline only`() {
        // Arrange
        val provider = makeProvider(gc = null, callDomains = emptyList())

        // Act — drive a synchronous recompute via the public hook so we don't
        // race the async init recompute.
        provider.onGlobalConfigChanged()

        // Assert — every baseline entry tunnels; nothing outside baseline does.
        baseline.forEach { entry ->
            assertTrue(provider.shouldTunnel(entry), "baseline entry should tunnel: $entry")
        }
        assertFalse(provider.shouldTunnel("not-in-baseline.example"))
    }

    // -----------------------------------------------------------------------
    // T17 — concurrent recompute
    // -----------------------------------------------------------------------

    @Test
    fun `T17 concurrent recompute - no torn set and baseline always present`() {
        // Arrange — mocks that return slightly different lists per call so we can
        // exercise the read-managers-then-publish sequence under contention.
        val gcm = mockk<IGlobalConfigsManager>(relaxed = true)
        val csp = mockk<ICallServiceUrlsProvider>(relaxed = true)
        every { gcm.getNewGlobalConfigs() } answers {
            configWithSelfCertEntries(hostNames = listOf("g${System.nanoTime() % 7}.example.com"))
        }
        every { csp.getCachedServiceUrlsDomains() } answers {
            listOf("c${System.nanoTime() % 5}.ablivekit.org")
        }
        val provider = ProxyConfigProvider(
            userManager = FakeUserManager(),
            globalConfigsManagerLazy = lazyOf(gcm),
            callServiceUrlsProviderLazy = lazyOfCall(csp),
            connectionRefresherLazy = noOpRefresherLazy,
        )

        val iterations = 1000
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(2)
        val errors = mutableListOf<Throwable>()
        val errorsLock = Any()

        // Two worker threads racing on recompute.
        val workers = (1..2).map { workerIdx ->
            Thread({
                try {
                    startGate.await()
                    repeat(iterations) { i ->
                        if (workerIdx == 1) provider.onGlobalConfigChanged()
                        else provider.onCallServiceUrlsChanged()
                        if (i % 50 == 0) {
                            // Sanity-read while writers are active. shouldTunnel
                            // does a single volatile load + iterate over a Set
                            // that the writer replaced atomically — no torn read.
                            provider.shouldTunnel("chative.im")
                        }
                    }
                } catch (t: Throwable) {
                    synchronized(errorsLock) { errors.add(t) }
                } finally {
                    doneGate.countDown()
                }
            }, "T17-worker-$workerIdx")
        }
        workers.forEach { it.start() }

        // Act
        startGate.countDown()
        val finished = doneGate.await(30, TimeUnit.SECONDS)

        // Assert: no exceptions, all baseline entries still present, set non-empty.
        assertTrue(finished, "workers did not finish within 30s")
        assertTrue(errors.isEmpty(), "concurrent recompute raised: ${errors.joinToString { it.message ?: it.toString() }}")
        // Final reads — should never throw and should always contain the baseline.
        baseline.forEach { entry ->
            assertTrue(provider.shouldTunnel(entry), "post-race: baseline entry must tunnel: $entry")
        }
    }

    // -----------------------------------------------------------------------
    // T18 — clear() preserves the tunnel-host snapshot
    // -----------------------------------------------------------------------

    @Test
    fun `T18 clear preserves tunnel-host snapshot (set independent of proxy on-off)`() {
        // Arrange — populate the set via push hooks.
        val gcm = mockk<IGlobalConfigsManager>(relaxed = true)
        every { gcm.getNewGlobalConfigs() } returns configWithSelfCertEntries(
            hostNames = listOf("g.example.com"),
        )
        val csp = mockk<ICallServiceUrlsProvider>(relaxed = true)
        every { csp.getCachedServiceUrlsDomains() } returns listOf("c.ablivekit.org")
        val provider = ProxyConfigProvider(
            userManager = FakeUserManager(),
            globalConfigsManagerLazy = lazyOf(gcm),
            callServiceUrlsProviderLazy = lazyOfCall(csp),
            connectionRefresherLazy = noOpRefresherLazy,
        )
        provider.onGlobalConfigChanged()
        assertTrue(provider.shouldTunnel("g.example.com"))
        assertTrue(provider.shouldTunnel("c.ablivekit.org"))
        assertTrue(provider.shouldTunnel("chative.im")) // baseline

        // Act — clear the proxy. This wipes the persisted link + the enabledFlag,
        // but per §6.3 / §9 it MUST NOT wipe tunnelHostSet (which describes
        // "which hosts WOULD be tunneled if the proxy were on").
        provider.clear()

        // Assert: proxy is off (cached == null) but the tunnel-host snapshot is intact.
        assertEquals(null, provider.current)
        assertEquals(null, provider.savedShareLink)
        assertTrue(provider.shouldTunnel("g.example.com"), "global-derived entry must persist post-clear")
        assertTrue(provider.shouldTunnel("c.ablivekit.org"), "call-derived entry must persist post-clear")
        baseline.forEach { entry ->
            assertTrue(provider.shouldTunnel(entry), "baseline entry must persist post-clear: $entry")
        }
    }

    // -----------------------------------------------------------------------
    // Sanity: provider construction returns a usable instance.
    // -----------------------------------------------------------------------

    @Test
    fun `provider constructs cleanly and exposes baseline immediately`() {
        // Arrange
        val provider = makeProvider(gc = null, callDomains = emptyList())

        // Assert — even before any recompute the baseline answer is correct.
        assertNotNull(provider)
        assertTrue(provider.shouldTunnel("chative.im"))
    }
}
