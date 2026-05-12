package com.difft.android.chat.jobmanager

internal class CompositeScheduler(vararg schedulers: Scheduler) : Scheduler {

    private val schedulers: List<Scheduler> = schedulers.toList()

    override fun schedule(delay: Long, constraints: List<Constraint>) {
        for (scheduler in schedulers) {
            scheduler.schedule(delay, constraints)
        }
    }
}
