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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    // All mutations run on the single-threaded managementDispatcher — no additional synchronization needed
    private val emptyQueueListeners: MutableSet<EmptyQueueListener> = mutableSetOf()

    private val initDeferred = CompletableDeferred<Unit>()

    init {
        jobController = JobController(
            application,
            configuration.jobStorage,
            configuration.jobInstantiator,
            configuration.constraintFactories,
            configuration.dataSerializer,
            configuration.jobTracker,
            if (Build.VERSION.SDK_INT < 26) AlarmManagerScheduler(application)
            else CompositeScheduler(InAppScheduler(this), JobSchedulerScheduler(application)),
            Debouncer(500),
            this::onEmptyQueue
        )

        scope.launch(managementDispatcher) {
            try {
                configuration.jobStorage.init()
                jobController.init()
                configuration.constraintObservers.forEach { it.register(this@JobManager) }
                if (Build.VERSION.SDK_INT < 26) {
                    application.startService(Intent(application, KeepAliveService::class.java))
                }
            } catch (e: Exception) {
                L.e { "Failed to initialize JobManager: ${e.message}" }
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
     * Enqueues a single job to be run.
     */
    fun add(job: Job) {
        scope.launch(managementDispatcher) {
            initDeferred.await()
            jobTracker.onStateChange(job, JobTracker.JobState.PENDING)
            jobController.submitJob(job)
            jobController.wakeUp()
        }
    }

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

    /**
     * Adds a listener that will be notified when the job queue has been drained.
     */
    internal fun addOnEmptyQueueListener(listener: EmptyQueueListener) {
        scope.launch(managementDispatcher) {
            initDeferred.await()
            emptyQueueListeners.add(listener)
        }
    }

    /**
     * Removes a listener that was added via [addOnEmptyQueueListener].
     */
    internal fun removeOnEmptyQueueListener(listener: EmptyQueueListener) {
        scope.launch(managementDispatcher) {
            initDeferred.await()
            emptyQueueListeners.remove(listener)
        }
    }

    override fun onConstraintMet(reason: String) {
        L.i { "onConstraintMet($reason)" }
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

    private fun onEmptyQueue() {
        scope.launch(managementDispatcher) {
            initDeferred.await()
            emptyQueueListeners.forEach { it.onQueueEmpty() }
        }
    }

    fun interface EmptyQueueListener {
        fun onQueueEmpty()
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
        val reservedJobRunners: List<JobPredicate>
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

            fun setJobThreadCount(jobThreadCount: Int): Builder {
                this.jobThreadCount = jobThreadCount
                return this
            }

            fun addReservedJobRunner(predicate: JobPredicate): Builder {
                reservedJobRunners.add(predicate)
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
                    reservedJobRunners = reservedJobRunners.toList()
                )
            }
        }
    }

}
