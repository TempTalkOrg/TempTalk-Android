package com.difft.android.chat.translate

import app.cash.turbine.test
import com.difft.android.base.utils.appScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * [TranslateQueue] — the single-worker serialisation layer. One ML Kit translation runs at a
 * time, and every result is broadcast on `completions` whether or not anyone still awaits the
 * handle it returned.
 *
 * The worker is deliberately started on `Dispatchers.Default` inside `init`, so a test dispatcher
 * could not drive it: every body therefore runs inside `withContext(Dispatchers.Default)`, which
 * also gives `withTimeout` a real clock instead of `runTest`'s virtual one. Determinism comes from a
 * primer job that parks the worker on a gate — everything submitted while the gate is closed is
 * provably still pending when the assertions about pending work are made.
 *
 * What each case asserts:
 *  - T14  priority ordering — a NEW_INCOMING submitted last still runs before queued BACKGROUND work
 *  - T15  same-key dedup — one engine call, one shared handle
 *  - T16  cancelOwner drops that owner's pending work and cancels its handles
 *  - T16b completions are broadcast even when nobody is awaiting the handle any more
 *  - T16c cancelOwner is owner-scoped: the manual owner survives an automatic cancel
 *  - T62  (queue half) cancelAll drops every owner's pending work — the logout path
 *  - T62b a job already inside ML Kit when cancelAll fires has its completion dropped
 *  - a cancelled backend task fails that one job without taking the single worker down with it
 */
class TranslateQueueTest {

    private lateinit var engine: TranslateEngine
    private lateinit var scope: CoroutineScope
    private lateinit var queue: TranslateQueue

    /** Completed by the engine fake the moment the primer job starts running on the worker. */
    private lateinit var primerStarted: CompletableDeferred<Unit>

    /** Released by the test to let the primer — and therefore the worker — continue. */
    private lateinit var gate: CompletableDeferred<Unit>

    private val calls = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        primerStarted = CompletableDeferred()
        gate = CompletableDeferred()
        calls.clear()

        engine = mockk()
        coEvery { engine.translate(any(), any(), any()) } coAnswers {
            val text = firstArg<String>()
            if (text == PRIMER) {
                primerStarted.complete(Unit)
                gate.await()
            }
            // How the GMS backend reports a cancelled Task: the continuation is cancelled while the
            // worker's own job is untouched.
            if (text == CANCELLED_TASK) throw CancellationException("backend task cancelled")
            calls.add(text)
            TranslateResult.Translated("[$text]", "en")
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns scope

        queue = TranslateQueue(engine)
    }

    @After
    fun tearDown() {
        if (!gate.isCompleted) gate.complete(Unit)
        scope.cancel()
        unmockkAll()
    }

    /** Real dispatcher + real clock; see the class comment. */
    private fun queueTest(body: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { body() }
    }

    private fun submit(
        key: String,
        owner: String = OWNER,
        priority: TranslateQueue.Priority = TranslateQueue.Priority.BACKGROUND,
        text: String = key,
    ): Deferred<TranslateResult> = queue.submit(
        owner = owner,
        key = key,
        messageId = key,
        priority = priority,
        text = text,
        target = TranslateLang.ZH,
        mode = TranslateMode.AUTO,
    )

    /** Parks the worker on [gate] and returns once it is provably inside the engine call. */
    private suspend fun occupyWorker(): Deferred<TranslateResult> {
        val handle = submit(PRIMER, owner = PRIMER_OWNER, priority = TranslateQueue.Priority.NEW_INCOMING)
        withTimeout(TIMEOUT_MS) { primerStarted.await() }
        return handle
    }

    // --- T14 ----------------------------------------------------------------

    @Test
    fun `T14 a NEW_INCOMING job jumps the queued BACKGROUND backlog`() = queueTest {
        val primer = occupyWorker()

        val b1 = submit("b1")
        val b2 = submit("b2")
        val b3 = submit("b3")
        val newIncoming = submit("n1", priority = TranslateQueue.Priority.NEW_INCOMING)

        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) {
            primer.await()
            newIncoming.await()
            b1.await()
            b2.await()
            b3.await()
        }

