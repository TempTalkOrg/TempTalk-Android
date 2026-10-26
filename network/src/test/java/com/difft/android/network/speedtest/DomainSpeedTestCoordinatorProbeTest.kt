package com.difft.android.network.speedtest

import com.difft.android.network.speedtest.CoordinatorHarness.Companion.A
import com.difft.android.network.speedtest.CoordinatorHarness.Companion.B
import com.difft.android.network.speedtest.CoordinatorHarness.Companion.C
import com.difft.android.network.speedtest.CoordinatorHarness.Companion.MINUTE_MS
import com.difft.android.network.speedtest.CoordinatorHarness.Companion.P1
import com.difft.android.network.speedtest.CoordinatorHarness.Companion.P2
import io.mockk.coEvery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probe publication, resets and probe triggers of [DomainSpeedTestCoordinator] (#1396): a probe
 * never overwrites a host that changed after it started, and every trigger respects its throttle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DomainSpeedTestCoordinatorProbeTest {

    private val h = CoordinatorHarness()
    private val coordinator get() = h.coordinator

    @After
    fun tearDown() {
        // startPeriodicTest launches a real timer on appScope; stop it so it does not outlive the test.
        coordinator.startPeriodicTest(isForeground = false)
    }

    // -- probe publication (T10, T10b) --

    // T10 — a mark that lands while a probe is in flight survives its publication.
    @Test
    fun `a mark made while a probe runs survives the probe publication`() = runTest {
        val gate = CompletableDeferred<List<HostSpeedResult>>()
        coEvery { h.speedTester.testHosts(any()) } coAnswers { gate.await() }
        val probeJob = launch { coordinator.runSpeedTest() }
        runCurrent()

        coordinator.markHostUnavailable(A)
        gate.complete(listOf(h.up(A), h.up(B), h.up(C)))
        probeJob.join()

        assertEquals(B, coordinator.getBestHostSync())
    }

    // T10b — the probe saw A down, but A answered before the probe published.
    @Test
    fun `a healthy answer made while a probe runs survives the probe publication`() = runTest {
        val gate = CompletableDeferred<List<HostSpeedResult>>()
        coEvery { h.speedTester.testHosts(any()) } coAnswers { gate.await() }
        val probeJob = launch { coordinator.runSpeedTest() }
        runCurrent()

        coordinator.markHostHealthy(A)
        gate.complete(listOf(h.down(A), h.up(B), h.up(C)))
        probeJob.join()

        assertEquals(A, coordinator.getBestHostSync())
    }

    // #1396 — a probe that ends while the network is down must not publish every host as down.
    @Test
    fun `a probe that completes while offline is discarded`() = runTest {
        h.setPersistedBestHost(B)
        h.nowMs += 2 * MINUTE_MS
        coEvery { h.speedTester.testHosts(any()) } coAnswers {
            h.online = false
            listOf(h.down(A), h.down(B), h.down(C))
        }

        val before = coordinator.currentGeneration()
        coordinator.runSpeedTest()
        h.online = true

        assertEquals(listOf(A, B, C), coordinator.getAllHostsRanked(), "no host is published as down")
        assertEquals(before, coordinator.currentGeneration(), "nothing was exhausted, so nothing was reset")
        assertEquals(B, h.persistedBestHost)
        h.nowMs += 30_000L
        coordinator.onNetworkAvailable()
        assertEquals(1, h.probeLaunches, "the discarded probe did not consume the probe throttle")
    }

    @Test
    fun `a probe verdict replaces marks that predate the probe`() = runTest {
        coordinator.markHostUnavailable(A)
        coEvery { h.speedTester.testHosts(any()) } returns listOf(h.up(A), h.up(B), h.up(C))

        coordinator.runSpeedTest()

        assertEquals(A, coordinator.getBestHostSync())
        assertEquals(A, h.persistedBestHost)
    }

    @Test
    fun `a probe verdict of down applies to hosts nothing newer touched`() = runTest {
        coEvery { h.speedTester.testHosts(any()) } returns listOf(h.up(B), h.up(C), h.down(A))

        coordinator.runSpeedTest()

        assertEquals(listOf(B, C), coordinator.getAllHostsRanked())
    }

    // -- reset --

    @Test
    fun `resetAvailability clears marks and probe verdicts`() {
        h.probe(A to false, B to true)
        coordinator.markHostUnavailable(B)

        coordinator.resetAvailability("test")

        assertEquals(listOf(A, B), coordinator.getAllHostsRanked().take(2).sorted())
    }

    @Test
    fun `a reset survives a probe that started before it`() = runTest {
        val gate = CompletableDeferred<List<HostSpeedResult>>()
        coEvery { h.speedTester.testHosts(any()) } coAnswers { gate.await() }
        val probeJob = launch { coordinator.runSpeedTest() }
        runCurrent()

        coordinator.resetAvailability("test")
        gate.complete(listOf(h.down(A), h.up(B), h.up(C)))
        probeJob.join()

        assertTrue(A in coordinator.getAllHostsRanked())
    }

    // T22 — under the proxy the periodic clear resets what failover marked.
    @Test
    fun `the periodic proxy clear makes a marked tunnel host eligible again`() = runTest {
        h.setProxy(P1, P2)
        coordinator.markHostUnavailable(P1)
        assertEquals(P2, coordinator.firstAvailableHost(listOf(P1, P2)))

        coordinator.runPeriodicTick()

        assertEquals(P1, coordinator.firstAvailableHost(listOf(P1, P2)))
    }

    @Test
    fun `without the proxy the periodic tick runs a speed test`() = runTest {
        coEvery { h.speedTester.testHosts(any()) } returns listOf(h.up(A), h.up(B), h.up(C))

        coordinator.runPeriodicTick()

        assertEquals(A, h.persistedBestHost)
    }

    // -- probe triggers --

    @Test
    fun `three consecutive websocket failures request a probe`() {
        repeat(3) { coordinator.onWsFailure() }

        assertEquals(1, h.probeLaunches)
    }

    @Test
    fun `onWsConnected resets failure counter`() {
        coordinator.onWsFailure()
        coordinator.onWsFailure()
        coordinator.onWsConnected()
        coordinator.onWsFailure()
        coordinator.onWsFailure()

        assertEquals(0, h.probeLaunches, "only two failures since the reset")
    }

    @Test
    fun `foreground probes only when the last probe is 30 minutes old`() {
        h.nowMs += 29 * MINUTE_MS
        coordinator.startPeriodicTest(isForeground = true)
        assertEquals(0, h.probeLaunches)

        h.nowMs += 2 * MINUTE_MS
        coordinator.startPeriodicTest(isForeground = true)
        assertEquals(1, h.probeLaunches)
    }

    @Test
    fun `foreground after a probe waits another 30 minutes`() = runTest {
        coEvery { h.speedTester.testHosts(any()) } returns listOf(h.up(A))
        h.nowMs += 31 * MINUTE_MS
        coordinator.runSpeedTest()

        h.nowMs += 10 * MINUTE_MS
        coordinator.startPeriodicTest(isForeground = true)

        assertEquals(0, h.probeLaunches)
    }

    @Test
    fun `network available probes behind a 60 second throttle`() = runTest {
        h.nowMs += 30_000L
        coordinator.onNetworkAvailable()
        assertEquals(0, h.probeLaunches, "a registration-time callback right after start must not probe")

        h.nowMs += 40_000L
        coordinator.onNetworkAvailable()
        assertEquals(1, h.probeLaunches)

        coEvery { h.speedTester.testHosts(any()) } returns listOf(h.up(A))
        coordinator.runSpeedTest()
        h.nowMs += 59_000L
        coordinator.onNetworkAvailable()
        assertEquals(1, h.probeLaunches, "inside 60 s of the last probe")

        h.nowMs += 2_000L
        coordinator.onNetworkAvailable()
        assertEquals(2, h.probeLaunches)
    }

    @Test
    fun `triggerSpeedTest keeps its 30 second floor`() = runTest {
        coEvery { h.speedTester.testHosts(any()) } returns listOf(h.up(A))
        coordinator.runSpeedTest()

        h.nowMs += 29_000L
        coordinator.triggerSpeedTest()
        assertEquals(0, h.probeLaunches)

        h.nowMs += 2_000L
        coordinator.triggerSpeedTest()
        assertEquals(1, h.probeLaunches)
    }
}
