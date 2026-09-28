package com.difft.android.chat.jobmanager

import android.app.Application
import com.difft.android.chat.jobmanager.persistence.JobStorage
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull

/**
 * Covers [JobManager.wakeUpFromSystem], the single management-dispatcher step a persisted wake-up
 * relies on: it must signal the job runners, and nothing else.
 *
 * Tech mirrors [JobManagerInitTest]: Robolectric because [JobManager] builds a
 * [com.difft.android.chat.util.Debouncer] holding a main-looper `Handler`, and
 * `runBlocking(Dispatchers.Default)` because the management dispatcher is a real thread pool that
 * a virtual-time test scheduler would never advance.
 */
@RunWith(RobolectricTestRunner::class)
class JobManagerWakeUpTest {

    private val application = mockk<Application>(relaxed = true)
    private val jobStorage = mockk<JobStorage>(relaxed = true)

    @Before
    fun setUp() {
        every { jobStorage.getAllJobSpecs() } returns emptyList()
        every { jobStorage.getConstraintSpecs(any()) } returns emptyList()
        every { jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        clearMocks(application, jobStorage)
    }

    @Test
    fun `wakeUpFromSystem signals a runner parked on an empty queue`() =
        runBlocking(Dispatchers.Default) {
            // A runner that found nothing registers its wait signal inside the same
            // management-dispatcher step that read storage, so observing a check proves the runner
            // is parked by the time a later step on that serial dispatcher runs. A second check is
            // therefore reachable only through the wake-up signal.
            val checks = LinkedBlockingQueue<Unit>()
            every { jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any()) } answers {
                checks.add(Unit)
                emptyList()
            }
            val jobManager = buildJobManager()

            jobManager.beginJobLoop()
            assertNotNull(checks.poll(AWAIT_SECONDS, TimeUnit.SECONDS), "the runner never checked the queue")

            withTimeout(AWAIT_MILLIS) { jobManager.wakeUpFromSystem() }

            assertNotNull(checks.poll(AWAIT_SECONDS, TimeUnit.SECONDS), "the parked runner was never signalled")
        }

    // -- Helpers --

    /** One runner, so the queue below records exactly one check per signal. */
    private fun buildJobManager(): JobManager {
        val configuration = JobManager.Configuration.Builder()
            .setJobThreadCount(1)
            .setJobStorage(jobStorage)
            .setJobFactories(emptyMap())
            .setConstraintFactories(emptyMap())
            .setConstraintObservers(emptyList())
            .build()
        return JobManager(application, configuration)
    }

    private companion object {
        const val AWAIT_MILLIS = AWAIT_SECONDS * 1000
    }
}
