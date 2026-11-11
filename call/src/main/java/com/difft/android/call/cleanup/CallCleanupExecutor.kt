package com.difft.android.call.cleanup

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference













class CallCleanupExecutor(
    private val runScope: CoroutineScope = appScope,
) {
    data class Step(val name: String, val action: suspend () -> Unit)

    private val mutex = Mutex()
    private val jobRef = AtomicReference<Job?>(null)
    private val released = AtomicBoolean(false)

    
    fun start(reason: String, steps: List<Step>) {
        val job = runScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                runOnce(reason, steps)
            }
        }
        if (!jobRef.compareAndSet(null, job)) {
            job.cancel()
            L.i { "[Call] CallCleanupExecutor already running, cancelled duplicate job. reason=$reason" }
        }
    }

    private suspend fun runOnce(reason: String, steps: List<Step>) {
        mutex.withLock {
            if (!released.compareAndSet(false, true)) {
                L.i { "[Call] CallCleanupExecutor already executed, skip. reason=$reason" }
                return
            }
            L.i { "[Call] CallCleanupExecutor start. reason=$reason" }
            for (step in steps) {
                runCatching { step.action() }
                    .onFailure { L.e(it) { "[Call] CallCleanupExecutor step '${step.name}' failed" } }
            }
            L.i { "[Call] CallCleanupExecutor done. reason=$reason" }
        }
    }
}
