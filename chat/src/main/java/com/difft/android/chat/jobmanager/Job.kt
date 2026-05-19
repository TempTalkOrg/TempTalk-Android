package com.difft.android.chat.jobmanager

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import java.util.UUID

/**
 * A durable unit of work.
 *
 * Jobs have [Parameters] that describe the conditions upon when you'd like them to run, how
 * often they should be retried, and how long they should be retried for.
 *
 * Never rely on a specific instance of this class being run. It can be created and destroyed as the
 * job is retried. State that you want to save is persisted to a [Data] object in
 * [serialize]. Your job is then recreated using a [Factory] that you register in
 * [JobManager.Configuration.Builder.setJobFactories], which is given the saved
 * [Data] bundle.
 */
abstract class Job(val parameters: Parameters) {

    var runAttempt: Int = 0
    var nextRunAttemptTime: Long = 0

    @Volatile
    var canceled: Boolean = false
        private set

    lateinit var context: Context

    val id: String
        get() = parameters.id

    /**
     * Should only be invoked by [JobController]
     */
    fun cancel() {
        this.canceled = true
    }

    @WorkerThread
    fun onSubmit() {
        L.i { JobLogger.format(this, "onSubmit()") }
        onAdded()
    }

    /**
     * @return True if your job has been marked as canceled while it was running, otherwise false.
     * If a job sees that it has been canceled, it should make a best-effort attempt at
     * stopping its work. This job will have [onFailure] called after [run]
     * has finished.
     */
    fun isCanceled(): Boolean = canceled

    /**
     * Called when the job is first submitted to the [JobManager].
     */
    @WorkerThread
    open fun onAdded() {
    }

    /**
     * Called after a job has run and its determined that a retry is required.
     */
    @WorkerThread
    open fun onRetry() {
    }

    /**
     * Serialize your job state so that it can be recreated in the future.
     */
    abstract fun serialize(): Data

    /**
     * Returns the key that can be used to find the relevant factory needed to create your job.
     */
    abstract fun getFactoryKey(): String

    /**
     * Called to do your actual work.
     */
    @WorkerThread
    abstract suspend fun run(): Result

    /**
     * Called when your job has completely failed and will not be run again.
     */
    @WorkerThread
    abstract fun onFailure()

    interface Factory<T : Job> {
        fun create(parameters: Parameters, data: Data): T
    }

    class Result private constructor(
        private val resultType: ResultType,
        private val runtimeException: RuntimeException?,
        private val backoffInterval: Long
    ) {

        @VisibleForTesting
        fun isSuccess(): Boolean = resultType == ResultType.SUCCESS

        @VisibleForTesting
        fun isRetry(): Boolean = resultType == ResultType.RETRY

        @VisibleForTesting
        fun isFailure(): Boolean = resultType == ResultType.FAILURE

        fun getException(): RuntimeException? = runtimeException

        fun getBackoffInterval(): Long = backoffInterval

        override fun toString(): String = when (resultType) {
            ResultType.SUCCESS, ResultType.RETRY -> resultType.toString()
            ResultType.FAILURE -> if (runtimeException == null) resultType.toString() else "FATAL_FAILURE"
        }

        private enum class ResultType {
            SUCCESS, FAILURE, RETRY
        }

        companion object {
            private const val INVALID_BACKOFF = -1L

            private val SUCCESS_INSTANCE = Result(ResultType.SUCCESS, null, INVALID_BACKOFF)
            private val FAILURE_INSTANCE = Result(ResultType.FAILURE, null, INVALID_BACKOFF)

            /** Job completed successfully. */
            fun success(): Result = SUCCESS_INSTANCE

            /**
             * Job did not complete successfully, but it can be retried later.
             *
             * @param backoffInterval How long to wait before retrying
             */
            fun retry(backoffInterval: Long): Result =
                Result(ResultType.RETRY, null, backoffInterval)

            /** Job did not complete successfully and should not be tried again. */
            fun failure(): Result = FAILURE_INSTANCE

            /** Same as [failure], except the app should also crash with the provided exception. */
            fun fatalFailure(runtimeException: RuntimeException): Result =
                Result(ResultType.FAILURE, runtimeException, INVALID_BACKOFF)
        }
    }

