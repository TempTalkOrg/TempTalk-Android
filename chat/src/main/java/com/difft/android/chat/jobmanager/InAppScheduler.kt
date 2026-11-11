package com.difft.android.chat.jobmanager

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch












internal class InAppScheduler(
    private val jobManager: JobManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : Scheduler {

    override fun schedule(delay: Long, constraints: List<Constraint>) {
        if (delay > 0 && constraints.all { it.isMet() }) {
            L.i { "Scheduling a retry in $delay ms." }
            scope.launch {
                delay(delay)
                L.i { "Triggering a job retry." }
                jobManager.wakeUp()
            }
        }
    }

    



    override fun cancelAllExcept(keep: Set<Int>) = Unit
}
