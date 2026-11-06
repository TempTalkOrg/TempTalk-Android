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
    private val persistentScheduler: Scheduler,
    private val debouncer: Debouncer,
    private val callback: Callback,
    private val executionHold: JobExecutionHold = JobExecutionHold.NONE,
    private val loginTag: JobLoginTag = JobLoginTag.NONE,
) : StoredJobs {

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

    /**
     * The ids of the persisted wake-ups that still have work to keep them alive for: the group id
     * derived from each durable spec's stored constraints, which is exactly the id
     * [JobSchedulerScheduler] registers that group's job under.
     *
     * The answer is per constraint group, not storage-wide: a constraint-less wake must not keep
     * re-running for specs that only wait on a network constraint. Those specs are owned by their
     * own NETWORK job, whose framework constraint already gates it, so claiming them here would
     * re-run the constraint-less job at every backoff for as long as the network stays down.
     *
     * Memory-only specs are excluded: nothing about them survives the process, so a wake-up the
     * framework brought back would find no trace of them and would have been re-run purely to
     * observe that.
     *
     * Within a group this reads [JobStorage.getAllJobSpecs] rather than the pending-jobs query:
     * running, pending and backoff-waiting specs all count, since the wake-up is exactly what has
     * to survive a process death mid-run or a long backoff.
     *
     * [cancelIdleWakeUps] is the single consumer: what this returns is exactly what stays
     * registered.
     */
    @WorkerThread
    internal fun pendingWakeUpIds(): Set<Int> = jobStorage.getAllJobSpecs()
        .filterNot { it.isMemoryOnly }
        .mapNotNullTo(mutableSetOf()) { spec ->
            runCatching {
                persistentJobId(
                    jobStorage.getConstraintSpecs(spec.id).map { constraintInstantiator.instantiate(it.factoryKey) }
                )
            }.onFailure {
                // An unknown or stale constraint key leaves the owning group unidentifiable;
                // treating it as no group's work is what keeps a wake-up from living on forever.
                L.w { "[JobScheduler] spec with unknown constraint key skipped id=${spec.id}: ${it.stackTraceToString()}" }
            }.getOrNull()
        }

    /**
     * Cancels the persisted wake-ups of every group that no longer has work, so the system stops
     * contacting the process while there is nothing for a wake-up to bring back. The only place a
     * persisted wake-up is removed.
     *
     * Called on every idle transition, which is what makes a per-wake reschedule decision
     * unnecessary: a group keeps its job exactly while it still has non-memory-only specs, and a
     * job is never retired in the window where a registration could dedup against it and be lost.
     *
     * The condition is [pendingWakeUpIds], not the weaker "no eligible job": that signal also
     * fires while jobs sit waiting for an unmet NetworkConstraint, and cancelling then would
     * destroy the very wake-up that brings them back when the network returns.
     */
    @WorkerThread
    internal fun cancelIdleWakeUps() {
        val keep = pendingWakeUpIds()
        L.i { "[JobScheduler] idle, keeping wake-ups ids=$keep" }
        persistentScheduler.cancelAllExcept(keep)
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

    /** Serialized with legacy submissions on the management dispatcher. Reuses the caller's ID. */
    @WorkerThread
    fun submitJobPersisted(job: Job): QueueAdmission {
        val result = admitPersistedJob(job, jobStorage, { exceedsMaximumInstances(job) },
            { buildFullSpec(job) }, { matchesCreator(it, job) })
        if (result.newlyCommitted) {
            try {
                jobTracker.onStateChange(job, JobTracker.JobState.PENDING)
            } catch (e: Exception) {
                L.w { "[JobManager] event=admitted_tracker_failed ${e.stackTraceToString()}" }
            }
            // The durable row remains accepted even when an optional hook or scheduler degrades.
            try {
                job.context = application
                job.onSubmit()
            } catch (e: Exception) {
                L.w { "[JobManager] event=admitted_hook_failed ${e.stackTraceToString()}" }
            }
        }
        if (result.needsScheduling) {
            signalJobAvailable()
            try {
                scheduleJob(job)
            } catch (e: Exception) {
                L.w { "[JobManager] event=admitted_schedule_failed ${e.stackTraceToString()}" }
            }
        }
        return result.admission
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

    /** Cancels every job [predicate] matches, as [cancelJob] does; returns how many matched. */
    @WorkerThread
    fun cancelMatching(predicate: (JobSpec) -> Boolean): Int {
        val ids = jobStorage.getAllJobSpecs().filter(predicate).map { it.id }
        ids.forEach(::cancelJob)
        return ids.size
    }

    @WorkerThread
    override fun failMatching(predicate: (spec: JobSpec, creatorLogin: String?) -> Boolean): Int =
        cancelMatching { spec -> predicate(spec, creatorLoginOf(spec)) }

    /** The stored [Job.creatorLogin]; null when untagged or unreadable (such a job never sends). */
    private fun creatorLoginOf(spec: JobSpec): String? = runCatching {
        dataSerializer.deserialize(spec.serializedData).getStringOrDefault(KEY_CREATOR_LOGIN, null)
    }.getOrNull()

    private fun matchesCreator(spec: JobSpec, job: Job): Boolean = try {
        dataSerializer.deserialize(spec.serializedData).getStringOrDefault(KEY_CREATOR_LOGIN, null) == job.creatorLogin
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    } catch (_: AssertionError) {
        false
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
        val serializedData = serialize(job)

        jobStorage.updateJobAfterRetry(job.id, false, nextRunAttempt, nextRunAttemptTime, serializedData)
        jobTracker.onStateChange(job, JobTracker.JobState.PENDING)

        val constraints = jobStorage.getConstraintSpecs(job.id)
            .map { constraintInstantiator.instantiate(it.factoryKey) }

        val delay = maxOf(0, nextRunAttemptTime - System.currentTimeMillis())

        L.i { JobLogger.format(job, "Scheduling a retry in $delay ms.") }
        scheduler.schedule(delay, constraints)

        signalJobAvailable()
    }

    /**
     * Puts a job that was not admitted back in the queue without consuming an attempt (#1382):
     * [Job.runAttempt] is kept and neither [Job.onRetry] nor [Job.onFailure] runs. The job's data
     * is re-serialized, so work it already finished (an uploaded attachment's pointer) is reused.
     * The next run time is a positive delay, as [onRetry] requires; the [executionHold] keeps a
     * send parked until messaging opens.
     */
    @WorkerThread
    fun onDeferred(job: Job) {
        val nextRunAttemptTime = System.currentTimeMillis() + DEFERRED_RUN_DELAY_MS
        val serializedData = serialize(job)

        jobStorage.updateJobAfterRetry(job.id, false, job.runAttempt, nextRunAttemptTime, serializedData)
        jobTracker.onStateChange(job, JobTracker.JobState.PENDING)

        val constraints = jobStorage.getConstraintSpecs(job.id)
            .map { constraintInstantiator.instantiate(it.factoryKey) }
        // The timer covers a hold released between the job's admission check and this write.
        scheduler.schedule(DEFERRED_RUN_DELAY_MS, constraints)

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
     * - Is not held by the [executionHold]
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
            serializedData = serialize(job),
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
            .filter { predicate.shouldRun(it) && !executionHold.holds(it) }

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

            job.creatorLogin = data.getStringOrDefault(KEY_CREATOR_LOGIN, null)
            job.loginTag = loginTag
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

    /** The job's own data plus its [Job.creatorLogin], so the tag survives every re-serialization. */
    private fun serialize(job: Job): String {
        val data = job.serialize()
        val tagged = job.creatorLogin?.let { data.buildUpon().putString(KEY_CREATOR_LOGIN, it).build() } ?: data
        return dataSerializer.serialize(tagged)
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

    internal companion object {
        /** Smallest positive delay; a deferred job is runnable again as soon as its hold lifts. */
        const val DEFERRED_RUN_DELAY_MS = 1L

        /** Reserved data key of [Job.creatorLogin]; no job uses it for its own data. */
        const val KEY_CREATOR_LOGIN = "__creator_login"
    }
}
