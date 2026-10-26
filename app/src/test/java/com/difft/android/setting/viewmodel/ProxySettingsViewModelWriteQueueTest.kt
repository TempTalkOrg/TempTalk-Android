package com.difft.android.setting.viewmodel

import app.cash.turbine.test
import com.difft.android.network.proxy.ProxyLinkCodec
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.ProbeState
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.UiEvent
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the off-main, serialised provider writes of [ProxySettingsViewModel] (#1400): the
 * switch and delete writes leave the tap thread, run in tap order, and lock the form until the
 * last queued write lands.
 */
class ProxySettingsViewModelWriteQueueTest : ProxySettingsViewModelTestBase() {

    // #1400: setEnabled evicts pooled connections, whose TLS close throws on the main thread,
    // so the switch write must run off the thread that handled the tap.
    @Test
    fun `toggle writes run off the calling thread`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        val tapThread = Thread.currentThread()
        val writeThreads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        backSwitchWithState(initial = true) { writeThreads += Thread.currentThread() }

        val vm = buildViewModel()
        vm.onUseProxyChange(false)
        verify(timeout = writeTimeoutMs) { provider.setEnabled(false) }
        vm.awaitState { !it.useProxy && it.savedUseProxy.not() }
        vm.onUseProxyChange(true)
        verify(timeout = writeTimeoutMs) { provider.setEnabled(true) }

        awaitWritesLanded()

        assertEquals(2, writeThreads.size)
        writeThreads.forEach { assertNotEquals(tapThread, it) }
    }

    // #1400: deleting the saved address calls clear(), which also evicts connections.
    @Test
    fun `delete saved address clears off the calling thread`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        val tapThread = Thread.currentThread()
        val clearThread = AtomicReference<Thread>()
        every { provider.clear() } answers { clearThread.set(Thread.currentThread()) }

        val vm = buildViewModel()
        vm.onAddressChange("")
        vm.save()

        verify(timeout = writeTimeoutMs) { provider.clear() }
        assertNotEquals(tapThread, clearThread.get())
    }

    // #1400: OFF then ON in quick succession must both be applied, in tap order. The first
    // write is held in flight so the ON tap lands while it is still running; it must queue
    // behind it rather than be dropped or overtake it.
    @Test
    fun `quick off then on taps both apply in order`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        val order = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val enabled = backSwitchWithState(initial = true) { target ->
            order += target
            if (!target) {
                firstWriteStarted.countDown()
                check(releaseFirstWrite.await(5, TimeUnit.SECONDS)) { "first write never released" }
            }
        }

        val vm = buildViewModel()
        vm.onUseProxyChange(false)
        assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS))
        // The OFF write is still in flight; the switch already shows OFF, so ON is a real change.
        assertEquals(false, vm.uiState.value.useProxy)
        vm.onUseProxyChange(true)
        verify(exactly = 0) { provider.setEnabled(true) }

        releaseFirstWrite.countDown()

        verify(timeout = writeTimeoutMs) { provider.setEnabled(true) }
        awaitWritesLanded()
        assertEquals(listOf(false, true), order.toList())
        assertTrue(enabled.get())
        val state = vm.awaitState { it.useProxy }
        assertTrue(state.useProxy)
    }

    // #1400: while a switch write is pending the form is locked: edits and saves are ignored,
    // and the reload that follows the write rebuilds the form from the provider.
    @Test
    fun `address edits and saves are ignored while a switch write is pending`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        val (started, release) = holdOffWrite()

        val vm = buildViewModel()
        vm.onUseProxyChange(false)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        vm.onAddressChange("ytp://draft")
        vm.save()

        val pending = vm.uiState.value
        assertEquals("ytp://saved", pending.address)
        assertTrue(pending.isSaving)
        verify(exactly = 0) { provider.save(any(), any()) }
        verify(exactly = 0) { provider.clear() }

        release.countDown()

        val state = vm.awaitState { !it.savedUseProxy }
        assertEquals(state.savedAddress, state.address)
        assertEquals(false, state.isSaving)
        verify(exactly = 0) { provider.save(any(), any()) }
        verify(exactly = 0) { provider.clear() }
    }

    // #1400: a call that starts while a switch write is pending keeps the screen view-only.
    @Test
    fun `a call starting during a pending switch write stays read only after the reload`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        val (started, release) = holdOffWrite()

        val vm = buildViewModel()
        vm.onUseProxyChange(false)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        setInCalling(true)
        vm.awaitState { it.readOnly }
        release.countDown()

        val state = vm.awaitState { !it.savedUseProxy }
        assertTrue(state.readOnly)
    }

    // #1400: a switch tap during an in-flight address save shares the provider write lock, so
    // it runs after the save instead of interleaving with it.
    @Test
    fun `a switch tap during an in-flight address save runs after it`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyLinkCodec.inspect(any()) } returns ProxyLinkCodec.Mode.PLAIN
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val saveStarted = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        every { provider.save(any(), any()) } answers {
            order += "save"
            saveStarted.countDown()
            check(releaseSave.await(5, TimeUnit.SECONDS)) { "save never released" }
            true
        }
        backSwitchWithState(initial = true) { order += if (it) "on" else "off" }

        val vm = buildViewModel()
        vm.onAddressChange("ytp://config?d=newaddress")
        vm.save()
        assertTrue(saveStarted.await(5, TimeUnit.SECONDS))
        vm.onUseProxyChange(false)
        Thread.sleep(200) // give a wrongly unserialised write the chance to run
        verify(exactly = 0) { provider.setEnabled(any()) }

        releaseSave.countDown()

        verify(timeout = writeTimeoutMs) { provider.setEnabled(false) }
        awaitWritesLanded()
        assertEquals(listOf("save", "off"), order.toList())
    }

    // Writes a left screen still has queued land before what a reopened screen writes, so the
    // user's last choice wins: the queue is process-wide, not per screen.
    @Test
    fun `a reopened screen's tap lands after the writes a left screen still has queued`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        val order = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val flag = java.util.concurrent.atomic.AtomicBoolean(true)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        every { provider.isEnabledByUser } answers { flag.get() }
        every { provider.isEnabled } answers { flag.get() }
        every { provider.setEnabled(any()) } answers {
            val target = firstArg<Boolean>()
            order += target
            flag.set(target)
            if (order.size == 1) {
                // The first write persists, then blocks in pool eviction.
                firstStarted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS)) { "first write never released" }
            }
            true
        }

        try {
            val left = buildViewModel()
            left.onUseProxyChange(false)
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            left.onUseProxyChange(true)
            left.onUseProxyChange(false) // OFF, ON, OFF queued; the user leaves the screen

            val reopened = buildViewModel() // reads the persisted OFF
            reopened.onUseProxyChange(true)
        } finally {
            // The write queue is process-wide: never leave a write parked for the next test.
            releaseFirst.countDown()
        }

        verify(timeout = writeTimeoutMs, exactly = 2) { provider.setEnabled(true) }
        verify(timeout = writeTimeoutMs, exactly = 2) { provider.setEnabled(false) }
        awaitWritesLanded()
        assertEquals(listOf(false, true, false, true), order.toList())
        assertTrue(flag.get(), "the reopened screen's ON is the last write")
    }

    // A screen reopened while a left screen's writes are queued holds its form, then reloads
    // to what those writes stored instead of staying on the state it read when it opened.
    @Test
    fun `a reopened screen reloads once a left screen's queued writes land`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        val flag = java.util.concurrent.atomic.AtomicBoolean(true)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val writes = AtomicInteger(0)
        every { provider.isEnabledByUser } answers { flag.get() }
        every { provider.isEnabled } answers { flag.get() }
        every { provider.setEnabled(any()) } answers {
            flag.set(firstArg())
            if (writes.incrementAndGet() == 1) {
                firstStarted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS)) { "first write never released" }
            }
            true
        }

        val reopened: ProxySettingsViewModel
        try {
            val left = buildViewModel()
            left.onUseProxyChange(false)
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            left.onUseProxyChange(true) // OFF then ON queued; the user leaves the screen

            reopened = buildViewModel() // opens on the persisted OFF, while ON is still queued
            val held = reopened.awaitState { it.isSaving }
            assertEquals(false, held.useProxy)
            reopened.onAddressChange("ytp://draft")
            assertEquals("ytp://saved", reopened.uiState.value.address, "edits are held while writes are queued")
        } finally {
            // The write queue is process-wide: never leave a write parked for the next test.
            releaseFirst.countDown()
        }

        val reloaded = reopened.awaitState { it.useProxy && !it.isSaving }
        assertTrue(reloaded.savedUseProxy)
        // The enter probe the reload retired runs again for the landed ON state; the default
        // checker stub reports the proxy unreachable.
        val probed = reopened.awaitState { it.probe is ProbeState.ProxyUnavailable }
        assertTrue(probed.useProxy)
    }

    // A save that finishes while a switch write is still queued must not flash the switch back
    // or unlock the form: its reload keeps the tapped value and the busy state.
    @Test
    fun `a save finishing before a queued switch write keeps the tapped switch and stays busy`() = runTest {
        every { provider.savedShareLink } returns "ytp://saved"
        every { ProxyLinkCodec.inspect(any()) } returns ProxyLinkCodec.Mode.PLAIN
        val releaseSave = CountDownLatch(1)
        val saveStarted = CountDownLatch(1)
        every { provider.save(any(), any()) } answers {
            saveStarted.countDown()
            check(releaseSave.await(5, TimeUnit.SECONDS)) { "save never released" }
            true
        }
        val offStarted = CountDownLatch(1)
        val releaseOff = CountDownLatch(1)
        backSwitchWithState(initial = true) { target ->
            if (!target) {
                offStarted.countDown()
                check(releaseOff.await(5, TimeUnit.SECONDS)) { "off write never released" }
            }
        }

        val vm = buildViewModel()
        vm.onAddressChange("ytp://config?d=newaddress")
        vm.save()
        assertTrue(saveStarted.await(5, TimeUnit.SECONDS))
        vm.onUseProxyChange(false)
        releaseSave.countDown()
        assertTrue(offStarted.await(5, TimeUnit.SECONDS))

        // Saved is emitted after the save's reload; the OFF write is still running.
        vm.events.test {
            assertEquals(UiEvent.Saved, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        val held = vm.uiState.value
        assertEquals(false, held.useProxy, "the switch must not flash back to ON")
        assertTrue(held.isSaving)

        releaseOff.countDown()
        val done = vm.awaitState { !it.savedUseProxy && !it.isSaving }
        assertEquals(false, done.useProxy)
    }
}
