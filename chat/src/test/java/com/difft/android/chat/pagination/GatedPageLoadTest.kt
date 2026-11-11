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









@OptIn(ExperimentalCoroutinesApi::class)
class GatedPageLoadTest {

    private val gate = Mutex()
    private val started = mutableListOf<String>()
    private val finished = mutableListOf<String>()

    
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

    
    
    
    @Test
    fun `a decision invalidated while waiting for the gate is not executed`() = runTest {
        assertStaleDecisionDropped(PageLoadDecision(loadNewer = false, loadOlder = true))
    }

    
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
