package com.difft.android.chat.jobmanager.persistence

import androidx.annotation.WorkerThread

interface JobStorage {

    @WorkerThread
    fun init()

    @WorkerThread
    fun insertJobs(fullSpecs: List<FullSpec>)

    
    @WorkerThread
    fun insertJobsStrict(fullSpecs: List<FullSpec>): StrictWrite = StrictWrite.Failed("unsupported")

    @WorkerThread
    fun findDurableJob(id: String): DurableJob = DurableJob.ReadFailed("unsupported")

    
    @WorkerThread
    fun restoreDurableJob(found: DurableJob.Found): DurableRestore = DurableRestore.Unsupported

    @WorkerThread
    fun getJobSpec(id: String): JobSpec?

    @WorkerThread
    fun getAllJobSpecs(): List<JobSpec>

    @WorkerThread
    fun getPendingJobsWithNoDependenciesInCreatedOrder(currentTime: Long): List<JobSpec>

    @WorkerThread
    fun getJobsInQueue(queue: String): List<JobSpec>

    @WorkerThread
    fun getJobCountForFactory(factoryKey: String): Int

    @WorkerThread
    fun getJobCountForFactoryAndQueue(factoryKey: String, queueKey: String): Int

    @WorkerThread
    fun updateJobRunningState(id: String, isRunning: Boolean)

    @WorkerThread
    fun updateJobAfterRetry(
        id: String,
        isRunning: Boolean,
        runAttempt: Int,
        nextRunAttemptTime: Long,
        serializedData: String
    )

    @WorkerThread
    fun updateAllJobsToBePending()

    @WorkerThread
    fun updateJobs(jobSpecs: List<JobSpec>)

    @WorkerThread
    fun deleteJob(id: String)

    @WorkerThread
    fun deleteJobs(ids: List<String>)

    @WorkerThread
    fun getConstraintSpecs(jobId: String): List<ConstraintSpec>

    @WorkerThread
    fun getAllConstraintSpecs(): List<ConstraintSpec>
}

sealed interface StrictWrite {
    data object Committed : StrictWrite
    data object RejectedMemoryOnly : StrictWrite
    data class Failed(val reason: String) : StrictWrite
    data class Unknown(val reason: String) : StrictWrite
}

sealed interface DurableJob {
    data class Found(val spec: JobSpec, val constraints: List<ConstraintSpec> = emptyList()) : DurableJob
    data object Absent : DurableJob
    data class ReadFailed(val reason: String) : DurableJob
}

enum class DurableRestore { Restored, AlreadyCached, Unsupported }
