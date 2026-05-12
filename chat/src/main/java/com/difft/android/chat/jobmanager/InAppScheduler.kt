package com.difft.android.chat.jobmanager

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Schedules future runs using coroutines. Intended to be used in combination with a persistent
 * [Scheduler] to improve responsiveness when the app is open.
 *
 * This should only schedule runs when all constraints are met. Because this only works when the
 * app is foregrounded, jobs that don't have their constraints met will be run when the relevant
 * [ConstraintObserver] is triggered.
 *
 * Similarly, this does not need to schedule retries with no delay, as this doesn't provide any
 * persistence, and other mechanisms will take care of that.
 */
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
}
