package com.difft.android.chat.jobmanager

import android.app.Application
import android.app.job.JobScheduler
import com.difft.android.chat.jobmanager.impl.JsonDataSerializer
import com.difft.android.chat.jobmanager.persistence.DurableRestore
import com.difft.android.chat.jobmanager.persistence.DurableJob
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.chat.jobmanager.persistence.StrictWrite
import com.difft.android.chat.util.Debouncer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
class StrictQueueAdmissionTest {
    private val serializer = JsonDataSerializer()
    private val scheduler = mockk<Scheduler>(relaxed = true)
    private val storage = mockk<JobStorage>(relaxed = true)
    private val application = mockk<Application>(relaxed = true)

    private fun controller() = JobController(application, storage, JobInstantiator(emptyMap()),
        ConstraintInstantiator(emptyMap()), serializer, JobTracker(), scheduler,
        mockk(relaxed = true), mockk<Debouncer>(relaxed = true), mockk(relaxed = true))

    private fun absent() {
        every { storage.findDurableJob(any()) } returns DurableJob.Absent
        every { storage.insertJobsStrict(any()) } returns StrictWrite.Committed
        every { storage.restoreDurableJob(any()) } returns DurableRestore.AlreadyCached
        every { storage.getJobCountForFactory(any()) } returns 0
    }

    @Test fun `committed submission reports durable admission and preserves original ID`() {
        absent()
        val job = RecordingJob()
        assertEquals(QueueAdmission.Persisted(job.id), controller().submitJobPersisted(job))
        assertEquals(1, job.added)
        verify(exactly = 1) { storage.insertJobsStrict(match { it.single().jobSpec.id == job.id }) }
        verify(exactly = 0) { storage.insertJobs(any()) }
    }

    @Test fun `limit is rejected before write`() {
        absent()
        every { storage.getJobCountForFactory(any()) } returns 1
        val job = RecordingJob(maxInstances = 1)
        assertEquals(QueueAdmission.RejectedLimit, controller().submitJobPersisted(job))
        assertEquals(0, job.added)
        verify(exactly = 0) { storage.insertJobsStrict(any()) }
    }

    @Test fun `memory only is never reported persisted`() {
        val job = RecordingJob(memory = true)
        assertIs<QueueAdmission.FailedBeforePersist>(controller().submitJobPersisted(job))
        verify(exactly = 0) { storage.insertJobsStrict(any()) }
    }

    @Test fun `known write failure is reported before hooks`() {
        absent()
        every { storage.insertJobsStrict(any()) } returns StrictWrite.Failed("disk_full")
        val job = RecordingJob()
        assertEquals(QueueAdmission.FailedBeforePersist("disk_full"), controller().submitJobPersisted(job))
        assertEquals(0, job.added)
        verify(exactly = 0) { scheduler.schedule(any(), any()) }
    }

    @Test fun `explicit original login is preserved when admission starts after account switch`() = runBlocking {
        absent()
        every { application.getSystemService(JobScheduler::class.java) } returns mockk(relaxed = true)
        val inserted = CompletableDeferred<FullSpec>()
        every { storage.insertJobsStrict(any()) } answers {
            inserted.complete(firstArg<List<FullSpec>>().single())
            StrictWrite.Committed
        }
        val manager = JobManager(application, JobManager.Configuration.Builder()
            .setJobStorage(storage).setJobFactories(emptyMap()).setConstraintFactories(emptyMap())
            .setLoginTag(JobLoginTag { "new-login" }).build())
        assertIs<QueueAdmission.Persisted>(withTimeout(5_000) { manager.enqueuePersisted(RecordingJob(), "original-login") })
        assertEquals("original-login", serializer.deserialize(inserted.await().jobSpec.serializedData)
            .getString(JobController.KEY_CREATOR_LOGIN))
    }

    @Test fun `unknown commit plus failed durable read remains unconfirmed`() {
        absent()
        every { storage.findDurableJob(any()) } returnsMany listOf(DurableJob.Absent, DurableJob.ReadFailed("io"))
        every { storage.insertJobsStrict(any()) } returns StrictWrite.Unknown("commit")
        val job = RecordingJob()
        assertEquals(QueueAdmission.Unconfirmed(job.id, "io"), controller().submitJobPersisted(job))
        assertEquals(0, job.added)
    }

    @Test fun `unknown write resolved absent is safe to retry using same ID`() {
        absent()
        every { storage.insertJobsStrict(any()) } returns StrictWrite.Unknown("commit")
        val job = RecordingJob()
        assertIs<QueueAdmission.FailedBeforePersist>(controller().submitJobPersisted(job))
        verify(exactly = 2) { storage.findDurableJob(job.id) }
    }

    @Test fun `stored job from another creator cannot acknowledge this submission`() {
        absent()
        val job = RecordingJob().apply { creatorLogin = "login-new" }
        every { storage.findDurableJob(job.id) } returns DurableJob.Found(spec(job, "login-old"))
        assertEquals(QueueAdmission.Unconfirmed(job.id, "creator_mismatch"), controller().submitJobPersisted(job))
        verify(exactly = 0) { storage.insertJobsStrict(any()) }
    }

