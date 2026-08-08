package com.difft.android.selector.thread

import android.os.Handler
import android.os.Looper
import androidx.annotation.CallSuper
import androidx.annotation.IntRange
import com.difft.android.base.log.lumberjack.L
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread pool utility. 1:1 Java→Kotlin port (issue #1077); concurrency model preserved
 * verbatim — no coroutine refactor, no structural split (both deferred to #1077 future work).
 */
@Suppress("LargeClass", "TooManyFunctions")
object PictureThreadUtils {

    private val HANDLER = Handler(Looper.getMainLooper())

    private val TYPE_PRIORITY_POOLS: MutableMap<Int, MutableMap<Int, ExecutorService>> = HashMap()

    internal val TASK_POOL_MAP: MutableMap<Task<*>, ExecutorService> = ConcurrentHashMap()

    private val CPU_COUNT = Runtime.getRuntime().availableProcessors()
    private val TIMER = Timer()

    private const val TYPE_SINGLE = -1
    private const val TYPE_CACHED = -2
    private const val TYPE_IO = -4
    private const val TYPE_CPU = -8

    private var sDeliver: Executor? = null

    /**
     * Return whether the thread is the main thread.
     */
    @JvmStatic
    fun isInUiThread(): Boolean {
        return Looper.myLooper() == Looper.getMainLooper()
    }

    @JvmStatic
    fun getMainHandler(): Handler {
        return HANDLER
    }

    @JvmStatic
    fun runOnUiThread(runnable: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            HANDLER.post(runnable)
        }
    }

    @JvmStatic
    fun runOnUiThreadDelayed(runnable: Runnable, delayMillis: Long) {
        HANDLER.postDelayed(runnable, delayMillis)
    }

    /**
     * Return a thread pool that reuses a fixed number of threads.
     */
    @JvmStatic
    fun getFixedPool(@IntRange(from = 1) size: Int): ExecutorService {
        return getPoolByTypeAndPriority(size)
    }

    /**
     * Return a thread pool that reuses a fixed number of threads.
     */
    @JvmStatic
    fun getFixedPool(
        @IntRange(from = 1) size: Int,
        @IntRange(from = 1, to = 10) priority: Int,
    ): ExecutorService {
        return getPoolByTypeAndPriority(size, priority)
    }

    /**
     * Return a thread pool that uses a single worker thread.
     */
    @JvmStatic
    fun getSinglePool(): ExecutorService {
        return getPoolByTypeAndPriority(TYPE_SINGLE)
    }

    /**
     * Return a thread pool that uses a single worker thread.
     */
    @JvmStatic
    fun getSinglePool(@IntRange(from = 1, to = 10) priority: Int): ExecutorService {
        return getPoolByTypeAndPriority(TYPE_SINGLE, priority)
    }

    /**
     * Return a thread pool that creates new threads as needed, but reuses previously
     * constructed threads when available.
     */
    @JvmStatic
    fun getCachedPool(): ExecutorService {
        return getPoolByTypeAndPriority(TYPE_CACHED)
    }

    /**
     * Return a thread pool that creates new threads as needed, but reuses previously
     * constructed threads when available.
     */
    @JvmStatic
    fun getCachedPool(@IntRange(from = 1, to = 10) priority: Int): ExecutorService {
        return getPoolByTypeAndPriority(TYPE_CACHED, priority)
    }

    /**
     * Return an IO thread pool.
     */
    @JvmStatic
    fun getIoPool(): ExecutorService {
        return getPoolByTypeAndPriority(TYPE_IO)
    }

    /**
     * Return an IO thread pool.
     */
    @JvmStatic
    fun getIoPool(@IntRange(from = 1, to = 10) priority: Int): ExecutorService {
        return getPoolByTypeAndPriority(TYPE_IO, priority)
    }

    /**
     * Return a cpu thread pool.
     */
    @JvmStatic
    fun getCpuPool(): ExecutorService {
        return getPoolByTypeAndPriority(TYPE_CPU)
    }

    /**
     * Return a cpu thread pool.
     */
    @JvmStatic
    fun getCpuPool(@IntRange(from = 1, to = 10) priority: Int): ExecutorService {
        return getPoolByTypeAndPriority(TYPE_CPU, priority)
    }

    @JvmStatic
    fun <T> executeByFixed(@IntRange(from = 1) size: Int, task: Task<T>) {
        execute(getPoolByTypeAndPriority(size), task)
    }

    @JvmStatic
    fun <T> executeByFixed(
        @IntRange(from = 1) size: Int,
        task: Task<T>,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        execute(getPoolByTypeAndPriority(size, priority), task)
    }

    @JvmStatic
    fun <T> executeByFixedWithDelay(
        @IntRange(from = 1) size: Int,
        task: Task<T>,
        delay: Long,
        unit: TimeUnit,
    ) {
        executeWithDelay(getPoolByTypeAndPriority(size), task, delay, unit)
    }

    @JvmStatic
    fun <T> executeByFixedWithDelay(
        @IntRange(from = 1) size: Int,
        task: Task<T>,
        delay: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeWithDelay(getPoolByTypeAndPriority(size, priority), task, delay, unit)
    }

    @JvmStatic
    fun <T> executeByFixedAtFixRate(
        @IntRange(from = 1) size: Int,
        task: Task<T>,
        period: Long,
        unit: TimeUnit,
    ) {
        executeAtFixedRate(getPoolByTypeAndPriority(size), task, 0, period, unit)
    }

    @JvmStatic
    fun <T> executeByFixedAtFixRate(
        @IntRange(from = 1) size: Int,
        task: Task<T>,
        period: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeAtFixedRate(getPoolByTypeAndPriority(size, priority), task, 0, period, unit)
    }

    @JvmStatic
    fun <T> executeByFixedAtFixRate(
        @IntRange(from = 1) size: Int,
        task: Task<T>,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
    ) {
        executeAtFixedRate(getPoolByTypeAndPriority(size), task, initialDelay, period, unit)
    }

    @JvmStatic
    fun <T> executeByFixedAtFixRate(
        @IntRange(from = 1) size: Int,
        task: Task<T>,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeAtFixedRate(getPoolByTypeAndPriority(size, priority), task, initialDelay, period, unit)
    }

    @JvmStatic
    fun <T> executeBySingle(task: Task<T>) {
        execute(getPoolByTypeAndPriority(TYPE_SINGLE), task)
    }

    @JvmStatic
    fun <T> executeBySingle(task: Task<T>, @IntRange(from = 1, to = 10) priority: Int) {
        execute(getPoolByTypeAndPriority(TYPE_SINGLE, priority), task)
    }

    @JvmStatic
    fun <T> executeBySingleWithDelay(task: Task<T>, delay: Long, unit: TimeUnit) {
        executeWithDelay(getPoolByTypeAndPriority(TYPE_SINGLE), task, delay, unit)
    }

    @JvmStatic
    fun <T> executeBySingleWithDelay(
        task: Task<T>,
        delay: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeWithDelay(getPoolByTypeAndPriority(TYPE_SINGLE, priority), task, delay, unit)
    }

    @JvmStatic
    fun <T> executeBySingleAtFixRate(task: Task<T>, period: Long, unit: TimeUnit) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_SINGLE), task, 0, period, unit)
    }

    @JvmStatic
    fun <T> executeBySingleAtFixRate(
        task: Task<T>,
        period: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_SINGLE, priority), task, 0, period, unit)
    }

    @JvmStatic
    fun <T> executeBySingleAtFixRate(task: Task<T>, initialDelay: Long, period: Long, unit: TimeUnit) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_SINGLE), task, initialDelay, period, unit)
    }

    @JvmStatic
    fun <T> executeBySingleAtFixRate(
        task: Task<T>,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_SINGLE, priority), task, initialDelay, period, unit)
    }

    @JvmStatic
    fun <T> executeByCached(task: Task<T>) {
        execute(getPoolByTypeAndPriority(TYPE_CACHED), task)
    }

    @JvmStatic
    fun <T> executeByCached(task: Task<T>, @IntRange(from = 1, to = 10) priority: Int) {
        execute(getPoolByTypeAndPriority(TYPE_CACHED, priority), task)
    }

    @JvmStatic
    fun <T> executeByCachedWithDelay(task: Task<T>, delay: Long, unit: TimeUnit) {
        executeWithDelay(getPoolByTypeAndPriority(TYPE_CACHED), task, delay, unit)
    }

    @JvmStatic
    fun <T> executeByCachedWithDelay(
        task: Task<T>,
        delay: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeWithDelay(getPoolByTypeAndPriority(TYPE_CACHED, priority), task, delay, unit)
    }

    @JvmStatic
    fun <T> executeByCachedAtFixRate(task: Task<T>, period: Long, unit: TimeUnit) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_CACHED), task, 0, period, unit)
    }

    @JvmStatic
    fun <T> executeByCachedAtFixRate(
        task: Task<T>,
        period: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_CACHED, priority), task, 0, period, unit)
    }

    @JvmStatic
    fun <T> executeByCachedAtFixRate(task: Task<T>, initialDelay: Long, period: Long, unit: TimeUnit) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_CACHED), task, initialDelay, period, unit)
    }

    @JvmStatic
    fun <T> executeByCachedAtFixRate(
        task: Task<T>,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_CACHED, priority), task, initialDelay, period, unit)
    }

    @JvmStatic
    fun <T> executeByIo(task: Task<T>) {
        execute(getPoolByTypeAndPriority(TYPE_IO), task)
    }

    @JvmStatic
    fun <T> executeByIo(task: Task<T>, @IntRange(from = 1, to = 10) priority: Int) {
        execute(getPoolByTypeAndPriority(TYPE_IO, priority), task)
    }

    @JvmStatic
    fun <T> executeByIoWithDelay(task: Task<T>, delay: Long, unit: TimeUnit) {
        executeWithDelay(getPoolByTypeAndPriority(TYPE_IO), task, delay, unit)
    }

    @JvmStatic
    fun <T> executeByIoWithDelay(
        task: Task<T>,
        delay: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeWithDelay(getPoolByTypeAndPriority(TYPE_IO, priority), task, delay, unit)
    }

    @JvmStatic
    fun <T> executeByIoAtFixRate(task: Task<T>, period: Long, unit: TimeUnit) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_IO), task, 0, period, unit)
    }

    @JvmStatic
    fun <T> executeByIoAtFixRate(
        task: Task<T>,
        period: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_IO, priority), task, 0, period, unit)
    }

    @JvmStatic
    fun <T> executeByIoAtFixRate(task: Task<T>, initialDelay: Long, period: Long, unit: TimeUnit) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_IO), task, initialDelay, period, unit)
    }

    @JvmStatic
    fun <T> executeByIoAtFixRate(
        task: Task<T>,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_IO, priority), task, initialDelay, period, unit)
    }

    @JvmStatic
    fun <T> executeByCpu(task: Task<T>) {
        execute(getPoolByTypeAndPriority(TYPE_CPU), task)
    }

    @JvmStatic
    fun <T> executeByCpu(task: Task<T>, @IntRange(from = 1, to = 10) priority: Int) {
        execute(getPoolByTypeAndPriority(TYPE_CPU, priority), task)
    }

    @JvmStatic
    fun <T> executeByCpuWithDelay(task: Task<T>, delay: Long, unit: TimeUnit) {
        executeWithDelay(getPoolByTypeAndPriority(TYPE_CPU), task, delay, unit)
    }

    @JvmStatic
    fun <T> executeByCpuWithDelay(
        task: Task<T>,
        delay: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeWithDelay(getPoolByTypeAndPriority(TYPE_CPU, priority), task, delay, unit)
    }

    @JvmStatic
    fun <T> executeByCpuAtFixRate(task: Task<T>, period: Long, unit: TimeUnit) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_CPU), task, 0, period, unit)
    }

    @JvmStatic
    fun <T> executeByCpuAtFixRate(
        task: Task<T>,
        period: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_CPU, priority), task, 0, period, unit)
    }

    @JvmStatic
    fun <T> executeByCpuAtFixRate(task: Task<T>, initialDelay: Long, period: Long, unit: TimeUnit) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_CPU), task, initialDelay, period, unit)
    }

    @JvmStatic
    fun <T> executeByCpuAtFixRate(
        task: Task<T>,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
        @IntRange(from = 1, to = 10) priority: Int,
    ) {
        executeAtFixedRate(getPoolByTypeAndPriority(TYPE_CPU, priority), task, initialDelay, period, unit)
    }

    @JvmStatic
    fun <T> executeByCustom(pool: ExecutorService, task: Task<T>) {
        execute(pool, task)
    }

    @JvmStatic
    fun <T> executeByCustomWithDelay(pool: ExecutorService, task: Task<T>, delay: Long, unit: TimeUnit) {
        executeWithDelay(pool, task, delay, unit)
    }

    @JvmStatic
    fun <T> executeByCustomAtFixRate(pool: ExecutorService, task: Task<T>, period: Long, unit: TimeUnit) {
        executeAtFixedRate(pool, task, 0, period, unit)
    }

    @JvmStatic
    fun <T> executeByCustomAtFixRate(
        pool: ExecutorService,
        task: Task<T>,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
    ) {
        executeAtFixedRate(pool, task, initialDelay, period, unit)
    }

    /**
     * Cancel the given task.
     */
    @JvmStatic
    fun cancel(task: Task<*>?) {
        if (task == null) return
        task.cancel()
    }

    /**
     * Cancel the given tasks.
     */
    @JvmStatic
    fun cancel(vararg tasks: Task<*>?) {
        if (tasks.isEmpty()) return
        for (task in tasks) {
            if (task == null) continue
            task.cancel()
        }
    }

    /**
     * Cancel the given tasks.
     */
    @JvmStatic
    fun cancel(tasks: List<Task<*>?>?) {
        if (tasks == null || tasks.size == 0) return
        for (task in tasks) {
            if (task == null) continue
            task.cancel()
        }
    }

    /**
     * Cancel the tasks in pool.
     */
    @JvmStatic
    fun cancel(executorService: ExecutorService?) {
        if (executorService is ThreadPoolExecutor4Util) {
            for (taskTaskInfoEntry in TASK_POOL_MAP.entries) {
                if (taskTaskInfoEntry.value === executorService) {
                    cancel(taskTaskInfoEntry.key)
                }
            }
        } else {
            L.e { "ThreadUtils" + "The executorService is not ThreadUtils's pool." }
        }
    }

    /**
     * Set the deliver.
     */
    @JvmStatic
    fun setDeliver(deliver: Executor?) {
        sDeliver = deliver
    }

    private fun <T> execute(pool: ExecutorService, task: Task<T>) {
        execute(pool, task, 0, 0, null)
    }

    private fun <T> executeWithDelay(pool: ExecutorService, task: Task<T>, delay: Long, unit: TimeUnit) {
        execute(pool, task, delay, 0, unit)
    }

    private fun <T> executeAtFixedRate(
        pool: ExecutorService,
        task: Task<T>,
        delay: Long,
        period: Long,
        unit: TimeUnit,
    ) {
        execute(pool, task, delay, period, unit)
    }

    private fun <T> execute(
        pool: ExecutorService,
        task: Task<T>,
        delay: Long,
        period: Long,
        unit: TimeUnit?,
    ) {
        synchronized(TASK_POOL_MAP) {
            if (TASK_POOL_MAP[task] != null) {
                L.e { "ThreadUtils" + "Task can only be executed once." }
                return
            }
            TASK_POOL_MAP[task] = pool
        }
        if (period == 0L) {
            if (delay == 0L) {
                pool.execute(task)
            } else {
                val timerTask = object : TimerTask() {
                    override fun run() {
                        pool.execute(task)
                    }
                }
                TIMER.schedule(timerTask, unit!!.toMillis(delay))
            }
        } else {
            task.setSchedule(true)
            val timerTask = object : TimerTask() {
                override fun run() {
                    pool.execute(task)
                }
            }
            TIMER.scheduleAtFixedRate(timerTask, unit!!.toMillis(delay), unit.toMillis(period))
        }
    }

    private fun getPoolByTypeAndPriority(type: Int, priority: Int = Thread.NORM_PRIORITY): ExecutorService {
        synchronized(TYPE_PRIORITY_POOLS) {
            val pool: ExecutorService
            val priorityPools = TYPE_PRIORITY_POOLS[type]
            if (priorityPools == null) {
                val newPools: MutableMap<Int, ExecutorService> = ConcurrentHashMap()
                pool = createPool(type, priority)
                newPools[priority] = pool
                TYPE_PRIORITY_POOLS[type] = newPools
            } else {
                var p = priorityPools[priority]
                if (p == null) {
                    p = createPool(type, priority)
                    priorityPools[priority] = p
                }
                pool = p
            }
            return pool
        }
    }

    private fun createPool(type: Int, priority: Int): ExecutorService {
        return when (type) {
            TYPE_SINGLE -> ThreadPoolExecutor4Util(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                LinkedBlockingQueue4Util(), UtilsThreadFactory("single", priority),
            )

            TYPE_CACHED -> ThreadPoolExecutor4Util(
                0, 128, 60L, TimeUnit.SECONDS,
                LinkedBlockingQueue4Util(true), UtilsThreadFactory("cached", priority),
            )

            TYPE_IO -> ThreadPoolExecutor4Util(
                2 * CPU_COUNT + 1, 2 * CPU_COUNT + 1, 30L, TimeUnit.SECONDS,
                LinkedBlockingQueue4Util(), UtilsThreadFactory("io", priority),
            )

            TYPE_CPU -> ThreadPoolExecutor4Util(
                CPU_COUNT + 1, 2 * CPU_COUNT + 1, 30L, TimeUnit.SECONDS,
                LinkedBlockingQueue4Util(true), UtilsThreadFactory("cpu", priority),
            )

            else -> ThreadPoolExecutor4Util(
                type, type, 0L, TimeUnit.MILLISECONDS,
                LinkedBlockingQueue4Util(), UtilsThreadFactory("fixed($type)", priority),
            )
        }
    }

    internal fun getGlobalDeliver(): Executor {
        if (sDeliver == null) {
            sDeliver = Executor { command -> runOnUiThread(command) }
        }
        return sDeliver!!
    }

    private class ThreadPoolExecutor4Util(
        corePoolSize: Int,
        maximumPoolSize: Int,
        keepAliveTime: Long,
        unit: TimeUnit,
        workQueue: LinkedBlockingQueue4Util,
        threadFactory: ThreadFactory,
    ) : ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory) {

        private val mSubmittedCount = AtomicInteger()
        private val mWorkQueue: LinkedBlockingQueue4Util = workQueue

        init {
            workQueue.mPool = this
        }

        override fun afterExecute(r: Runnable?, t: Throwable?) {
            mSubmittedCount.decrementAndGet()
            super.afterExecute(r, t)
        }

        override fun execute(command: Runnable) {
            if (this.isShutdown) return
            mSubmittedCount.incrementAndGet()
            try {
                super.execute(command)
            } catch (ignore: RejectedExecutionException) {
                L.e { "ThreadUtils" + "This will not happen!" }
                mWorkQueue.offer(command)
            } catch (t: Throwable) {
                mSubmittedCount.decrementAndGet()
            }
        }
    }

    private class LinkedBlockingQueue4Util : LinkedBlockingQueue<Runnable> {

        @Volatile
        internal var mPool: ThreadPoolExecutor4Util? = null

        private var mCapacity = Int.MAX_VALUE

        constructor() : super()

        constructor(isAddSubThreadFirstThenAddQueue: Boolean) : super() {
            if (isAddSubThreadFirstThenAddQueue) {
                mCapacity = 0
            }
        }

        constructor(capacity: Int) : super() {
            mCapacity = capacity
        }

        override fun offer(runnable: Runnable): Boolean {
            val pool = mPool
            if (mCapacity <= size && pool != null && pool.poolSize < pool.maximumPoolSize) {
                // create a non-core thread
                return false
            }
            return super.offer(runnable)
        }
    }

    private class UtilsThreadFactory(
        prefix: String,
        private val priority: Int,
        private val isDaemon: Boolean = false,
    ) : AtomicLong(), ThreadFactory {

        private val namePrefix: String = prefix + "-pool-" + POOL_NUMBER.getAndIncrement() + "-thread-"

        override fun toByte(): Byte = get().toByte()

        override fun toShort(): Short = get().toShort()

        override fun newThread(r: Runnable): Thread {
            val t = object : Thread(r, namePrefix + getAndIncrement()) {
                override fun run() {
                    try {
                        super.run()
                    } catch (t: Throwable) {
                        L.e(t) { "ThreadUtils Request threw uncaught throwable" }
                    }
                }
            }
            t.isDaemon = isDaemon
            t.setUncaughtExceptionHandler { thread, e ->
                L.e(e) { "[PictureThreadUtils] uncaught exception in thread " + thread.name }
            }
            t.priority = priority
            return t
        }

        companion object {
            private val POOL_NUMBER = AtomicInteger(1)
            private const val serialVersionUID = -9209200509960368598L
        }
    }

    abstract class SimpleTask<T> : Task<T>() {

        override fun onCancel() {
            L.e { "ThreadUtils" + "onCancel: " + Thread.currentThread() }
        }

        override fun onFail(t: Throwable) {
            L.e(t) { "ThreadUtils onFail" }
        }
    }

    abstract class Task<T> : Runnable {

        private val state = AtomicInteger(NEW)

        @Volatile
        private var isSchedule = false

        @Volatile
        private var runner: Thread? = null

        private var mTimer: Timer? = null
        private var mTimeoutMillis: Long = 0
        private var mTimeoutListener: OnTimeoutListener? = null

        private var deliver: Executor? = null

        @Throws(Throwable::class)
        abstract fun doInBackground(): T

        abstract fun onSuccess(result: T)

        abstract fun onCancel()

        abstract fun onFail(t: Throwable)

        override fun run() {
            if (isSchedule) {
                if (runner == null) {
                    if (!state.compareAndSet(NEW, RUNNING)) return
                    runner = Thread.currentThread()
                    if (mTimeoutListener != null) {
                        L.w { "ThreadUtils" + "Scheduled task doesn't support timeout." }
                    }
                } else {
                    if (state.get() != RUNNING) return
                }
            } else {
                if (!state.compareAndSet(NEW, RUNNING)) return
                runner = Thread.currentThread()
                if (mTimeoutListener != null) {
                    mTimer = Timer()
                    mTimer!!.schedule(object : TimerTask() {
                        override fun run() {
                            if (!isDone() && mTimeoutListener != null) {
                                timeout()
                                mTimeoutListener!!.onTimeout()
                                onDone()
                            }
                        }
                    }, mTimeoutMillis)
                }
            }
            try {
                val result = doInBackground()
                if (isSchedule) {
                    if (state.get() != RUNNING) return
                    getDeliver().execute { onSuccess(result) }
                } else {
                    if (!state.compareAndSet(RUNNING, COMPLETING)) return
                    getDeliver().execute {
                        onSuccess(result)
                        onDone()
                    }
                }
            } catch (ignore: InterruptedException) {
                state.compareAndSet(CANCELLED, INTERRUPTED)
            } catch (throwable: Throwable) {
                if (!state.compareAndSet(RUNNING, EXCEPTIONAL)) return
                getDeliver().execute {
                    onFail(throwable)
                    onDone()
                }
            }
        }

        fun cancel() {
            cancel(true)
        }

        fun cancel(mayInterruptIfRunning: Boolean) {
            synchronized(state) {
                if (state.get() > RUNNING) return
                state.set(CANCELLED)
            }
            if (mayInterruptIfRunning) {
                if (runner != null) {
                    runner!!.interrupt()
                }
            }

            getDeliver().execute {
                onCancel()
                onDone()
            }
        }

        private fun timeout() {
            synchronized(state) {
                if (state.get() > RUNNING) return
                state.set(TIMEOUT)
            }
            if (runner != null) {
                runner!!.interrupt()
            }
        }

        fun isCanceled(): Boolean {
            return state.get() >= CANCELLED
        }

        fun isDone(): Boolean {
            return state.get() > RUNNING
        }

        fun setDeliver(deliver: Executor): Task<T> {
            this.deliver = deliver
            return this
        }

        /**
         * Scheduled task doesn't support timeout.
         */
        fun setTimeout(timeoutMillis: Long, listener: OnTimeoutListener): Task<T> {
            mTimeoutMillis = timeoutMillis
            mTimeoutListener = listener
            return this
        }

        internal fun setSchedule(isSchedule: Boolean) {
            this.isSchedule = isSchedule
        }

        private fun getDeliver(): Executor {
            return deliver ?: getGlobalDeliver()
        }

        @CallSuper
        protected open fun onDone() {
            TASK_POOL_MAP.remove(this)
            if (mTimer != null) {
                mTimer!!.cancel()
                mTimer = null
                mTimeoutListener = null
            }
        }

        fun interface OnTimeoutListener {
            fun onTimeout()
        }

        companion object {
            private const val NEW = 0
            private const val RUNNING = 1
            private const val EXCEPTIONAL = 2
            private const val COMPLETING = 3
            private const val CANCELLED = 4
            private const val INTERRUPTED = 5
            private const val TIMEOUT = 6
        }
    }

    class SyncValue<T> {

        private val mLatch = CountDownLatch(1)
        private val mFlag = AtomicBoolean()
        private var mValue: T? = null

        fun setValue(value: T) {
            if (mFlag.compareAndSet(false, true)) {
                mValue = value
                mLatch.countDown()
            }
        }

        fun getValue(): T? {
            if (!mFlag.get()) {
                try {
                    mLatch.await()
                } catch (e: InterruptedException) {
                    L.w(e) { "[PictureThreadUtils] SyncValue getValue error:" }
                }
            }
            return mValue
        }

        fun getValue(timeout: Long, unit: TimeUnit, defaultValue: T?): T? {
            if (!mFlag.get()) {
                try {
                    mLatch.await(timeout, unit)
                } catch (e: InterruptedException) {
                    L.w(e) { "[PictureThreadUtils] SyncValue getValue timeout error:" }
                    return defaultValue
                }
            }
            return mValue
        }
    }
}
