package com.difft.android.chat.jobmanager

import com.difft.android.chat.jobmanager.persistence.JobSpec

/**
 * Work on the stored jobs that must finish before [JobManager] dispatches its first job, such as
 * the one-time failure of send jobs stored without a creator login (#1382 A04). Runs once per
 * process, on the management dispatcher, after the job storage is loaded and before any job is
 * added, cancelled or run.
 */
fun interface JobStartupTask {

    suspend fun run(jobs: StoredJobs)

    companion object {
        val NONE: JobStartupTask = JobStartupTask { }
    }
}

/** The stored jobs as a [JobStartupTask] sees them. */
fun interface StoredJobs {

    /**
     * Fails every stored job [predicate] matches as a cancel does: the job is removed and its
     * [Job.onFailure] runs. [predicate] is given the job's [Job.creatorLogin]. Returns how many
     * jobs matched.
     */
    fun failMatching(predicate: (spec: JobSpec, creatorLogin: String?) -> Boolean): Int
}
