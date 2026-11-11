package com.difft.android.chat.jobmanager

import android.content.Context
import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import java.io.Closeable
import java.util.UUID













abstract class Job(val parameters: Parameters) {

    var runAttempt: Int = 0
    var nextRunAttemptTime: Long = 0

    @Volatile
    var canceled: Boolean = false
        private set

    lateinit var context: Context

    val id: String
        get() = parameters.id

    



    internal var admissionCreatorCaptured: Boolean = false

    var creatorLogin: String? = null
        internal set

    
    internal var loginTag: JobLoginTag = JobLoginTag.NONE

    
    internal fun currentLogin(): String? = loginTag.current()

    
    private val runResources = mutableListOf<Closeable>()

    


    fun cancel() {
        this.canceled = true
    }

    @WorkerThread
    fun onSubmit() {
        L.i { JobLogger.format(this, "onSubmit()") }
        onAdded()
    }

    





    fun isCanceled(): Boolean = canceled

    


    @WorkerThread
    open fun onAdded() {
    }

    


    @WorkerThread
    open fun onRetry() {
    }

    


    abstract fun serialize(): Data

    


    abstract fun getFactoryKey(): String

    


    @WorkerThread
    abstract suspend fun run(): Result

    




    protected fun closeWhenRunEnds(resource: Closeable) {
        synchronized(runResources) { runResources += resource }
    }

    
    internal fun closeRunResources() {
        val resources = synchronized(runResources) { runResources.toList().also { runResources.clear() } }
        resources.forEach { resource ->
            try {
                resource.close()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                L.e { "[JobRunner] event=run_resource_close_failed job=$id ${e.stackTraceToString()}" }
            }
        }
    }

    


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

        fun isSuccess(): Boolean = resultType == ResultType.SUCCESS

        fun isRetry(): Boolean = resultType == ResultType.RETRY

        fun isFailure(): Boolean = resultType == ResultType.FAILURE

        fun isDeferred(): Boolean = resultType == ResultType.DEFERRED

        fun getException(): RuntimeException? = runtimeException

        fun getBackoffInterval(): Long = backoffInterval

        override fun toString(): String = when (resultType) {
            ResultType.SUCCESS, ResultType.RETRY, ResultType.DEFERRED -> resultType.toString()
            ResultType.FAILURE -> if (runtimeException == null) resultType.toString() else "FATAL_FAILURE"
        }

        private enum class ResultType {
            SUCCESS, FAILURE, RETRY, DEFERRED
        }

        companion object {
            private const val INVALID_BACKOFF = -1L

            private val SUCCESS_INSTANCE = Result(ResultType.SUCCESS, null, INVALID_BACKOFF)
            private val FAILURE_INSTANCE = Result(ResultType.FAILURE, null, INVALID_BACKOFF)
            private val DEFERRED_INSTANCE = Result(ResultType.DEFERRED, null, INVALID_BACKOFF)

            
            fun success(): Result = SUCCESS_INSTANCE

            




            fun retry(backoffInterval: Long): Result =
                Result(ResultType.RETRY, null, backoffInterval)

            




            fun deferred(): Result = DEFERRED_INSTANCE

            
            fun failure(): Result = FAILURE_INSTANCE

            
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

            


            fun setCreateTime(createTime: Long): Builder {
                this.createTime = createTime
                return this
            }

            


            fun setLifespan(lifespan: Long): Builder {
                this.lifespan = lifespan
                return this
            }

            


            fun setMaxAttempts(maxAttempts: Int): Builder {
                this.maxAttempts = maxAttempts
                return this
            }

            






            fun setMaxInstancesForFactory(maxInstancesForFactory: Int): Builder {
                this.maxInstancesForFactory = maxInstancesForFactory
                return this
            }

            






            fun setMaxInstancesForQueue(maxInstancesForQueue: Int): Builder {
                this.maxInstancesForQueue = maxInstancesForQueue
                return this
            }

            




            fun setQueue(queue: String?): Builder {
                this.queue = queue
                return this
            }

            



            fun addConstraint(constraintKey: String): Builder {
                constraintKeys.add(constraintKey)
                return this
            }

            



            fun setConstraints(constraintKeys: List<String>): Builder {
                this.constraintKeys.clear()
                this.constraintKeys.addAll(constraintKeys)
                return this
            }

            





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
