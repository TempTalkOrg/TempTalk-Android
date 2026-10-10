package com.difft.android.p2p.engine.session

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reverse-pairing registry's two guarantees: a generation that dies takes every answer about it
 * with it (so a stale `pairToken` can never be honoured, and a waiter can never park on a dead
 * session), and the token is checked before the PIN so only a caller that already holds the token
 * can spend a PIN attempt.
 */
class PairingSessionRegistryTest {

    private val registry = PairingSessionRegistry()

    @Test
    fun `a second register supersedes the first, whose token is then refused`() {
        val first = registry.register(pin = "111111", transferKey = "k1", pairToken = "t1")
        val second = registry.register(pin = "222222", transferKey = "k2", pairToken = "t2")

        assertNull("the superseded generation answers nothing", registry.phase(first.generation))
        assertEquals(PairingSessionRegistry.Phase.PENDING, registry.phase(second.generation))
        assertEquals(
            PairingSessionRegistry.Decision.RejectPairToken,
            registry.authorizePrepareUpload(first.pairToken, "111111"),
        )
    }

    @Test
    fun `a wrong token is refused without spending a PIN attempt`() {
        val handle = registry.register(pin = "111111", transferKey = "k", pairToken = "t")

        repeat(PinPolicy.MAX_PIN_ATTEMPTS * 2) {
            assertEquals(
                PairingSessionRegistry.Decision.RejectPairToken,
                registry.authorizePrepareUpload("wrong", "111111"),
            )
        }

        assertTrue("token spraying must not lock the pairing out", registry.isActive())
        assertEquals(PairingSessionRegistry.Phase.PENDING, registry.phase(handle.generation))
        assertEquals(PairingSessionRegistry.Decision.Accept, registry.authorizePrepareUpload("t", "111111"))
    }

    @Test
    fun `five wrong PINs against the right token invalidate the session`() {
        val handle = registry.register(pin = "111111", transferKey = "k", pairToken = "t")

        repeat(PinPolicy.MAX_PIN_ATTEMPTS) {
            assertEquals(PairingSessionRegistry.Decision.RejectPin, registry.authorizePrepareUpload("t", "000000"))
        }

        assertFalse(registry.isActive())
        assertNull(registry.phase(handle.generation))
    }

    @Test
    fun `the phase advances PENDING to CONNECTED to UPLOADING and releases a parked waiter`() = runTest {
        val handle = registry.register(pin = "111111", transferKey = "k", pairToken = "t")
        val waiter = async { registry.awaitPhaseAtLeast(handle.generation, PairingSessionRegistry.Phase.CONNECTED) }
        testScheduler.runCurrent() // let the waiter park on PENDING before the phase moves

        assertEquals(PairingSessionRegistry.Decision.Accept, registry.authorizePrepareUpload("t", "111111"))
        withTimeout(WAIT_BUDGET_MS) { waiter.await() }
        assertEquals(PairingSessionRegistry.Phase.CONNECTED, registry.phase(handle.generation))

        registry.markUploading()
        assertEquals(PairingSessionRegistry.Phase.UPLOADING, registry.phase(handle.generation))
    }

    @Test
    fun `a parked waiter is released with PairingSuperseded when its generation is invalidated`() = runTest {
        val handle = registry.register(pin = "111111", transferKey = "k", pairToken = "t")
        val waiter = async { runCatching { registry.awaitPhaseAtLeast(handle.generation, PairingSessionRegistry.Phase.CONNECTED) } }
        testScheduler.runCurrent()

        registry.invalidate(handle.generation)

        val outcome = withTimeout(WAIT_BUDGET_MS) { waiter.await() }
        assertTrue("a dead generation must never leave a waiter parked", outcome.exceptionOrNull() is PairingSuperseded)
    }

    @Test
    fun `a parked waiter is released with PairingSuperseded when a re-scan supersedes it`() = runTest {
        val handle = registry.register(pin = "111111", transferKey = "k", pairToken = "t")
        val waiter = async { runCatching { registry.awaitPhaseAtLeast(handle.generation, PairingSessionRegistry.Phase.CONNECTED) } }
        testScheduler.runCurrent()

        registry.register(pin = "222222", transferKey = "k2", pairToken = "t2")

        val outcome = withTimeout(WAIT_BUDGET_MS) { waiter.await() }
        assertTrue(outcome.exceptionOrNull() is PairingSuperseded)
    }

    @Test
    fun `the accepted transfer key is scoped to its own generation`() {
        val first = registry.register(pin = "111111", transferKey = "k1", pairToken = "t1")
        assertNull("nothing accepted yet", registry.acceptedTransferKey())

        assertEquals(PairingSessionRegistry.Decision.Accept, registry.authorizePrepareUpload("t1", "111111"))
        assertEquals("k1", registry.acceptedTransferKey())
        assertEquals(PairingSessionRegistry.Phase.CONNECTED, registry.phase(first.generation))

        registry.register(pin = "222222", transferKey = "k2", pairToken = "t2")
        assertNull("a re-scan must never hand out the new generation's key", registry.acceptedTransferKey())
    }

    private companion object {
        const val WAIT_BUDGET_MS = 1_000L
    }
}
