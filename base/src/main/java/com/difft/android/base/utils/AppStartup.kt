package com.difft.android.base.utils

import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger















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

    
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    
    private var postRenderTimeoutJob: Job? = null
    
    
    private var backgroundPostRenderJob: Job? = null

    


    fun onApplicationCreate() {
        applicationStartTime = System.currentTimeMillis()
        L.i { "[$TAG]Application creation started at: $applicationStartTime" }
    }

    





    fun addBlocking(name: String, task: () -> Unit): AppStartup {
        blocking.add(Task(name, task))
        return this
    }

    





    fun addNonBlocking(name: String, task: () -> Unit): AppStartup {
        nonBlocking.add(Task(name, task))
        return this
    }

    







    fun addPostRender(name: String, task: () -> Unit): AppStartup {
        postRender.add(Task(name, task))
        return this
    }

    




    fun onCriticalRenderEventStart() {
        if (outstandingCriticalRenderEvents.get() == 0 && postRender.isNotEmpty()) {
            L.i { "[$TAG]Received first critical render event" }
            renderStartTime = System.currentTimeMillis()

            
            postRenderTimeoutJob?.cancel()
            backgroundPostRenderJob?.cancel()
            
            
            postRenderTimeoutJob = startupScope.launch {
                delay(FAILSAFE_RENDER_TIME)
                L.w { "[$TAG]Reached the failsafe event for post-render! Either someone forgot to call onCriticalRenderEventEnd(), the activity was started while the phone was locked, or app start is taking a very long time." }
                executePostRender()
            }
        }

        outstandingCriticalRenderEvents.incrementAndGet()
    }

    



    fun onCriticalRenderEventEnd() {
        if (outstandingCriticalRenderEvents.get() <= 0) {
            L.w { "[$TAG]Too many end events! onCriticalRenderEventStart/End was mismanaged." }
        }

        val currentCount = outstandingCriticalRenderEvents.decrementAndGet()

        if (currentCount == 0 && postRender.isNotEmpty()) {
            renderEndTime = System.currentTimeMillis()

            L.i { "[$TAG]First render has finished. Cold Start: ${renderEndTime - applicationStartTime} ms, Render Time: ${renderEndTime - renderStartTime} ms" }

            
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

    









    private fun reportTaskFailure(kind: String, taskName: String, e: Throwable) {
        val displayName = taskName.ifEmpty { "<anonymous>" }
        val label = "[$TAG] $kind task '$displayName' failed — continuing"

        runCatching { L.e { "$label: ${e.stackTraceToString()}" } }
    }

    


    private data class Task(
        val name: String,
        val runnable: () -> Unit
    )

    fun getApplicationStartTime(): Long {
        return applicationStartTime
    }

    
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
        
        
        
        startupScope.coroutineContext[Job]?.children?.forEach { it.cancel() }
    }
}