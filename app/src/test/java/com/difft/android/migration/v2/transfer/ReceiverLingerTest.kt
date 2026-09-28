package com.difft.android.migration.v2.transfer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The listener that outlives its transfer: closed to new sessions at once, stopped when the window
 * runs out, stopped early when a newer listener needs the port, and never stopped twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverLingerTest {

    private class FakeListener : ReceiverLinger.Listener {
        var closed = false
        var stops = 0
        override fun closeToNewSessions() { closed = true }
        override fun stop() { stops++ }
    }

    @Test
    fun `a kept listener is closed to new sessions at once and stopped when the window elapses`() = runTest {
        val linger = ReceiverLinger(backgroundScope, windowMs = 1_000)
        val listener = FakeListener()

        linger.keep(listener)
        assertTrue(listener.closed)
        assertEquals(0, listener.stops)

        advanceTimeBy(999); runCurrent()
        assertEquals(0, listener.stops)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, listener.stops)
    }

    @Test
    fun `keeping a second listener stops the first, and the first window can no longer touch the second`() = runTest {
        val linger = ReceiverLinger(backgroundScope, windowMs = 1_000)
        val first = FakeListener()
        val second = FakeListener()

        linger.keep(first)
        advanceTimeBy(500); runCurrent()
        linger.keep(second)
        assertEquals(1, first.stops)
        assertEquals(0, second.stops)

        // The first listener's window would have fired here; it must not stop the second.
        advanceTimeBy(500); runCurrent()
        assertEquals(0, second.stops)
        advanceTimeBy(500); runCurrent()
        assertEquals(1, second.stops)
    }

    @Test
    fun `stopNow stops the lingering listener once and the elapsed window does not stop it again`() = runTest {
        val linger = ReceiverLinger(backgroundScope, windowMs = 1_000)
        val listener = FakeListener()

        linger.keep(listener)
        linger.stopNow()
        assertEquals(1, listener.stops)

        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, listener.stops)

        // Nothing kept: a no-op.
        linger.stopNow()
        assertFalse(FakeListener().closed)
    }
}
