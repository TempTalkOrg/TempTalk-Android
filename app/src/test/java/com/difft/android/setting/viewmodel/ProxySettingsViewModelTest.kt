package com.difft.android.setting.viewmodel

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.difft.android.R
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.network.proxy.ProxyConfig
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.proxy.ProxyConnectivityChecker
import com.difft.android.network.proxy.ProxyLinkCodec
import com.difft.android.setting.proxy.ProxyE2eProbe
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.ProbeState
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.UiEvent
import com.difft.android.test.TestDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
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
    // Real instance (no-arg @Inject constructor): mocking is impractical because the
    // class declares both a `val isInCalling` property and an `isInCalling()` function,
    // whose name clash defeats MockK's `every {}` recorder.
    private lateinit var callState: OnGoingCallStateManager

    private val dummyConfig: ProxyConfig = mockk(relaxed = true)

    @Before
    fun setup() {
        mockkObject(ProxyConnectivityChecker)
        // checkConnectivity() now probes the SAVED address via ProxyConfig.parse()
        // (→ ProxyLinkCodec.decodePlain), so the codec is mocked globally: blank →
        // null (no probe), non-blank → a dummy config. Individual tests override.
        mockkObject(ProxyLinkCodec)
        provider = mockk(relaxed = true)
        probe = mockk(relaxed = true)
        callState = OnGoingCallStateManager()
        // loadState() reads these in init{}; relaxed defaults are fine but pin them
        // so the editable-form state is deterministic.
        every { provider.savedShareLink } returns ""
        every { provider.isEnabledByUser } returns true
        every { provider.isEnabled } returns true
        every { ProxyLinkCodec.decodePlain(any()) } answers {
            if (firstArg<String>().isBlank()) null else dummyConfig
        }
        every { ProxyLinkCodec.isUnsupportedVersion(any()) } returns false
        // Default stub so a stray probe never touches a real socket; tests override.
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = false, failure = ProxyConnectivityChecker.Failure.UNREACHABLE)
    }

    // Track built VMs so tearDown can cancel each viewModelScope. The PR3 call-state
    // collector runs forever in viewModelScope; without cleanup it leaks across tests
    // and surfaces as UncaughtExceptionsBeforeTest in the next runTest.
    private val builtViewModels = mutableListOf<ProxySettingsViewModel>()

    @After
    fun tearDown() {
        builtViewModels.forEach { it.viewModelScope.cancel() }
        builtViewModels.clear()
        unmockkObject(ProxyConnectivityChecker)
        unmockkObject(ProxyLinkCodec)
    }

    private fun buildViewModel() =
        ProxySettingsViewModel(provider, probe, callState).also { builtViewModels += it }

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

    // T1: stage 1 fails → ProxyUnavailable, stage 2 must NOT trigger.
    @Test
    fun `stage1 failure shows proxy unavailable and skips stage2`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = false, failure = ProxyConnectivityChecker.Failure.TIMEOUT)

        val vm = buildViewModel()

        val state = vm.awaitState { it.probe is ProbeState.ProxyUnavailable }
        assertEquals(false, (state.probe as ProbeState.ProxyUnavailable).verifyFailed)
        coVerify(exactly = 0) { probe.probe() }
    }

    // T1b: stage 1 PIN_MISMATCH → ProxyUnavailable(verifyFailed=true) ("无法验证代理").
    @Test
    fun `stage1 pin mismatch maps to verify failed`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = false, failure = ProxyConnectivityChecker.Failure.PIN_MISMATCH)

        val vm = buildViewModel()

        val state = vm.awaitState { it.probe is ProbeState.ProxyUnavailable }
        assertTrue((state.probe as ProbeState.ProxyUnavailable).verifyFailed)
    }

    // T2: switch ON, stage 1 OK + stage 2 OK → ServiceReachable. While stage 2 is
    // gated the VM PARKS at Checking (single loading, §6.0) — no intermediate main
    // status leaks before the business result.
    @Test
    fun `stage1 ok then probe success transitions to service reachable`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)
        val gate = CompletableDeferred<Boolean>()
        coEvery { probe.probe() } coAnswers { gate.await() }

        val vm = buildViewModel()

        vm.uiState.test {
            var item = awaitItem()
            while (item.probe != ProbeState.Checking) item = awaitItem()
            // Release the probe and assert the terminal transition.
            gate.complete(true)
            var terminal = awaitItem()
            while (terminal.probe != ProbeState.ServiceReachable) terminal = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { probe.probe() }
    }

    // T3: switch ON, stage 1 OK + probe false → ServiceUnreachable.
    @Test
    fun `probe false maps to service unreachable`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)
        coEvery { probe.probe() } returns false

        val vm = buildViewModel()

        val state = vm.awaitState { it.probe == ProbeState.ServiceUnreachable }
        assertEquals(ProbeState.ServiceUnreachable, state.probe)
    }

    // T4: probe throws → VM does not crash, ServiceUnreachable (runCatching defense).
    @Test
    fun `probe throwing is caught and maps to service unreachable`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)
        coEvery { probe.probe() } throws RuntimeException("boom")

        val vm = buildViewModel()

        val state = vm.awaitState { it.probe == ProbeState.ServiceUnreachable }
        assertEquals(ProbeState.ServiceUnreachable, state.probe)
    }

    // T5: no saved link → parse null → probe NONE, both stages skipped.
    @Test
    fun `no saved link skips both stages`() = runTest {
        every { provider.savedShareLink } returns ""

        val vm = buildViewModel()

        val state = vm.uiState.value
        assertEquals(ProbeState.None, state.probe)
        coVerify(exactly = 0) { probe.probe() }
        coVerify(exactly = 0) { ProxyConnectivityChecker.check(any(), any()) }
    }

    // T5b: switch OFF with a saved link. Entering does NOT auto-probe (§6), but a
    // manual recheck (probeWhenDisabled) probes stage 1 only → ProxyAvailable (no
    // business status); stage 2 must NOT run (§6.1).
    @Test
    fun `switch off recheck shows proxy available without stage2`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabled } returns false // switch off / not active
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)

        val vm = buildViewModel()
        // Entering with the switch off must not auto-probe.
        assertEquals(ProbeState.None, vm.uiState.value.probe)

        vm.checkConnectivity() // manual recheck (probeWhenDisabled = true)

        val state = vm.awaitState { it.probe == ProbeState.ProxyAvailable }
        assertEquals(ProbeState.ProxyAvailable, state.probe)
        coVerify(exactly = 0) { probe.probe() }
    }

    // T11: saveWithPassphrase(correct) → reload → two-stage path → ServiceReachable.
    @Test
    fun `saveWithPassphrase success triggers stage2`() = runTest {
        // init: no saved link so no probe during construction; flip after save.
        every { provider.savedShareLink } returns ""
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)
        coEvery { probe.probe() } returns true

        val decodedConfig: ProxyConfig = mockk(relaxed = true)
        every { decodedConfig.toShareLink() } returns "ytp://plain-link"
        every { ProxyLinkCodec.decodeEncrypted(any(), any()) } returns
            ProxyLinkCodec.Decoded.Success(decodedConfig)
        every { provider.save(any(), any()) } returns true

        val vm = buildViewModel()
        vm.onAddressChange("ytp://encrypted")
        assertEquals(ProbeState.None, vm.uiState.value.probe)

        // After save succeeds, reload() re-probes; the proxy is now active with a link.
        every { provider.savedShareLink } returns "ytp://plain-link"
        every { provider.isEnabledByUser } returns true
        every { provider.isEnabled } returns true

        vm.saveWithPassphrase("correct")

        val terminal = vm.awaitState { it.probe == ProbeState.ServiceReachable }
        assertEquals(ProbeState.ServiceReachable, terminal.probe)
        coVerify(exactly = 1) { probe.probe() }
    }

    // T12: stage 1 OK but proxy disabled BEFORE stage 2 (isEnabled flips false on the
    // mid-probe re-check) → ProxyAvailable; stage 2 probe NOT called.
    @Test
    fun `stage1 ok but proxy disabled before stage2 shows proxy available`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        // loadState (#1), checkConnectivity top (#2) see enabled; mid-probe recheck (#3) sees disabled.
        every { provider.isEnabled } returnsMany listOf(true, true, false)
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)

        val vm = buildViewModel()

        val state = vm.awaitState { it.probe == ProbeState.ProxyAvailable }
        assertEquals(ProbeState.ProxyAvailable, state.probe)
        coVerify(exactly = 0) { probe.probe() }
    }

    // T13: editing the address mid-probe clears the status and the stale result is dropped.
    @Test
    fun `editing address mid probe clears status and drops stale result`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)
        val gate = CompletableDeferred<Boolean>()
        coEvery { probe.probe() } coAnswers { gate.await() }

        val vm = buildViewModel()

        vm.uiState.test {
            var item = awaitItem()
            while (item.probe != ProbeState.Checking) item = awaitItem()
            // Edit the address: status must clear immediately to None.
            vm.onAddressChange("ytp://edited")
            var edited = awaitItem()
            while (edited.probe != ProbeState.None) edited = awaitItem()
            // Releasing the stale probe must NOT overwrite the cleared status.
            gate.complete(true)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A stage-2 probe that announces its own start and then parks until released.
     *
     * The tests below must act while stage 2 is genuinely in flight, and
     * [ProbeState.Checking] cannot express that — it covers stage 1 too, and stage 1
     * hops through the real [kotlinx.coroutines.Dispatchers.IO], so a state-based wait
     * can return before [ProxyE2eProbe.probe] was ever called (that raced green when the
     * class ran alone and failed under a loaded full-suite run). [started] is the
     * unambiguous signal; [released] then lets the test choose exactly when the verdict
     * lands, reproducing the "arrives after the toggle already cleared the status"
     * ordering that the real IO→Main resume produces.
     *
     * `NonCancellable` keeps a retired probe parked instead of unwinding at cancel, so a
     * superseded run still delivers its (stale) verdict — the case the fix must survive.
     */
    private class FakeStage2Probe(runs: Int) {
        val started = List(runs) { CompletableDeferred<Unit>() }
        val released = List(runs) { CompletableDeferred<Unit>() }
        private val invocations = AtomicInteger(0)

        val invocationCount: Int get() = invocations.get()

        suspend fun run(): Boolean {
            val index = invocations.getAndIncrement()
            started[index].complete(Unit)
            withContext(NonCancellable) { released[index].await() }
            return false
        }
    }

    // T14: turning the switch OFF while stage 2 is in flight must leave the status area
    // empty. Settling that late verdict used to repaint the red "unable to connect" line
    // plus its recheck icon under a switch the user had just turned off.
    @Test
    fun `toggle off mid probe leaves status cleared`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)
        val fake = FakeStage2Probe(runs = 1)
        coEvery { probe.probe() } coAnswers { fake.run() }

        val vm = buildViewModel()
        fake.started[0].await()

        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false
        vm.onUseProxyChange(false)
        assertEquals(ProbeState.None, vm.uiState.value.probe)

        fake.released[0].complete(Unit)

        verify { provider.setEnabled(false) }
        assertEquals(ProbeState.None, vm.uiState.value.probe)
    }

    // T15: toggling OFF then straight back ON supersedes the first probe with one whose
    // target is IDENTICAL (same address, switch ON again), so the staleness guard cannot
    // tell them apart and only the retired job's cancelled state can. The late first
    // verdict must not settle the status the second probe still owns.
    @Test
    fun `superseded probe does not settle status of the probe that replaced it`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)
        val fake = FakeStage2Probe(runs = 2)
        coEvery { probe.probe() } coAnswers { fake.run() }

        val vm = buildViewModel()
        fake.started[0].await()

        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false
        vm.onUseProxyChange(false)
        every { provider.isEnabledByUser } returns true
        every { provider.isEnabled } returns true
        vm.onUseProxyChange(true)
        fake.started[1].await()

        // The retired probe reports failure; the second one is still in flight.
        fake.released[0].complete(Unit)

        assertEquals(2, fake.invocationCount)
        assertEquals(ProbeState.Checking, vm.uiState.value.probe)

        // `NonCancellable` means tearDown's viewModelScope.cancel() cannot unwind the
        // second run, so release it here rather than leave it parked for the suite.
        fake.released[1].complete(Unit)
    }

    // ---- PR1: switch / save action separation (design §2.2 / §5) ----

    // Toggling the switch OFF disables routing immediately, no "Save" needed.
    @Test
    fun `toggle off disables proxy immediately`() = runTest {
        every { provider.current } returns null
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns true
        every { provider.isEnabled } returns true

        val vm = buildViewModel()
        vm.onUseProxyChange(false)

        verify { provider.setEnabled(false) }
    }

    // Toggling ON with NO saved address must prompt "save first" and NOT enable.
    @Test
    fun `toggle on without saved address prompts save first`() = runTest {
        every { provider.current } returns null
        every { provider.savedShareLink } returns ""
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false

        val vm = buildViewModel()
        vm.events.test {
            vm.onUseProxyChange(true)
            assertEquals(UiEvent.Toast(R.string.proxy_save_first), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 0) { provider.setEnabled(true) }
    }

    // Toggling ON while there are UNSAVED edits must prompt "save first".
    @Test
    fun `toggle on with unsaved edits prompts save first`() = runTest {
        every { provider.current } returns null
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false

        val vm = buildViewModel()
        vm.onAddressChange("ytp://edited")
        vm.events.test {
            vm.onUseProxyChange(true)
            assertEquals(UiEvent.Toast(R.string.proxy_save_first), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 0) { provider.setEnabled(true) }
    }

    // Toggling ON with a saved, unedited address enables routing right away.
    @Test
    fun `toggle on with saved clean address enables proxy`() = runTest {
        every { provider.current } returns null
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false

        val vm = buildViewModel()
        vm.onUseProxyChange(true)

        verify { provider.setEnabled(true) }
    }

    // Save with empty input and no saved address → toast "enter address", no write.
    @Test
    fun `save empty without saved address toasts enter address`() = runTest {
        every { provider.current } returns null
        every { provider.savedShareLink } returns ""
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false

        val vm = buildViewModel()
        vm.events.test {
            vm.save()
            assertEquals(UiEvent.Toast(R.string.proxy_address_empty), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 0) { provider.clear() }
        verify(exactly = 0) { provider.save(any(), any()) }
    }

    // Clearing the input and saving deletes the saved address (clear + disable).
    @Test
    fun `save empty with saved address deletes saved address`() = runTest {
        every { provider.current } returns null
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns true
        every { provider.isEnabled } returns true

        val vm = buildViewModel()
        vm.onAddressChange("")
        vm.save()

        verify { provider.clear() }
        verify(exactly = 0) { provider.save(any(), any()) }
    }

    // An otherwise-valid link with an unsupported version byte → precise toast.
    @Test
    fun `save unsupported version toasts unsupported`() = runTest {
        every { provider.savedShareLink } returns ""
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false
        every { ProxyLinkCodec.isUnsupportedVersion(any()) } returns true

        val vm = buildViewModel()
        vm.onAddressChange("ytp://config?d=futureversion")
        vm.events.test {
            vm.save()
            assertEquals(UiEvent.Toast(R.string.proxy_unsupported_version), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 0) { provider.save(any(), any()) }
    }

    // A valid plain link persists via provider.save and emits Saved.
    @Test
    fun `save valid plain link persists and emits saved`() = runTest {
        every { provider.savedShareLink } returns ""
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false
        every { ProxyLinkCodec.inspect(any()) } returns ProxyLinkCodec.Mode.PLAIN
        // decodePlain default (non-blank → dummyConfig) makes ProxyConfig.parse != null.
        every { provider.save(any(), any()) } returns true

        val vm = buildViewModel()
        vm.onAddressChange("ytp://config?d=validplain")
        vm.events.test {
            vm.save()
            assertEquals(UiEvent.Saved, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify { provider.save("ytp://config?d=validplain", false) }
    }

    // ---- PR3: call-in-progress makes the screen view-only (§7 / §8) ----

    // A call in progress sets readOnly and blocks the toggle / save / edit actions.
    @Test
    fun `call in progress sets readonly and blocks mutations`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false
        callState.setIsInCalling(true)

        val vm = buildViewModel()
        val state = vm.awaitState { it.readOnly }
        assertTrue(state.readOnly)

        vm.onUseProxyChange(true)
        vm.onAddressChange("ytp://edited")
        vm.save()

        verify(exactly = 0) { provider.setEnabled(any()) }
        verify(exactly = 0) { provider.save(any(), any()) }
        verify(exactly = 0) { provider.clear() }
        // The address edit was rejected, so the draft is unchanged.
        assertEquals("ytp://saved", vm.uiState.value.address)
    }

    // Tapping the (non-greyed) switch during a call raises the restricted toast and
    // does not flip the proxy — the switch keeps showing the real state (§8).
    @Test
    fun `toggle during call emits restricted toast and keeps state`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false
        callState.setIsInCalling(true)

        val vm = buildViewModel()
        vm.awaitState { it.readOnly }

        vm.events.test {
            vm.onUseProxyChange(true)
            assertEquals(UiEvent.Toast(R.string.proxy_call_restricted_toast), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 0) { provider.setEnabled(any()) }
    }

    // A call starting while the passphrase dialog is open must block the confirm
    // path too: saveWithPassphrase toasts and never persists during the call (§8).
    @Test
    fun `save with passphrase during call is blocked`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false
        callState.setIsInCalling(true)

        val vm = buildViewModel()
        vm.awaitState { it.readOnly }

        vm.events.test {
            vm.saveWithPassphrase("secret")
            assertEquals(UiEvent.Toast(R.string.proxy_call_restricted_toast), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 0) { provider.save(any(), any()) }
    }

    // Ending the call clears readOnly so the screen becomes editable again.
    @Test
    fun `ending call clears readonly`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        callState.setIsInCalling(true)

        val vm = buildViewModel()
        vm.awaitState { it.readOnly }

        callState.setIsInCalling(false)
        val state = vm.awaitState { !it.readOnly }
        assertTrue(!state.readOnly)
    }
}
