package com.difft.android.call.manager

import android.media.Ringtone
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the assumption [CallRingtoneManager] rests on (issue #1313): one
 * `Dispatchers.IO.limitedParallelism(1)` view serializes ringtone work issued from real threads,
 * so `Ringtone.play()`/`stop()` never overlap. State-machine rows live in [CallRingtoneManagerTest].
 */
class CallRingtoneManagerRealThreadTest {

    private lateinit var source: CallRingtoneSource

    @Before
    fun setUp() {
        source = mockk()
        every { source.isRingerAudible() } returns true
    }

    private class RealHarness {
        val scope = CoroutineScope(SupervisorJob())
        val serial = Dispatchers.IO.limitedParallelism(1)
        private val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        /** Runs [body] while counting concurrent framework calls. */
        fun tracked(body: () -> Unit) {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                body()
            } finally {
                inFlight.decrementAndGet()
            }
        }

        /** Reports `isPlaying == true` after `play()` and `false` after `stop()`. */
        fun ringtone(): Ringtone {
            val playing = AtomicBoolean(false)
            val rt = mockk<Ringtone>(relaxed = true)
            every { rt.isPlaying } answers { playing.get() }
            every { rt.play() } answers { tracked { playing.set(true) } }
            every { rt.stop() } answers { tracked { playing.set(false) } }
            return rt
        }

        /** Waits until every task enqueued so far has run (loops are parked at a delay). */
        fun awaitIdle() = runBlocking {
            withTimeout(5_000) { scope.launch(serial) {}.join() }
        }

        fun concurrently(vararg actions: () -> Unit) {
            val start = CountDownLatch(1)
            val threads = actions.map { action -> Thread { start.await(); action() }.apply { start() } }
            start.countDown()
            threads.forEach { it.join() }
        }
    }

    @Test
    fun `R15 eight concurrent stops on real threads stop the tone once without overlap`() {
        val h = RealHarness()
        try {
            val rt = h.ringtone()
            every { source.create(any()) } returns rt
            val m = CallRingtoneManager(source, h.serial, h.scope)
            m.playRingTone(ONE_ON_ONE, CALLEE)
            h.awaitIdle()

            h.concurrently(*Array(8) { i -> { m.stopRingTone("t$i") } })
            h.awaitIdle()

            verify(exactly = 1) { rt.play() }
            verify(exactly = 1) { rt.stop() }
            assertEquals(1, h.maxInFlight.get())
        } finally {
            h.scope.cancel()
        }
    }

    @Test
    fun `R22 two concurrent plays on real threads leave only the later tone`() {
        val h = RealHarness()
        try {
            val created = Collections.synchronizedList(mutableListOf<Ringtone>())
            every { source.create(any()) } answers { h.ringtone().also { created += it } }
            val m = CallRingtoneManager(source, h.serial, h.scope)

            h.concurrently({ m.playRingTone(ONE_ON_ONE, CALLER) }, { m.playRingTone(ONE_ON_ONE, CALLEE) })
            h.awaitIdle()

            assertTrue("created=${created.size}", created.size in 1..2)
            val survivor = created.last()
            verify(atLeast = 1) { survivor.play() }
            verify(exactly = 0) { survivor.stop() }
            created.dropLast(1).forEach { earlier ->
                verify(atMost = 1) { earlier.play() }
                verify(exactly = 1) { earlier.stop() }
            }

            m.stopRingTone()
            h.awaitIdle()

            created.forEach { verify(exactly = 1) { it.stop() } }
            assertTrue("maxInFlight=${h.maxInFlight.get()}", h.maxInFlight.get() <= 1)
        } finally {
            h.scope.cancel()
        }
    }

    private companion object {
        val ONE_ON_ONE = CallType.ONE_ON_ONE.type
        val CALLER = CallRole.CALLER.type
        val CALLEE = CallRole.CALLEE.type
    }
}
