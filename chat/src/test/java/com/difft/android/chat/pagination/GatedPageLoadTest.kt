package com.difft.android.chat.pagination

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases #86, #87 and #92 — the page-load gate.
 *
 * Without the gate, a prefetch-driven older page and an IDLE-driven newer page overlap, and the
 * controller merges pages with a read-modify-write on its own state flow: the later writer
 * overwrites the earlier one's merge and a whole page silently disappears. The per-direction
 * `isLoadingTop` / `isLoadingBottom` flags cannot see that, being per direction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatedPageLoadTest {

    private val gate = Mutex()
    private val started = mutableListOf<String>()
    private val finished = mutableListOf<String>()

    // #86 — cross-direction overlap: the waiting caller starts only after the in-flight load returns.
    @Test
    fun `a waiting caller runs strictly after the in-flight load finishes`() = runTest {
        val release = CompletableDeferred<Unit>()
        val first = launch {
            runGatedPageLoad(
                gate = gate,
                waitIfBusy = false,
                decide = { PageLoadDecision(loadNewer = false, loadOlder = true) },
                loadNewer = { },
                loadOlder = { record("older") { release.await() } },
            )
        }
        advanceUntilIdle()
        assertEquals(listOf("older"), started)

        val second = launch {
            runGatedPageLoad(
                gate = gate,
                waitIfBusy = true,
                decide = { PageLoadDecision(loadNewer = true, loadOlder = false) },
                loadNewer = { record("newer") { } },
                loadOlder = { },
            )
        }
        advanceUntilIdle()
        assertEquals("the waiting caller must not have started yet", listOf("older"), started)

        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf("older", "newer"), started)
        assertEquals("newer must begin after older returned", listOf("older", "newer"), finished)
    }

    // #87 — the prefetch path drops the check instead of queueing it: the next scroll signal or the
    // next IDLE re-issues it, and queueing prefetches would pile up stale ones behind a slow load.
    @Test
    fun `a prefetch check is dropped while the gate is busy`() = runTest {
        val release = CompletableDeferred<Unit>()
        val first = launch {
            runGatedPageLoad(
                gate = gate,
                waitIfBusy = false,
                decide = { PageLoadDecision(loadNewer = false, loadOlder = true) },
                loadNewer = { },
                loadOlder = { record("older") { release.await() } },
            )
        }
        advanceUntilIdle()

        runGatedPageLoad(
            gate = gate,
            waitIfBusy = false,
            decide = { PageLoadDecision(loadNewer = true, loadOlder = true) },
            loadNewer = { record("newer") { } },
            loadOlder = { record("older-2") { } },
        )

        assertEquals("the busy-gate prefetch must load nothing", listOf("older"), started)
        release.complete(Unit)
        first.join()
    }

    // #92 — RACE-1. `lock()` suspends for the whole duration of the in-flight load, during which the
    // edge flags can flip; the decision taken before the gate is therefore stale by the time the
    // caller owns it. Deleting the post-acquire `decide()` call turns this case red.
    @Test
    fun `a decision invalidated while waiting for the gate is not executed`() = runTest {
        assertStaleDecisionDropped(PageLoadDecision(loadNewer = false, loadOlder = true))
    }

    // Symmetric variant: the newer direction goes stale instead.
    @Test
    fun `a stale newer-page decision is not executed either`() = runTest {
        assertStaleDecisionDropped(PageLoadDecision(loadNewer = true, loadOlder = false))
    }

    private suspend fun TestScope.assertStaleDecisionDropped(beforeGate: PageLoadDecision) {
        val release = CompletableDeferred<Unit>()
        val inFlight = launch {
            runGatedPageLoad(
                gate = gate,
                waitIfBusy = false,
                decide = { PageLoadDecision(loadNewer = false, loadOlder = true) },
                loadNewer = { },
                loadOlder = { record("in-flight") { release.await() } },
            )
        }
        advanceUntilIdle()
        assertTrue("precondition: the gate is held", gate.isLocked)

        // Fresh while the pre-check runs, NONE by the time the gate is acquired: exactly what an
        // in-flight page load does when it reaches the conversation's edge.
        var decisions = 0
        val waiting = launch {
            runGatedPageLoad(
                gate = gate,
                waitIfBusy = true,
                decide = { if (decisions++ == 0) beforeGate else PageLoadDecision.NONE },
                loadNewer = { record("newer") { } },
                loadOlder = { record("older") { } },
            )
        }
        advanceUntilIdle()

        release.complete(Unit)
        inFlight.join()
        waiting.join()

        assertEquals("only the in-flight load may have run", listOf("in-flight"), started)
        assertEquals("the decision must be re-read after acquiring the gate", 2, decisions)
        assertFalse("the gate must be released by the finally block", gate.isLocked)

        // …and the gate really is usable afterwards, so `finally` was not skipped.
        runGatedPageLoad(
            gate = gate,
            waitIfBusy = false,
            decide = { PageLoadDecision(loadNewer = false, loadOlder = true) },
            loadNewer = { },
            loadOlder = { record("after") { } },
        )
        assertEquals(listOf("in-flight", "after"), started)
    }

    private suspend fun record(name: String, body: suspend () -> Unit) {
        started += name
        body()
        finished += name
    }
}
