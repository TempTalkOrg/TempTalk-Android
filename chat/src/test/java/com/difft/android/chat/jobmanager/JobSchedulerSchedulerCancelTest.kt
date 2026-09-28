package com.difft.android.chat.jobmanager

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [JobSchedulerScheduler.cancelAllExcept] and its ordering against
 * [JobSchedulerScheduler.schedule]. Kept apart from `JobSchedulerSchedulerTest` so neither file
 * grows past the project's file-size limit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JobSchedulerSchedulerCancelTest {

    private val jobScheduler = mockk<JobScheduler>(relaxed = true)
    private val application = mockk<Application>(relaxed = true)

    /** Binder calls in the order the scheduler's serial executor issued them. */
    private val calls = LinkedBlockingQueue<String>()

    @Before
    fun setUp() {
        calls.clear()
        every { application.packageName } returns PACKAGE
        every { application.getSystemService(JobScheduler::class.java) } returns jobScheduler
    }

    @After
    fun tearDown() {
        clearMocks(jobScheduler, application)
        calls.clear()
    }

    @Test
    fun `cancelling everything clears our own wake-ups and leaves other components alone`() {
        // The foreign job sits in the middle, so observing both of our cancels proves the whole
        // list was walked and the foreign entry was deliberately skipped.
        every { jobScheduler.allPendingJobs } returns listOf(
            pendingJob(PLAIN_JOB_ID, OWN_SERVICE),
            pendingJob(FOREIGN_JOB_ID, FOREIGN_SERVICE),
            pendingJob(NETWORK_JOB_ID, OWN_SERVICE)
        )
        recordCancels()

        JobSchedulerScheduler(application).cancelAllExcept(emptySet())

        assertEquals(listOf("cancel:$PLAIN_JOB_ID", "cancel:$NETWORK_JOB_ID"), drain(2))
        verify(exactly = 0) { jobScheduler.cancel(FOREIGN_JOB_ID) }
    }

    @Test
    fun `a kept id survives while every other wake-up of ours is cancelled`() {
        every { jobScheduler.allPendingJobs } returns listOf(
            pendingJob(PLAIN_JOB_ID, OWN_SERVICE),
            pendingJob(NETWORK_JOB_ID, OWN_SERVICE)
        )
        recordCancels()
        recordSchedules()

        val scheduler = JobSchedulerScheduler(application)
        scheduler.cancelAllExcept(setOf(NETWORK_JOB_ID))

        assertEquals(listOf("cancel:$PLAIN_JOB_ID"), drain(1))
        // The serial executor runs this fence only after the cancel task, so observing it proves
        // that task already ran and left the network job alone.
        scheduler.schedule(0, emptyList())
        assertEquals(listOf("schedule:$PLAIN_JOB_ID"), drain(1))
        verify(exactly = 0) { jobScheduler.cancel(NETWORK_JOB_ID) }
    }

    @Test
    fun `an enumeration that kept a job leaves the next drain able to cancel it`() {
        val enumerations = AtomicInteger()
        every { jobScheduler.allPendingJobs } answers {
            enumerations.incrementAndGet()
            listOf(pendingJob(NETWORK_JOB_ID, OWN_SERVICE))
        }
        recordCancels()

        val scheduler = JobSchedulerScheduler(application)
        scheduler.cancelAllExcept(setOf(NETWORK_JOB_ID))
        scheduler.cancelAllExcept(emptySet())

        assertEquals(listOf("cancel:$NETWORK_JOB_ID"), drain(1))
        assertEquals(2, enumerations.get(), "a kept job must keep the next drain enumerating")
    }

    @Test
    fun `cancelling survives the framework refusing to list pending jobs`() {
        val attempted = CountDownLatch(1)
        every { jobScheduler.allPendingJobs } answers {
            attempted.countDown()
            throw SecurityException("caller cannot enumerate jobs")
        }

        withUncaughtExceptions { uncaught ->
            JobSchedulerScheduler(application).cancelAllExcept(emptySet())

            assertTrue(attempted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the listing was never attempted")
            assertNull(uncaught.poll(1, TimeUnit.SECONDS), "the failure must not escape the executor task")
            verify(exactly = 0) { jobScheduler.cancel(any()) }
        }
    }

    @Test
    fun `a registration submitted before a cancel is never overtaken by it`() {
        val released = CountDownLatch(1)
        every { jobScheduler.getPendingJob(any()) } answers {
            // Holds the registration task until the cancel has been submitted, so a parallel
            // executor would let the cancel run first.
            released.await(AWAIT_SECONDS, TimeUnit.SECONDS)
            null
        }
        every { jobScheduler.schedule(any()) } answers {
            calls.add("schedule:${firstArg<JobInfo>().id}")
            JobScheduler.RESULT_SUCCESS
        }
        every { jobScheduler.allPendingJobs } returns listOf(pendingJob(NETWORK_JOB_ID, OWN_SERVICE))
        recordCancels()

        val scheduler = JobSchedulerScheduler(application)
        scheduler.schedule(0, listOf(TestNetworkConstraint()))
        scheduler.cancelAllExcept(emptySet())
        released.countDown()

        assertEquals(listOf("schedule:$NETWORK_JOB_ID", "cancel:$NETWORK_JOB_ID"), drain(2))
    }

    // -- Armed flag --

    @Test
    fun `a repeat drain with nothing registered in between never reaches the binder`() {
        val enumerations = countEnumerations(PLAIN_JOB_ID)
        recordCancels()
        recordSchedules()

        val scheduler = JobSchedulerScheduler(application)
        scheduler.cancelAllExcept(emptySet())
        assertEquals(listOf("cancel:$PLAIN_JOB_ID"), drain(1))

        scheduler.cancelAllExcept(emptySet())
        // The serial executor runs this fence only after the second cancel task, so observing it
        // proves that task already ran and chose not to enumerate.
        scheduler.schedule(0, listOf(TestNetworkConstraint()))

        assertEquals(listOf("schedule:$NETWORK_JOB_ID"), drain(1))
        assertEquals(1, enumerations.get(), "the second drain must skip the enumeration")
    }

    @Test
    fun `a registration re-arms the drain`() {
        val enumerations = countEnumerations(NETWORK_JOB_ID)
        recordCancels()
        recordSchedules()

        val scheduler = JobSchedulerScheduler(application)
        scheduler.cancelAllExcept(emptySet())
        assertEquals(listOf("cancel:$NETWORK_JOB_ID"), drain(1))

        scheduler.schedule(0, listOf(TestNetworkConstraint()))
        assertEquals(listOf("schedule:$NETWORK_JOB_ID"), drain(1))

        scheduler.cancelAllExcept(emptySet())
        assertEquals(listOf("cancel:$NETWORK_JOB_ID"), drain(1))
        assertEquals(2, enumerations.get(), "a new registration must make the next drain enumerate")
    }

    // -- Helpers --

    private fun recordCancels() {
        every { jobScheduler.cancel(any()) } answers { calls.add("cancel:${firstArg<Int>()}") }
    }

    private fun recordSchedules() {
        every { jobScheduler.getPendingJob(any()) } returns null
        every { jobScheduler.schedule(any()) } answers {
            calls.add("schedule:${firstArg<JobInfo>().id}")
            JobScheduler.RESULT_SUCCESS
        }
    }

    /** Counts the enumerations while always reporting one of our own jobs as pending. */
    private fun countEnumerations(pendingId: Int): AtomicInteger {
        val enumerations = AtomicInteger()
        every { jobScheduler.allPendingJobs } answers {
            enumerations.incrementAndGet()
            listOf(pendingJob(pendingId, OWN_SERVICE))
        }
        return enumerations
    }

    private fun pendingJob(id: Int, className: String): JobInfo {
        val info = mockk<JobInfo>(relaxed = true)
        every { info.id } returns id
        every { info.service } returns ComponentName(PACKAGE, className)
        return info
    }

    private fun drain(count: Int): List<String> = calls.drainInOrder(count, "binder call")

    private companion object {
        const val PACKAGE = "com.difft.android"

        val OWN_SERVICE: String = JobSchedulerScheduler.SystemService::class.java.name
        const val FOREIGN_SERVICE = "androidx.work.impl.background.systemjob.SystemJobService"

        const val FOREIGN_JOB_ID = 777
    }
}
