package com.difft.android.chat.jobs

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage

class FastJobStorage(private val jobStorage: WcdbJobStorage) : JobStorage {

    private val jobs: MutableList<JobSpec> = mutableListOf()
    private val constraintsByJobId: MutableMap<String, MutableList<ConstraintSpec>> = mutableMapOf()

    override fun init() {
        try {
            val jobSpecs = jobStorage.getAllJobSpecs()
            val constraintSpecs = jobStorage.getAllConstraintSpecs()
            L.i { "[JobStorage] jobSpecs=${jobSpecs.size}, constraintSpecs=${constraintSpecs.size}" }
            jobs.addAll(jobSpecs)

            for (constraintSpec in constraintSpecs) {
                val jobConstraints = constraintsByJobId.getOrPut(constraintSpec.jobSpecId) { mutableListOf() }
                jobConstraints.add(constraintSpec)
            }
        } catch (e: Exception) {
            // DB may be corrupt — degrade to an empty in-memory queue (DB rows untouched).
            // MainActivity's recovery gate drives the real recovery; after its restart,
            // JobManager.init{} re-runs against the recovered/fresh DB and reloads the queue.
            L.e { "[JobStorage] init failed (DB may be corrupt — queue empty until recovery restart): ${e.stackTraceToString()}" }
            jobs.clear()
            constraintsByJobId.clear()
        }
    }

    override fun insertJobs(fullSpecs: List<FullSpec>) {
        val durable = fullSpecs.filterNot { it.isMemoryOnly }
        if (durable.isNotEmpty()) {
            jobStorage.insertJobs(durable)
        }

        for (fullSpec in fullSpecs) {
            jobs.add(fullSpec.jobSpec)
            constraintsByJobId[fullSpec.jobSpec.id] = fullSpec.constraintSpecs.toMutableList()
        }
    }

    override fun getJobSpec(id: String): JobSpec? {
        return jobs.firstOrNull { it.id == id }
    }

    override fun getAllJobSpecs(): List<JobSpec> = jobs.toList()

    override fun getPendingJobsWithNoDependenciesInCreatedOrder(currentTime: Long): List<JobSpec> {
        val migrationJob = getMigrationJob()
        if (migrationJob != null) {
            return if (!migrationJob.isRunning && migrationJob.nextRunAttemptTime <= currentTime) {
                listOf(migrationJob)
            } else {
                emptyList()
            }
        }

        return jobs
            .groupBy { it.queueKey ?: it.id }
            .values
            .mapNotNull { group -> group.minByOrNull { it.createTime } }
            .filter { !it.isRunning && it.nextRunAttemptTime <= currentTime }
            .sortedBy { it.createTime }
    }

    override fun getJobsInQueue(queue: String): List<JobSpec> {
        return jobs
            .filter { queue == it.queueKey }
            .sortedBy { it.createTime }
    }

    private fun getMigrationJob(): JobSpec? {
        return jobs
            .filter { Job.Parameters.MIGRATION_QUEUE_KEY == it.queueKey }
            .filter { firstInQueue(it) }
            .minByOrNull { it.createTime }
    }

    private fun firstInQueue(job: JobSpec): Boolean {
        if (job.queueKey == null) {
            return true
        }

        return jobs
            .filter { it.queueKey == job.queueKey }
            .sortedBy { it.createTime }
            .first() == job
    }

    override fun getJobCountForFactory(factoryKey: String): Int {
        return jobs.count { it.factoryKey == factoryKey }
    }

    override fun getJobCountForFactoryAndQueue(factoryKey: String, queueKey: String): Int {
        return jobs.count { factoryKey == it.factoryKey && queueKey == it.queueKey }
    }

    override fun updateJobRunningState(id: String, isRunning: Boolean) {
        val job = getJobById(id)
        if (job == null || !job.isMemoryOnly) {
            jobStorage.updateJobRunningState(id, isRunning)
        }

        val iter = jobs.listIterator()
        while (iter.hasNext()) {
            val existing = iter.next()
            if (existing.id == id) {
                iter.set(existing.copy(isRunning = isRunning))
            }
        }
    }

    override fun updateJobAfterRetry(
        id: String,
        isRunning: Boolean,
        runAttempt: Int,
        nextRunAttemptTime: Long,
        serializedData: String
    ) {
        val job = getJobById(id)
        if (job == null || !job.isMemoryOnly) {
            jobStorage.updateJobAfterRetry(id, isRunning, runAttempt, nextRunAttemptTime, serializedData)
        }

        val iter = jobs.listIterator()
        while (iter.hasNext()) {
            val existing = iter.next()
            if (existing.id == id) {
                iter.set(
                    existing.copy(
                        nextRunAttemptTime = nextRunAttemptTime,
                        runAttempt = runAttempt,
                        serializedData = serializedData,
                        isRunning = isRunning
                    )
                )
            }
        }
    }

    override fun updateAllJobsToBePending() {
        jobStorage.updateAllJobsToBePending()

        val iter = jobs.listIterator()
        while (iter.hasNext()) {
            val existing = iter.next()
            iter.set(existing.copy(isRunning = false))
        }
    }

    override fun updateJobs(jobSpecs: List<JobSpec>) {
        val durable = jobSpecs.filter { update ->
            val found = getJobById(update.id)
            found == null || !found.isMemoryOnly
        }

        if (durable.isNotEmpty()) {
            jobStorage.updateJobs(durable)
        }

        val updates = jobSpecs.associateBy { it.id }
        val iter = jobs.listIterator()
        while (iter.hasNext()) {
            val existing = iter.next()
            updates[existing.id]?.let { iter.set(it) }
        }
    }

    override fun deleteJob(id: String) {
        deleteJobs(listOf(id))
    }

    override fun deleteJobs(ids: List<String>) {
        val durableIds = ids.filter { id ->
            val job = getJobById(id)
            job == null || !job.isMemoryOnly
        }

        if (durableIds.isNotEmpty()) {
            jobStorage.deleteJobs(durableIds)
        }

        val deleteIds = ids.toHashSet()
        jobs.removeAll { it.id in deleteIds }
        ids.forEach { constraintsByJobId.remove(it) }
    }

    override fun getConstraintSpecs(jobId: String): List<ConstraintSpec> {
        return constraintsByJobId[jobId] ?: emptyList()
    }

    override fun getAllConstraintSpecs(): List<ConstraintSpec> {
        return constraintsByJobId.values.flatten()
    }

    private fun getJobById(id: String): JobSpec? {
        val job = jobs.firstOrNull { it.id == id }
        if (job == null) {
            L.w { "[JobStorage] Was looking for job with ID JOB::$id, but it doesn't exist in memory!" }
        }
        return job
    }

}
