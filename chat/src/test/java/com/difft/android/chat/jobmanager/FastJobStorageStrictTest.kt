package com.difft.android.chat.jobmanager

import com.difft.android.chat.jobmanager.persistence.DurableRestore
import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.DurableJob
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.StrictWrite
import com.difft.android.chat.jobs.FastJobStorage
import com.difft.android.chat.jobs.WcdbJobStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FastJobStorageStrictTest {
    private val wcdb = mockk<WcdbJobStorage>(relaxed = true)
    private val fast = FastJobStorage(wcdb)
    private val full = FullSpec(JobSpec("strict-id", "key", null, 1, 0, 0, 5, 10_000, "creator", false, false), emptyList())

    @Test fun `failed durable write cannot populate fast cache`() {
        every { wcdb.insertJobsStrict(any()) } returns StrictWrite.Failed("disk")
        assertEquals(StrictWrite.Failed("disk"), fast.insertJobsStrict(listOf(full)))
        assertNull(fast.getJobSpec(full.jobSpec.id))
    }

    @Test fun `unknown write plus failed query cannot populate cache or imply absence`() {
        every { wcdb.insertJobsStrict(any()) } returns StrictWrite.Unknown("commit")
        every { wcdb.findDurableJob(any()) } returns DurableJob.ReadFailed("io")
        assertEquals(StrictWrite.Unknown("commit"), fast.insertJobsStrict(listOf(full)))
        assertEquals(DurableJob.ReadFailed("io"), fast.findDurableJob(full.jobSpec.id))
        assertNull(fast.getJobSpec(full.jobSpec.id))
    }

    @Test fun `committed durable writes populate cache once`() {
        every { wcdb.insertJobsStrict(any()) } returns StrictWrite.Committed
        repeat(2) { assertEquals(StrictWrite.Committed, fast.insertJobsStrict(listOf(full))) }
        assertEquals(listOf(full.jobSpec), fast.getAllJobSpecs())
    }

    @Test fun `durable lookup bypasses optimistic legacy cache`() {
        every { wcdb.findDurableJob(any()) } returns DurableJob.Absent
        fast.insertJobs(listOf(full))
        assertEquals(full.jobSpec, fast.getJobSpec(full.jobSpec.id))
        assertEquals(DurableJob.Absent, fast.findDurableJob(full.jobSpec.id))
        verify(exactly = 1) { wcdb.findDurableJob(full.jobSpec.id) }
    }

    @Test fun `memory only strict insert never reaches wcdb`() {
        val memory = full.copy(jobSpec = full.jobSpec.copy(isMemoryOnly = true))
        assertEquals(StrictWrite.RejectedMemoryOnly, fast.insertJobsStrict(listOf(memory)))
        assertNull(fast.getJobSpec(memory.jobSpec.id))
        verify(exactly = 0) { wcdb.insertJobsStrict(any()) }
    }

    @Test fun `recovered durable row hydrates exact constraints and never downgrades running cache`() {
        val constraints = listOf(ConstraintSpec(full.jobSpec.id, "network", false))
        val found = DurableJob.Found(full.jobSpec, constraints)
        assertEquals(DurableRestore.Restored, fast.restoreDurableJob(found))
        assertEquals(full.jobSpec, fast.getJobSpec(full.jobSpec.id))
        assertEquals(constraints, fast.getConstraintSpecs(full.jobSpec.id))
        fast.updateJobRunningState(full.jobSpec.id, true)
        assertEquals(DurableRestore.AlreadyCached, fast.restoreDurableJob(found))
        assertEquals(true, fast.getJobSpec(full.jobSpec.id)?.isRunning)
    }

    @Test fun `legacy insertion still keeps its best effort cache behavior`() {
        fast.insertJobs(listOf(full))
        assertEquals(full.jobSpec, fast.getJobSpec(full.jobSpec.id))
        verify(exactly = 1) { wcdb.insertJobs(listOf(full)) }
        verify(exactly = 0) { wcdb.insertJobsStrict(any()) }
    }
}
