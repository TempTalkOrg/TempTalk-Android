package com.difft.android.network.speedtest

import com.difft.android.network.speedtest.CoordinatorHarness.Companion.A
import com.difft.android.network.speedtest.CoordinatorHarness.Companion.B
import com.difft.android.network.speedtest.CoordinatorHarness.Companion.C
import com.difft.android.network.speedtest.CoordinatorHarness.Companion.P1
import com.difft.android.network.speedtest.CoordinatorHarness.Companion.P2
import com.difft.android.base.log.lumberjack.L
import io.mockk.coEvery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host selection, marks and exhaustion of [DomainSpeedTestCoordinator] (#1396). Probe publication,
 * resets and probe triggers are in [DomainSpeedTestCoordinatorProbeTest].
 *
 * Post-issue-#725 the persisted best host lives in the unified UserManager snapshot; the harness's
 * in-memory [com.difft.android.base.user.UserManager] verifies level-2 fallback end to end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DomainSpeedTestCoordinatorTest {

    private val h = CoordinatorHarness()
    private val coordinator get() = h.coordinator

    // -- getBestHostSync --

    @Test
    fun `getBestHostSync returns snapshot host when available`() {
        h.probe(B to true, A to true)

        assertEquals(B, coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync skips marked host in snapshot`() {
        h.probe(A to true, B to true)
        coordinator.markHostUnavailable(A)

        assertEquals(B, coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync falls back to persisted host when snapshot empty`() {
        h.setPersistedBestHost(B)

        assertEquals(B, coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync ignores a persisted host that left the pool`() {
        h.setPersistedBestHost("retired.test")

        assertEquals(A, coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync skips a marked persisted host`() {
        h.setPersistedBestHost(A)
        coordinator.markHostUnavailable(A)

        assertEquals(B, coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync returns null when the pool is unknown`() {
        h.setPoolUnknown()

        assertNull(coordinator.getBestHostSync())
    }

    @Test
    fun `getBestHostSync falls back to resolver host from global config`() {
        // Snapshot empty + no persisted host → level-3 fallback resolves the chat
        // host pool from services + domains via ServiceUrlResolver.
        assertEquals(A, coordinator.getBestHostSync())
    }

    // T12 — a host the probe found down is chosen by no tier, persisted or not.
    @Test
    fun `a host the probe found down is chosen by no tier`() {
        h.probe(A to true, B to false)
        h.setPersistedBestHost(B)
        coordinator.markHostUnavailable(A)

        assertEquals(C, coordinator.getBestHostSync())
        assertEquals(listOf(C), coordinator.getAllHostsRanked())
        assertEquals(C, coordinator.firstAvailableHost(listOf(B, C)))
    }

    // -- exhaustion (T11) --

    // T11 — every host marked and the probe throttled: availability still resets at once.
    @Test
    fun `exhausted pool resets availability even when the probe is throttled`() = runTest {
        coEvery { h.speedTester.testHosts(any()) } returns listOf(h.up(A), h.up(B), h.up(C))
        coordinator.runSpeedTest()
        coordinator.markHostUnavailable(A)
        coordinator.markHostUnavailable(B)
        coordinator.markHostUnavailable(C)

        val host = coordinator.getBestHostSync()

        assertTrue(host in listOf(A, B, C), "a pool host is chosen again, got $host")
        assertEquals(0, h.probeLaunches, "the probe is throttled (30 s floor), the reset alone applies")
        assertEquals(listOf(A, B, C), coordinator.getAllHostsRanked().sorted())
    }

    @Test
    fun `exhausted pool requests a probe when one is allowed`() {
        coordinator.markHostUnavailable(A)
        coordinator.markHostUnavailable(B)
        coordinator.markHostUnavailable(C)

        assertEquals(A, coordinator.getBestHostSync())

        assertEquals(1, h.probeLaunches)
    }

    @Test
    fun `exhaustion under the proxy resets the tunnel hosts and still requests a probe`() {
        h.setProxy(P1, P2)
        coordinator.markHostUnavailable(P1)
        coordinator.markHostUnavailable(P2)

        assertEquals(P1, coordinator.firstAvailableHost(listOf(P1, P2)))
        assertEquals(1, h.probeLaunches, "the request is made; runSpeedTest itself skips under the proxy")
    }

    @Test
    fun `getAllHostsRanked excludes marked hosts and resets when none is left`() {
        h.probe(A to true, B to true, C to true)
        coordinator.markHostUnavailable(B)

        assertEquals(listOf(A, C), coordinator.getAllHostsRanked())

        coordinator.markHostUnavailable(A)
        coordinator.markHostUnavailable(C)
        assertEquals(listOf(A, B, C), coordinator.getAllHostsRanked().sorted())
    }

    // -- markHostUnavailable --

    @Test
    fun `markHostUnavailable keeps the host out of every tier until it is healthy again`() {
        coordinator.markHostUnavailable(A)

        assertEquals(B, coordinator.getBestHostSync())
        assertEquals(listOf(B, C), coordinator.getAllHostsRanked())
    }

    @Test
    fun `markHostUnavailable ignores a host outside the pool`() {
        val before = coordinator.currentGeneration()

        coordinator.markHostUnavailable("cdn.test")

        assertEquals(before, coordinator.currentGeneration())
    }

    // T14 — no usable network: a connect failure says nothing about the host.
    @Test
    fun `markHostUnavailable is dropped while the device is offline`() {
        h.online = false
        coordinator.markHostUnavailable(A)
        coordinator.markHostUnavailable(B)
        h.online = true

        assertEquals(A, coordinator.getBestHostSync())
        assertEquals(listOf(A, B, C), coordinator.getAllHostsRanked())
    }

    // -- healthy answers (T13) --

    // T13 — any answer from a marked host makes it eligible again.
    @Test
    fun `a healthy answer makes a marked host eligible again`() {
        coordinator.markHostUnavailable(A)
        assertEquals(B, coordinator.getBestHostSync())

        coordinator.markHostHealthy(A)

        assertEquals(A, coordinator.getBestHostSync())
    }

    @Test
    fun `a healthy answer overrides a probe verdict of down`() {
        h.probe(A to false, B to true)

        coordinator.markHostHealthy(A)

        assertTrue(A in coordinator.getAllHostsRanked())
    }

    @Test
    fun `a healthy answer from an unmarked host writes nothing`() {
        val before = coordinator.currentGeneration()

        coordinator.markHostHealthy(A)
        coordinator.markHostHealthy("cdn.test")

        assertEquals(before, coordinator.currentGeneration())
    }

    // #1396 — a one-host pool (proxy mode with one tunnel domain) has nowhere to fail over to.
    @Test
    fun `marking the only pool host writes nothing and never exhausts the pool`() {
        h.setProxy(P1)
        val before = coordinator.currentGeneration()

        coordinator.markHostUnavailable(P1)

        assertEquals(P1, coordinator.firstAvailableHost(listOf(P1)))
        assertEquals(before, coordinator.currentGeneration(), "no mark and no exhaustion reset")
        assertEquals(0, h.probeLaunches)
    }

    @Test
    fun `marking one host of a two host pool still works`() {
        h.setProxy(P1, P2)

        coordinator.markHostUnavailable(P1)

        assertEquals(P2, coordinator.firstAvailableHost(listOf(P1, P2)))
    }

    // #1396 — only a real down to up change is logged, not every answer while a probe is in flight.
    @Test
    fun `a healthy answer logs only when it clears a down host`() = runTest {
        val lines = CopyOnWriteArrayList<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += message
            }
        }
        Timber.plant(tree)
        try {
            val gate = CompletableDeferred<List<HostSpeedResult>>()
            coEvery { h.speedTester.testHosts(any()) } coAnswers { gate.await() }
            val probeJob = launch { coordinator.runSpeedTest() }
            runCurrent()

            repeat(3) { coordinator.markHostHealthy(A) }
            coordinator.markHostUnavailable(B)
            coordinator.markHostHealthy(B)
            L.i { "sentinel" }
            withContext(Dispatchers.Default) { withTimeout(5_000) { while ("sentinel" !in lines) delay(10) } }
            gate.complete(listOf(h.up(A), h.up(B), h.up(C)))
            probeJob.join()

            val healthy = lines.filter { "markHostHealthy" in it }
            assertEquals(1, healthy.size, "A answered while up, only B changed state: $healthy")
            assertTrue("host=$B" in healthy.single())
        } finally {
            Timber.uproot(tree)
        }
    }

    // -- firstAvailableHost / eligibleHosts (proxy embedded-host failover) --

    @Test
    fun `firstAvailableHost returns first candidate when none marked`() {
        assertEquals(P1, coordinator.firstAvailableHost(listOf(P1, P2)))
    }

    @Test
    fun `firstAvailableHost skips a marked candidate`() {
        h.setProxy(P1, P2)
        coordinator.markHostUnavailable(P1)

        assertEquals(P2, coordinator.firstAvailableHost(listOf(P1, P2)))
        assertEquals(listOf(P2), coordinator.eligibleHosts(listOf(P1, P2)))
    }

    @Test
    fun `firstAvailableHost returns null for empty candidates`() {
        assertNull(coordinator.firstAvailableHost(emptyList()))
    }

    @Test
    fun `probe verdicts are ignored while the proxy is on`() {
        h.probe(P1 to false, P2 to true)
        h.setProxy(P1, P2)

        assertEquals(P1, coordinator.firstAvailableHost(listOf(P1, P2)))
    }

    // -- resetSession --

    @Test
    fun `resetSession clears marks`() {
        coordinator.markHostUnavailable(A)
        assertEquals(B, coordinator.getBestHostSync())

        coordinator.resetSession()

        assertEquals(A, coordinator.getBestHostSync())
    }
}
