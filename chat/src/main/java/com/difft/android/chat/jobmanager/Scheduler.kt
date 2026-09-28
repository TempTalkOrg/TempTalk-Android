package com.difft.android.chat.jobmanager

interface Scheduler {
    fun schedule(delay: Long, constraints: List<@JvmSuppressWildcards Constraint>)

    /**
     * Cancels every pending wake-up this scheduler registered whose id is not in [keep].
     *
     * `emptySet()` cancels all of them.
     */
    fun cancelAllExcept(keep: Set<Int>)
}
