package com.difft.android.chat.jobmanager

import android.app.Application
import android.app.job.JobScheduler
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.chat.jobs.MessagingPauseJobHold
import com.difft.android.chat.jobs.PushTextSendJob
import com.difft.android.chat.jobs.testJobHold
import com.difft.android.test.fakes.TestMessagingPause
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull








@RunWith(RobolectricTestRunner::class)
class JobManagerMessagingPauseTest {

    private val application = mockk<Application>(relaxed = true)
    private val jobStorage = mockk<JobStorage>(relaxed = true)
    private val pending = mutableListOf<JobSpec>()

    @Before
    fun setUp() {
        
        
        
        every { application.getSystemService(JobScheduler::class.java) } returns mockk(relaxed = true)
        every { jobStorage.getAllJobSpecs() } answers { synchronized(pending) { pending.toList() } }
        every { jobStorage.getConstraintSpecs(any()) } returns emptyList()
        every { jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any()) } answers {
            synchronized(pending) { pending.toList() }
        }
        every { jobStorage.deleteJob(any()) } answers {
            synchronized(pending) { pending.removeAll { it.id == firstArg<String>() } }
        }
    }

    @Test
    fun `a send held while paused runs once messaging opens`() = runBlocking(Dispatchers.Default) {
        val pause = TestMessagingPause().open().pauseMessaging()
        synchronized(pending) { pending += sendSpec("held-send") }
        val ran = LinkedBlockingQueue<String>()
        val jobManager = buildJobManager(pause, onRun = { ran.add(it) })

        jobManager.beginJobLoop()
        assertNull(ran.poll(HELD_MILLIS, TimeUnit.MILLISECONDS), "a send must not run while paused")

        pause.resumeMessaging()

        assertNotNull(ran.poll(AWAIT_SECONDS, TimeUnit.SECONDS), "the held send never ran after resume")
        Unit
    }

    @Test
    fun `the held sends of an ended login fail and never run, other jobs stay`() = runBlocking(Dispatchers.Default) {
        val pause = TestMessagingPause().open().pauseMessaging()
        synchronized(pending) {
            pending += sendSpec("held-send")
            pending += sendSpec("other").copy(factoryKey = OTHER_KEY)
        }
        every { jobStorage.getJobSpec(any()) } answers {
            synchronized(pending) { pending.firstOrNull { it.id == firstArg<String>() } }
        }
        val ran = LinkedBlockingQueue<String>()
        val failed = LinkedBlockingQueue<String>()
        val jobManager = buildJobManager(pause, onRun = { ran.add(it) }, onFailure = { failed.add(it) })

        MessagingPauseJobHold.Drop { jobManager }.dropAll("test", keepLogin = null)

        assertEquals(listOf("held-send"), failed.toList())
        assertEquals(listOf("other"), synchronized(pending) { pending.map { it.id } })

        pause.resumeMessaging()
        jobManager.beginJobLoop()
        assertEquals("other", ran.poll(AWAIT_SECONDS, TimeUnit.SECONDS))
        assertNull(ran.poll(HELD_MILLIS, TimeUnit.MILLISECONDS), "a dropped send must never run")
    }

    

    private fun buildJobManager(
        pause: TestMessagingPause,
        onRun: (String) -> Unit = {},
        onFailure: (String) -> Unit = {},
    ): JobManager {
        val factories = listOf(PushTextSendJob.KEY, OTHER_KEY).associateWith { key ->
            object : Job.Factory<Job> {
                override fun create(parameters: Job.Parameters, data: Data): Job = RecordingJob(parameters, key, onRun, onFailure)
            }
        }
        val configuration = JobManager.Configuration.Builder()
            .setJobThreadCount(1)
            .setJobStorage(jobStorage)
            .setJobFactories(factories)
            .setConstraintFactories(emptyMap())
            .setConstraintObservers(emptyList())
            .setExecutionHold(testJobHold(pause.gate))
            .build()
        return JobManager(application, configuration)
    }

    private fun sendSpec(id: String) = JobSpec(
        id = id,
        factoryKey = PushTextSendJob.KEY,
        queueKey = "queue-$id",
        createTime = 1L,
        nextRunAttemptTime = 0L,
        runAttempt = 0,
        maxAttempts = 3,
        lifespan = Job.Parameters.IMMORTAL,
        serializedData = "{}",
        isRunning = false,
        isMemoryOnly = false,
    )

    private class RecordingJob(
        parameters: Parameters,
        private val key: String,
        private val onRun: (String) -> Unit,
        private val onFailed: (String) -> Unit,
    ) : Job(parameters) {
        override fun serialize(): Data = Data.EMPTY
        override fun getFactoryKey(): String = key
        override suspend fun run(): Result = Result.success().also { onRun(id) }
        override fun onFailure() = onFailed(id)
    }

    private companion object {
        const val AWAIT_SECONDS = 5L
        const val HELD_MILLIS = 500L

        
        const val OTHER_KEY = "NotASendJob"
    }
}
