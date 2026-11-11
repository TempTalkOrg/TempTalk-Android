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

    


    fun addListener(id: String, listener: JobTracker.JobListener) {
        jobTracker.addListener(JobIdFilter(id), listener)
    }

    




    fun addListener(filter: JobTracker.JobFilter, listener: JobTracker.JobListener) {
        jobTracker.addListener(filter, listener)
    }

    


    fun removeListener(listener: JobTracker.JobListener) {
        jobTracker.removeListener(listener)
    }

    






    fun add(job: Job) {
        val loginTag = configuration.loginTag
        
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

    
    suspend fun enqueuePersisted(job: Job): QueueAdmission {
        val tag = configuration.loginTag
        val captured = if (tag.loaded()) CompletableDeferred(tag.current()) else scope.async { tag.awaitFirstLoad() }
        return enqueueWithCreator(job) { captured.await() }
    }

    
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

    







    fun cancel(id: String) {
        scope.launch(managementDispatcher) {
            initDeferred.await()
            jobController.cancelJob(id)
        }
    }

    


    fun cancelAllInQueue(queue: String) {
        scope.launch(managementDispatcher) {
            initDeferred.await()
            jobController.cancelAllInQueue(queue)
        }
    }

    




    suspend fun cancelMatching(predicate: (spec: JobSpec, creatorLogin: String?) -> Boolean): Int =
        withContext(managementDispatcher) {
            initDeferred.await()
            jobController.failMatching(predicate)
        }

    




    suspend fun findJobsInQueue(queue: String): List<JobSpec> =
        withContext(managementDispatcher) {
            initDeferred.await()
            jobController.findJobsInQueue(queue)
        }

    


    
    
    
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

    


    internal fun wakeUp() {
        scope.launch(managementDispatcher) {
            initDeferred.await()
            jobController.wakeUp()
        }
    }

    




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

            
            fun setExecutionHold(hold: JobExecutionHold): Builder {
                this.executionHold = hold
                return this
            }

            
            fun setLoginTag(loginTag: JobLoginTag): Builder {
                this.loginTag = loginTag
                return this
            }

            
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
