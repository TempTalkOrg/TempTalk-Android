package com.difft.android.setting.viewmodel

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.network.proxy.ProxyConfig
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.proxy.ProxyConnectivityChecker
import com.difft.android.network.proxy.ProxyLinkCodec
import com.difft.android.setting.proxy.ProxyE2eProbe
import com.difft.android.test.TestDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Shared fixture for the [ProxySettingsViewModel] test classes. */
abstract class ProxySettingsViewModelTestBase {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    protected lateinit var provider: ProxyConfigProvider
    protected lateinit var probe: ProxyE2eProbe
    // Real instance (no-arg @Inject constructor): mocking is impractical because the
    // class declares both a `val isInCalling` property and an `isInCalling()` function,
    // whose name clash defeats MockK's `every {}` recorder.
    protected lateinit var callState: OnGoingCallStateManager

    // `isInCalling` is written only by the call session (through an internal, fenced writer this
    // module cannot reach), so the test drives the private backing flow the public read exposes.
    @Suppress("UNCHECKED_CAST")
    protected fun setInCalling(value: Boolean) {
        val field = OnGoingCallStateManager::class.java.getDeclaredField("_isInCalling").apply { isAccessible = true }
        (field.get(callState) as MutableStateFlow<Boolean>).value = value
    }

    protected val dummyConfig: ProxyConfig = mockk(relaxed = true)

    // The switch write now runs on a real Dispatchers.IO thread (#1400), so assertions on it
    // must wait for it; this is the upper bound for that hop.
    protected val writeTimeoutMs = 5_000L

    /**
     * Backs the provider's switch state with a flag that [ProxyConfigProvider.setEnabled]
     * flips (via [onWrite]), so the reload that follows an off-main write reads what the
     * write stored instead of a value the test flipped ahead of it.
     */
    protected fun backSwitchWithState(initial: Boolean, onWrite: (Boolean) -> Unit = {}): AtomicBoolean {
        val flag = AtomicBoolean(initial)
        every { provider.isEnabledByUser } answers { flag.get() }
        every { provider.isEnabled } answers { flag.get() }
        every { provider.setEnabled(any()) } answers {
            val target = firstArg<Boolean>()
            onWrite(target)
            flag.set(target)
            true
        }
        return flag
    }

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
    protected val builtViewModels = mutableListOf<ProxySettingsViewModel>()

    @After
    fun tearDown() {
        try {
            // Before the scopes are cancelled and the dispatcher rule resets Main (its finished()
            // runs after @After): a write still in flight would otherwise skip its finally and
            // leave the process-wide queue and mutex dirty for later tests (#1400).
            awaitWritesLanded()
        } finally {
            builtViewModels.forEach { it.viewModelScope.cancel() }
            builtViewModels.clear()
            unmockkObject(ProxyConnectivityChecker)
            unmockkObject(ProxyLinkCodec)
        }
    }

    /**
     * Waits (real time) until every provider write has fully landed: the process-wide queue is
     * empty and the write mutex released. MockK records a call before running its answer, so
     * `verify(timeout)` can return while the answer, the IO hop and the write's `finally` are
     * still running (#1400).
     */
    protected fun awaitWritesLanded(timeoutMs: Long = writeTimeoutMs) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (ProxySettingsViewModel.queuedWrites.value != 0 || ProxySettingsViewModel.providerWriteMutex.isLocked) {
            check(System.nanoTime() < deadline) {
                "provider writes did not land within ${timeoutMs}ms: " +
                    "queuedWrites=${ProxySettingsViewModel.queuedWrites.value} " +
                    "mutexLocked=${ProxySettingsViewModel.providerWriteMutex.isLocked}"
            }
            Thread.sleep(10)
        }
    }

    protected fun buildViewModel() =
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
    protected suspend fun ProxySettingsViewModel.awaitState(
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

    /** Holds the OFF switch write in flight until the returned latch is released. */
    protected fun holdOffWrite(): Pair<CountDownLatch, CountDownLatch> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        backSwitchWithState(initial = true) { target ->
            if (!target) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "write never released" }
            }
        }
        return started to release
    }
}
