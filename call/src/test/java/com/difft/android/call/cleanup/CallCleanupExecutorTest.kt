package com.difft.android.call.cleanup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real [CallCleanupExecutor] on a real IO thread (session tests capture its starts instead).
 * Row: T5-21.
 */
class CallCleanupExecutorTest {

    @Test
    fun `T5-21 steps run once across both triggers, a failing step is isolated, and the run survives scope cancel`() {
        val runScope = CoroutineScope(SupervisorJob())
        val executor = CallCleanupExecutor(runScope = runScope)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val firstRuns = AtomicInteger()
        val secondRuns = AtomicInteger()
        val thirdRuns = AtomicInteger()
        var scopeActiveAtSecond: Boolean? = null
        val steps = listOf(
            CallCleanupExecutor.Step("first") {
                firstRuns.incrementAndGet()
                entered.countDown()
                release.await(TIMEOUT_S, TimeUnit.SECONDS)
                error("step failure")
            },
            CallCleanupExecutor.Step("second") {
                scopeActiveAtSecond = runScope.isActive
                delay(1) // throws CancellationException unless the run is NonCancellable
                secondRuns.incrementAndGet()
            },
            CallCleanupExecutor.Step("third") {
                thirdRuns.incrementAndGet()
                done.countDown()
            },
        )

        executor.start("doExitClear", steps)
        executor.start("onCleared", steps)
        assertTrue("first step never started", entered.await(TIMEOUT_S, TimeUnit.SECONDS))
        runScope.cancel()
        release.countDown()

        assertTrue("cleanup did not finish after scope cancel", done.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertEquals(false, scopeActiveAtSecond)
        assertEquals(1, firstRuns.get())
        assertEquals("step 2 runs although step 1 threw", 1, secondRuns.get())
        assertEquals(1, thirdRuns.get())
    }

    private companion object {
        const val TIMEOUT_S = 5L
    }
}
