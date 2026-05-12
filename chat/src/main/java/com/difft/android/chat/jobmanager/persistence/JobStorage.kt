package com.difft.android.chat.jobmanager.persistence

import androidx.annotation.WorkerThread

interface JobStorage {

    @WorkerThread
    fun init()

    @WorkerThread
    fun insertJobs(fullSpecs: List<FullSpec>)

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