        assertEquals(listOf(PRIMER, "n1", "b1", "b2", "b3"), calls.toList())
    }

    // --- T15 ----------------------------------------------------------------

    @Test
    fun `T15 a second submit of a live key joins the first instead of queueing again`() = queueTest {
        val primer = occupyWorker()

        val first = submit("dup", text = "same text")
        val second = submit("dup", text = "same text")

        assertSame("the second submit must hand back the first handle", first, second)

        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) {
            primer.await()
            assertSame(first.await(), second.await())
        }

        assertEquals(1, calls.count { it == "same text" })
        coVerify(exactly = 1) { engine.translate("same text", TranslateLang.ZH, TranslateMode.AUTO) }
    }

    // --- T16 ----------------------------------------------------------------

    @Test
    fun `T16 cancelOwner drops pending work and cancels the handles so awaiters can roll back`() = queueTest {
        val primer = occupyWorker()
        val a1 = submit("a1")
        val a2 = submit("a2")

        queue.cancelOwner(OWNER)

        assertTrue("a1 handle must be cancelled", a1.isCancelled)
        assertTrue("a2 handle must be cancelled", a2.isCancelled)

        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) { primer.await() }

        assertEquals(listOf(PRIMER), calls.toList())
    }

    // --- T16b ---------------------------------------------------------------

    @Test
    fun `T16b a result is broadcast even after the awaiter gave up`() = queueTest {
        val primer = occupyWorker()

        queue.completions.test {
            val abandoned = submit("abandoned")
            // The caller stops waiting — cancelling a wait is NOT cancelling the work.
            abandoned.cancel()
            assertTrue(abandoned.isCancelled)

            gate.complete(Unit)

            val seen = listOf(awaitItem(), awaitItem())
            val abandonedCompletion = seen.single { it.key == "abandoned" }

            assertEquals("abandoned", abandonedCompletion.messageId)
            assertEquals(OWNER, abandonedCompletion.owner)
            assertEquals(TranslateLang.ZH, abandonedCompletion.target)
            assertTrue(abandonedCompletion.result is TranslateResult.Translated)
            cancelAndIgnoreRemainingEvents()
        }

        withTimeout(TIMEOUT_MS) { primer.await() }
        assertTrue("the work itself still ran", calls.contains("abandoned"))
    }

    // --- T16c ---------------------------------------------------------------

    @Test
    fun `T16c cancelOwner leaves other owners alone`() = queueTest {
        val primer = occupyWorker()
        val auto = submit("auto", owner = OWNER)
        val manual = submit("manual", owner = MANUAL_OWNER)

        queue.cancelOwner(OWNER)

        assertTrue(auto.isCancelled)
        assertFalse(manual.isCancelled)

        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) {
            primer.await()
            manual.await()
        }

        assertEquals(listOf(PRIMER, "manual"), calls.toList())
    }

    // --- T62 (queue half) ---------------------------------------------------

    @Test
    fun `T62 cancelAll drops every owner`() = queueTest {
        val primer = occupyWorker()
        val auto = submit("auto", owner = OWNER)
        val manual = submit("manual", owner = MANUAL_OWNER)

        queue.cancelAll()

        assertTrue(auto.isCancelled)
        assertTrue(manual.isCancelled)

        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) { primer.await() }

        assertEquals(listOf(PRIMER), calls.toList())
    }

    @Test
    fun `T62b a job running when cancelAll fires has its completion dropped`() = queueTest {
        val primer = occupyWorker()

        queue.completions.test {
            // The running job cannot be interrupted — an ML Kit task has no cancel — so what
            // cancelAll actually guarantees is that its RESULT never reaches an owner.
            queue.cancelAll()
            gate.complete(Unit)
            withTimeout(TIMEOUT_MS) { primer.await() }

            // The broadcast happens before the handle completes, so anything emitted would already
            // be here; the delay only covers the collector's own hop.
            delay(BROADCAST_SETTLE_MS)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue("the work itself still ran to the end", calls.contains(PRIMER))
        // A job submitted after the bump is a new generation and is broadcast again.
        queue.completions.test {
            submit("after")
            assertEquals("after", awaitItem().key)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- cancelled backend task ---------------------------------------------

    @Test
    fun `a cancelled backend task fails the job but the worker keeps serving the next submission`() =
        queueTest {
            val primer = occupyWorker()
            val cancelledByTask = submit(CANCELLED_TASK)
            val next = submit("after-cancel")

            gate.complete(Unit)
            val cancelledResult = withTimeout(TIMEOUT_MS) {
                primer.await()
                cancelledByTask.await()
            }
            val nextResult = withTimeout(TIMEOUT_MS) { next.await() }

            assertTrue("an inner task cancellation is a normal failed result", cancelledResult is TranslateResult.Failed)
            assertTrue("the worker must survive it", nextResult is TranslateResult.Translated)
            assertEquals(listOf(PRIMER, "after-cancel"), calls.toList())
        }

    // --- barrier ------------------------------------------------------------

    @Test
    fun `barrier runs on the worker, never beside a translation`() = queueTest {
        val primer = occupyWorker()
        var barrierRan = false
        var overlapped = true

        val barrier = scope.async {
            queue.barrier {
                barrierRan = true
                // The worker completes the primer's handle before it polls the next item.
                overlapped = !primer.isCompleted
            }
        }

        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) {
            primer.await()
            barrier.await()
        }

        assertTrue(barrierRan)
        assertFalse("the barrier must not overlap a running translation", overlapped)
    }

    private companion object {
        const val OWNER = "room-1"
        const val MANUAL_OWNER = "room-1#manual"
        const val PRIMER_OWNER = "primer-owner"
        const val PRIMER = "primer"
        const val CANCELLED_TASK = "cancelled-task"
        const val TIMEOUT_MS = 10_000L
        const val BROADCAST_SETTLE_MS = 200L
    }
}