    @Test fun `commit survives hook and scheduler failure and retry does not insert twice`() {
        absent()
        every { scheduler.schedule(any(), any()) } throws IllegalStateException("scheduler")
        val job = RecordingJob(failHook = true).apply { creatorLogin = "login" }
        val controller = controller()
        assertIs<QueueAdmission.Persisted>(controller.submitJobPersisted(job))
        every { storage.findDurableJob(job.id) } returns DurableJob.Found(spec(job, "login"))
        assertIs<QueueAdmission.Persisted>(controller.submitJobPersisted(job))
        assertEquals(1, job.added)
        verify(exactly = 1) { storage.insertJobsStrict(any()) }
    }

    @Test fun `awaitable manager waits for initialization and captures login before waiting`() = runBlocking {
        absent()
        every { application.getSystemService(JobScheduler::class.java) } returns mockk(relaxed = true)
        val initEntered = CompletableDeferred<Unit>()
        val releaseInit = CompletableDeferred<Unit>()
        val currentLogin = AtomicReference("login-a")
        val stored = CompletableDeferred<FullSpec>()
        every { storage.insertJobsStrict(any()) } answers {
            stored.complete(firstArg<List<FullSpec>>().single())
            StrictWrite.Committed
        }
        val manager = JobManager(application, JobManager.Configuration.Builder()
            .setJobStorage(storage).setJobFactories(emptyMap()).setConstraintFactories(emptyMap())
            .setLoginTag(JobLoginTag { currentLogin.get() })
            .setStartupTask { initEntered.complete(Unit); releaseInit.await() }
            .build())
        withTimeout(5_000) { initEntered.await() }
        val admission = CoroutineScope(Dispatchers.Unconfined).async { manager.enqueuePersisted(RecordingJob()) }
        assertFalse(admission.isCompleted)
        currentLogin.set("login-b")
        releaseInit.complete(Unit)
        assertIs<QueueAdmission.Persisted>(withTimeout(5_000) { admission.await() })
        val data = serializer.deserialize(stored.await().jobSpec.serializedData)
        assertEquals("login-a", data.getString(JobController.KEY_CREATOR_LOGIN))
    }

    @Test fun `unknown commit and failed reads recover same durable ID into runnable cache without hooks`() {
        val backend = mockk<com.difft.android.chat.jobs.WcdbJobStorage>()
        val fast = com.difft.android.chat.jobs.FastJobStorage(backend)
        var saved: FullSpec? = null
        every { backend.insertJobsStrict(any()) } answers {
            saved = firstArg<List<FullSpec>>().single()
            StrictWrite.Unknown("commit")
        }
        var reads = 0
        every { backend.findDurableJob(any()) } answers {
            reads++
            when (reads) {
                1 -> DurableJob.Absent
                2, 3 -> DurableJob.ReadFailed("io")
                else -> DurableJob.Found(saved!!.jobSpec, saved!!.constraintSpecs)
            }
        }
        val controller = JobController(application, fast, JobInstantiator(emptyMap()),
            ConstraintInstantiator(emptyMap()), serializer, JobTracker(), scheduler,
            mockk(relaxed = true), mockk<Debouncer>(relaxed = true), mockk(relaxed = true))
        val job = RecordingJob().apply { creatorLogin = "login-a" }
        assertIs<QueueAdmission.Unconfirmed>(controller.submitJobPersisted(job))
        assertEquals(emptyList(), fast.getAllJobSpecs())
        assertIs<QueueAdmission.Persisted>(controller.submitJobPersisted(job))
        assertEquals(listOf(job.id), fast.getPendingJobsWithNoDependenciesInCreatedOrder(Long.MAX_VALUE).map { it.id })
        assertEquals(0, job.added)
        verify(exactly = 1) { backend.insertJobsStrict(any()) }
        verify(exactly = 1) { scheduler.schedule(any(), any()) }
    }

    @Test fun `legacy add remains fire and forget on legacy storage path`() {
        every { application.getSystemService(JobScheduler::class.java) } returns mockk(relaxed = true)
        val manager = JobManager(application, JobManager.Configuration.Builder()
            .setJobStorage(storage).setJobFactories(emptyMap()).setConstraintFactories(emptyMap()).build())
        manager.add(RecordingJob())
        manager.flush()
        verify(exactly = 1) { storage.insertJobs(any()) }
        verify(exactly = 0) { storage.insertJobsStrict(any()) }
    }

    private fun spec(job: RecordingJob, creator: String) = JobSpec(job.id, job.getFactoryKey(), null,
        1, 0, 0, 1, 10_000, serializer.serialize(Data.Builder().putString(JobController.KEY_CREATOR_LOGIN, creator).build()), false, false)

    private class RecordingJob(memory: Boolean = false, maxInstances: Int = Parameters.UNLIMITED,
        private val failHook: Boolean = false) : Job(Parameters.Builder()
        .setMemoryOnly(memory).setMaxInstancesForFactory(maxInstances).build()) {
        var added = 0
        override fun serialize(): Data = Data.Builder().build()
        override fun getFactoryKey() = "strict-test"
        override suspend fun run(): Result = Result.success()
        override fun onFailure() = Unit
        override fun onAdded() { added++; if (failHook) error("hook") }
    }
}
