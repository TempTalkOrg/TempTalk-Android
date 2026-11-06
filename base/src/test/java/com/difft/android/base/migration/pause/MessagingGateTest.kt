package com.difft.android.base.migration.pause

import com.difft.android.test.fakes.RecordingPreferencesDataStore
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** [MessagingGate]: one admission protocol for tickets, the pause and the idle wait (#1382). */
class MessagingGateTest {

    private val owner = "11111111-aaaa-bbbb-cccc-000000000001"
    private val other = "22222222-aaaa-bbbb-cccc-000000000002"

    private val gate = MessagingGate()
    private val pause = MigrationMessagePause(RecordingPreferencesDataStore(), gate)

    private suspend fun open() {
        pause.load()
        assertEquals(MessagingPauseState.Open, gate.state)
    }

    private suspend fun pauseFor(who: String = owner) = assertTrue(pause.begin(who, "scope", "cred"))

    @Test
    fun `loading admits nothing`() {
        assertEquals(MessagingPauseState.Loading, gate.state)
        assertFalse(gate.isOpen)
        assertNull(gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        assertNull(gate.tryAdmit(AdmissionKind.SEND, "job"))
        assertNull(gate.tryAdmitAsOwner(owner, AdmissionKind.RECEIVE, "drain"))
    }

    @Test
    fun `open admits and paused refuses`() = runTest {
        open()
        assertNotNull(gate.tryAdmit(AdmissionKind.RECEIVE, "ws")).close()
        pauseFor()
        assertNull(gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        assertNull(gate.tryAdmit(AdmissionKind.SEND, "job"))
    }

    @Test
    fun `T8 close waits for receive and send tickets issued before the pause`() = runTest {
        open()
        val receive = assertNotNull(gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        val send = assertNotNull(gate.tryAdmit(AdmissionKind.SEND, "job"))
        pauseFor()
        val idle = async { gate.closeAndAwaitIdle(owner, 30.seconds) }
        runCurrent()
        assertFalse(idle.isCompleted)
        receive.close()
        runCurrent()
        assertFalse(idle.isCompleted, "a send ticket is still out")
        send.close()
        assertTrue(idle.await())
    }

    @Test
    fun `a retained ticket keeps the close waiting after its parent closes`() = runTest {
        open()
        val parent = assertNotNull(gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        pauseFor()
        // Retained after the pause: the unit was admitted with the parent.
        val held = assertNotNull(parent.retain("group-notify"))
        assertEquals(AdmissionKind.RECEIVE, held.kind)
        val idle = async { gate.closeAndAwaitIdle(owner, 30.seconds) }
        parent.close()
        runCurrent()
        assertFalse(idle.isCompleted, "the retained unit is still out")
        held.close()
        assertTrue(idle.await())
    }

    @Test
    fun `the owner's second wait passes on a spent budget only when idle`() = runTest {
        open()
        pauseFor()
        assertTrue(gate.closeAndAwaitIdle(owner, 30.seconds))
        // A spent budget (the owner's second wait after its drain) still passes when nothing is out.
        assertTrue(gate.closeAndAwaitIdle(owner, Duration.ZERO))
        val drained = assertNotNull(gate.tryAdmitAsOwner(owner, AdmissionKind.RECEIVE, "drain"))
        assertFalse(gate.closeAndAwaitIdle(owner, Duration.ZERO))
        drained.close()
        assertTrue(gate.closeAndAwaitIdle(owner, Duration.ZERO))
    }

    @Test
    fun `a closed ticket retains nothing`() = runTest {
        open()
        val parent = assertNotNull(gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        parent.close()
        assertNull(parent.retain("group-notify"))
        pauseFor()
        assertTrue(gate.closeAndAwaitIdle(owner, 1.seconds))
    }

    @Test
    fun `closing a ticket twice releases it once`() = runTest {
        open()
        val first = assertNotNull(gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        val second = assertNotNull(gate.tryAdmit(AdmissionKind.RECEIVE, "fcm"))
        pauseFor()
        val idle = async { gate.closeAndAwaitIdle(owner, 30.seconds) }
        first.close()
        first.close()
        runCurrent()
        assertFalse(idle.isCompleted, "a double close must not release the other ticket")
        second.close()
        assertTrue(idle.await())
    }

    @Test
    fun `close times out while a ticket is held`() = runTest {
        open()
        val held = assertNotNull(gate.tryAdmit(AdmissionKind.SEND, "upload"))
        pauseFor()
        val idle = async { gate.closeAndAwaitIdle(owner, 30.seconds) }
        advanceTimeBy(29_000)
        assertFalse(idle.isCompleted)
        advanceTimeBy(2_000)
        assertFalse(idle.await())
        held.close()
    }

    @Test
    fun `close with nothing in flight returns at once`() = runTest {
        open()
        pauseFor()
        assertTrue(gate.closeAndAwaitIdle(owner, 30.seconds))
    }

    @Test
    fun `close is refused without the owner's pause`() = runTest {
        assertFalse(gate.closeAndAwaitIdle(owner, 1.seconds), "loading")
        open()
        assertFalse(gate.closeAndAwaitIdle(owner, 1.seconds), "open")
        pauseFor(other)
        assertFalse(gate.closeAndAwaitIdle(owner, 1.seconds), "another owner")
    }

    @Test
    fun `close fails when the pause is cleared during the wait`() = runTest {
        open()
        val held = assertNotNull(gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        pauseFor()
        val idle = async { gate.closeAndAwaitIdle(owner, 30.seconds) }
        runCurrent()
        pause.clear(null, "logout")
        held.close()
        assertFalse(idle.await())
    }

    @Test
    fun `owner ticket is issued only to the exporting owner and is awaited`() = runTest {
        open()
        assertNull(gate.tryAdmitAsOwner(owner, AdmissionKind.RECEIVE, "drain"), "open is not a bypass")
        pauseFor()
        assertNull(gate.tryAdmitAsOwner(other, AdmissionKind.RECEIVE, "drain"))
        val drain = assertNotNull(gate.tryAdmitAsOwner(owner, AdmissionKind.RECEIVE, "drain"))
        val idle = async { gate.closeAndAwaitIdle(owner, 30.seconds) }
        runCurrent()
        assertFalse(idle.isCompleted)
        drain.close()
        assertTrue(idle.await())
        pause.committing(owner, "session")
        assertNull(gate.tryAdmitAsOwner(owner, AdmissionKind.RECEIVE, "drain"), "only while exporting")
    }

    @Test
    fun `reopening admits again`() = runTest {
        open()
        pauseFor()
        pause.finish(owner, null, MigrationPauseOutcome.FAILED)
        assertNotNull(gate.tryAdmit(AdmissionKind.SEND, "job")).close()
    }

    /**
     * T10: threads race [MessagingGate.tryAdmit] against the pause and [MessagingGate.closeAndAwaitIdle].
     * Every ticket issued before the pause is closed when the wait returns, no call that starts after
     * the pause took effect gets a ticket, and none is issued after the wait returned.
     */
    @Test
    fun `T10 tryAdmit racing the close never escapes the wait`() = runBlocking {
        repeat(ROUNDS) { round ->
            val gate = MessagingGate()
            val pause = MigrationMessagePause(RecordingPreferencesDataStore(), gate)
            pause.load()
            val paused = AtomicBoolean(false)
            val stop = AtomicBoolean(false)
            val holding = AtomicInteger(0)
            val issued = AtomicInteger(0)
            val issuedAfterPause = AtomicInteger(0)
            val loops = AtomicInteger(0)
            val ready = CountDownLatch(THREADS)
            val pool = Executors.newFixedThreadPool(THREADS)
            repeat(THREADS) { index ->
                pool.execute {
                    val kind = if (index % 2 == 0) AdmissionKind.RECEIVE else AdmissionKind.SEND
                    ready.countDown()
                    while (!stop.get()) {
                        loops.incrementAndGet()
                        val startedAfterPause = paused.get()
                        val ticket = gate.tryAdmit(kind, "t$index") ?: continue
                        if (startedAfterPause) issuedAfterPause.incrementAndGet()
                        issued.incrementAndGet()
                        holding.incrementAndGet()
                        var spin = 0
                        while (spin < (index + 1) * SPIN) spin++
                        holding.decrementAndGet()
                        ticket.close()
                    }
                }
            }
            ready.await()
            while (issued.get() < MIN_ISSUED_BEFORE_PAUSE) Thread.yield()
            assertTrue(pause.begin(owner, "scope", "cred"))
            paused.set(true)
            val idle = gate.closeAndAwaitIdle(owner, 10.seconds)
            val holdingAtIdle = holding.get()
            val issuedAtIdle = issued.get()
            // Keep racing after the wait returned: a ticket issued now would have escaped it.
            val loopsAtIdle = loops.get()
            while (loops.get() < loopsAtIdle + THREADS * LOOPS_AFTER_IDLE) Thread.yield()
            stop.set(true)
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            assertTrue(idle, "round $round: close timed out")
            assertEquals(0, holdingAtIdle, "round $round: a ticket was still working when the wait returned")
            assertEquals(0, issuedAfterPause.get(), "round $round: a ticket was issued after the pause")
            assertEquals(issuedAtIdle, issued.get(), "round $round: a ticket was issued after the wait returned")
            // The gate itself is balanced: it can close again at once.
            assertTrue(gate.closeAndAwaitIdle(owner, 1.seconds))
        }
    }

    private companion object {
        const val ROUNDS = 50
        const val THREADS = 8
        const val SPIN = 2_000
        const val MIN_ISSUED_BEFORE_PAUSE = 200
        const val LOOPS_AFTER_IDLE = 100
    }
}
