package com.difft.android.chat.jobmanager

import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobTrackerTest {

    private lateinit var tracker: JobTracker

    @Before
    fun setUp() {
        tracker = JobTracker()
    }

    // -- onStateChange / getFirstMatchingJobState --

    @Test
    fun `onStateChange updates job state`() {
        val job = createMockJob("job-1")

        tracker.onStateChange(job, JobTracker.JobState.RUNNING)

        val state = tracker.getFirstMatchingJobState { it.id == "job-1" }
        assertEquals(JobTracker.JobState.RUNNING, state)
    }

    @Test
    fun `getFirstMatchingJobState returns null for unknown job`() {
        val state = tracker.getFirstMatchingJobState { it.id == "nonexistent" }

        assertNull(state)
    }

    @Test
    fun `onStateChange overwrites previous state`() {
        val job = createMockJob("job-1")

        tracker.onStateChange(job, JobTracker.JobState.PENDING)
        tracker.onStateChange(job, JobTracker.JobState.RUNNING)
        tracker.onStateChange(job, JobTracker.JobState.SUCCESS)

        val state = tracker.getFirstMatchingJobState { it.id == "job-1" }
        assertEquals(JobTracker.JobState.SUCCESS, state)
    }

    // -- haveAnyFailed --

    @Test
    fun `haveAnyFailed returns true if any job has FAILURE state`() {
        val job1 = createMockJob("job-1")
        val job2 = createMockJob("job-2")

        tracker.onStateChange(job1, JobTracker.JobState.SUCCESS)
        tracker.onStateChange(job2, JobTracker.JobState.FAILURE)

        assertTrue(tracker.haveAnyFailed(listOf("job-1", "job-2")))
    }

    @Test
    fun `haveAnyFailed returns false if no job has FAILURE state`() {
        val job1 = createMockJob("job-1")
        val job2 = createMockJob("job-2")

        tracker.onStateChange(job1, JobTracker.JobState.SUCCESS)
        tracker.onStateChange(job2, JobTracker.JobState.RUNNING)

        assertFalse(tracker.haveAnyFailed(listOf("job-1", "job-2")))
    }

    @Test
    fun `haveAnyFailed returns false for unknown job ids`() {
        assertFalse(tracker.haveAnyFailed(listOf("unknown-1", "unknown-2")))
    }

    @Test
    fun `haveAnyFailed returns false for empty collection`() {
        assertFalse(tracker.haveAnyFailed(emptyList()))
    }

    // -- Listener notification --

    @Test
    fun `listener is notified on matching state change`() {
        val job = createMockJob("job-1")
        val latch = CountDownLatch(1)
        val capturedState = AtomicReference<JobTracker.JobState>()

        tracker.addListener({ it.id == "job-1" }) { _, state ->
            capturedState.set(state)
            latch.countDown()
        }

        tracker.onStateChange(job, JobTracker.JobState.SUCCESS)

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(JobTracker.JobState.SUCCESS, capturedState.get())
    }

    @Test
    fun `listener is not notified for non-matching jobs`() {
        val job = createMockJob("job-2")
        val latch = CountDownLatch(1)
        var notified = false

        tracker.addListener({ it.id == "job-1" }) { _, _ ->
            notified = true
            latch.countDown()
        }

        tracker.onStateChange(job, JobTracker.JobState.SUCCESS)

        assertFalse(latch.await(200, TimeUnit.MILLISECONDS))
        assertFalse(notified)
    }

    @Test
    fun `removeListener stops notifications`() {
        val job = createMockJob("job-1")
        val latch = CountDownLatch(1)
        var notifyCount = 0
        val listener = JobTracker.JobListener { _, _ ->
            notifyCount++
            latch.countDown()
        }

        tracker.addListener({ true }, listener)
        tracker.onStateChange(job, JobTracker.JobState.PENDING)
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        tracker.removeListener(listener)
        tracker.onStateChange(job, JobTracker.JobState.RUNNING)

        // Give async executor time to potentially fire (it shouldn't)
        Thread.sleep(200)
        assertEquals(1, notifyCount)
    }

    // -- LRU eviction --

    @Test
    fun `oldest entries are evicted when exceeding capacity of 1000`() {
        // Add 1001 jobs
        for (i in 1..1001) {
            val job = createMockJob("job-$i")
            tracker.onStateChange(job, JobTracker.JobState.SUCCESS)
        }

        // The first job should have been evicted
        assertNull(tracker.getFirstMatchingJobState { it.id == "job-1" })
        // The last job should still be present
        assertEquals(
            JobTracker.JobState.SUCCESS,
            tracker.getFirstMatchingJobState { it.id == "job-1001" }
        )
    }

    // -- JobState properties --

    @Test
    fun `SUCCESS state is complete`() {
        assertTrue(JobTracker.JobState.SUCCESS.isComplete)
    }

    @Test
    fun `FAILURE state is complete`() {
        assertTrue(JobTracker.JobState.FAILURE.isComplete)
    }

    @Test
    fun `IGNORED state is complete`() {
        assertTrue(JobTracker.JobState.IGNORED.isComplete)
    }

    @Test
    fun `PENDING state is not complete`() {
        assertFalse(JobTracker.JobState.PENDING.isComplete)
    }

    @Test
    fun `RUNNING state is not complete`() {
        assertFalse(JobTracker.JobState.RUNNING.isComplete)
    }

    // -- Helper --

    private fun createMockJob(id: String): Job {
        val params = Job.Parameters.Builder(id)
            .setCreateTime(System.currentTimeMillis())
            .build()
        return mockk<Job>(relaxed = true) {
            every { this@mockk.id } returns id
            every { parameters } returns params
        }
    }
}
