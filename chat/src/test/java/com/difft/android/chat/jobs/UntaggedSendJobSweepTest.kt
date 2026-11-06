package com.difft.android.chat.jobs

import com.difft.android.base.storage.AppStateKeys
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.StoredJobs
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one-time failure of send jobs stored without a creator login (#1382 A04): only untagged send
 * jobs fail, the completion flag is written after the sweep and only then, and a sweep cut short
 * runs again.
 */
class UntaggedSendJobSweepTest {

    private val appState = RecordingPreferencesDataStore()
    private val sweep = UntaggedSendJobSweep(appState)

    @Test
    fun `fails untagged send jobs only and sets the flag`() = runTest {
        val jobs = FakeStoredJobs(
            stored("untagged-text", PushTextSendJob.KEY, null),
            stored("untagged-setting", SettingSyncJob.KEY, null),
            stored("tagged-text", PushTextSendJob.KEY, TEST_LOGIN),
            stored("untagged-download", DownloadAttachmentJob.KEY, null),
        )

        sweep.run(jobs)

        assertEquals(listOf("untagged-text", "untagged-setting"), jobs.failed)
        assertEquals(listOf("tagged-text", "untagged-download"), jobs.remaining)
        assertEquals(true, appState.data.first()[AppStateKeys.UNTAGGED_SEND_JOBS_SWEPT])
    }

    @Test
    fun `does nothing once the flag is set`() = runTest {
        sweep.run(FakeStoredJobs())
        val jobs = FakeStoredJobs(stored("untagged-text", PushTextSendJob.KEY, null))

        sweep.run(jobs)

        assertTrue(jobs.failed.isEmpty(), "a finished sweep must not run again")
    }

    @Test
    fun `a sweep cut short leaves the flag unset and runs again at the next start`() = runTest {
        val jobs = FakeStoredJobs(
            stored("first", PushTextSendJob.KEY, null),
            stored("second", PushReactionSendJob.KEY, null),
            crashAfter = 1,
        )

        assertFailsWith<IllegalStateException> { sweep.run(jobs) }
        assertNull(appState.data.first()[AppStateKeys.UNTAGGED_SEND_JOBS_SWEPT], "the flag must wait for the whole sweep")

        sweep.run(jobs.restarted())

        assertEquals(listOf("first", "second"), jobs.failed)
        assertEquals(true, appState.data.first()[AppStateKeys.UNTAGGED_SEND_JOBS_SWEPT])
    }

    // -- Helpers --

    private class Stored(val spec: JobSpec, val creatorLogin: String?)

    /** Stored jobs that fail as the job controller does: one by one, each removed once failed. */
    private class FakeStoredJobs(vararg jobs: Stored, private var crashAfter: Int = Int.MAX_VALUE) : StoredJobs {
        private val stored = jobs.toMutableList()
        val failed = mutableListOf<String>()
        val remaining: List<String> get() = stored.map { it.spec.id }

        override fun failMatching(predicate: (spec: JobSpec, creatorLogin: String?) -> Boolean): Int {
            val matches = stored.filter { predicate(it.spec, it.creatorLogin) }
            matches.forEach {
                check(crashAfter-- > 0) { "process died mid-sweep" }
                stored.remove(it)
                failed += it.spec.id
            }
            return matches.size
        }

        fun restarted(): FakeStoredJobs = apply { crashAfter = Int.MAX_VALUE }
    }

    private fun stored(id: String, factoryKey: String, creatorLogin: String?) = Stored(
        JobSpec(
            id = id,
            factoryKey = factoryKey,
            queueKey = null,
            createTime = 1L,
            nextRunAttemptTime = 0L,
            runAttempt = 0,
            maxAttempts = 3,
            lifespan = Job.Parameters.IMMORTAL,
            serializedData = "{}",
            isRunning = false,
            isMemoryOnly = false,
        ),
        creatorLogin,
    )
}