    class Parameters internal constructor(
        val id: String,
        val createTime: Long,
        val lifespan: Long,
        val maxAttempts: Int,
        val maxInstancesForFactory: Int,
        val maxInstancesForQueue: Int,
        val queue: String?,
        val constraintKeys: List<String>,
        val isMemoryOnly: Boolean
    ) {

        fun toBuilder(): Builder = Builder(
            id, createTime, lifespan, maxAttempts,
            maxInstancesForFactory, maxInstancesForQueue,
            queue, constraintKeys.toMutableList(), isMemoryOnly
        )

        class Builder internal constructor(
            private var id: String,
            private var createTime: Long,
            private var lifespan: Long,
            private var maxAttempts: Int,
            private var maxInstancesForFactory: Int,
            private var maxInstancesForQueue: Int,
            private var queue: String?,
            private var constraintKeys: MutableList<String>,
            private var memoryOnly: Boolean
        ) {
            constructor() : this(UUID.randomUUID().toString())

            constructor(id: String) : this(
                id, System.currentTimeMillis(), IMMORTAL, 1,
                UNLIMITED, UNLIMITED, null, mutableListOf(), false
            )

            /**
             * Should only be invoked by [JobController]
             */
            fun setCreateTime(createTime: Long): Builder {
                this.createTime = createTime
                return this
            }

            /**
             * Specify the amount of time this job is allowed to be retried. Defaults to [IMMORTAL].
             */
            fun setLifespan(lifespan: Long): Builder {
                this.lifespan = lifespan
                return this
            }

            /**
             * Specify the maximum number of times you want to attempt this job. Defaults to 1.
             */
            fun setMaxAttempts(maxAttempts: Int): Builder {
                this.maxAttempts = maxAttempts
                return this
            }

            /**
             * Specify the maximum number of instances you'd want of this job at any given time, as
             * determined by the job's factory key. If enqueueing this job would put it over that limit,
             * it will be ignored.
             *
             * Defaults to [UNLIMITED].
             */
            fun setMaxInstancesForFactory(maxInstancesForFactory: Int): Builder {
                this.maxInstancesForFactory = maxInstancesForFactory
                return this
            }

            /**
             * Specify the maximum number of instances you'd want of this job at any given time, as
             * determined by the job's factory key and queue key. If enqueueing this job would put it over
             * that limit, it will be ignored.
             *
             * Defaults to [UNLIMITED].
             */
            fun setMaxInstancesForQueue(maxInstancesForQueue: Int): Builder {
                this.maxInstancesForQueue = maxInstancesForQueue
                return this
            }

            /**
             * Specify a string representing a queue. All jobs within the same queue are run in a
             * serialized fashion -- one after the other, in order of insertion. Failure of a job earlier
             * in the queue has no impact on the execution of jobs later in the queue.
             */
            fun setQueue(queue: String?): Builder {
                this.queue = queue
                return this
            }

            /**
             * Add a constraint via the key that was used to register its factory in
             * [JobManager.Configuration].
             */
            fun addConstraint(constraintKey: String): Builder {
                constraintKeys.add(constraintKey)
                return this
            }

            /**
             * Set constraints via the key that was used to register its factory in
             * [JobManager.Configuration].
             */
            fun setConstraints(constraintKeys: List<String>): Builder {
                this.constraintKeys.clear()
                this.constraintKeys.addAll(constraintKeys)
                return this
            }

            /**
             * Specify whether or not you want this job to only live in memory. If true, this job will
             * *not* survive application death. This defaults to false, and should be used with care.
             *
             * Defaults to false.
             */
            fun setMemoryOnly(memoryOnly: Boolean): Builder {
                this.memoryOnly = memoryOnly
                return this
            }

            fun build(): Parameters = Parameters(
                id, createTime, lifespan, maxAttempts,
                maxInstancesForFactory, maxInstancesForQueue,
                queue, constraintKeys, memoryOnly
            )
        }

        companion object {
            const val MIGRATION_QUEUE_KEY = "MIGRATION"
            const val IMMORTAL = -1L
            const val UNLIMITED = -1
        }
    }
}
