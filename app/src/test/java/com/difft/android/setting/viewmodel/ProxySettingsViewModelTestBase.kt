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


abstract class ProxySettingsViewModelTestBase {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    protected lateinit var provider: ProxyConfigProvider
    protected lateinit var probe: ProxyE2eProbe
    
    
    
    protected lateinit var callState: OnGoingCallStateManager

    
    
    @Suppress("UNCHECKED_CAST")
    protected fun setInCalling(value: Boolean) {
        val field = OnGoingCallStateManager::class.java.getDeclaredField("_isInCalling").apply { isAccessible = true }
        (field.get(callState) as MutableStateFlow<Boolean>).value = value
    }

    protected val dummyConfig: ProxyConfig = mockk(relaxed = true)

    
    
    protected val writeTimeoutMs = 5_000L

    




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
        
        
        
        mockkObject(ProxyLinkCodec)
        provider = mockk(relaxed = true)
        probe = mockk(relaxed = true)
        callState = OnGoingCallStateManager()
        
        
        every { provider.savedShareLink } returns ""
        every { provider.isEnabledByUser } returns true
        every { provider.isEnabled } returns true
        every { ProxyLinkCodec.decodePlain(any()) } answers {
            if (firstArg<String>().isBlank()) null else dummyConfig
        }
        every { ProxyLinkCodec.isUnsupportedVersion(any()) } returns false
        
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = false, failure = ProxyConnectivityChecker.Failure.UNREACHABLE)
    }

    
    
    
    protected val builtViewModels = mutableListOf<ProxySettingsViewModel>()

    @After
    fun tearDown() {
        try {
            
            
            
            awaitWritesLanded()
        } finally {
            builtViewModels.forEach { it.viewModelScope.cancel() }
            builtViewModels.clear()
            unmockkObject(ProxyConnectivityChecker)
            unmockkObject(ProxyLinkCodec)
        }
    }

    





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
