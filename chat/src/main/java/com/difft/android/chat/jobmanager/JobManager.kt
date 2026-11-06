package com.difft.android.chat.jobmanager

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.dbKeyFailSoftExceptionHandler
import com.difft.android.chat.jobmanager.impl.JsonDataSerializer
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.chat.util.Debouncer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Allows the scheduling of durable jobs that will be run as early as possible.
 */
class JobManager(
    private val application: Application,
    private val configuration: Configuration
) : ConstraintObserver.Notifier {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("JobManager") + dbKeyFailSoftExceptionHandler)
    private val jobRunnerScope = CoroutineScope(SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.IO + CoroutineName("JobRunnerScope") + dbKeyFailSoftExceptionHandler)
    private val managementDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val jobController: JobController
    private val jobTracker: JobTracker = configuration.jobTracker

    private val initDeferred = CompletableDeferred<Unit>()

    init {
        // The scheduler that survives process death, kept separately so the drained-queue cancel
        // can target it without fanning out to the in-process timer the composite also carries.
        val persistentScheduler: Scheduler =
            if (Build.VERSION.SDK_INT < 26) AlarmManagerScheduler(application)
            else JobSchedulerScheduler(application)

        jobController = JobController(
            application,
            configuration.jobStorage,
            configuration.jobInstantiator,
            configuration.constraintFactories,
            configuration.dataSerializer,
            configuration.jobTracker,
            if (Build.VERSION.SDK_INT < 26) persistentScheduler
            else CompositeScheduler(InAppScheduler(this), persistentScheduler),
            persistentScheduler,
            Debouncer(500),
            this::onEmptyQueue,
            configuration.executionHold,
            configuration.loginTag,
        )

        // A lifted hold leaves the runners parked on an unchanged queue; wake them to re-scan.
        scope.launch {
            configuration.executionHold.released.collect {
                L.i { "[JobManager] event=hold_released" }
                wakeUp()
            }
        }

        scope.launch(managementDispatcher) {
            var storageReady = false
            try {
                configuration.jobStorage.init()
                jobController.init()
                storageReady = true
                configuration.constraintObservers.forEach { it.register(this@JobManager) }
                if (Build.VERSION.SDK_INT < 26) {
                    application.startService(Intent(application, KeepAliveService::class.java))
                }
            } catch (e: Exception) {
                L.e { "Failed to initialize JobManager: ${e.message}" }
            }
            try {
                // Before initDeferred completes, so no job is dispatched, added or cancelled first.
                // Not over a storage that failed to load: it would see no jobs and record its work done.
                if (storageReady) {
                    configuration.startupTask.run(jobController)
                } else {
                    L.w { "[JobManager] event=startup_task_skipped reason=storage_init_failed" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                L.e { "[JobManager] event=startup_task_failed ${e.stackTraceToString()}" }
            } finally {
                initDeferred.complete(Unit)
            }
        }
    }

    /**
     * Begins the execution of jobs.
     */
    fun beginJobLoop() {
        scope.launch(managementDispatcher) {
            initDeferred.await()

            for (i in 1..configuration.jobThreadCount) {
                JobRunner(application, i, jobController, JobPredicate.NONE, managementDispatcher)
                    .launchIn(jobRunnerScope)
            }

            for ((idx, predicate) in configuration.reservedJobRunners.withIndex()) {
                JobRunner(application, configuration.jobThreadCount + idx + 1, jobController, predicate, managementDispatcher)
                    .launchIn(jobRunnerScope)
            }

            jobController.wakeUp()
        }
    }

    /**
     * Convenience method for [addListener] that takes in an ID to filter on.
     */
    fun addListener(id: String, listener: JobTracker.JobListener) {
        jobTracker.addListener(JobIdFilter(id), listener)
    }

    /**
     * Add a listener to subscribe to job state updates. Listeners will be invoked on an arbitrary
     * background thread. You must eventually call [removeListener] to avoid
     * memory leaks.
     */
    fun addListener(filter: JobTracker.JobFilter, listener: JobTracker.JobListener) {
        jobTracker.addListener(filter, listener)
    }

    /**
     * Unsubscribe the provided listener from all job updates.
     */
    fun removeListener(listener: JobTracker.JobListener) {
        jobTracker.removeListener(listener)
    }

    /**
     * Enqueues a single job to be run. The job is tagged with the signed-in login here, on the
     * caller's thread, so it belongs to the login that created it (#1382 A04). Before the login
     * has loaded, the job is tagged with the login [JobLoginTag.awaitFirstLoad] captured and is
     * only stored after that load; a process death before it loses the job (accepted: the wait
     * is the bounded user-data warm-up at start).
     */
    fun add(job: Job) {
        val loginTag = configuration.loginTag
        // Loaded first: the login is published before it is marked loaded, so a loaded read sees it.
        val loadedAtAdd = loginTag.loaded()
        if (loadedAtAdd) job.creatorLogin = loginTag.current()
        scope.launch(managementDispatcher) {
            if (!loadedAtAdd) job.creatorLogin = loginTag.awaitFirstLoad()
            initDeferred.await()
            jobTracker.onStateChange(job, JobTracker.JobState.PENDING)
            jobController.submitJob(job)
            jobController.wakeUp()
        }
    }

    /** Awaitable durable admission; cancellation of the UI waiter does not cancel submission. */
    suspend fun enqueuePersisted(job: Job): QueueAdmission {
        val tag = configuration.loginTag
        val captured = if (tag.loaded()) CompletableDeferred(tag.current()) else scope.async { tag.awaitFirstLoad() }
        return enqueueWithCreator(job) { captured.await() }
    }

    /** Uses the login that authorized the whole operation, even after an earlier recipient awaited. */
    suspend fun enqueuePersisted(job: Job, creatorLogin: String): QueueAdmission =
        enqueueWithCreator(job) { creatorLogin }

    private suspend fun enqueueWithCreator(job: Job, creator: suspend () -> String?): QueueAdmission =
        scope.async(managementDispatcher) {
            val expected = creator()
            if (job.admissionCreatorCaptured && job.creatorLogin != expected) {
                return@async QueueAdmission.Unconfirmed(job.id, "creator_mismatch")
            }
            if (!job.admissionCreatorCaptured) {
                job.creatorLogin = expected
                job.admissionCreatorCaptured = true
            }
            initDeferred.await()
            try {
                jobController.submitJobPersisted(job)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[JobManager] event=admission_unconfirmed ${e.stackTraceToString()}" }
                QueueAdmission.Unconfirmed(job.id, "submission_exception")
            }
        }.await()

    /**
     * Attempts to cancel a job. This is best-effort and may not actually prevent a job from
     * completing if it was already running. If this job is running, this can only stop jobs that
     * bother to check [Job.isCanceled].
     *
     * When a job is canceled, [Job.onFailure] will be triggered at the earliest possible
     * moment.
     */
    fun cancel(id: String) {
        scope.launch(managementDispatcher) {
            initDeferred.await()
            jobController.cancelJob(id)
        }
    }

    /**
     * Cancels all jobs in the specified queue. See [cancel] for details.
     */
    fun cancelAllInQueue(queue: String) {
        scope.launch(managementDispatcher) {
            initDeferred.await()
            jobController.cancelAllInQueue(queue)
        }
    }

    /**
     * Cancels every pending or running job [predicate] matches (see [cancel]) and returns once the
     * cancels are applied, with how many matched. [predicate] is given the job's stored
     * [Job.creatorLogin].
     */
    suspend fun cancelMatching(predicate: (spec: JobSpec, creatorLogin: String?) -> Boolean): Int =
        withContext(managementDispatcher) {
            initDeferred.await()
            jobController.failMatching(predicate)
        }

    /**
     * Snapshot of pending+running jobs in [queue], sorted by createTime ascending.
     * The framework does NOT provide RMW atomicity across `find → cancel → add`; callers
     * that need it must layer their own serialization (e.g. a business-side Mutex).
     */
    suspend fun findJobsInQueue(queue: String): List<JobSpec> =
        withContext(managementDispatcher) {
            initDeferred.await()
            jobController.findJobsInQueue(queue)
        }

    /**
     * Retrieves a string representing the state of the job queue. Intended for debugging.
     */
    // @WorkerThread contract on getDebugInfo + flush: production callers are
    // test-only. runBlocking bridges async job completion to non-suspend API
    // for debug/diagnostic surface.
    @Suppress("BanRunBlockingOutsideTests")
    @WorkerThread
    fun getDebugInfo(): String {
        val deferred = CompletableDeferred<String>()

        scope.launch(managementDispatcher) {
            initDeferred.await()
            deferred.complete(jobController.getDebugInfo())
        }

        return runBlocking {
            withTimeoutOrNull(10_000) { deferred.await() }
                ?: "Timed out waiting for Job info."
        }
    }

    override fun onConstraintMet(reason: String) {
        L.d { "[JobManager] onConstraintMet($reason)" }
        wakeUp()
    }

    /**
     * Blocks until all pending operations are finished.
     */
    @Suppress("BanRunBlockingOutsideTests")
    @WorkerThread
    fun flush() {
        val deferred = CompletableDeferred<Unit>()

        scope.launch(managementDispatcher) {
            initDeferred.await()
            deferred.complete(Unit)
        }

        val flushed = runBlocking {
            withTimeoutOrNull(10_000) { deferred.await() } != null
        }
        if (flushed) {
            L.i { "Successfully flushed." }
        } else {
            L.w { "Flush timed out after 10 seconds." }
        }
    }

    /**
     * Pokes the system to take another pass at the job queue.
     */
    internal fun wakeUp() {
        scope.launch(managementDispatcher) {
            initDeferred.await()
            jobController.wakeUp()
        }
    }

    /**
     * Signals the job runners on behalf of a persisted wake-up the system started.
     *
     * Suspends until the signal is delivered, so the caller can hold its wake lock until then.
     */
    internal suspend fun wakeUpFromSystem() = withContext(managementDispatcher) {
        initDeferred.await()
        jobController.wakeUp()
    }

    private fun onEmptyQueue() {
        scope.launch(managementDispatcher) {
            initDeferred.await()
            jobController.cancelIdleWakeUps()
        }
    }

    class JobIdFilter(private val id: String) : JobTracker.JobFilter {
        override fun matches(job: Job): Boolean = id == job.id
    }

    class Configuration internal constructor(
        val jobThreadCount: Int,
        val jobInstantiator: JobInstantiator,
        val constraintFactories: ConstraintInstantiator,
        val constraintObservers: List<ConstraintObserver>,
        val dataSerializer: Data.Serializer,
        val jobStorage: JobStorage,
        val jobTracker: JobTracker,
        val reservedJobRunners: List<JobPredicate>,
        val executionHold: JobExecutionHold,
        val loginTag: JobLoginTag,
        val startupTask: JobStartupTask,
    ) {

        class Builder {
            private var jobThreadCount: Int = maxOf(2, minOf(Runtime.getRuntime().availableProcessors() - 1, 4))
            private var jobFactories: Map<String, Job.Factory<*>> = emptyMap()
            private var constraintFactories: Map<String, Constraint.Factory<*>> = emptyMap()
            private var constraintObservers: List<ConstraintObserver> = emptyList()
            private var dataSerializer: Data.Serializer = JsonDataSerializer()
            private var jobStorage: JobStorage? = null
            private var jobTracker: JobTracker = JobTracker()
            private val reservedJobRunners: MutableList<JobPredicate> = mutableListOf()
            private var executionHold: JobExecutionHold = JobExecutionHold.NONE
            private var loginTag: JobLoginTag = JobLoginTag.NONE
            private var startupTask: JobStartupTask = JobStartupTask.NONE

            fun setJobThreadCount(jobThreadCount: Int): Builder {
                this.jobThreadCount = jobThreadCount
                return this
            }

            fun addReservedJobRunner(predicate: JobPredicate): Builder {
                reservedJobRunners.add(predicate)
                return this
            }

            /** Jobs [hold] matches stay queued until it releases them. */
            fun setExecutionHold(hold: JobExecutionHold): Builder {
                this.executionHold = hold
                return this
            }

            /** The login every added job is tagged with; see [JobLoginTag]. */
            fun setLoginTag(loginTag: JobLoginTag): Builder {
                this.loginTag = loginTag
                return this
            }

            /** Runs before the first job is dispatched; see [JobStartupTask]. */
            fun setStartupTask(startupTask: JobStartupTask): Builder {
                this.startupTask = startupTask
                return this
            }

            fun setJobFactories(jobFactories: Map<String, Job.Factory<*>>): Builder {
                this.jobFactories = jobFactories
                return this
            }

            fun setConstraintFactories(constraintFactories: Map<String, Constraint.Factory<*>>): Builder {
                this.constraintFactories = constraintFactories
                return this
            }

            fun setConstraintObservers(constraintObservers: List<ConstraintObserver>): Builder {
                this.constraintObservers = constraintObservers
                return this
            }

            fun setDataSerializer(dataSerializer: Data.Serializer): Builder {
                this.dataSerializer = dataSerializer
                return this
            }

            fun setJobStorage(jobStorage: JobStorage): Builder {
                this.jobStorage = jobStorage
                return this
            }

            fun build(): Configuration {
                return Configuration(
                    jobThreadCount = jobThreadCount,
                    jobInstantiator = JobInstantiator(jobFactories),
                    constraintFactories = ConstraintInstantiator(constraintFactories),
                    constraintObservers = constraintObservers.toList(),
                    dataSerializer = dataSerializer,
                    jobStorage = requireNotNull(jobStorage) { "jobStorage must be set" },
                    jobTracker = jobTracker,
                    reservedJobRunners = reservedJobRunners.toList(),
                    executionHold = executionHold,
                    loginTag = loginTag,
                    startupTask = startupTask,
                )
            }
        }
    }

}
