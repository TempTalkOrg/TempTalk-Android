package com.difft.android.chat

import com.difft.android.base.monitor.KeepAliveHealthCounters
import com.difft.android.chat.PendingMessageHelper.PullTrigger
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/** #1328: only FCM-triggered pulls feed pull_ok / pull_fail; re-entrant skips are not counted. */
class PendingMessageHelperPullCountingTest {

    private val counters = mockk<KeepAliveHealthCounters>(relaxed = true)
    private val job = Job()
    private val scope = CoroutineScope(job)
    private lateinit var helper: PendingMessageHelper

    @Before
    fun setUp() {
        helper = spyk(PendingMessageHelper(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), counters))
    }

    @After
    fun tearDown() = job.cancel()

    private fun awaitPulls() = runBlocking { job.children.toList().forEach { it.join() } }

    @Test
    fun `an FCM pull counts ok on success and fail on failure`() {
        coEvery { helper.obtainPendingMessageAndSave() } returns true
        helper.schedulePendingMessageWork(PullTrigger.FCM, scope)
        awaitPulls()
        verify(exactly = 1) { counters.onPullResult(true) }

        coEvery { helper.obtainPendingMessageAndSave() } returns false
        helper.schedulePendingMessageWork(PullTrigger.FCM, scope)
        awaitPulls()
        verify(exactly = 1) { counters.onPullResult(false) }
    }

    @Test
    fun `an FCM pull that throws counts one fail`() {
        coEvery { helper.obtainPendingMessageAndSave() } throws IllegalStateException("boom")
        helper.schedulePendingMessageWork(PullTrigger.FCM, scope)
        awaitPulls()
        verify(exactly = 1) { counters.onPullResult(false) }
        verify(exactly = 0) { counters.onPullResult(true) }
    }

    @Test
    fun `other triggers and the default never count`() {
        coEvery { helper.obtainPendingMessageAndSave() } returns false
        helper.schedulePendingMessageWork(PullTrigger.OTHER, scope)
        awaitPulls()
        helper.schedulePendingMessageWork(scope = scope)
        awaitPulls()
        verify(exactly = 0) { counters.onPullResult(any()) }
    }

    @Test
    fun `a re-entrant FCM call skipped by the guard is not counted`() {
        val gate = CompletableDeferred<Unit>()
        coEvery { helper.obtainPendingMessageAndSave() } coAnswers { gate.await(); true }
        helper.schedulePendingMessageWork(PullTrigger.FCM, scope)
        helper.schedulePendingMessageWork(PullTrigger.FCM, scope) // skipped: the first is still running
        gate.complete(Unit)
        awaitPulls()
        verify(exactly = 1) { counters.onPullResult(any()) }
    }

    @Test
    fun `a cancelled FCM pull is not counted as a failure and frees the guard`() {
        // obtainPendingMessageAndSave maps scope cancellation to `false`; that must not become pull_fail.
        coEvery { helper.obtainPendingMessageAndSave() } coAnswers { job.cancel(); false }
        helper.schedulePendingMessageWork(PullTrigger.FCM, scope)
        awaitPulls()
        verify(exactly = 0) { counters.onPullResult(any()) }

        coEvery { helper.obtainPendingMessageAndSave() } throws CancellationException("shutdown")
        val job2 = Job()
        helper.schedulePendingMessageWork(PullTrigger.FCM, CoroutineScope(job2))
        runBlocking { job2.children.toList().forEach { it.join() } }
        verify(exactly = 0) { counters.onPullResult(any()) }

        // The re-entrancy guard was released both times, so the next pull runs and counts.
        coEvery { helper.obtainPendingMessageAndSave() } returns true
        val job3 = Job()
        helper.schedulePendingMessageWork(PullTrigger.FCM, CoroutineScope(job3))
        runBlocking { job3.children.toList().forEach { it.join() } }
        verify(exactly = 1) { counters.onPullResult(true) }
    }
}
