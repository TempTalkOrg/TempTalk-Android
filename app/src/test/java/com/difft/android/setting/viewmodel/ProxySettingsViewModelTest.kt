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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue






class ProxySettingsViewModelTest : ProxySettingsViewModelTestBase() {

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

    @Test
    fun `stage1 pin mismatch maps to verify failed`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = false, failure = ProxyConnectivityChecker.Failure.PIN_MISMATCH)

        val vm = buildViewModel()

        val state = vm.awaitState { it.probe is ProbeState.ProxyUnavailable }
        assertTrue((state.probe as ProbeState.ProxyUnavailable).verifyFailed)
    }

    
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
            
            gate.complete(true)
            var terminal = awaitItem()
            while (terminal.probe != ProbeState.ServiceReachable) terminal = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { probe.probe() }
    }

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

    @Test
    fun `no saved link skips both stages`() = runTest {
        every { provider.savedShareLink } returns ""

        val vm = buildViewModel()

        val state = vm.uiState.value
        assertEquals(ProbeState.None, state.probe)
        coVerify(exactly = 0) { probe.probe() }
        coVerify(exactly = 0) { ProxyConnectivityChecker.check(any(), any()) }
    }

    
    @Test
    fun `switch off recheck shows proxy available without stage2`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabled } returns false
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)

        val vm = buildViewModel()
        
        assertEquals(ProbeState.None, vm.uiState.value.probe)

        vm.checkConnectivity()

        val state = vm.awaitState { it.probe == ProbeState.ProxyAvailable }
        assertEquals(ProbeState.ProxyAvailable, state.probe)
        coVerify(exactly = 0) { probe.probe() }
    }

    @Test
    fun `saveWithPassphrase success triggers stage2`() = runTest {
        
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

        
        every { provider.savedShareLink } returns "ytp://plain-link"
        every { provider.isEnabledByUser } returns true
        every { provider.isEnabled } returns true

        vm.saveWithPassphrase("correct")

        val terminal = vm.awaitState { it.probe == ProbeState.ServiceReachable }
        assertEquals(ProbeState.ServiceReachable, terminal.probe)
        coVerify(exactly = 1) { probe.probe() }
    }

    
    @Test
    fun `stage1 ok but proxy disabled before stage2 shows proxy available`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        
        every { provider.isEnabled } returnsMany listOf(true, true, false)
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)

        val vm = buildViewModel()

        val state = vm.awaitState { it.probe == ProbeState.ProxyAvailable }
        assertEquals(ProbeState.ProxyAvailable, state.probe)
        coVerify(exactly = 0) { probe.probe() }
    }

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
            
            vm.onAddressChange("ytp://edited")
            var edited = awaitItem()
            while (edited.probe != ProbeState.None) edited = awaitItem()
            
            gate.complete(true)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    




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

    
    @Test
    fun `toggle off mid probe leaves status cleared`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)
        val fake = FakeStage2Probe(runs = 1)
        coEvery { probe.probe() } coAnswers { fake.run() }

        val vm = buildViewModel()
        fake.started[0].await()

        backSwitchWithState(initial = true)
        vm.onUseProxyChange(false)
        assertEquals(ProbeState.None, vm.uiState.value.probe)

        fake.released[0].complete(Unit)

        verify(timeout = writeTimeoutMs) { provider.setEnabled(false) }
        assertEquals(ProbeState.None, vm.uiState.value.probe)
    }

    
    @Test
    fun `superseded probe does not settle status of the probe that replaced it`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyConnectivityChecker.check(any(), any()) } returns
            ProxyConnectivityChecker.Outcome(ok = true)
        val fake = FakeStage2Probe(runs = 2)
        coEvery { probe.probe() } coAnswers { fake.run() }

        val vm = buildViewModel()
        fake.started[0].await()

        backSwitchWithState(initial = true)
        vm.onUseProxyChange(false)
        vm.onUseProxyChange(true)
        fake.started[1].await()

        
        fake.released[0].complete(Unit)

        assertEquals(2, fake.invocationCount)
        assertEquals(ProbeState.Checking, vm.uiState.value.probe)

        
        fake.released[1].complete(Unit)
    }

    

    @Test
    fun `toggle off disables proxy immediately`() = runTest {
        every { provider.current } returns null
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns true
        every { provider.isEnabled } returns true

        val vm = buildViewModel()
        vm.onUseProxyChange(false)

        verify(timeout = writeTimeoutMs) { provider.setEnabled(false) }
    }

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

    @Test
    fun `toggle on with saved clean address enables proxy`() = runTest {
        every { provider.current } returns null
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false

        val vm = buildViewModel()
        vm.onUseProxyChange(true)

        verify(timeout = writeTimeoutMs) { provider.setEnabled(true) }
    }

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

    @Test
    fun `save empty with saved address deletes saved address`() = runTest {
        every { provider.current } returns null
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns true
        every { provider.isEnabled } returns true

        val vm = buildViewModel()
        vm.onAddressChange("")
        vm.save()

        verify(timeout = writeTimeoutMs) { provider.clear() }
        verify(exactly = 0) { provider.save(any(), any()) }
    }

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

    @Test
    fun `save valid plain link persists and emits saved`() = runTest {
        every { provider.savedShareLink } returns ""
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false
        every { ProxyLinkCodec.inspect(any()) } returns ProxyLinkCodec.Mode.PLAIN
        
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

    

    @Test
    fun `call in progress sets readonly and blocks mutations`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false
        setInCalling(true)

        val vm = buildViewModel()
        val state = vm.awaitState { it.readOnly }
        assertTrue(state.readOnly)

        vm.onUseProxyChange(true)
        vm.onAddressChange("ytp://edited")
        vm.save()

        verify(exactly = 0) { provider.setEnabled(any()) }
        verify(exactly = 0) { provider.save(any(), any()) }
        verify(exactly = 0) { provider.clear() }
        
        assertEquals("ytp://saved", vm.uiState.value.address)
    }

    
    @Test
    fun `toggle during call emits restricted toast and keeps state`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false
        setInCalling(true)

        val vm = buildViewModel()
        vm.awaitState { it.readOnly }

        vm.events.test {
            vm.onUseProxyChange(true)
            assertEquals(UiEvent.Toast(R.string.proxy_call_restricted_toast), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 0) { provider.setEnabled(any()) }
    }

    
    @Test
    fun `save with passphrase during call is blocked`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false
        setInCalling(true)

        val vm = buildViewModel()
        vm.awaitState { it.readOnly }

        vm.events.test {
            vm.saveWithPassphrase("secret")
            assertEquals(UiEvent.Toast(R.string.proxy_call_restricted_toast), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 0) { provider.save(any(), any()) }
    }

    @Test
    fun `ending call clears readonly`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        setInCalling(true)

        val vm = buildViewModel()
        vm.awaitState { it.readOnly }

        setInCalling(false)
        val state = vm.awaitState { !it.readOnly }
        assertTrue(!state.readOnly)
    }
}
