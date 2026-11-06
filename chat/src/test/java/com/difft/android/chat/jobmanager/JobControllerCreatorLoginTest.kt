package com.difft.android.chat.jobmanager

import android.app.Application
import com.difft.android.chat.jobmanager.impl.JsonDataSerializer
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.chat.util.Debouncer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [Job.creatorLogin] (#1382 A04) is stored with the job's data and restored with it, so it survives
 * submission, retries, deferrals and a process restart, which re-creates every job from storage.
 */
class JobControllerCreatorLoginTest {

    private val jobStorage = mockk<JobStorage>(relaxed = true)
    private val serializer = JsonDataSerializer()
    private val failed = mutableListOf<String>()
    private lateinit var controller: JobController

    @Before
    fun setUp() {
        every { jobStorage.getConstraintSpecs(any()) } returns emptyList()
        val factory = object : Job.Factory<Job> {
            override fun create(parameters: Job.Parameters, data: Data): Job =
                PayloadJob(parameters, data.getString(KEY_PAYLOAD).orEmpty(), failed)
        }
        controller = JobController(
            mockk<Application>(relaxed = true), jobStorage, JobInstantiator(mapOf(KEY to factory)),
            ConstraintInstantiator(emptyMap()), serializer, JobTracker(), mockk(relaxed = true),
            mockk(relaxed = true), mockk<Debouncer>(relaxed = true), mockk(relaxed = true),
            JobExecutionHold.NONE, JobLoginTag { CURRENT },
        )
    }

    @Test
    fun `the creator login survives storage and a restart`() = runTest {
        val stored = submit(PayloadJob(payload = "p1").apply { creatorLogin = CREATOR })

        val restored = restore(stored)

        assertEquals(CREATOR, restored.creatorLogin)
        assertEquals("p1", restored.payload, "the job's own data must be kept")
        assertEquals(CURRENT, restored.currentLogin(), "a restored job reads the signed-in login")
    }

    @Test
    fun `an untagged job stays untagged`() = runTest {
        val stored = submit(PayloadJob(payload = "p1"))

        assertNull(restore(stored).creatorLogin)
    }

    @Test
    fun `retry and deferral keep the creator login`() {
        val job = PayloadJob(payload = "p1").apply { creatorLogin = CREATOR }
        val data = mutableListOf<String>()
        every { jobStorage.updateJobAfterRetry(any(), any(), any(), any(), capture(data)) } returns Unit

        controller.onRetry(job, 1_000L)
        controller.onDeferred(job)

        assertEquals(2, data.size)
        data.forEach { assertEquals(CREATOR, serializer.deserialize(it).getString(JobController.KEY_CREATOR_LOGIN)) }
    }

    @Test
    fun `failMatching sees each stored creator login and fails only the matches`() {
        val tagged = submit(PayloadJob(id = "tagged", payload = "a").apply { creatorLogin = CREATOR })
        val untagged = submit(PayloadJob(id = "untagged", payload = "b"))
        every { jobStorage.getAllJobSpecs() } returns listOf(tagged, untagged)
        every { jobStorage.getJobSpec(any()) } answers { listOf(tagged, untagged).first { it.id == firstArg() } }
        val seen = mutableMapOf<String, String?>()

        val count = controller.failMatching { spec, creatorLogin ->
            seen[spec.id] = creatorLogin
            creatorLogin == null
        }

        assertEquals(mapOf("tagged" to CREATOR, "untagged" to null), seen)
        assertEquals(1, count)
        assertEquals(listOf("untagged"), failed)
        verify(exactly = 1) { jobStorage.deleteJob("untagged") }
        verify(exactly = 0) { jobStorage.deleteJob("tagged") }
    }

    // -- Helpers --

    private fun submit(job: PayloadJob): JobSpec {
        val inserted = slot<List<FullSpec>>()
        every { jobStorage.insertJobs(capture(inserted)) } returns Unit
        controller.submitJob(job)
        return inserted.captured.single().jobSpec
    }

    private suspend fun restore(spec: JobSpec): PayloadJob {
        every { jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any()) } returns listOf(spec)
        return controller.pullNextEligibleJobForExecution(JobPredicate.NONE, Dispatchers.Unconfined) as PayloadJob
    }

    private class PayloadJob(
        parameters: Parameters,
        val payload: String,
        private val failed: MutableList<String> = mutableListOf(),
    ) : Job(parameters) {
        constructor(id: String = "job", payload: String) : this(Parameters.Builder(id).build(), payload)

        override fun serialize(): Data = Data.Builder().putString(KEY_PAYLOAD, payload).build()
        override fun getFactoryKey(): String = KEY
        override suspend fun run(): Result = Result.success()
        override fun onFailure() {
            failed += id
        }
    }

    private companion object {
        const val KEY = "PayloadJob"
        const val KEY_PAYLOAD = "payload"
        const val CREATOR = "login-a"
        const val CURRENT = "login-current"
    }
}
