package com.difft.android.chat.jobmanager

import android.app.Application
import android.app.job.JobScheduler
import com.difft.android.chat.jobmanager.impl.JsonDataSerializer
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.chat.jobs.DownloadAttachmentJob
import com.difft.android.chat.jobs.MessagingPauseJobHold
import com.difft.android.chat.jobs.PushTextSendJob
import com.difft.android.chat.jobs.TEST_LOGIN
import com.difft.android.chat.jobs.UntaggedSendJobSweep
import com.difft.android.chat.jobs.sendLogin
import com.difft.android.chat.jobs.testJobHold
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import com.difft.android.test.fakes.TestMessagingPause
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertTrue









@RunWith(RobolectricTestRunner::class)
class JobManagerLoginTagTest {

    private val application = mockk<Application>(relaxed = true)
    private val jobStorage = mockk<JobStorage>(relaxed = true)
    private val pending = mutableListOf<JobSpec>()
    private val ran = LinkedBlockingQueue<String>()
    private val failed = LinkedBlockingQueue<String>()

    
    private val sendChecks = LinkedBlockingQueue<String>()

    @Before
    fun setUp() {
        every { application.getSystemService(JobScheduler::class.java) } returns mockk(relaxed = true)
        every { jobStorage.getAllJobSpecs() } answers { synchronized(pending) { pending.toList() } }
        every { jobStorage.getConstraintSpecs(any()) } returns emptyList()
        every { jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any()) } answers {
            synchronized(pending) { pending.toList() }
        }
        every { jobStorage.getJobSpec(any()) } answers {
            synchronized(pending) { pending.firstOrNull { it.id == firstArg<String>() } }
        }
        every { jobStorage.deleteJob(any()) } answers {
            synchronized(pending) { pending.removeAll { it.id == firstArg<String>() } }
        }
    }

    @Test
    fun `a job is tagged with the login signed in when it is added`() {
        val inserted = slot<List<FullSpec>>()
        every { jobStorage.insertJobs(capture(inserted)) } returns Unit
        val jobManager = buildJobManager()

        jobManager.add(RecordingJob(Job.Parameters.Builder("new").build(), PushTextSendJob.KEY, ran, failed))
        jobManager.flush()

        val stored = JsonDataSerializer().deserialize(inserted.captured.single().jobSpec.serializedData)
        assertEquals(TEST_LOGIN, stored.getString(JobController.KEY_CREATOR_LOGIN))
    }

    @Test
    fun `a job added before the login loaded is stored only after the load, with the login it captured`() {
        val inserted = recordInserts()
        val login = MutableStateFlow<String?>(null)
        val firstLoad = CompletableDeferred<String?>()
        val jobManager = buildJobManager(hold = MessagingPauseJobHold(TestMessagingPause().open().gate, login, firstLoad))

        jobManager.add(RecordingJob(Job.Parameters.Builder("before-load").build(), PushTextSendJob.KEY, ran, failed))
        jobManager.flush()
        assertTrue(synchronized(inserted) { inserted.isEmpty() }, "stored before the login loaded")

        login.value = "login-a"
        firstLoad.complete("login-a")

        assertEquals("login-a", awaitInsertedTag(inserted, "before-load"))
    }

    
    @Test
    fun `a sign-in after a signed-out load does not claim a job added before the load`() = runBlocking(Dispatchers.Default) {
        val inserted = recordInserts()
        val login = MutableStateFlow<String?>(null)
        val firstLoad = CompletableDeferred<String?>()
        val jobManager = buildJobManager(hold = MessagingPauseJobHold(TestMessagingPause().open().gate, login, firstLoad))
        jobManager.beginJobLoop()

        jobManager.add(RecordingJob(Job.Parameters.Builder("before-load").build(), PushTextSendJob.KEY, ran, failed))
        jobManager.flush()
        
        login.value = "login-b"
        firstLoad.complete(null)

        assertNull(awaitInsertedTag(inserted, "before-load"))
        assertEquals("before-load:OTHER", sendChecks.poll(AWAIT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun `a job added after the load is tagged with the login signed in at the add`() {
        val inserted = recordInserts()
        val hold = MessagingPauseJobHold(TestMessagingPause().open().gate, MutableStateFlow("login-b"), CompletableDeferred("login-a"))
        val jobManager = buildJobManager(hold = hold)

        jobManager.add(RecordingJob(Job.Parameters.Builder("after-load").build(), PushTextSendJob.KEY, ran, failed))
        jobManager.flush()

        assertEquals("login-b", awaitInsertedTag(inserted, "after-load"))
    }

    
    private fun recordInserts(): MutableList<JobSpec> {
        val inserted = mutableListOf<JobSpec>()
        every { jobStorage.insertJobs(any()) } answers {
            val specs = firstArg<List<FullSpec>>().map { it.jobSpec }
            synchronized(inserted) { inserted += specs }
            synchronized(pending) { pending += specs }
        }
        return inserted
    }

    private fun awaitInsertedTag(inserted: List<JobSpec>, id: String): String? {
        val deadline = System.currentTimeMillis() + AWAIT_SECONDS * 1_000
        while (System.currentTimeMillis() < deadline) {
            val spec = synchronized(inserted) { inserted.firstOrNull { it.id == id } }
            if (spec != null) {
                return JsonDataSerializer().deserialize(spec.serializedData).getStringOrDefault(JobController.KEY_CREATOR_LOGIN, null)
            }
            Thread.sleep(10)
        }
        error("$id was never stored")
    }

    @Test
    fun `a login change drops every send but the new login's own`() = runBlocking(Dispatchers.Default) {
        synchronized(pending) {
            pending += spec("old-login", PushTextSendJob.KEY, "login-old")
            pending += spec("new-login", PushTextSendJob.KEY, TEST_LOGIN)
            pending += spec("untagged", PushTextSendJob.KEY, null)
            pending += spec("download", DownloadAttachmentJob.KEY, "login-old")
        }
        val jobManager = buildJobManager()

        MessagingPauseJobHold.Drop { jobManager }.dropAll("test", keepLogin = TEST_LOGIN)

        assertEquals(setOf("old-login", "untagged"), failed.toSet())
        assertEquals(setOf("new-login", "download"), synchronized(pending) { pending.map { it.id }.toSet() })
    }

    @Test
    fun `the startup task does not run over a job storage that failed to load`() = runBlocking(Dispatchers.Default) {
        every { jobStorage.init() } throws IllegalStateException("db")
        val appState = RecordingPreferencesDataStore()
        val jobManager = buildJobManager(startupTask = UntaggedSendJobSweep(appState))

        jobManager.flush()

        assertNull(appState.data.first()[AppStateKeys.UNTAGGED_SEND_JOBS_SWEPT], "the sweep must not be recorded as done")
    }

    @Test
    fun `no job is dispatched until the startup task completes`() = runBlocking(Dispatchers.Default) {
        synchronized(pending) { pending += spec("tagged-send", PushTextSendJob.KEY, TEST_LOGIN) }
        val release = CompletableDeferred<Unit>()
        val jobManager = buildJobManager(startupTask = { release.await() })

        jobManager.beginJobLoop()
        assertNull(ran.poll(HELD_MILLIS, TimeUnit.MILLISECONDS), "a job ran before the startup task finished")

        release.complete(Unit)
        assertEquals("tagged-send", ran.poll(AWAIT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun `the upgrade sweep fails untagged sends before anything runs and leaves the rest`() = runBlocking(Dispatchers.Default) {
        synchronized(pending) {
            pending += spec("untagged-send", PushTextSendJob.KEY, null)
            pending += spec("tagged-send", PushTextSendJob.KEY, TEST_LOGIN)
            pending += spec("untagged-download", DownloadAttachmentJob.KEY, null)
        }
        val jobManager = buildJobManager(startupTask = UntaggedSendJobSweep(RecordingPreferencesDataStore()))

        jobManager.beginJobLoop()
        val runs = setOf(ran.poll(AWAIT_SECONDS, TimeUnit.SECONDS), ran.poll(AWAIT_SECONDS, TimeUnit.SECONDS))

        assertEquals(setOf("tagged-send", "untagged-download"), runs)
        assertEquals(listOf("untagged-send"), failed.toList())
        assertNull(ran.poll(HELD_MILLIS, TimeUnit.MILLISECONDS), "an untagged send must never run")
    }

    @Test
    fun `a failing startup task does not stop the job loop`() = runBlocking(Dispatchers.Default) {
        synchronized(pending) { pending += spec("tagged-send", PushTextSendJob.KEY, TEST_LOGIN) }
        val jobManager = buildJobManager(startupTask = { error("disk") })

        jobManager.beginJobLoop()

        assertNotNull(ran.poll(AWAIT_SECONDS, TimeUnit.SECONDS))
        Unit
    }

    

    private fun buildJobManager(
        startupTask: JobStartupTask = JobStartupTask.NONE,
        hold: MessagingPauseJobHold = testJobHold(TestMessagingPause().open().gate),
    ): JobManager {
        val factories = listOf(PushTextSendJob.KEY, DownloadAttachmentJob.KEY).associateWith { key ->
            object : Job.Factory<Job> {
                override fun create(parameters: Job.Parameters, data: Data): Job = RecordingJob(parameters, key, ran, failed, sendChecks)
            }
        }
        val configuration = JobManager.Configuration.Builder()
            .setJobThreadCount(1)
            .setJobStorage(jobStorage)
            .setJobFactories(factories)
            .setConstraintFactories(emptyMap())
            .setConstraintObservers(emptyList())
            .setExecutionHold(hold)
            .setLoginTag(hold)
            .setStartupTask(startupTask)
            .build()
        return JobManager(application, configuration)
    }

    private fun spec(id: String, factoryKey: String, creatorLogin: String?) = JobSpec(
        id = id,
        factoryKey = factoryKey,
        queueKey = "queue-$id",
        createTime = 1L,
        nextRunAttemptTime = 0L,
        runAttempt = 0,
        maxAttempts = 3,
        lifespan = Job.Parameters.IMMORTAL,
        serializedData = JsonDataSerializer().serialize(
            creatorLogin?.let { Data.Builder().putString(JobController.KEY_CREATOR_LOGIN, it).build() } ?: Data.EMPTY
        ),
        isRunning = false,
        isMemoryOnly = false,
    )

    
    private class RecordingJob(
        parameters: Parameters,
        private val key: String,
        private val ran: LinkedBlockingQueue<String>,
        private val failed: LinkedBlockingQueue<String>,
        private val sendChecks: LinkedBlockingQueue<String> = LinkedBlockingQueue(),
    ) : Job(parameters) {
        override fun serialize(): Data = Data.EMPTY
        override fun getFactoryKey(): String = key
        override suspend fun run(): Result {
            sendChecks.add("$id:${sendLogin()}")
            ran.add(id)
            return Result.success()
        }
        override fun onFailure() {
            failed.add(id)
        }
    }

    private companion object {
        const val AWAIT_SECONDS = 5L
        const val HELD_MILLIS = 500L
    }
}
