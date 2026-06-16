package com.difft.android.setting.viewmodel

import app.cash.turbine.test
import com.difft.android.network.proxy.ProxyConfig
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.proxy.ProxyConnectivityChecker
import com.difft.android.network.proxy.ProxyLinkCodec
import com.difft.android.setting.proxy.ProxyE2eProbe
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.ConnStatus
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.E2eStatus
import com.difft.android.test.TestDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ProxySettingsViewModel]'s stage-1 → stage-2 orchestration
 * (proxy E2E probe, design §10 T1–T5, T11, T12).
 *
 * [ProxyConnectivityChecker] is an `object`, so `mockkObject` is a GLOBAL mock —
 * every test that uses it MUST pair `@Before mockkObject` with `@After unmockkObject`
 * or stubbed `check()` behaviour leaks into the next test (TEST-1).
 *
 * The probe is injected as a fake [ProxyE2eProbe]; no real network is touched.
 */
class ProxySettingsViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var provider: ProxyConfigProvider
    private lateinit var probe: ProxyE2eProbe

    private val dummyConfig: ProxyConfig = mockk(relaxed = true)

    @Before
    fun setup() {
        mockkObject(ProxyConnectivityChecker)
        provider = mockk(relaxed = true)
        probe = mockk(relaxed = true)
        // loadState() reads these in init{}; relaxed defaults are fine but pin them
        // so the editable-form state is deterministic.
        every { provider.savedShareLink } returns ""
        every { provider.isEnabledByUser } returns true
        every { provider.isEnabled } returns true
    }

    @After
    fun tearDown() {
        unmockkObject(ProxyConnectivityChecker)
    }

    private fun buildViewModel() = ProxySettingsViewModel(provider, probe)

    /**
     * Awaits the terminal [ProxySettingsViewModel.UiState] via Turbine.
     *
     * [checkConnectivity] / [runE2eProbe] run in [viewModelScope] (a separate
     * scope from the test's [runTest] scope) and use real `Dispatchers.IO` inside
     * `withContext`. Therefore `runTest` does NOT await them and reading
     * `uiState.value` immediately after construction observes the intermediate
     * CHECKING frame. Turbine's `awaitItem()` suspends until the real emission
     * arrives, so we drain until [predicate] holds.
     */
    private suspend fun ProxySettingsViewModel.awaitState(
        predicate: (ProxySettingsViewModel.UiState) -> Boolean,
    ): ProxySettingsViewModel.UiState {
        var result: ProxySettingsViewModel.UiState? = null
        uiState.test {
            var item = awaitItem()
            while (!predicate(item)) item = awaitItem()
            result = item
            cancelAndIgnoreRemainingEvents()
        }
        return result!!
    }

    // T1: stage 1 fails → stage 2 must NOT trigger.
    @Test
    fun `stage1 failure does not trigger stage2`() = runTest {
        every { provider.current } returns dummyConfig
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = false, failure = ProxyConnectivityChecker.Failure.TIMEOUT)

        val vm = buildViewModel()

        val state = vm.awaitState { it.connStatus == ConnStatus.UNAVAILABLE }
        assertEquals(ConnStatus.UNAVAILABLE, state.connStatus)
        assertEquals(E2eStatus.NONE, state.e2eStatus)
        coVerify(exactly = 0) { probe.probe() }
    }

    // T2: stage 1 OK + recheck current != null + probe true → e2eStatus CHECKING → OK,
    // and AVAILABLE is emitted in the SAME emission as e2eStatus=CHECKING (merged update, no AVAILABLE+NONE frame).
    @Test
    fun `stage1 ok then probe success transitions to OK with merged update`() = runTest {
        every { provider.current } returns dummyConfig
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)
        coEvery { probe.probe() } returns true

        val vm = buildViewModel()

        vm.uiState.test {
            // Drain to the merged AVAILABLE + CHECKING emission and assert there is
            // NO AVAILABLE+NONE intermediate frame (GAP-4 merged update).
            var sawMergedCheck = false
            var item = awaitItem()
            while (item.e2eStatus != E2eStatus.OK) {
                // Any AVAILABLE frame must carry CHECKING (or already OK) — never NONE.
                if (item.connStatus == ConnStatus.AVAILABLE && item.e2eStatus == E2eStatus.CHECKING) {
                    sawMergedCheck = true
                }
                assertTrue(
                    !(item.connStatus == ConnStatus.AVAILABLE && item.e2eStatus == E2eStatus.NONE),
                    "Unexpected AVAILABLE+NONE intermediate frame (merged update violated)",
                )
                item = awaitItem()
            }
            assertEquals(ConnStatus.AVAILABLE, item.connStatus)
            assertEquals(E2eStatus.OK, item.e2eStatus)
            assertTrue(sawMergedCheck, "Expected a merged AVAILABLE+CHECKING emission")
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { probe.probe() }
    }

    // T3: stage 1 OK + probe false → e2eStatus FAILED, connStatus stays AVAILABLE.
    @Test
    fun `probe false maps to FAILED`() = runTest {
        every { provider.current } returns dummyConfig
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)
        coEvery { probe.probe() } returns false

        val vm = buildViewModel()

        val state = vm.awaitState { it.e2eStatus == E2eStatus.FAILED }
        assertEquals(ConnStatus.AVAILABLE, state.connStatus)
        assertEquals(E2eStatus.FAILED, state.e2eStatus)
    }

    // T4: probe throws → VM does not crash, e2eStatus FAILED (runCatching defense).
    @Test
    fun `probe throwing is caught and maps to FAILED`() = runTest {
        every { provider.current } returns dummyConfig
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)
        coEvery { probe.probe() } throws RuntimeException("boom")

        val vm = buildViewModel()

        val state = vm.awaitState { it.e2eStatus == E2eStatus.FAILED }
        assertEquals(ConnStatus.AVAILABLE, state.connStatus)
        assertEquals(E2eStatus.FAILED, state.e2eStatus)
    }

    // T5: toggle OFF / no link → current == null → both stages skipped.
    @Test
    fun `toggle off skips both stages`() = runTest {
        every { provider.current } returns null

        val vm = buildViewModel()

        // current == null path is synchronous (no withContext), so .value settles immediately.
        val state = vm.uiState.value
        assertEquals(ConnStatus.NONE, state.connStatus)
        assertEquals(E2eStatus.NONE, state.e2eStatus)
        coVerify(exactly = 0) { probe.probe() }
        // ProxyConnectivityChecker.check must also be skipped when there's no config.
        coVerify(exactly = 0) { ProxyConnectivityChecker.check(any(), any()) }
    }

    // T11: saveWithPassphrase(correct) → reload → two-stage path → e2eStatus OK.
    @Test
    fun `saveWithPassphrase success triggers stage2`() = runTest {
        mockkObject(ProxyLinkCodec)
        try {
            // init{}: current null so no probe fires during construction; flip to non-null after save.
            every { provider.current } returns null
            every { ProxyConnectivityChecker.check(any(), any()) } returns
                ProxyConnectivityChecker.Outcome(ok = true)
            coEvery { probe.probe() } returns true

            val decodedConfig: ProxyConfig = mockk(relaxed = true)
            every { decodedConfig.toShareLink() } returns "proxy://plain-link"
            every { ProxyLinkCodec.decodeEncrypted(any(), any()) } returns
                ProxyLinkCodec.Decoded.Success(decodedConfig)
            every { provider.save(any(), any()) } returns true

            val vm = buildViewModel()
            assertEquals(E2eStatus.NONE, vm.uiState.value.e2eStatus)

            // After save succeeds, reload() re-probes; the proxy is now active.
            every { provider.current } returns dummyConfig
            every { provider.isEnabledByUser } returns true
            every { provider.isEnabled } returns true

            vm.saveWithPassphrase("correct")

            val terminal = vm.awaitState { it.e2eStatus == E2eStatus.OK }
            assertEquals(ConnStatus.AVAILABLE, terminal.connStatus)
            assertEquals(E2eStatus.OK, terminal.e2eStatus)
            coVerify(exactly = 1) { probe.probe() }
        } finally {
            unmockkObject(ProxyLinkCodec)
        }
    }

    // T12: stage 1 OK but proxy disabled BEFORE stage 2 (current flips to null on recheck) →
    // connStatus AVAILABLE but e2eStatus stays NONE; probe NOT called.
    @Test
    fun `stage1 ok but proxy disabled before stage2 does not probe`() = runTest {
        // First read (start of checkConnectivity) non-null; second read (re-check) null.
        every { provider.current } returnsMany listOf(dummyConfig, null)
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)

        val vm = buildViewModel()

        val state = vm.awaitState { it.connStatus == ConnStatus.AVAILABLE }
        assertEquals(ConnStatus.AVAILABLE, state.connStatus)
        assertEquals(E2eStatus.NONE, state.e2eStatus)
        coVerify(exactly = 0) { probe.probe() }
    }
}
