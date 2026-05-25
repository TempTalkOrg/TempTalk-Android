package com.difft.android.chat.jobmanager

import android.app.Application
import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.chat.util.Debouncer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Manages the queue of jobs. This is the only class that should write to [JobStorage] to
 * ensure consistency.
 */
internal class JobController(
    private val application: Application,
    private val jobStorage: JobStorage,
    private val jobInstantiator: JobInstantiator,
    private val constraintInstantiator: ConstraintInstantiator,
    private val dataSerializer: Data.Serializer,
    private val jobTracker: JobTracker,
    private val scheduler: Scheduler,
    private val debouncer: Debouncer,
    private val callback: Callback
) {

    private val runningJobs: MutableMap<String, Job> = mutableMapOf()

    /**
     * Runners waiting for a job to become available. Each runner registers a
     * [CompletableDeferred] here when no eligible job is found; producers complete
     * all of them via [signalJobAvailable] to wake every waiting runner.
     *
     * All access is serialized by the managementDispatcher (single-threaded).
     */
    private val waitingRunners = mutableListOf<CompletableDeferred<Unit>>()

    /**
     * Completes all waiting runner signals, waking them up to re-check for
     * eligible jobs. Must be called on managementDispatcher.
     */
    private fun signalJobAvailable() {
        val waiting = waitingRunners.toList()
        waitingRunners.clear()
        waiting.forEach { it.complete(Unit) }
    }

    @WorkerThread
    fun init() {
        jobStorage.updateAllJobsToBePending()
        signalJobAvailable()
    }

    fun wakeUp() {
        signalJobAvailable()
    }

    @WorkerThread
    fun submitJob(job: Job) {
        if (exceedsMaximumInstances(job)) {
            jobTracker.onStateChange(job, JobTracker.JobState.IGNORED)
            L.w {
                JobLogger.format(
                    job,
                    "Already at the max instance count. Factory limit: ${job.parameters.maxInstancesForFactory}" +
                        ", Queue limit: ${job.parameters.maxInstancesForQueue}. Skipping."
                )
            }
            return
        }

        insertJob(job)
        job.context = application
        job.onSubmit()
        signalJobAvailable()
        scheduleJob(job)
    }

    @WorkerThread
    fun cancelJob(id: String) {
        val runningJob = runningJobs[id]

        if (runningJob != null) {
            L.w { JobLogger.format(runningJob, "Canceling while running.") }
            runningJob.cancel()
        } else {
            val jobSpec = jobStorage.getJobSpec(id)

            if (jobSpec != null) {
                val job = createJob(jobSpec, jobStorage.getConstraintSpecs(id))
                if (job != null) {
                    L.w { JobLogger.format(job, "Canceling while inactive.") }
                    L.w { JobLogger.format(job, "Job failed.") }

                    job.cancel()
                    onFailure(job)
                    job.onFailure()
                }
            } else {
                L.w { "Tried to cancel JOB::$id, but it could not be found." }
            }
        }
    }

    @WorkerThread
    fun cancelAllInQueue(queue: String) {
        jobStorage.getJobsInQueue(queue).forEach { cancelJob(it.id) }
    }

    /**
     * Snapshot of pending+running jobs in [queue], sorted by createTime ascending.
     * Must be invoked on managementDispatcher; public entry is [JobManager.findJobsInQueue].
     */
    @WorkerThread
    internal fun findJobsInQueue(queue: String): List<JobSpec> = jobStorage.getJobsInQueue(queue)

    @WorkerThread
    fun onRetry(job: Job, backoffInterval: Long) {
        require(backoffInterval > 0) { "Invalid backoff interval! $backoffInterval" }

        val nextRunAttempt = job.runAttempt + 1
        val nextRunAttemptTime = System.currentTimeMillis() + backoffInterval
        val serializedData = dataSerializer.serialize(job.serialize())

        jobStorage.updateJobAfterRetry(job.id, false, nextRunAttempt, nextRunAttemptTime, serializedData)
        jobTracker.onStateChange(job, JobTracker.JobState.PENDING)

        val constraints = jobStorage.getConstraintSpecs(job.id)
            .map { constraintInstantiator.instantiate(it.factoryKey) }

        val delay = maxOf(0, nextRunAttemptTime - System.currentTimeMillis())

        L.i { JobLogger.format(job, "Scheduling a retry in $delay ms.") }
        scheduler.schedule(delay, constraints)

        signalJobAvailable()
    }

    fun onJobFinished(job: Job) {
        runningJobs.remove(job.id)
    }

    @WorkerThread
    fun onSuccess(job: Job) {
        jobStorage.deleteJob(job.id)
        jobTracker.onStateChange(job, JobTracker.JobState.SUCCESS)
        signalJobAvailable()
    }

    @WorkerThread
    fun onFailure(job: Job) {
        jobStorage.deleteJob(job.id)
        jobTracker.onStateChange(job, JobTracker.JobState.FAILURE)
        signalJobAvailable()
    }

    /**
     * Retrieves the next job that is eligible for execution. To be 'eligible' means that the job:
     * - Has no unmet constraints
     *
     * This method will suspend until a job is available.
     * When the job returned from this method has been run, you must call [onJobFinished].
     *
     * **Race-freedom invariants:**
     * 1. Check + register happens atomically on [managementDispatcher] (serial)
     * 2. Signal await happens OUTSIDE [managementDispatcher] (does not block management)
     * 3. Signal completion ([signalJobAvailable]) happens on [managementDispatcher]
     * 4. No signal can be lost between check and registration (atomic on serial dispatcher)
     */
    suspend fun pullNextEligibleJobForExecution(
        predicate: JobPredicate,
        managementDispatcher: CoroutineDispatcher
    ): Job {
        while (true) {
            // Atomically check for a job OR register a wait signal on the serial managementDispatcher.
            // Both the check and signal registration must happen in one withContext block to prevent
            // a missed wake-up between the two operations.
            val (foundJob, signal) = withContext(managementDispatcher) {
                val found = getNextEligibleJobForExecution(predicate)
                if (found != null) {
                    jobStorage.updateJobRunningState(found.id, true)
                    runningJobs[found.id] = found
                    jobTracker.onStateChange(found, JobTracker.JobState.RUNNING)
                    Pair(found, null)
                } else {
                    if (runningJobs.isEmpty()) debouncer.publish(callback::onEmpty)
                    val pending = CompletableDeferred<Unit>()
                    waitingRunners.add(pending)
                    Pair(null, pending)
                }
            }
            if (foundJob != null) return foundJob
            signal!!.await() // Suspend OUTSIDE managementDispatcher — does not block management
        }
    }

    /**
     * Retrieves a string representing the state of the job queue. Intended for debugging.
     */
    @WorkerThread
    fun getDebugInfo(): String {
        val jobs = jobStorage.getAllJobSpecs()
        val constraints = jobStorage.getAllConstraintSpecs()

        return buildString {
            append("-- Jobs\n")
            if (jobs.isNotEmpty()) {
                jobs.forEach { append(it).append('\n') }
            } else {
                append("None\n")
            }

            append("\n-- Constraints\n")
            if (constraints.isNotEmpty()) {
                constraints.forEach { append(it).append('\n') }
            } else {
                append("None\n")
            }
        }
    }

    @WorkerThread
    private fun exceedsMaximumInstances(job: Job): Boolean {
        val exceedsFactory = job.parameters.maxInstancesForFactory != Job.Parameters.UNLIMITED &&
            jobStorage.getJobCountForFactory(job.getFactoryKey()) >= job.parameters.maxInstancesForFactory

        if (exceedsFactory) {
            return true
        }

        return job.parameters.queue != null &&
            job.parameters.maxInstancesForQueue != Job.Parameters.UNLIMITED &&
            jobStorage.getJobCountForFactoryAndQueue(job.getFactoryKey(), job.parameters.queue!!) >= job.parameters.maxInstancesForQueue
    }

    @WorkerThread
    private fun insertJob(job: Job) {
        val fullSpec = buildFullSpec(job)
        jobStorage.insertJobs(listOf(fullSpec))
    }

    @WorkerThread
    private fun buildFullSpec(job: Job): FullSpec {
        job.runAttempt = 0

        val jobSpec = JobSpec(
            id = job.id,
            factoryKey = job.getFactoryKey(),
            queueKey = job.parameters.queue,
            createTime = System.currentTimeMillis(),
            nextRunAttemptTime = job.nextRunAttemptTime,
            runAttempt = job.runAttempt,
            maxAttempts = job.parameters.maxAttempts,
            lifespan = job.parameters.lifespan,
            serializedData = dataSerializer.serialize(job.serialize()),
            isRunning = false,
            isMemoryOnly = job.parameters.isMemoryOnly
        )

        val constraintSpecs = job.parameters.constraintKeys
            .map { key -> ConstraintSpec(jobSpec.id, key, jobSpec.isMemoryOnly) }

        return FullSpec(jobSpec, constraintSpecs)
    }

    @WorkerThread
    private fun scheduleJob(job: Job) {
        val constraints = job.parameters.constraintKeys.map { constraintInstantiator.instantiate(it) }
        scheduler.schedule(0, constraints)
    }

    @WorkerThread
    private fun getNextEligibleJobForExecution(predicate: JobPredicate): Job? {
        val jobSpecs = jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(System.currentTimeMillis())
            .filter { predicate.shouldRun(it) }

        for (jobSpec in jobSpecs) {
            val constraintSpecs = jobStorage.getConstraintSpecs(jobSpec.id)
            val constraints = constraintSpecs.map { constraintInstantiator.instantiate(it.factoryKey) }
            if (constraints.all { it.isMet() }) {
                return createJob(jobSpec, constraintSpecs)
            }
        }

        return null
    }

    private fun createJob(jobSpec: JobSpec, constraintSpecs: List<ConstraintSpec>): Job? {
        val parameters = buildJobParameters(jobSpec, constraintSpecs)

        try {
            val data = dataSerializer.deserialize(jobSpec.serializedData)
            val job = jobInstantiator.instantiate(jobSpec.factoryKey, parameters, data)

            job.runAttempt = jobSpec.runAttempt
            job.nextRunAttemptTime = jobSpec.nextRunAttemptTime
            job.context = application

            return job
        } catch (e: Throwable) {
            L.w { "Failed to instantiate job! Failing it without calling Job#onFailure. Deleting job ${jobSpec.id}." }
            jobStorage.deleteJob(jobSpec.id)
        }
        return null
    }

    private fun buildJobParameters(jobSpec: JobSpec, constraintSpecs: List<ConstraintSpec>): Job.Parameters {
        return Job.Parameters.Builder(jobSpec.id)
            .setCreateTime(jobSpec.createTime)
            .setLifespan(jobSpec.lifespan)
            .setMaxAttempts(jobSpec.maxAttempts)
            .setQueue(jobSpec.queueKey)
            .setConstraints(constraintSpecs.map { it.factoryKey })
            .build()
    }

    internal fun interface Callback {
        fun onEmpty()
    }
}
