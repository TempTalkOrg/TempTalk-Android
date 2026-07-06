package com.difft.android.base.utils

import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages our app startup flow.
 *
 * Tasks must be registered before [execute] is called; additions made from inside
 * a running task body are silently dropped (the iteration takes a snapshot up front
 * to guard against [java.util.ConcurrentModificationException]).
 *
 * Tasks run with **continue-on-failure** semantics — a throw does NOT abort the
 * rest of the chain (see #863 for why this matters). Failures are reported to
 * [L] and Crashlytics; the goal is "guarantee no crash", not strict fail-fast.
 * If a task's failure should be fatal, handle it in the task body.
 *
 * All public methods must be called on the main thread.
 */
object AppStartup {

    private const val TAG = "AppStartup"
    private const val UI_WAIT_TIME = 500L
    private const val FAILSAFE_RENDER_TIME = 2500L

    private val blocking = mutableListOf<Task>()
    private val nonBlocking = mutableListOf<Task>()
    private val postRender = mutableListOf<Task>()

    private val outstandingCriticalRenderEvents = AtomicInteger(0)

    private var applicationStartTime: Long = 0
    private var renderStartTime: Long = 0
    private var renderEndTime: Long = 0

    // Coroutine scope for managing startup tasks
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Job for managing post-render timeout
    private var postRenderTimeoutJob: Job? = null
    
    // Job for managing background post-render task
    private var backgroundPostRenderJob: Job? = null

    /**
     * Records the application creation time.
     */
    fun onApplicationCreate() {
        applicationStartTime = System.currentTimeMillis()
        L.i { "[$TAG]Application creation started at: $applicationStartTime" }
    }

    /**
     * Schedules a task that must happen during app startup in a blocking fashion.
     * @param name Task name for logging and debugging
     * @param task The task to execute
     * @return This AppStartup instance for chaining
     */
    fun addBlocking(name: String, task: () -> Unit): AppStartup {
        blocking.add(Task(name, task))
        return this
    }

    /**
     * Schedules a task that should not block app startup, but should still happen as quickly as possible.
     * @param name Task name for logging and Crashlytics attribution
     * @param task The task to execute
     * @return This AppStartup instance for chaining
     */
    fun addNonBlocking(name: String, task: () -> Unit): AppStartup {
        nonBlocking.add(Task(name, task))
        return this
    }

    /**
     * Schedules a task that should only be executed after all critical UI has been rendered.
     * If no UI will be shown (i.e. the Application was created in the background),
     * this will simply happen a short delay after Application#onCreate().
     * @param name Task name for logging and Crashlytics attribution
     * @param task The task to execute
     * @return This AppStartup instance for chaining
     */
    fun addPostRender(name: String, task: () -> Unit): AppStartup {
        postRender.add(Task(name, task))
        return this
    }

    /**
     * Indicates a UI event critical to initial rendering has started.
     * This will delay tasks that were scheduled via addPostRender().
     * You MUST call onCriticalRenderEventEnd() for each invocation of this method.
     */
    fun onCriticalRenderEventStart() {
        if (outstandingCriticalRenderEvents.get() == 0 && postRender.isNotEmpty()) {
            L.i { "[$TAG]Received first critical render event" }
            renderStartTime = System.currentTimeMillis()

            // Cancel any existing timeout job and background post-render job
            postRenderTimeoutJob?.cancel()
            backgroundPostRenderJob?.cancel()
            
            // Start a new timeout job using coroutines
            postRenderTimeoutJob = startupScope.launch {
                delay(FAILSAFE_RENDER_TIME)
                L.w { "[$TAG]Reached the failsafe event for post-render! Either someone forgot to call onCriticalRenderEventEnd(), the activity was started while the phone was locked, or app start is taking a very long time." }
                executePostRender()
            }
        }

        outstandingCriticalRenderEvents.incrementAndGet()
    }

    /**
     * Indicates a UI event critical to initial rendering has ended.
     * Should only be paired with onCriticalRenderEventStart().
     */
    fun onCriticalRenderEventEnd() {
        if (outstandingCriticalRenderEvents.get() <= 0) {
            L.w { "[$TAG]Too many end events! onCriticalRenderEventStart/End was mismanaged." }
        }

        val currentCount = outstandingCriticalRenderEvents.decrementAndGet()

        if (currentCount == 0 && postRender.isNotEmpty()) {
            renderEndTime = System.currentTimeMillis()

            L.i { "[$TAG]First render has finished. Cold Start: ${renderEndTime - applicationStartTime} ms, Render Time: ${renderEndTime - renderStartTime} ms" }

            // Cancel the timeout job since render completed successfully
            postRenderTimeoutJob?.cancel()
            postRenderTimeoutJob = null
            backgroundPostRenderJob?.cancel()
            backgroundPostRenderJob = null
            
            executePostRender()
        }
    }

    fun execute() {
        val stopwatch = Stopwatch("init")

        executeBlockingTasks(stopwatch)
        executeNonBlockingTasks()

        stopwatch.split("schedule-non-blocking")
        stopwatch.stop(TAG)

        schedulePostRenderTasks()
    }

    private fun executeBlockingTasks(stopwatch: Stopwatch) {
        // Snapshot-before-iterate guards against ConcurrentModificationException
        // if a task body re-enters addBlocking (e.g. via a lazy ContentProvider).
        val tasks = blocking.toList()
        blocking.clear()
        tasks.forEach { task ->
            try {
                val taskStartTime = System.currentTimeMillis()
                task.runnable()
                val taskDuration = System.currentTimeMillis() - taskStartTime

                stopwatch.split(task.name)
                L.d { "[$TAG]Blocking task '${task.name}' completed in ${taskDuration}ms" }

                if (taskDuration > 100) {
                    L.w { "[$TAG]Blocking task '${task.name}' took ${taskDuration}ms - consider moving to non-blocking" }
                }

            } catch (e: Throwable) {
                reportTaskFailure(kind = "blocking", taskName = task.name, e = e)
            }
        }
    }

    private fun executeNonBlockingTasks() {
        val tasks = nonBlocking.toList()
        nonBlocking.clear()
        tasks.forEach { task ->
            startupScope.launch(Dispatchers.IO) {
                try {
                    val taskStartTime = System.currentTimeMillis()
                    task.runnable()
                    val taskDuration = System.currentTimeMillis() - taskStartTime

                    L.d { "[$TAG]Non-blocking task '${task.name}' completed in ${taskDuration}ms" }

                    if (taskDuration > 500) {
                        L.w { "[$TAG]Non-blocking task '${task.name}' took ${taskDuration}ms - consider optimization" }
                    }

                } catch (e: CancellationException) {
                    // Preserve structured-concurrency cancellation.
                    throw e
                } catch (e: Throwable) {
                    reportTaskFailure(kind = "non-blocking", taskName = task.name, e = e)
                }
            }
        }
    }

    private fun schedulePostRenderTasks() {
        backgroundPostRenderJob = startupScope.launch {
            delay(UI_WAIT_TIME)
            L.i { "[$TAG]Assuming the application has started in the background. Running post-render tasks." }
            executePostRender()
        }
    }

    private fun executePostRender() {
        val tasks = postRender.toList()
        postRender.clear()
        tasks.forEach { task ->
            startupScope.launch(Dispatchers.IO) {
                try {
                    val taskStartTime = System.currentTimeMillis()
                    task.runnable()
                    val taskDuration = System.currentTimeMillis() - taskStartTime

                    L.d { "[$TAG]Post-render task '${task.name}' completed in ${taskDuration}ms" }

                    if (taskDuration > 1000) {
                        L.w { "[$TAG]Post-render task '${task.name}' took ${taskDuration}ms - consider optimization" }
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    reportTaskFailure(kind = "post-render", taskName = task.name, e = e)
                }
            }
        }
    }

    /**
     * Two sinks: [L] for the local log file, Crashlytics for ops visibility. Each
     * call is independently wrapped so one failing sink does not break the others.
     * The goal is "guarantee no crash", not full log fidelity — `android.util.Log`
     * was intentionally excluded since it does not reach the local log file.
     *
     * The stack trace is inlined into the message via `stackTraceToString()` per
     * `.claude/rules/logging-standards.md` so it is always present in the file log
     * regardless of the Timber formatter's throwable-handling policy.
     */
    private fun reportTaskFailure(kind: String, taskName: String, e: Throwable) {
        val displayName = taskName.ifEmpty { "<anonymous>" }
        val label = "[$TAG] $kind task '$displayName' failed — continuing"

        runCatching { L.e { "$label: ${e.stackTraceToString()}" } }
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            runCatching { crashlytics.log(label) }
            runCatching { crashlytics.recordException(e) }
        }
    }

    /**
     * Represents a startup task with name and runnable.
     */
    private data class Task(
        val name: String,
        val runnable: () -> Unit
    )

    fun getApplicationStartTime(): Long {
        return applicationStartTime
    }

    /** Test-only: singleton state can leak across tests if [execute] is not reached. */
    @VisibleForTesting
    internal fun reset() {
        blocking.clear()
        nonBlocking.clear()
        postRender.clear()
        outstandingCriticalRenderEvents.set(0)
        applicationStartTime = 0L
        renderStartTime = 0L
        renderEndTime = 0L
        postRenderTimeoutJob?.cancel()
        postRenderTimeoutJob = null
        backgroundPostRenderJob?.cancel()
        backgroundPostRenderJob = null
        // Cancel anonymous IO children launched by executeNonBlockingTasks /
        // executePostRender — without this they survive teardown and can race
        // against the next test (e.g. CountDownLatch in non-blocking test).
        startupScope.coroutineContext[Job]?.children?.forEach { it.cancel() }
    }
}