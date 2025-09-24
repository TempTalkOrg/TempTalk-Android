package com.difft.android.base.utils

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages our app startup flow with improved performance and error handling.
 * Uses Kotlin object for thread-safe singleton pattern and coroutines for efficient concurrency.
 *
 * Note: All public methods should be called on the main thread.
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
     * @param task The task to execute
     * @return This AppStartup instance for chaining
     */
    fun addNonBlocking(task: () -> Unit): AppStartup {
        nonBlocking.add(Task("", task))
        return this
    }

    /**
     * Schedules a task that should only be executed after all critical UI has been rendered.
     * If no UI will be shown (i.e. the Application was created in the background),
     * this will simply happen a short delay after Application#onCreate().
     * @param task The task to execute
     * @return This AppStartup instance for chaining
     */
    fun addPostRender(task: () -> Unit): AppStartup {
        postRender.add(Task("", task))
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

    /**
     * Begins all pending task execution with improved error handling and performance monitoring.
     */
    fun execute() {
        val stopwatch = Stopwatch("init")

        try {
            // Execute blocking tasks with detailed logging
            executeBlockingTasks(stopwatch)

            // Schedule non-blocking tasks using coroutines
            executeNonBlockingTasks()

            stopwatch.split("schedule-non-blocking")
            stopwatch.stop(TAG)

            // Schedule post-render tasks
            schedulePostRenderTasks()

        } catch (e: Exception) {
            L.e(e) { "[$TAG]Error during app startup execution" }
            // Continue with non-blocking and post-render tasks even if blocking tasks fail
            executeNonBlockingTasks()
            schedulePostRenderTasks()
        }
    }

    private fun executeBlockingTasks(stopwatch: Stopwatch) {
        blocking.forEach { task ->
            try {
                val taskStartTime = System.currentTimeMillis()
                task.runnable()
                val taskDuration = System.currentTimeMillis() - taskStartTime

                stopwatch.split(task.name)
                L.d { "[$TAG]Blocking task '${task.name}' completed in ${taskDuration}ms" }

                if (taskDuration > 100) {
                    L.w { "[$TAG]Blocking task '${task.name}' took ${taskDuration}ms - consider moving to non-blocking" }
                }

            } catch (e: Exception) {
                L.e(e) { "[$TAG]Error executing blocking task: ${task.name}" }
                throw e // Re-throw to stop execution of remaining blocking tasks
            }
        }
        blocking.clear()
    }

    private fun executeNonBlockingTasks() {
        nonBlocking.forEach { task ->
            startupScope.launch(Dispatchers.IO) {
                try {
                    val taskStartTime = System.currentTimeMillis()
                    task.runnable()
                    val taskDuration = System.currentTimeMillis() - taskStartTime

                    L.d { "[$TAG]Non-blocking task completed in ${taskDuration}ms" }

                    if (taskDuration > 500) {
                        L.w { "[$TAG]Non-blocking task took ${taskDuration}ms - consider optimization" }
                    }

                } catch (e: Exception) {
                    L.e(e) { "[$TAG]Error executing non-blocking task" }
                }
            }
        }
        nonBlocking.clear()
    }

    private fun schedulePostRenderTasks() {
        backgroundPostRenderJob = startupScope.launch {
            delay(UI_WAIT_TIME)
            L.i { "[$TAG]Assuming the application has started in the background. Running post-render tasks." }
            executePostRender()
        }
    }

    private fun executePostRender() {
        postRender.forEach { task ->
            startupScope.launch(Dispatchers.IO) {
                try {
                    val taskStartTime = System.currentTimeMillis()
                    task.runnable()
                    val taskDuration = System.currentTimeMillis() - taskStartTime

                    L.d { "[$TAG]Post-render task completed in ${taskDuration}ms" }

                    if (taskDuration > 1000) {
                        L.w { "[$TAG]Post-render task took ${taskDuration}ms - consider optimization" }
                    }

                } catch (e: Exception) {
                    L.e(e) { "[$TAG]Error executing post-render task" }
                }
            }
        }
        postRender.clear()
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
} 